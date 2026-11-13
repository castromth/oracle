package oracle.captura.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientePncpTest {
  private interface Tratador {
    void tratar(HttpExchange troca, int chamada) throws IOException;
  }

  private HttpServer servidor;
  private final AtomicInteger chamadas = new AtomicInteger();
  private final List<Long> instantes = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void iniciar() {
    chamadas.set(0);
    instantes.clear();
  }

  @AfterEach
  void parar() {
    if (servidor != null) {
      servidor.stop(0);
    }
  }

  private URI servir(Tratador tratador) throws IOException {
    servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    servidor.setExecutor(Executors.newCachedThreadPool());
    servidor.createContext(
        "/",
        troca -> {
          instantes.add(System.nanoTime());
          int n = chamadas.incrementAndGet();
          try {
            tratador.tratar(troca, n);
          } finally {
            troca.close();
          }
        });
    servidor.start();
    return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/x");
  }

  private static void responder(HttpExchange troca, int status, String corpo) throws IOException {
    byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0) {
      troca.sendResponseHeaders(status, -1);
    } else {
      troca.sendResponseHeaders(status, bytes.length);
      troca.getResponseBody().write(bytes);
    }
  }

  private static ClientePncp cliente(Duration timeout, int max) {
    return new ClientePncp(timeout, max, Duration.ofMillis(1), Duration.ofMillis(5));
  }

  @Test
  void tentaNovamenteApos429eDevolve200() throws Exception {
    URI url =
        servir(
            (t, n) -> {
              if (n < 3) {
                responder(t, 429, "");
              } else {
                responder(t, 200, "{\"ok\":true}");
              }
            });
    Resposta r = cliente(Duration.ofSeconds(5), 5).get(url);
    assertEquals(200, r.status());
    assertEquals("{\"ok\":true}", new String(r.corpo(), StandardCharsets.UTF_8));
    assertEquals(url, r.url());
    assertEquals(3, chamadas.get());
  }

  @Test
  void erro500PersistenteFalhaAposExatamenteMaxTentativas() throws Exception {
    URI url = servir((t, n) -> responder(t, 500, "erro"));
    assertThrows(FalhaHttp.class, () -> cliente(Duration.ofSeconds(5), 4).get(url));
    assertEquals(4, chamadas.get());
  }

  @Test
  void status204DevolveCorpoVazio() throws Exception {
    URI url = servir((t, n) -> responder(t, 204, ""));
    Resposta r = cliente(Duration.ofSeconds(5), 3).get(url);
    assertEquals(204, r.status());
    assertEquals(0, r.corpo().length);
    assertEquals(1, chamadas.get());
  }

  @Test
  void status422FalhaDeImediatoComUmaChamada() throws Exception {
    URI url = servir((t, n) -> responder(t, 422, "invalido"));
    assertThrows(FalhaHttp.class, () -> cliente(Duration.ofSeconds(5), 5).get(url));
    assertEquals(1, chamadas.get());
  }

  @Test
  void timeoutContaComoTentativaEERefeito() throws Exception {
    URI url =
        servir(
            (t, n) -> {
              if (n == 1) {
                try {
                  Thread.sleep(1500);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                try {
                  responder(t, 200, "tarde");
                } catch (IOException ignorado) {
                  // cliente já desistiu
                }
              } else {
                responder(t, 200, "ok");
              }
            });
    Resposta r = cliente(Duration.ofMillis(300), 3).get(url);
    assertEquals("ok", new String(r.corpo(), StandardCharsets.UTF_8));
    assertEquals(2, chamadas.get());
  }

  @Test
  void retryAfterEsperaPeloMenosUmSegundo() throws Exception {
    URI url =
        servir(
            (t, n) -> {
              if (n == 1) {
                t.getResponseHeaders().add("Retry-After", "1");
                responder(t, 429, "");
              } else {
                responder(t, 200, "ok");
              }
            });
    Resposta r = cliente(Duration.ofSeconds(5), 3).get(url);
    assertEquals(200, r.status());
    assertEquals(2, chamadas.get());
    long deltaMs = (instantes.get(1) - instantes.get(0)) / 1_000_000;
    assertTrue(deltaMs >= 1000, "esperou apenas " + deltaMs + " ms");
  }
}
