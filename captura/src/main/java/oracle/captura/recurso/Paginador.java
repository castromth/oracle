package oracle.captura.recurso;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.stream.Collectors;
import oracle.captura.Json;
import oracle.captura.bronze.EscritorBronze;
import oracle.captura.estado.Status;
import oracle.captura.http.FalhaHttp;
import oracle.captura.http.Resposta;

/** Captura todas as páginas de uma unidade e a grava no bronze, completa ou ausente. */
public final class Paginador {
  private Paginador() {}

  public static ResultadoUnidade capturar(
      UnidadeCaptura unidade,
      URI endpoint,
      SequencedMap<String, String> parametros,
      int tamanhoPagina,
      Recurso recurso,
      Contexto contexto) {
    EscritorBronze.Sessao sessao;
    try {
      sessao = contexto.bronze().abrir(unidade);
    } catch (IOException e) {
      return falha(unidade, e);
    }
    try {
      Resposta primeira = buscar(contexto, endpoint, parametros, 1, tamanhoPagina, sessao);
      if (primeira.status() == 204) {
        sessao.descartar();
        return new ResultadoUnidade(unidade, Status.VAZIA, 0, 0, null, null);
      }
      JsonNode corpo = Json.MAPPER.readTree(primeira.corpo());
      int totalPaginas = Math.max(1, corpo.path("totalPaginas").asInt(1));
      int registros = recurso.contarRegistros(corpo);
      for (int pagina = 2; pagina <= totalPaginas; pagina++) {
        Resposta resposta = buscar(contexto, endpoint, parametros, pagina, tamanhoPagina, sessao);
        if (resposta.status() == 204) {
          throw new IOException("204 na página " + pagina + " de " + totalPaginas);
        }
        registros += recurso.contarRegistros(Json.MAPPER.readTree(resposta.corpo()));
      }
      String caminho = sessao.concluir();
      return new ResultadoUnidade(unidade, Status.OK, totalPaginas, registros, caminho, null);
    } catch (FalhaHttp | IOException | RuntimeException e) {
      sessao.descartar();
      return falha(unidade, e);
    }
  }

  private static Resposta buscar(
      Contexto contexto,
      URI endpoint,
      SequencedMap<String, String> parametros,
      int pagina,
      int tamanhoPagina,
      EscritorBronze.Sessao sessao)
      throws FalhaHttp, IOException {
    SequencedMap<String, String> todos = new LinkedHashMap<>(parametros);
    todos.put("pagina", Integer.toString(pagina));
    todos.put("tamanhoPagina", Integer.toString(tamanhoPagina));
    String consulta =
        todos.entrySet().stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    Resposta resposta = contexto.http().get(URI.create(endpoint + "?" + consulta));
    if (resposta.status() != 204) {
      Json.MAPPER.readTree(resposta.corpo()); // valida JSON antes de gravar
      sessao.gravarPagina(pagina, resposta, todos);
    }
    return resposta;
  }

  private static ResultadoUnidade falha(UnidadeCaptura unidade, Exception e) {
    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    return new ResultadoUnidade(unidade, Status.FALHA, 0, 0, null, msg);
  }
}
