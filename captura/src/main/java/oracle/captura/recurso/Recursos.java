package oracle.captura.recurso;

import oracle.captura.ConfigInvalida;

public final class Recursos {
  private Recursos() {}

  public static Recurso porNome(String nome) {
    if (Contratacoes.NOME.equals(nome)) {
      return new Contratacoes();
    }
    throw new ConfigInvalida("Recurso desconhecido: " + nome);
  }
}
