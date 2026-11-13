package oracle.captura.recurso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.stream.Stream;
import oracle.captura.Config;
import oracle.captura.Modo;
import oracle.captura.bronze.EscritorBronze;
import oracle.captura.estado.Status;
import oracle.captura.http.ClientePncp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaginadorTest {
  private interface Tratador {
    void tratar(HttpExchange troca, int pagina) throws IOException;
  }

  @TempDir Path raiz;
  private HttpServer servidor;

  @AfterEach
  void parar() {
    if (servidor != null) {
      servidor.stop(0);
    }
  }

  private URI servir(Tratador tratador) throws IOException {
    servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    servidor.createContext(
        "/x",
        troca -> {
          try {
            String q = troca.getRequestURI().getQuery();
            int pagina = 1;
            for (String par : q.split("&")) {
              if (par.startsWith("pagina=")) {
                pagina = Integer.parseInt(par.substring(7));
              }
            }
            tratador.tratar(troca, pagina);
          } finally {
            troca.close();
          }
        });
    servidor.start();
    return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/x");
  }

  static void responder(HttpExchange troca, int status, String corpo) throws IOException {
    byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0) {
      troca.sendResponseHeaders(status, -1);
    } else {
      troca.sendResponseHeaders(status, bytes.length);
      troca.getResponseBody().write(bytes);
    }
  }

  private Contexto contexto() {
    Config config =
        new Config(
            "contratacoes", Modo.BACKFILL, raiz, null, null, "GO", List.of(1), 2, 2,
            Duration.ofSeconds(5), 1, Duration.ofMillis(1), Duration.ofMillis(2),
            URI.create("http://localhost"), URI.create("http://localhost"));
    ClientePncp http = new ClientePncp(Duration.ofSeconds(5), 1, Duration.ofMillis(1), Duration.ofMillis(2));
    return new Contexto(config, http, new EscritorBronze(raiz, LocalDate.of(2026, 11, 12), "run1"));
  }

  private static final UnidadeCaptura UNIDADE;

  static {
    SequencedMap<String, String> chave = new LinkedHashMap<>();
    chave.put("dia", "2026-11-10");
    chave.put("modalidade", "8");
    UNIDADE = new UnidadeCaptura("contratacoes", chave);
  }

  private long arquivosNoBronze() throws IOException {
    Path bronze = raiz.resolve("bronze");
    if (!Files.exists(bronze)) {
      return 0;
    }
    try (Stream<Path> s = Files.walk(bronze)) {
      return s.filter(p -> p.toString().endsWith(".json.gz")).count();
    }
  }

  @Test
  void duasPaginasViramOkComRegistrosSomados() throws Exception {
    URI endpoint =
        servir(
            (t, p) ->
                responder(
                    t,
                    200,
                    p == 1
                        ? "{\"data\":[{\"a\":1},{\"a\":2}],\"totalPaginas\":2}"
                        : "{\"data\":[{\"a\":3}],\"totalPaginas\":2}"));
    var r = Paginador.capturar(UNIDADE, endpoint, new LinkedHashMap<>(), 50, new Contratacoes(), contexto());
    assertEquals(Status.OK, r.status(), r.erro());
    assertEquals(2, r.paginas());
    assertEquals(3, r.registros());
    assertNotNull(r.caminho());
    assertEquals(2, arquivosNoBronze());
    assertTrue(Files.isRegularFile(raiz.resolve(r.caminho()).resolve("pagina-0001.json.gz")));
    assertTrue(Files.isRegularFile(raiz.resolve(r.caminho()).resolve("pagina-0002.json.gz")));
  }

  @Test
  void semConteudoViraVaziaSemArquivo() throws Exception {
    URI endpoint = servir((t, p) -> responder(t, 204, ""));
    var r = Paginador.capturar(UNIDADE, endpoint, new LinkedHashMap<>(), 50, new Contratacoes(), contexto());
    assertEquals(Status.VAZIA, r.status());
    assertNull(r.caminho());
    assertEquals(0, arquivosNoBronze());
  }

  @Test
  void falhaNaPaginaDoisViraFalhaSemNadaNoBronze() throws Exception {
    URI endpoint =
        servir(
            (t, p) ->
                responder(
                    t, p == 1 ? 200 : 500, p == 1 ? "{\"data\":[{}],\"totalPaginas\":2}" : "erro"));
    var r = Paginador.capturar(UNIDADE, endpoint, new LinkedHashMap<>(), 50, new Contratacoes(), contexto());
    assertEquals(Status.FALHA, r.status());
    assertNotNull(r.erro());
    assertFalse(r.erro().isBlank());
    assertEquals(0, arquivosNoBronze());
    assertFalse(Files.exists(raiz.resolve("bronze/metadados/contratacoes")));
  }
}
