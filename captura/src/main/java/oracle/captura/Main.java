package oracle.captura;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {

  private static final Logger LOG = LoggerFactory.getLogger(Main.class);

  private Main() {}

  public static void main(String[] args) {
    int saida;
    try {
      saida = Aplicacao.executar(Config.doAmbiente(System.getenv()), Clock.system(Config.FUSO));
    } catch (ConfigInvalida e) {
      LOG.error("Configuração inválida: {}", e.getMessage());
      saida = 1;
    }
    System.exit(saida);
  }
}
