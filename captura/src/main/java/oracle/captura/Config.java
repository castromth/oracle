package oracle.captura;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

public record Config(
    String recurso,
    Modo modo,
    Path raiz,
    LocalDate janelaInicio,
    LocalDate janelaFim,
    String uf,
    List<Integer> modalidades,
    int concorrencia,
    int sobreposicaoDias,
    Duration timeout,
    int maxTentativasHttp,
    Duration backoffBase,
    Duration backoffTeto,
    URI baseConsulta,
    URI basePncp) {

  public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

  private static final int MAX_TENTATIVAS_HTTP = 6;
  private static final Duration BACKOFF_BASE = Duration.ofSeconds(1);
  private static final Duration BACKOFF_TETO = Duration.ofSeconds(60);
  private static final URI BASE_CONSULTA = URI.create("https://pncp.gov.br/api/consulta");
  private static final URI BASE_PNCP = URI.create("https://pncp.gov.br/api/pncp");

  public static Config doAmbiente(Map<String, String> ambiente) {
    String recurso = obrigatoria(ambiente, "CAPTURA_RECURSO");
    Modo modo = lerModo(obrigatoria(ambiente, "CAPTURA_MODO"));
    Path raiz = lerRaiz(obrigatoria(ambiente, "CAPTURA_RAIZ"));

    LocalDate inicio = lerData(ambiente, "CAPTURA_JANELA_INICIO");
    LocalDate fim = lerData(ambiente, "CAPTURA_JANELA_FIM");
    if ((inicio == null) != (fim == null)) {
      throw new ConfigInvalida(
          "CAPTURA_JANELA_INICIO e CAPTURA_JANELA_FIM devem ser informadas juntas ou nenhuma");
    }
    if (inicio != null && inicio.isAfter(fim)) {
      throw new ConfigInvalida(
          "CAPTURA_JANELA_INICIO (" + inicio + ") é posterior a CAPTURA_JANELA_FIM (" + fim + ")");
    }

    String uf = opcional(ambiente, "CAPTURA_UF");
    uf = uf == null ? "GO" : uf.toUpperCase(Locale.ROOT);
    if (!uf.matches("[A-Z]{2}")) {
      throw new ConfigInvalida("CAPTURA_UF inválida: " + uf);
    }

    List<Integer> modalidades = lerModalidades(opcional(ambiente, "CAPTURA_MODALIDADES"));

    int concorrencia = lerInteiro(ambiente, "CAPTURA_CONCORRENCIA", 3);
    if (concorrencia < 2 || concorrencia > 4) {
      throw new ConfigInvalida("CAPTURA_CONCORRENCIA deve estar entre 2 e 4: " + concorrencia);
    }

    int sobreposicao = lerInteiro(ambiente, "CAPTURA_SOBREPOSICAO_DIAS", 2);
    if (sobreposicao < 0) {
      throw new ConfigInvalida("CAPTURA_SOBREPOSICAO_DIAS não pode ser negativa: " + sobreposicao);
    }

    int timeoutSegundos = lerInteiro(ambiente, "CAPTURA_TIMEOUT_S", 90);
    if (timeoutSegundos < 1) {
      throw new ConfigInvalida("CAPTURA_TIMEOUT_S deve ser positiva: " + timeoutSegundos);
    }

    return new Config(
        recurso,
        modo,
        raiz,
        inicio,
        fim,
        uf,
        modalidades,
        concorrencia,
        sobreposicao,
        Duration.ofSeconds(timeoutSegundos),
        MAX_TENTATIVAS_HTTP,
        BACKOFF_BASE,
        BACKOFF_TETO,
        BASE_CONSULTA,
        BASE_PNCP);
  }

  private static String opcional(Map<String, String> ambiente, String nome) {
    String valor = ambiente.get(nome);
    if (valor == null || valor.isBlank()) {
      return null;
    }
    return valor.trim();
  }

  private static String obrigatoria(Map<String, String> ambiente, String nome) {
    String valor = opcional(ambiente, nome);
    if (valor == null) {
      throw new ConfigInvalida("Variável obrigatória ausente: " + nome);
    }
    return valor;
  }

  private static Modo lerModo(String texto) {
    return switch (texto.toLowerCase(Locale.ROOT)) {
      case "backfill" -> Modo.BACKFILL;
      case "incremental" -> Modo.INCREMENTAL;
      default -> throw new ConfigInvalida(
          "CAPTURA_MODO desconhecido: " + texto + " (use backfill ou incremental)");
    };
  }

  private static Path lerRaiz(String texto) {
    try {
      return Path.of(texto).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      throw new ConfigInvalida("CAPTURA_RAIZ inválida: " + texto);
    }
  }

  private static LocalDate lerData(Map<String, String> ambiente, String nome) {
    String texto = opcional(ambiente, nome);
    if (texto == null) {
      return null;
    }
    try {
      return LocalDate.parse(texto);
    } catch (DateTimeParseException e) {
      throw new ConfigInvalida(nome + " ilegível (esperado YYYY-MM-DD): " + texto);
    }
  }

  private static int lerInteiro(Map<String, String> ambiente, String nome, int padrao) {
    String texto = opcional(ambiente, nome);
    if (texto == null) {
      return padrao;
    }
    try {
      return Integer.parseInt(texto);
    } catch (NumberFormatException e) {
      throw new ConfigInvalida(nome + " não é um inteiro: " + texto);
    }
  }

  private static List<Integer> lerModalidades(String texto) {
    if (texto == null) {
      return IntStream.rangeClosed(1, 14).boxed().toList();
    }
    List<Integer> codigos = new ArrayList<>();
    for (String parte : texto.split(",")) {
      String p = parte.trim();
      try {
        int codigo = Integer.parseInt(p);
        if (codigo < 1) {
          throw new ConfigInvalida("CAPTURA_MODALIDADES com código inválido: " + p);
        }
        if (!codigos.contains(codigo)) {
          codigos.add(codigo);
        }
      } catch (NumberFormatException e) {
        throw new ConfigInvalida("CAPTURA_MODALIDADES com código ilegível: '" + p + "'");
      }
    }
    return List.copyOf(codigos);
  }
}
