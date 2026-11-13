package oracle.captura.recurso;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
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

public final class Contratacoes implements Recurso {
  static final String NOME = "contratacoes";
  static final int TAMANHO_PAGINA = 50;
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
    LocalDate inicio;
    LocalDate fim;
    if (config.modo() == Modo.BACKFILL) {
      inicio = config.janelaInicio();
      fim = config.janelaFim();
      if (inicio == null || fim == null) {
        throw new ConfigInvalida("Backfill de contratacoes exige janela");
      }
    } else {
      if (estado.watermarkContratacoes == null) {
        throw new ConfigInvalida("Incremental de contratacoes exige watermark; faça backfill antes");
      }
      inicio = estado.watermarkContratacoes.minusDays(config.sobreposicaoDias());
      fim = hoje;
    }
    List<UnidadeCaptura> unidades = new ArrayList<>();
    for (LocalDate dia = inicio; !dia.isAfter(fim); dia = dia.plusDays(1)) {
      for (int modalidade : config.modalidades()) {
        SequencedMap<String, String> chave = new LinkedHashMap<>();
        chave.put("dia", dia.toString());
        chave.put("modalidade", Integer.toString(modalidade));
        unidades.add(new UnidadeCaptura(NOME, chave));
      }
    }
    return unidades;
  }

  @Override
  public ResultadoUnidade capturar(UnidadeCaptura unidade, Contexto contexto) {
    Config config = contexto.config();
    String dia = LocalDate.parse(unidade.chave().get("dia")).format(COMPACTO);
    SequencedMap<String, String> parametros = new LinkedHashMap<>();
    parametros.put("dataInicial", dia);
    parametros.put("dataFinal", dia);
    parametros.put("codigoModalidadeContratacao", unidade.chave().get("modalidade"));
    parametros.put("uf", config.uf());
    URI endpoint = URI.create(config.baseConsulta() + "/v1/contratacoes/publicacao");
    return Paginador.capturar(unidade, endpoint, parametros, TAMANHO_PAGINA, this, contexto);
  }

  @Override
  public int contarRegistros(JsonNode corpo) {
    return corpo.path("data").size();
  }

  @Override
  public void concluir(
      Config config, Estado estado, LocalDate hoje, List<ResultadoUnidade> resultados) {
    LocalDate fim = config.modo() == Modo.BACKFILL ? config.janelaFim() : hoje;
    if (estado.watermarkContratacoes == null || fim.isAfter(estado.watermarkContratacoes)) {
      estado.watermarkContratacoes = fim;
    }
  }
}
