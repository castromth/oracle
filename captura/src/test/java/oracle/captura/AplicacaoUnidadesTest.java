package oracle.captura;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import oracle.captura.estado.Estado;
import oracle.captura.estado.RepositorioEstado;
import oracle.captura.estado.Status;
import oracle.captura.recurso.CatalogoUnidades;
import oracle.captura.recurso.UnidadeOrgao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AplicacaoUnidadesTest {
  private static final LocalDate HOJE = LocalDate.of(2026, 11, 12);
  private static final String A = "11111111000111";
  private static final String B = "22222222000122";
  private static final String C = "33333333000133";

  @TempDir Path raiz;
  private HttpServer servidor;
  private final List<String> requisicoesUnidades = new CopyOnWriteArrayList<>();

  @BeforeEach
  void iniciar() throws IOException {
    servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    servidor.createContext("/v1/contratacoes/publicacao", t -> atender(t, true));
    servidor.createContext("/v1/orgaos/", t -> atender(t, false));
    servidor.start();
  }

  @AfterEach
  void parar() {
    servidor.stop(0);
  }

  private void atender(HttpExchange troca, boolean contratacoes) throws IOException {
    try {
      String query = troca.getRequestURI().getQuery();
      if (contratacoes) {
        // Só 2026-11-10 / modalidade 6 tem dados: 2 páginas, CNPJs A e C na 1ª e B na 2ª.
        boolean dados = query.contains("dataInicial=20261110")
            && query.contains("codigoModalidadeContratacao=6&");
        if (!dados) {
          enviar(troca, 204, "");
        } else if (query.contains("pagina=1&")) {
          enviar(troca, 200, compras(1, A, C));
        } else {
          enviar(troca, 200, compras(2, B));
        }
        return;
      }
      String caminho = troca.getRequestURI().getPath();
      requisicoesUnidades.add(caminho + (query == null ? "" : "?" + query));
      if (caminho.equals("/v1/orgaos/" + A + "/unidades")) {
        enviar(
            troca,
            200,
            "["
                + unidade(A, "1", "Um", "GO", true)
                + ","
                + unidade(A, "01", "Zero um", "GO", false)
                + ","
                + unidade(A, "9", "Fora", "SP", true)
                + "]");
      } else if (caminho.equals("/v1/orgaos/" + B + "/unidades")) {
        enviar(troca, 200, "[" + unidade(B, "1", "Outro", "GO", true) + "]");
      } else {
        enviar(troca, 204, "");
      }
    } finally {
      troca.close();
    }
  }

  private static String compras(int pagina, String... cnpjs) {
    List<String> itens = new ArrayList<>();
    for (String c : cnpjs) {
      itens.add("{\"orgaoEntidade\":{\"cnpj\":\"" + c + "\"}}");
    }
    return "{\"data\":["
        + String.join(",", itens)
        + "],\"totalRegistros\":3,\"totalPaginas\":2,\"numeroPagina\":"
        + pagina
        + "}";
  }

  private static String unidade(String cnpj, String codigo, String nome, String uf, boolean ativa) {
    return "{\"orgao\":{\"cnpj\":\""
        + cnpj
        + "\"},\"codigoUnidade\":\""
        + codigo
        + "\",\"nomeUnidade\":\""
        + nome
        + "\",\"municipio\":{\"codigoIbge\":\"5211909\",\"uf\":{\"siglaUF\":\""
        + uf
        + "\"}},\"statusAtivo\":"
        + ativa
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

  private Config config(String recurso, Modo modo, LocalDate inicio, LocalDate fim) {
    URI base = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort());
    return new Config(
        recurso, modo, raiz, inicio, fim, "GO", List.of(6, 7), 2, 2,
        Duration.ofSeconds(5), 1, Duration.ofMillis(1), Duration.ofMillis(2), base, base);
  }

  private static Clock relogio() {
    return Clock.fixed(
        LocalDateTime.of(HOJE, java.time.LocalTime.NOON).atZone(Config.FUSO).toInstant(),
        Config.FUSO);
  }

  @Test
  void runDeContratacoesSeguidoDeRunDeUnidadesGeraBronzeECatalogo() throws IOException {
    LocalDate dia = LocalDate.of(2026, 11, 10);
    assertEquals(0, Aplicacao.executar(config("contratacoes", Modo.BACKFILL, dia, dia), relogio()));
    assertEquals(0, Aplicacao.executar(config("unidades", Modo.BACKFILL, null, null), relogio()));

    Estado estado = new RepositorioEstado(raiz.resolve("estado/captura.json")).carregar();
    assertEquals(Status.OK, estado.unidadesCaptura.get("unidades/cnpj=" + A).status());
    assertEquals(Status.OK, estado.unidadesCaptura.get("unidades/cnpj=" + B).status());
    assertEquals(Status.VAZIA, estado.unidadesCaptura.get("unidades/cnpj=" + C).status());
    assertEquals(3, estado.unidadesCaptura.get("unidades/cnpj=" + A).registros());

    String base = "bronze/metadados/unidades/data_captura=2026-11-12/cnpj=";
    assertEquals(
        List.of(base + A + "/pagina-0001.json.gz", base + B + "/pagina-0001.json.gz"),
        paginas("unidades"));

    assertEquals(
        List.of(
            new UnidadeOrgao(A, "01", "Zero um", "GO", "5211909", false),
            new UnidadeOrgao(A, "1", "Um", "GO", "5211909", true),
            new UnidadeOrgao(B, "1", "Outro", "GO", "5211909", true)),
        CatalogoUnidades.ler(raiz));
    assertEquals(3, requisicoesUnidades.size());
    assertFalse(requisicoesUnidades.stream().anyMatch(r -> r.contains("pagina=")));
  }

  @Test
  void runDeUnidadesComJanelaSaiCom1() throws IOException {
    LocalDate dia = LocalDate.of(2026, 11, 10);
    assertEquals(1, Aplicacao.executar(config("unidades", Modo.BACKFILL, dia, dia), relogio()));
    assertEquals(0, requisicoesUnidades.size());
    assertFalse(Files.exists(raiz.resolve("catalogo")));
  }

  private List<String> paginas(String recurso) throws IOException {
    try (Stream<Path> s = Files.walk(raiz.resolve("bronze/metadados/" + recurso))) {
      return s.filter(p -> p.getFileName().toString().endsWith(".json.gz"))
          .map(p -> raiz.relativize(p).toString())
          .sorted()
          .toList();
    }
  }
}
