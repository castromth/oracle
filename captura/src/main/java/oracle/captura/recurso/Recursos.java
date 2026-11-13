package oracle.captura.recurso;

import oracle.captura.ConfigInvalida;

public final class Recursos {
  private Recursos() {}

  public static Recurso porNome(String nome) {
    if (Contratacoes.NOME.equals(nome)) {
      return new Contratacoes();
    }
    if (Unidades.NOME.equals(nome)) {
      return new Unidades();
    }
    if (Contratos.NOME.equals(nome)) {
      return new Contratos();
    }
    throw new ConfigInvalida("Recurso desconhecido: " + nome);
  }
}
