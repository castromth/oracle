package oracle.captura;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import oracle.captura.estado.Estado;
import oracle.captura.estado.RegistroUnidade;
import oracle.captura.estado.RepositorioEstado;
import oracle.captura.estado.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AplicacaoContratacoesTest {
  private static final LocalDate DIA = LocalDate.of(2026, 11, 10);
  private static final String PREFIXO = "contratacoes/dia=2026-11-10/modalidade=";

  @TempDir Path raiz;
  private HttpServer servidor;
  private final AtomicBoolean modalidade8Saudavel = new AtomicBoolean(false);

  @BeforeEach
  void iniciar() throws IOException {
    servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    servidor.createContext(
        "/v1/contratacoes/publicacao",
        troca -> {
          try {
            responder(troca);
          } finally {
            troca.close();
          }
        });
    servidor.start();
  }

  @AfterEach
  void parar() {
    servidor.stop(0);
  }

  /** Só o dia 2026-11-10 tem dados: modalidade 6 com 2 páginas, 7 vazia, 8 com 500 até ser curada. */
  private void responder(HttpExchange troca) throws IOException {
    Map<String, String> q = new HashMap<>();
    for (String par : troca.getRequestURI().getQuery().split("&")) {
      String[] kv = par.split("=", 2);
      q.put(kv[0], kv[1]);
    }
    String dia = q.get("dataInicial");
    String modalidade = q.get("codigoModalidadeContratacao");
    int pagina = Integer.parseInt(q.get("pagina"));
    if (!"20261110".equals(dia)) {
      enviar(troca, 204, "");
    } else if ("6".equals(modalidade)) {
      enviar(troca, 200, envelope(pagina, 2));
    } else if ("8".equals(modalidade)) {
      if (modalidade8Saudavel.get()) {
        enviar(troca, 200, envelope(pagina, 1));
      } else {
        enviar(troca, 500, "{\"erro\":\"falha\"}");
      }
    } else {
      enviar(troca, 204, "");
    }
  }

  private static String envelope(int pagina, int totalPaginas) {
    return "{\"data\":[{\"numeroControlePNCP\":\"p"
        + pagina
        + "\"}],\"totalRegistros\":"
        + totalPaginas
        + ",\"totalPaginas\":"
        + totalPaginas
        + ",\"numeroPagina\":"
        + pagina
        + "}";
  }

  private static void enviar(HttpExchange troca, int status, String corpo) throws IOException {
    byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0) {
      troca.sendResponseHeaders(status, -1);
    } else {
      troca.getResponseHeaders().add("Content-Type", "application/json");
      troca.sendResponseHeaders(status, bytes.length);
      troca.getResponseBody().write(bytes);
    }
  }

  private Config config(Modo modo, LocalDate inicio, LocalDate fim) {
    URI base = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort());
    return new Config(
        "contratacoes",
        modo,
        raiz,
        inicio,
        fim,
        "GO",
        List.of(6, 7, 8),
        2,
        2,
        Duration.ofSeconds(5),
        1,
        Duration.ofMillis(1),
        Duration.ofMillis(2),
        base,
        base);
  }

  private static Clock relogio(LocalDate dia) {
    return Clock.fixed(
        LocalDateTime.of(dia, java.time.LocalTime.NOON).atZone(Config.FUSO).toInstant(),
        Config.FUSO);
  }

  private Estado estado() throws IOException {
    return new RepositorioEstado(raiz.resolve("estado/captura.json")).carregar();
  }

  private List<String> paginasNoBronze() throws IOException {
    try (Stream<Path> s = Files.walk(raiz.resolve("bronze"))) {
      return s.filter(p -> p.getFileName().toString().endsWith(".json.gz"))
          .map(p -> raiz.relativize(p).toString())
          .sorted()
          .collect(Collectors.toList());
    }
  }

  @Test
  void backfillDeUmDiaEIncrementalSeguinteCuraAFalha() throws IOException {
    assertEquals(0, Aplicacao.executar(config(Modo.BACKFILL, DIA, DIA), relogio(DIA.plusDays(1))));

    Estado depoisDoBackfill = estado();
    RegistroUnidade m6 = depoisDoBackfill.unidadesCaptura.get(PREFIXO + "6");
    RegistroUnidade m7 = depoisDoBackfill.unidadesCaptura.get(PREFIXO + "7");
    RegistroUnidade m8 = depoisDoBackfill.unidadesCaptura.get(PREFIXO + "8");
    assertEquals(Status.OK, m6.status());
    assertEquals(2, m6.paginas());
    assertEquals(2, m6.registros());
    assertNotNull(m6.caminho());
    assertEquals(Status.VAZIA, m7.status());
    assertNull(m7.caminho());
    assertEquals(Status.FALHA, m8.status());
    assertNotNull(m8.erro());
    assertEquals(DIA, depoisDoBackfill.watermarkContratacoes);

    List<String> paginas = paginasNoBronze();
    assertEquals(2, paginas.size());
    assertTrue(paginas.stream().allMatch(p -> p.contains("/modalidade=6/")), paginas.toString());

    modalidade8Saudavel.set(true);
    LocalDate amanha = DIA.plusDays(2);
    assertEquals(0, Aplicacao.executar(config(Modo.INCREMENTAL, null, null), relogio(amanha)));

    Estado depoisDoIncremental = estado();
    RegistroUnidade curada = depoisDoIncremental.unidadesCaptura.get(PREFIXO + "8");
    assertEquals(Status.OK, curada.status());
    assertEquals(2, curada.tentativas());
    assertEquals(amanha, curada.dataCaptura());
    assertEquals(amanha, depoisDoIncremental.watermarkContratacoes);
    assertTrue(
        paginasNoBronze().stream().anyMatch(p -> p.contains("/modalidade=8/")),
        "bronze da modalidade 8 ausente");
  }

  @Test
  void backfillSemJanelaEIncrementalComJanelaSaemCom1() throws IOException {
    assertEquals(
        1, Aplicacao.executar(config(Modo.BACKFILL, null, null), relogio(DIA.plusDays(1))));
    assertEquals(
        1, Aplicacao.executar(config(Modo.INCREMENTAL, DIA, DIA), relogio(DIA.plusDays(1))));
    assertTrue(paginasNoBronzeOuVazio().isEmpty());
  }

  @Test
  void travaOcupadaSaiCom1() throws IOException {
    Path trava = raiz.resolve("estado/captura.lock");
    Files.createDirectories(trava.getParent());
    try (FileChannel canal =
            FileChannel.open(trava, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock ocupada = canal.tryLock()) {
      assertNotNull(ocupada);
      assertEquals(
          1, Aplicacao.executar(config(Modo.BACKFILL, DIA, DIA), relogio(DIA.plusDays(1))));
    }
    assertTrue(paginasNoBronzeOuVazio().isEmpty());
    assertTrue(!Files.exists(raiz.resolve("estado/captura.json")));
  }

  private List<String> paginasNoBronzeOuVazio() throws IOException {
    return Files.exists(raiz.resolve("bronze")) ? paginasNoBronze() : List.of();
  }
}
