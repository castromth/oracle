package oracle.captura.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Cliente HTTP do PNCP: GET com retry para 429, 5xx, erro de E/S e timeout. */
public final class ClientePncp {
  private static final Logger LOG = LoggerFactory.getLogger(ClientePncp.class);

  private final HttpClient cliente;
  private final Duration timeout;
  private final int maxTentativas;
  private final Duration backoffBase;
  private final Duration backoffTeto;

  public ClientePncp(
      Duration timeout, int maxTentativas, Duration backoffBase, Duration backoffTeto) {
    this.timeout = timeout;
    this.maxTentativas = maxTentativas;
    this.backoffBase = backoffBase;
    this.backoffTeto = backoffTeto;
    this.cliente =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** Devolve só 200 ou 204; qualquer outro desfecho vira {@link FalhaHttp}. */
  public Resposta get(URI url) throws FalhaHttp {
    HttpRequest requisicao =
        HttpRequest.newBuilder(url)
            .GET()
            .header("Accept", "application/json")
            .timeout(timeout)
            .build();
    FalhaHttp ultima = null;
    for (int tentativa = 1; tentativa <= maxTentativas; tentativa++) {
      Duration espera;
      try {
        HttpResponse<byte[]> r = cliente.send(requisicao, HttpResponse.BodyHandlers.ofByteArray());
        int status = r.statusCode();
        if (status == 200 || status == 204) {
          return new Resposta(url, status, r.body(), Instant.now());
        }
        if (status != 429 && status < 500) {
          throw new FalhaHttp("HTTP " + status + " em " + url, null);
        }
        String retryAfter = r.headers().firstValue("Retry-After").orElse(null);
        Long segundos = null;
        if (retryAfter != null) {
          try {
            segundos = Long.parseLong(retryAfter.trim());
          } catch (NumberFormatException e) {
            segundos = null;
          }
        }
        if (status == 429) {
          LOG.warn(
              "429 em {} (tentativa {}/{}): Retry-After {}",
              url,
              tentativa,
              maxTentativas,
              retryAfter == null ? "ausente" : "presente (" + retryAfter + ")");
        } else {
          LOG.warn("HTTP {} em {} (tentativa {}/{})", status, url, tentativa, maxTentativas);
        }
        ultima = new FalhaHttp("HTTP " + status + " em " + url + " após " + tentativa + " tentativas", null);
        espera = segundos != null && segundos >= 0 ? Duration.ofSeconds(segundos) : backoff(tentativa);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new FalhaHttp("Interrompido em " + url, e);
      } catch (IOException e) {
        LOG.warn(
            "{} em {} (tentativa {}/{}): {}",
            e.getClass().getSimpleName(),
            url,
            tentativa,
            maxTentativas,
            e.getMessage());
        ultima = new FalhaHttp("Erro de E/S em " + url + " após " + tentativa + " tentativas", e);
        espera = backoff(tentativa);
      }
      if (tentativa < maxTentativas) {
        try {
          Thread.sleep(espera);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new FalhaHttp("Interrompido em " + url, e);
        }
      }
    }
    if (ultima == null) {
      throw new FalhaHttp("Nenhuma tentativa feita para " + url, null);
    }
    throw ultima;
  }

  private Duration backoff(int tentativa) {
    long baseNs = backoffBase.toNanos();
    long tetoNs = backoffTeto.toNanos();
    long limite = tetoNs;
    int expoente = tentativa - 1;
    if (expoente < 62 && baseNs <= (Long.MAX_VALUE >> expoente)) {
      limite = Math.min(tetoNs, baseNs << expoente);
    }
    if (limite <= 0) {
      return Duration.ZERO;
    }
    return Duration.ofNanos(ThreadLocalRandom.current().nextLong(limite + 1));
  }
}
