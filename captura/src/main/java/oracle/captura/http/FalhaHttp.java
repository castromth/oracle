package oracle.captura.http;

public final class FalhaHttp extends Exception {
  public FalhaHttp(String mensagem, Throwable causa) {
    super(mensagem, causa);
  }
}
