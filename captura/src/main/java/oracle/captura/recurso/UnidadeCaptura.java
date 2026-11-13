package oracle.captura.recurso;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;

public record UnidadeCaptura(String recurso, SequencedMap<String, String> chave) {

  public UnidadeCaptura {
    chave = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(chave));
  }

  /** {@code recurso/k1=v1/k2=v2}, valores em URL-encode, ordem das chaves preservada. */
  public String id() {
    StringBuilder sb = new StringBuilder(recurso);
    chave.forEach(
        (k, v) ->
            sb.append('/').append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
    return sb.toString();
  }

  public static UnidadeCaptura deId(String id) {
    String[] partes = id.split("/", -1);
    if (partes[0].isEmpty()) {
      throw new IllegalArgumentException("Id de unidade sem recurso: " + id);
    }
    SequencedMap<String, String> chave = new LinkedHashMap<>();
    for (int i = 1; i < partes.length; i++) {
      int igual = partes[i].indexOf('=');
      if (igual <= 0) {
        throw new IllegalArgumentException("Segmento inválido '" + partes[i] + "' no id: " + id);
      }
      String valor = URLDecoder.decode(partes[i].substring(igual + 1), StandardCharsets.UTF_8);
      if (chave.put(partes[i].substring(0, igual), valor) != null) {
        throw new IllegalArgumentException("Chave repetida no id: " + id);
      }
    }
    return new UnidadeCaptura(partes[0], chave);
  }
}
