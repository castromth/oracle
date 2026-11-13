package oracle.captura.recurso;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import oracle.captura.Config;
import oracle.captura.estado.Estado;

/** Um recurso da API do PNCP capturável para o bronze. */
public interface Recurso {
  String nome();

  boolean usaJanela();

  /** Unidades da janela do run. Pode lançar {@code ConfigInvalida}. */
  List<UnidadeCaptura> planejar(Config config, Estado estado, LocalDate hoje);

  /** Não lança: falha vira {@code FALHA}. */
  ResultadoUnidade capturar(UnidadeCaptura unidade, Contexto contexto);

  int contarRegistros(JsonNode corpo);

  void concluir(Config config, Estado estado, LocalDate hoje, List<ResultadoUnidade> resultados)
      throws IOException;
}
