package oracle.captura;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {

  private static Map<String, String> minimo() {
    Map<String, String> env = new HashMap<>();
    env.put("CAPTURA_RECURSO", "contratacoes");
    env.put("CAPTURA_MODO", "backfill");
    env.put("CAPTURA_RAIZ", "dados");
    return env;
  }

  @Test
  void aplicaPadroesEValoresFixos() {
    Config c = Config.doAmbiente(minimo());

    assertEquals("contratacoes", c.recurso());
    assertEquals(Modo.BACKFILL, c.modo());
    assertTrue(c.raiz().isAbsolute());
    assertEquals(Path.of("dados").toAbsolutePath(), c.raiz());
    assertNull(c.janelaInicio());
    assertNull(c.janelaFim());
    assertEquals("GO", c.uf());
    assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14), c.modalidades());
    assertEquals(3, c.concorrencia());
    assertEquals(2, c.sobreposicaoDias());
    assertEquals(Duration.ofSeconds(90), c.timeout());
    assertEquals(6, c.maxTentativasHttp());
    assertEquals(Duration.ofSeconds(1), c.backoffBase());
    assertEquals(Duration.ofSeconds(60), c.backoffTeto());
    assertEquals(URI.create("https://pncp.gov.br/api/consulta"), c.baseConsulta());
    assertEquals(URI.create("https://pncp.gov.br/api/pncp"), c.basePncp());
  }

  @Test
  void leValoresInformados() {
    Map<String, String> env = minimo();
    env.put("CAPTURA_MODO", "Incremental");
    env.put("CAPTURA_RAIZ", "/tmp/x");
    env.put("CAPTURA_JANELA_INICIO", "2026-08-12");
    env.put("CAPTURA_JANELA_FIM", "2026-11-12");
    env.put("CAPTURA_UF", "df");
    env.put("CAPTURA_MODALIDADES", "8, 6");
    env.put("CAPTURA_CONCORRENCIA", "4");
    env.put("CAPTURA_SOBREPOSICAO_DIAS", "5");
    env.put("CAPTURA_TIMEOUT_S", "30");

    Config c = Config.doAmbiente(env);

    assertEquals(Modo.INCREMENTAL, c.modo());
    assertEquals(Path.of("/tmp/x"), c.raiz());
    assertEquals(LocalDate.of(2026, 8, 12), c.janelaInicio());
    assertEquals(LocalDate.of(2026, 11, 12), c.janelaFim());
    assertEquals("DF", c.uf());
    assertEquals(List.of(8, 6), c.modalidades());
    assertEquals(4, c.concorrencia());
    assertEquals(5, c.sobreposicaoDias());
    assertEquals(Duration.ofSeconds(30), c.timeout());
  }

  @Test
  void variavelObrigatoriaAusente() {
    for (String nome : List.of("CAPTURA_RECURSO", "CAPTURA_MODO", "CAPTURA_RAIZ")) {
      Map<String, String> env = minimo();
      env.remove(nome);
      ConfigInvalida e = assertThrows(ConfigInvalida.class, () -> Config.doAmbiente(env));
      assertTrue(e.getMessage().contains(nome), e.getMessage());
    }
  }

  @Test
  void modoDesconhecido() {
    Map<String, String> env = minimo();
    env.put("CAPTURA_MODO", "tudo");
    assertThrows(ConfigInvalida.class, () -> Config.doAmbiente(env));
  }

  @Test
  void dataIlegivel() {
    Map<String, String> env = minimo();
    env.put("CAPTURA_JANELA_INICIO", "12/11/2026");
    env.put("CAPTURA_JANELA_FIM", "2026-11-12");
    assertThrows(ConfigInvalida.class, () -> Config.doAmbiente(env));
  }

  @Test
  void soUmaPontaDaJanela() {
    Map<String, String> soInicio = minimo();
    soInicio.put("CAPTURA_JANELA_INICIO", "2026-11-01");
    assertThrows(ConfigInvalida.class, () -> Config.doAmbiente(soInicio));

    Map<String, String> soFim = minimo();
    soFim.put("CAPTURA_JANELA_FIM", "2026-11-01");
    assertThrows(ConfigInvalida.class, () -> Config.doAmbiente(soFim));
  }

  @Test
  void inicioDepoisDoFim() {
    Map<String, String> env = minimo();
    env.put("CAPTURA_JANELA_INICIO", "2026-11-02");
    env.put("CAPTURA_JANELA_FIM", "2026-11-01");
    assertThrows(ConfigInvalida.class, () -> Config.doAmbiente(env));
  }

  @Test
  void janelaDeUmDiaEhValida() {
    Map<String, String> env = minimo();
    env.put("CAPTURA_JANELA_INICIO", "2026-11-01");
    env.put("CAPTURA_JANELA_FIM", "2026-11-01");
    assertEquals(LocalDate.of(2026, 11, 1), Config.doAmbiente(env).janelaInicio());
  }

  @Test
  void concorrenciaForaDeDoisAQuatro() {
    for (String valor : List.of("1", "5", "0", "-3", "abc")) {
      Map<String, String> env = minimo();
      env.put("CAPTURA_CONCORRENCIA", valor);
      assertThrows(ConfigInvalida.class, () -> Config.doAmbiente(env), valor);
    }
    for (String valor : List.of("2", "3", "4")) {
      Map<String, String> env = minimo();
      env.put("CAPTURA_CONCORRENCIA", valor);
      assertEquals(Integer.parseInt(valor), Config.doAmbiente(env).concorrencia());
    }
  }

  @Test
  void modalidadesIlegiveis() {
    Map<String, String> env = minimo();
    env.put("CAPTURA_MODALIDADES", "8,x");
    assertThrows(ConfigInvalida.class, () -> Config.doAmbiente(env));
  }
}
