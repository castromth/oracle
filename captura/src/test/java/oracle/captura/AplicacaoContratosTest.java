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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import oracle.captura.estado.Estado;
import oracle.captura.estado.RegistroUnidade;
import oracle.captura.estado.RepositorioEstado;
import oracle.captura.estado.Status;
import oracle.captura.recurso.CatalogoUnidades;
import oracle.captura.recurso.UnidadeOrgao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AplicacaoContratosTest {
  private static final LocalDate INICIO = LocalDate.of(2026, 11, 1);
  private static final LocalDate FIM = LocalDate.of(2026, 11, 10);
  private static final String COM_DADOS = "contratos/cnpj=111/unidade=01/janela=";
  private static final String SEM_DADOS = "contratos/cnpj=222/unidade=02/janela=";
  private static final String COM_FALHA = "contratos/cnpj=333/unidade=03/janela=";
  private static final String NOVA = "contratos/cnpj=444/unidade=04/janela=";

  @TempDir Path raiz;
  private HttpServer servidor;
  private final AtomicBoolean falhaSaudavel = new AtomicBoolean(false);
  private final List<Map<String, String>> requisicoes = new CopyOnWriteArrayList<>();

  @BeforeEach
  void iniciar() throws IOException {
    servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    servidor.createContext(
        "/v1/contratos",
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

  private void responder(HttpExchange troca) throws IOException {
    Map<String, String> q = new HashMap<>();
    for (String par : troca.getRequestURI().getQuery().split("&")) {
      String[] kv = par.split("=", 2);
      q.put(kv[0], kv[1]);
    }
    requisicoes.add(q);
    String cnpj = q.get("cnpjOrgao");
    if ("111".equals(cnpj) || "444".equals(cnpj)) {
      enviar(troca, 200, envelope());
    } else if ("333".equals(cnpj) && falhaSaudavel.get()) {
      enviar(troca, 200, envelope());
    } else if ("333".equals(cnpj)) {
      enviar(troca, 500, "{\"erro\":\"falha\"}");
    } else {
      enviar(troca, 204, "");
    }
  }

  private static String envelope() {
    return "{\"data\":[{\"numeroControlePNCP\":\"a\"},{\"numeroControlePNCP\":\"b\"}],"
        + "\"totalRegistros\":2,\"totalPaginas\":1,\"numeroPagina\":1}";
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
        "contratos", modo, raiz, inicio, fim, "GO", List.of(6), 2, 2,
        Duration.ofSeconds(5), 1, Duration.ofMillis(1), Duration.ofMillis(2), base, base);
  }

  private static Clock relogio(LocalDate dia) {
    return Clock.fixed(
        LocalDateTime.of(dia, java.time.LocalTime.NOON).atZone(Config.FUSO).toInstant(),
        Config.FUSO);
  }

  private static UnidadeOrgao par(String cnpj, String codigo) {
    return new UnidadeOrgao(cnpj, codigo, "Unidade " + codigo, "GO", "5208707", true);
  }

  private Estado estado() throws IOException {
    return new RepositorioEstado(raiz.resolve("estado/captura.json")).carregar();
  }

  private List<Map<String, String>> requisicoesDo(String cnpj) {
    return requisicoes.stream().filter(q -> cnpj.equals(q.get("cnpjOrgao"))).toList();
  }

  @Test
  void backfillComDadosVazioEFalhaEIncrementalRefazAFalhaECriaUnidadeNova() throws IOException {
    CatalogoUnidades.gravar(raiz, List.of(par("111", "01"), par("222", "02"), par("333", "03")));
    assertEquals(0, Aplicacao.executar(config(Modo.BACKFILL, INICIO, FIM), relogio(FIM.plusDays(1))));

    String janelaBackfill = "20261101-20261110";
    Estado depoisDoBackfill = estado();
    RegistroUnidade dados = depoisDoBackfill.unidadesCaptura.get(COM_DADOS + janelaBackfill);
    RegistroUnidade vazia = depoisDoBackfill.unidadesCaptura.get(SEM_DADOS + janelaBackfill);
    RegistroUnidade falha = depoisDoBackfill.unidadesCaptura.get(COM_FALHA + janelaBackfill);
    assertEquals(Status.OK, dados.status());
    assertEquals(2, dados.registros());
    assertNotNull(dados.caminho());
    assertEquals(Status.VAZIA, vazia.status());
    assertNull(vazia.caminho());
    assertEquals(Status.FALHA, falha.status());
    assertNotNull(falha.erro());
    assertEquals(INICIO, depoisDoBackfill.inicioBackfillContratos);
    assertEquals(FIM, depoisDoBackfill.watermarkContratos.get("111/01"));
    assertEquals(FIM, depoisDoBackfill.watermarkContratos.get("222/02"));
    assertEquals(FIM, depoisDoBackfill.watermarkContratos.get("333/03"));
    assertEquals("500", requisicoesDo("111").get(0).get("tamanhoPagina"));
    assertEquals("01", requisicoesDo("111").get(0).get("codigoUnidadeAdministrativa"));

    falhaSaudavel.set(true);
    requisicoes.clear();
    CatalogoUnidades.gravar(
        raiz, List.of(par("111", "01"), par("222", "02"), par("333", "03"), par("444", "04")));
    LocalDate hoje = FIM.plusDays(2);
    assertEquals(0, Aplicacao.executar(config(Modo.INCREMENTAL, null, null), relogio(hoje)));

    Estado depoisDoIncremental = estado();
    RegistroUnidade curada = depoisDoIncremental.unidadesCaptura.get(COM_FALHA + janelaBackfill);
    assertEquals(Status.OK, curada.status());
    assertEquals(2, curada.tentativas());
    assertTrue(
        requisicoesDo("333").stream()
            .anyMatch(q -> "20261101".equals(q.get("dataInicial")) && "20261110".equals(q.get("dataFinal"))),
        "falha deve ser refeita com a janela original: " + requisicoesDo("333"));

    String janelaIncremental = "20261108-20261112";
    for (String prefixo : List.of(COM_DADOS, SEM_DADOS, COM_FALHA)) {
      assertNotNull(
          depoisDoIncremental.unidadesCaptura.get(prefixo + janelaIncremental), prefixo);
    }
    RegistroUnidade nova = depoisDoIncremental.unidadesCaptura.get(NOVA + "20261101-20261112");
    assertNotNull(nova, "par novo começa em inicioBackfillContratos");
    assertEquals(Status.OK, nova.status());
    assertEquals(INICIO, depoisDoIncremental.inicioBackfillContratos);
    assertEquals(hoje, depoisDoIncremental.watermarkContratos.get("111/01"));
    assertEquals(hoje, depoisDoIncremental.watermarkContratos.get("444/04"));
  }

  @Test
  void backfillComCatalogoAusenteSaiCom1() {
    assertEquals(1, Aplicacao.executar(config(Modo.BACKFILL, INICIO, FIM), relogio(FIM.plusDays(1))));
  }
}
