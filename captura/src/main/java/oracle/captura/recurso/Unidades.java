package oracle.captura.recurso;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import oracle.captura.Config;
import oracle.captura.ConfigInvalida;
import oracle.captura.Json;
import oracle.captura.Modo;
import oracle.captura.bronze.EscritorBronze;
import oracle.captura.bronze.LeitorBronze;
import oracle.captura.estado.Estado;
import oracle.captura.estado.RegistroUnidade;
import oracle.captura.estado.Status;
import oracle.captura.http.FalhaHttp;
import oracle.captura.http.Resposta;

/** Lista as unidades de cada órgão visto nas contratações e mantém o catálogo derivado. */
public final class Unidades implements Recurso {
  static final String NOME = "unidades";
  private static final String PREFIXO_CONTRATACOES = Contratacoes.NOME + "/";

  @Override
  public String nome() {
    return NOME;
  }

  @Override
  public boolean usaJanela() {
    return false;
  }

  @Override
  public List<UnidadeCaptura> planejar(Config config, Estado estado, LocalDate hoje) {
    TreeSet<String> cnpjs = new TreeSet<>();
    for (Map.Entry<String, RegistroUnidade> e : estado.unidadesCaptura.entrySet()) {
      RegistroUnidade r = e.getValue();
      if (!e.getKey().startsWith(PREFIXO_CONTRATACOES)
          || r.status() != Status.OK
          || r.caminho() == null) {
        continue;
      }
      try {
        for (JsonNode corpo : LeitorBronze.corpos(config.raiz().resolve(r.caminho()))) {
          for (JsonNode item : corpo.path("data")) {
            String cnpj = item.path("orgaoEntidade").path("cnpj").asText("");
            if (!cnpj.isEmpty()) {
              cnpjs.add(cnpj);
            }
          }
        }
      } catch (IOException ex) {
        throw new ConfigInvalida(
            "Bronze de " + e.getKey() + " ilegível em " + r.caminho() + ": " + ex.getMessage());
      }
    }
    List<UnidadeCaptura> unidades = new ArrayList<>();
    for (String cnpj : cnpjs) {
      UnidadeCaptura unidade = unidade(cnpj);
      if (config.modo() == Modo.INCREMENTAL && jaListada(estado.unidadesCaptura.get(unidade.id()))) {
        continue;
      }
      unidades.add(unidade);
    }
    return unidades;
  }

  private static boolean jaListada(RegistroUnidade r) {
    return r != null && (r.status() == Status.OK || r.status() == Status.VAZIA);
  }

  private static UnidadeCaptura unidade(String cnpj) {
    SequencedMap<String, String> chave = new LinkedHashMap<>();
    chave.put("cnpj", cnpj);
    return new UnidadeCaptura(NOME, chave);
  }

  @Override
  public ResultadoUnidade capturar(UnidadeCaptura unidade, Contexto contexto) {
    EscritorBronze.Sessao sessao;
    try {
      sessao = contexto.bronze().abrir(unidade);
    } catch (IOException e) {
      return falha(unidade, e);
    }
    try {
      String cnpj = URLEncoder.encode(unidade.chave().get("cnpj"), StandardCharsets.UTF_8);
      URI url = URI.create(contexto.config().basePncp() + "/v1/orgaos/" + cnpj + "/unidades");
      Resposta resposta = contexto.http().get(url);
      if (resposta.status() == 204) {
        sessao.descartar();
        return new ResultadoUnidade(unidade, Status.VAZIA, 0, 0, null, null);
      }
      JsonNode corpo = Json.MAPPER.readTree(resposta.corpo());
      if (!corpo.isArray()) {
        throw new IOException("Resposta de unidades não é um array JSON");
      }
      sessao.gravarPagina(1, resposta, new LinkedHashMap<>());
      String caminho = sessao.concluir();
      return new ResultadoUnidade(unidade, Status.OK, 1, contarRegistros(corpo), caminho, null);
    } catch (FalhaHttp | IOException | RuntimeException e) {
      sessao.descartar();
      return falha(unidade, e);
    }
  }

  private static ResultadoUnidade falha(UnidadeCaptura unidade, Exception e) {
    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    return new ResultadoUnidade(unidade, Status.FALHA, 0, 0, null, msg);
  }

  @Override
  public int contarRegistros(JsonNode corpo) {
    return corpo.size();
  }

  /** Reescreve o catálogo a partir de todas as unidades {@code unidades/cnpj=…} com status OK. */
  @Override
  public void concluir(
      Config config, Estado estado, LocalDate hoje, List<ResultadoUnidade> resultados)
      throws IOException {
    Map<String, UnidadeOrgao> catalogo = new TreeMap<>();
    Path raiz = config.raiz();
    for (Map.Entry<String, RegistroUnidade> e : estado.unidadesCaptura.entrySet()) {
      RegistroUnidade r = e.getValue();
      if (!e.getKey().startsWith(NOME + "/") || r.status() != Status.OK || r.caminho() == null) {
        continue;
      }
      for (JsonNode corpo : LeitorBronze.corpos(raiz.resolve(r.caminho()))) {
        for (JsonNode item : corpo) {
          UnidadeOrgao.deResposta(item)
              .filter(u -> config.uf().equals(u.uf()))
              .ifPresent(u -> catalogo.put(u.chavePar(), u));
        }
      }
    }
    CatalogoUnidades.gravar(raiz, catalogo.values());
  }
}
