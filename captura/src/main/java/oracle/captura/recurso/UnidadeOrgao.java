package oracle.captura.recurso;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Uma unidade administrativa de um órgão, como listada em {@code /orgaos/{cnpj}/unidades}. */
public record UnidadeOrgao(
    String cnpj,
    String codigoUnidade,
    String nomeUnidade,
    String uf,
    String ibge,
    boolean statusAtivo) {

  /** {@code cnpj/codigoUnidade}, com o código em URL-encode: a chave do par nos watermarks. */
  public String chavePar() {
    return cnpj + "/" + URLEncoder.encode(codigoUnidade, StandardCharsets.UTF_8);
  }

  /**
   * Extrai uma unidade de um item da resposta bruta. Vazio se faltar {@code orgao.cnpj} ou {@code
   * codigoUnidade}, sem os quais o par não pode ser identificado.
   */
  static Optional<UnidadeOrgao> deResposta(JsonNode item) {
    String cnpj = item.path("orgao").path("cnpj").asText("");
    String codigo = item.path("codigoUnidade").asText("");
    if (cnpj.isEmpty() || codigo.isEmpty()) {
      return Optional.empty();
    }
    JsonNode municipio = item.path("municipio");
    return Optional.of(
        new UnidadeOrgao(
            cnpj,
            codigo,
            item.path("nomeUnidade").asText(""),
            municipio.path("uf").path("siglaUF").asText(""),
            municipio.path("codigoIbge").asText(""),
            item.path("statusAtivo").asBoolean(false)));
  }
}
