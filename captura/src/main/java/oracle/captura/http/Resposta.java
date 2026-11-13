package oracle.captura.http;

import java.net.URI;
import java.time.Instant;

public record Resposta(URI url, int status, byte[] corpo, Instant recebidaEm) {}
