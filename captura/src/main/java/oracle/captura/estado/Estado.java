package oracle.captura.estado;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/** POJO mutável; só o coordenador altera. */
public final class Estado {
  public int versao = 1;
  public LocalDate watermarkContratacoes;
  public LocalDate inicioBackfillContratos;
  public Map<String, LocalDate> watermarkContratos = new TreeMap<>();
  public Map<String, RegistroUnidade> unidadesCaptura = new TreeMap<>();
}
