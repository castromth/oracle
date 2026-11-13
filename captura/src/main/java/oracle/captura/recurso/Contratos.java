package oracle.captura.recurso;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import oracle.captura.Config;
import oracle.captura.ConfigInvalida;
import oracle.captura.Modo;
import oracle.captura.estado.Estado;

/** Contratos por par {@code (cnpj, codigoUnidade)} do catálogo, numa janela de publicação. */
public final class Contratos implements Recurso {
  static final String NOME = "contratos";
  static final int TAMANHO_PAGINA = 500;
  private static final DateTimeFormatter COMPACTO = DateTimeFormatter.BASIC_ISO_DATE;

  @Override
  public String nome() {
    return NOME;
  }

  @Override
  public boolean usaJanela() {
    return true;
  }

  @Override
  public List<UnidadeCaptura> planejar(Config config, Estado estado, LocalDate hoje) {
    List<UnidadeOrgao> pares = lerCatalogo(config);
    boolean backfill = config.modo() == Modo.BACKFILL;
    if (backfill) {
      if (config.janelaInicio() == null || config.janelaFim() == null) {
        throw new ConfigInvalida("Backfill de contratos exige janela");
      }
      if (pares.isEmpty()) {
        throw new ConfigInvalida(
            "Catálogo de unidades ausente ou vazio em " + config.raiz() + "; rode 'unidades' antes");
      }
    }
    List<UnidadeCaptura> unidades = new ArrayList<>();
    for (UnidadeOrgao par : pares) {
      LocalDate inicio;
      LocalDate fim;
      if (backfill) {
        inicio = config.janelaInicio();
        fim = config.janelaFim();
      } else {
        LocalDate watermark = estado.watermarkContratos.get(par.chavePar());
        if (watermark != null) {
          inicio = watermark.minusDays(config.sobreposicaoDias());
        } else if (estado.inicioBackfillContratos != null) {
          inicio = estado.inicioBackfillContratos;
        } else {
          throw new ConfigInvalida(
              "Par " + par.chavePar() + " sem watermark e sem inicioBackfillContratos; faça backfill antes");
        }
        fim = hoje;
      }
      if (inicio.isAfter(fim)) {
        continue;
      }
      SequencedMap<String, String> chave = new LinkedHashMap<>();
      chave.put("cnpj", par.cnpj());
      chave.put("unidade", par.codigoUnidade());
      chave.put("janela", inicio.format(COMPACTO) + "-" + fim.format(COMPACTO));
      unidades.add(new UnidadeCaptura(NOME, chave));
    }
    return unidades;
  }

  @Override
  public ResultadoUnidade capturar(UnidadeCaptura unidade, Contexto contexto) {
    Config config = contexto.config();
    String[] janela = unidade.chave().get("janela").split("-", 2);
    SequencedMap<String, String> parametros = new LinkedHashMap<>();
    parametros.put("dataInicial", janela[0]);
    parametros.put("dataFinal", janela[1]);
    parametros.put("cnpjOrgao", unidade.chave().get("cnpj"));
    parametros.put("codigoUnidadeAdministrativa", unidade.chave().get("unidade"));
    URI endpoint = URI.create(config.baseConsulta() + "/v1/contratos");
    return Paginador.capturar(unidade, endpoint, parametros, TAMANHO_PAGINA, this, contexto);
  }

  @Override
  public int contarRegistros(JsonNode corpo) {
    return corpo.path("data").size();
  }

  @Override
  public void concluir(
      Config config, Estado estado, LocalDate hoje, List<ResultadoUnidade> resultados) {
    if (config.modo() == Modo.BACKFILL
        && (estado.inicioBackfillContratos == null
            || config.janelaInicio().isBefore(estado.inicioBackfillContratos))) {
      estado.inicioBackfillContratos = config.janelaInicio();
    }
    for (ResultadoUnidade r : resultados) {
      SequencedMap<String, String> chave = r.unidade().chave();
      LocalDate fim = LocalDate.parse(chave.get("janela").split("-", 2)[1], COMPACTO);
      String par =
          chave.get("cnpj") + "/" + URLEncoder.encode(chave.get("unidade"), StandardCharsets.UTF_8);
      LocalDate atual = estado.watermarkContratos.get(par);
      if (atual == null || fim.isAfter(atual)) {
        estado.watermarkContratos.put(par, fim);
      }
    }
  }

  private static List<UnidadeOrgao> lerCatalogo(Config config) {
    try {
      return CatalogoUnidades.ler(config.raiz());
    } catch (IOException e) {
      throw new ConfigInvalida("Catálogo de unidades ilegível: " + e.getMessage());
    }
  }
}
