package oracle.captura;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import oracle.captura.bronze.EscritorBronze;
import oracle.captura.estado.Estado;
import oracle.captura.estado.RegistroUnidade;
import oracle.captura.estado.RepositorioEstado;
import oracle.captura.estado.Status;
import oracle.captura.http.ClientePncp;
import oracle.captura.recurso.Contexto;
import oracle.captura.recurso.Recurso;
import oracle.captura.recurso.ResultadoUnidade;
import oracle.captura.recurso.UnidadeCaptura;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoordenadorTest {

  static final LocalDate HOJE = LocalDate.of(2026, 11, 12);

  @TempDir Path raiz;
  RepositorioEstado repo;
  Config config;
  Contexto contexto;
  FakeRecurso recurso;

  static UnidadeCaptura un(String dia) {
    SequencedMap<String, String> chave = new LinkedHashMap<>();
    chave.put("dia", dia);
    return new UnidadeCaptura("fake", chave);
  }

  static RegistroUnidade reg(Status s, int tent, LocalDate dataCaptura) {
    return new RegistroUnidade(s, tent, Instant.EPOCH, dataCaptura, 0, 0, null, null);
  }

  /** Recurso em memória: planeja o que lhe foi dado, registra chamadas e falha sob demanda. */
  static final class FakeRecurso implements Recurso {
    List<UnidadeCaptura> janela = new ArrayList<>();
    final Set<String> chamadas = ConcurrentHashMap.newKeySet();
    final Set<String> explodem = ConcurrentHashMap.newKeySet();
    final List<String> eventos = Collections.synchronizedList(new ArrayList<>());
    List<ResultadoUnidade> concluidos;
    int chamadasConcluir;

    public String nome() {
      return "fake";
    }

    public boolean usaJanela() {
      return true;
    }

    public List<UnidadeCaptura> planejar(Config c, Estado e, LocalDate hoje) {
      return janela;
    }

    public ResultadoUnidade capturar(UnidadeCaptura u, Contexto ctx) {
      chamadas.add(u.id());
      eventos.add("capturar");
      if (explodem.contains(u.id())) {
        throw new IllegalStateException("boom");
      }
      return new ResultadoUnidade(u, Status.OK, 1, 5, "bronze/" + u.id(), null);
    }

    public int contarRegistros(JsonNode corpo) {
      return 7;
    }

    public void concluir(Config c, Estado e, LocalDate hoje, List<ResultadoUnidade> r) {
      eventos.add("concluir");
      chamadasConcluir++;
      concluidos = new ArrayList<>(r);
    }
  }

  @BeforeEach
  void preparar() {
    repo = new RepositorioEstado(raiz.resolve("estado/captura.json"));
    config =
        new Config(
            "fake", Modo.BACKFILL, raiz, HOJE, HOJE, "GO", List.of(1), 2, 2,
            Duration.ofSeconds(5), 1, Duration.ofMillis(1), Duration.ofMillis(2),
            URI.create("http://localhost:1/"), URI.create("http://localhost:1/"));
    contexto =
        new Contexto(
            config,
            new ClientePncp(Duration.ofSeconds(5), 1, Duration.ofMillis(1), Duration.ofMillis(2)),
            new EscritorBronze(raiz, HOJE, "run-teste"));
    recurso = new FakeRecurso();
  }

  Coordenador.Resumo rodar() throws IOException {
    return new Coordenador(config, recurso, repo, contexto, HOJE).executar();
  }

  @Test
  void falhaForaDaJanelaEReprocessada() throws IOException {
    Estado e = new Estado();
    e.unidadesCaptura.put(un("2026-01-01").id(), reg(Status.FALHA, 1, null));
    repo.gravar(e);
    recurso.janela = List.of(un("2026-11-10"));

    Coordenador.Resumo r = rodar();

    assertEquals(Set.of(un("2026-01-01").id(), un("2026-11-10").id()), recurso.chamadas);
    assertEquals(new Coordenador.Resumo(2, 0, 0), r);
    assertEquals(Status.OK, repo.carregar().unidadesCaptura.get(un("2026-01-01").id()).status());
  }

  @Test
  void okDeHojeNaoExecutaEOkDeOntemExecuta() throws IOException {
    Estado e = new Estado();
    e.unidadesCaptura.put(un("2026-11-09").id(), reg(Status.OK, 1, HOJE));
    e.unidadesCaptura.put(un("2026-11-10").id(), reg(Status.OK, 1, HOJE.minusDays(1)));
    repo.gravar(e);
    recurso.janela = List.of(un("2026-11-09"), un("2026-11-10"));

    rodar();

    assertEquals(Set.of(un("2026-11-10").id()), recurso.chamadas);
  }

  @Test
  void tentativasAumentaUmPorExecucao() throws IOException {
    recurso.janela = List.of(un("2026-11-10"));
    recurso.explodem.add(un("2026-11-10").id());

    rodar();
    assertEquals(1, repo.carregar().unidadesCaptura.get(un("2026-11-10").id()).tentativas());
    rodar();
    assertEquals(2, repo.carregar().unidadesCaptura.get(un("2026-11-10").id()).tentativas());
    recurso.explodem.clear();
    rodar();
    RegistroUnidade fim = repo.carregar().unidadesCaptura.get(un("2026-11-10").id());
    assertEquals(3, fim.tentativas());
    assertEquals(Status.OK, fim.status());
    assertEquals(HOJE, fim.dataCaptura());
  }

  @Test
  void diretorioFinalExistenteViraOkSemCapturar() throws IOException {
    UnidadeCaptura u = un("2026-11-10");
    Path dir = contexto.bronze().caminhoFinal(u);
    Files.createDirectories(dir);
    SequencedMap<String, String> params = new LinkedHashMap<>();
    // cria uma página real via sessão de outro escritor não é necessário: basta um arquivo gz válido
    try (var out =
        new java.util.zip.GZIPOutputStream(Files.newOutputStream(dir.resolve("pagina-0001.json.gz")))) {
      out.write("{\"corpo\":{\"data\":[]}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    recurso.janela = List.of(u);

    Coordenador.Resumo r = rodar();

    assertTrue(recurso.chamadas.isEmpty());
    assertEquals(new Coordenador.Resumo(1, 0, 0), r);
    RegistroUnidade reg = repo.carregar().unidadesCaptura.get(u.id());
    assertEquals(Status.OK, reg.status());
    assertEquals(1, reg.paginas());
    assertEquals(7, reg.registros());
    assertEquals(HOJE, reg.dataCaptura());
    assertTrue(reg.caminho() != null && reg.caminho().endsWith("dia=2026-11-10"));
  }

  @Test
  void excecaoViraFalhaEDemaisTerminam() throws IOException {
    recurso.janela = List.of(un("2026-11-08"), un("2026-11-09"), un("2026-11-10"));
    recurso.explodem.add(un("2026-11-09").id());

    Coordenador.Resumo r = rodar();

    assertEquals(new Coordenador.Resumo(2, 0, 1), r);
    Estado e = repo.carregar();
    RegistroUnidade falha = e.unidadesCaptura.get(un("2026-11-09").id());
    assertEquals(Status.FALHA, falha.status());
    assertTrue(falha.erro().contains("boom"));
    assertNull(falha.caminho());
    assertEquals(Status.OK, e.unidadesCaptura.get(un("2026-11-08").id()).status());
    assertEquals(Status.OK, e.unidadesCaptura.get(un("2026-11-10").id()).status());
  }

  @Test
  void concluirUmaVezDepoisDeTodosOsResultados() throws IOException {
    recurso.janela = List.of(un("2026-11-08"), un("2026-11-09"), un("2026-11-10"));

    rodar();

    assertEquals(1, recurso.chamadasConcluir);
    assertEquals(3, recurso.concluidos.size());
    assertEquals(List.of("capturar", "capturar", "capturar", "concluir"), recurso.eventos);
  }
}
