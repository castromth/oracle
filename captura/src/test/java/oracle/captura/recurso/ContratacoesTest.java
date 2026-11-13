package oracle.captura.recurso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import oracle.captura.Config;
import oracle.captura.ConfigInvalida;
import oracle.captura.Modo;
import oracle.captura.estado.Estado;
import org.junit.jupiter.api.Test;

class ContratacoesTest {
  private static Config config(Modo modo, LocalDate ini, LocalDate fim) {
    return new Config(
        "contratacoes", modo, Path.of("/tmp/x"), ini, fim, "GO", List.of(6, 8), 3, 2,
        Duration.ofSeconds(5), 1, Duration.ofMillis(1), Duration.ofMillis(2),
        URI.create("http://localhost"), URI.create("http://localhost"));
  }

  private final Contratacoes recurso = new Contratacoes();

  @Test
  void backfillGeraDiasVezesModalidades() {
    var c = config(Modo.BACKFILL, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));
    var u = recurso.planejar(c, new Estado(), LocalDate.of(2026, 11, 12));
    assertEquals(6, u.size());
    assertEquals("contratacoes/dia=2026-11-01/modalidade=6", u.get(0).id());
    assertEquals("contratacoes/dia=2026-11-03/modalidade=8", u.get(5).id());
  }

  @Test
  void incrementalComecaDoisDiasAntesDoWatermarkAteHoje() {
    Estado e = new Estado();
    e.watermarkContratacoes = LocalDate.of(2026, 11, 10);
    var u = recurso.planejar(config(Modo.INCREMENTAL, null, null), e, LocalDate.of(2026, 11, 12));
    assertEquals("contratacoes/dia=2026-11-08/modalidade=6", u.get(0).id());
    assertEquals("contratacoes/dia=2026-11-12/modalidade=8", u.get(u.size() - 1).id());
    assertEquals(10, u.size());
  }

  @Test
  void incrementalSemWatermarkLancaConfigInvalida() {
    assertThrows(
        ConfigInvalida.class,
        () ->
            recurso.planejar(
                config(Modo.INCREMENTAL, null, null), new Estado(), LocalDate.of(2026, 11, 12)));
  }

  @Test
  void concluirNaoRecuaOWatermark() throws Exception {
    Estado e = new Estado();
    e.watermarkContratacoes = LocalDate.of(2026, 11, 10);
    var antiga = config(Modo.BACKFILL, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5));
    recurso.concluir(antiga, e, LocalDate.of(2026, 11, 12), List.of());
    assertEquals(LocalDate.of(2026, 11, 10), e.watermarkContratacoes);

    recurso.concluir(config(Modo.INCREMENTAL, null, null), e, LocalDate.of(2026, 11, 12), List.of());
    assertEquals(LocalDate.of(2026, 11, 12), e.watermarkContratacoes);
  }
}
