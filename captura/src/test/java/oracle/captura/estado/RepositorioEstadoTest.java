package oracle.captura.estado;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositorioEstadoTest {
  @TempDir Path tmp;

  @Test
  void arquivoAusenteDevolveEstadoVazio() throws IOException {
    Estado e = new RepositorioEstado(tmp.resolve("estado/captura.json")).carregar();
    assertEquals(1, e.versao);
    assertNull(e.watermarkContratacoes);
    assertNull(e.inicioBackfillContratos);
    assertTrue(e.watermarkContratos.isEmpty());
    assertTrue(e.unidadesCaptura.isEmpty());
  }

  @Test
  void idaEVoltaPreservaTudo() throws IOException {
    RepositorioEstado repo = new RepositorioEstado(tmp.resolve("estado/captura.json"));
    Estado e = new Estado();
    e.watermarkContratacoes = LocalDate.parse("2026-11-12");
    e.inicioBackfillContratos = LocalDate.parse("2026-08-12");
    e.watermarkContratos.put("01165729000180/2985", LocalDate.parse("2026-11-12"));
    e.watermarkContratos.put("01165729000180/01", LocalDate.parse("2026-11-11"));
    Instant t = Instant.parse("2026-11-12T06:10:22Z");
    LocalDate d = LocalDate.parse("2026-11-12");
    e.unidadesCaptura.put(
        "contratacoes/dia=2026-11-10/modalidade=8",
        new RegistroUnidade(
            Status.OK, 1, t, d, 7, 331,
            "bronze/metadados/contratacoes/data_captura=2026-11-12/dia=2026-11-10/modalidade=8",
            null));
    e.unidadesCaptura.put(
        "contratacoes/dia=2026-11-10/modalidade=9",
        new RegistroUnidade(Status.VAZIA, 2, t, d, 1, 0, null, null));
    e.unidadesCaptura.put(
        "contratacoes/dia=2026-11-10/modalidade=10",
        new RegistroUnidade(Status.FALHA, 3, t, d, 0, 0, null, "HTTP 500"));
    e.unidadesCaptura.put(
        "contratacoes/dia=2026-11-10/modalidade=11",
        new RegistroUnidade(Status.PENDENTE, 0, t, null, 0, 0, null, null));

    repo.gravar(e);
    Estado lido = repo.carregar();

    assertEquals(e.versao, lido.versao);
    assertEquals(e.watermarkContratacoes, lido.watermarkContratacoes);
    assertEquals(e.inicioBackfillContratos, lido.inicioBackfillContratos);
    assertEquals(e.watermarkContratos, lido.watermarkContratos);
    assertEquals(e.unidadesCaptura, lido.unidadesCaptura);
    assertEquals("01165729000180/01", lido.watermarkContratos.keySet().iterator().next());
  }

  @Test
  void gravarDuasVezesDeixaSoOArquivoFinal() throws IOException {
    Path dir = tmp.resolve("estado");
    RepositorioEstado repo = new RepositorioEstado(dir.resolve("captura.json"));
    Estado e = new Estado();
    repo.gravar(e);
    e.watermarkContratacoes = LocalDate.parse("2026-11-12");
    repo.gravar(e);

    try (Stream<Path> s = Files.list(dir)) {
      assertEquals(List.of("captura.json"), s.map(p -> p.getFileName().toString()).toList());
    }
    assertEquals(LocalDate.parse("2026-11-12"), repo.carregar().watermarkContratacoes);
  }
}
