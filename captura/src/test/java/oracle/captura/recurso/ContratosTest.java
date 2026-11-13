package oracle.captura.recurso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import oracle.captura.Config;
import oracle.captura.ConfigInvalida;
import oracle.captura.Modo;
import oracle.captura.estado.Estado;
import oracle.captura.estado.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContratosTest {
  private static final LocalDate HOJE = LocalDate.of(2026, 11, 12);

  @TempDir Path raiz;
  private final Contratos recurso = new Contratos();

  private Config config(Modo modo, LocalDate ini, LocalDate fim) {
    return new Config(
        "contratos", modo, raiz, ini, fim, "GO", List.of(6), 3, 2,
        Duration.ofSeconds(5), 1, Duration.ofMillis(1), Duration.ofMillis(2),
        URI.create("http://localhost"), URI.create("http://localhost"));
  }

  private static UnidadeOrgao par(String cnpj, String codigo) {
    return new UnidadeOrgao(cnpj, codigo, "Unidade " + codigo, "GO", "5208707", true);
  }

  private void catalogo(UnidadeOrgao... pares) throws IOException {
    CatalogoUnidades.gravar(raiz, List.of(pares));
  }

  private static List<String> ids(List<UnidadeCaptura> unidades) {
    return unidades.stream().map(UnidadeCaptura::id).toList();
  }

  private static ResultadoUnidade resultado(String cnpj, String codigo, String janela, Status s) {
    SequencedMap<String, String> chave = new LinkedHashMap<>();
    chave.put("cnpj", cnpj);
    chave.put("unidade", codigo);
    chave.put("janela", janela);
    return new ResultadoUnidade(
        new UnidadeCaptura("contratos", chave), s, 0, 0, null, s == Status.FALHA ? "x" : null);
  }

  @Test
  void backfillGeraUmaUnidadePorParComAJanelaInteira() throws IOException {
    catalogo(par("111", "01"), par("111", "1"), par("222", "7/A"));
    var c = config(Modo.BACKFILL, LocalDate.of(2026, 8, 12), LocalDate.of(2026, 11, 12));
    var u = recurso.planejar(c, new Estado(), HOJE);
    assertEquals(
        List.of(
            "contratos/cnpj=111/unidade=01/janela=20260812-20261112",
            "contratos/cnpj=111/unidade=1/janela=20260812-20261112",
            "contratos/cnpj=222/unidade=7%2FA/janela=20260812-20261112"),
        ids(u));
  }

  @Test
  void backfillComCatalogoAusenteOuVazioLancaConfigInvalida() throws IOException {
    var c = config(Modo.BACKFILL, LocalDate.of(2026, 8, 12), LocalDate.of(2026, 11, 12));
    assertThrows(ConfigInvalida.class, () -> recurso.planejar(c, new Estado(), HOJE));
    catalogo();
    assertThrows(ConfigInvalida.class, () -> recurso.planejar(c, new Estado(), HOJE));
  }

  @Test
  void incrementalComecaNoWatermarkMenosSobreposicaoEParNovoNoInicioDoBackfill()
      throws IOException {
    catalogo(par("111", "01"), par("222", "02"));
    Estado e = new Estado();
    e.inicioBackfillContratos = LocalDate.of(2026, 8, 12);
    e.watermarkContratos.put("111/01", LocalDate.of(2026, 11, 10));
    var u = recurso.planejar(config(Modo.INCREMENTAL, null, null), e, HOJE);
    assertEquals(
        List.of(
            "contratos/cnpj=111/unidade=01/janela=20261108-20261112",
            "contratos/cnpj=222/unidade=02/janela=20260812-20261112"),
        ids(u));
  }

  @Test
  void incrementalSemInicioBackfillParaParNovoLancaConfigInvalida() throws IOException {
    catalogo(par("111", "01"), par("222", "02"));
    Estado e = new Estado();
    e.watermarkContratos.put("111/01", LocalDate.of(2026, 11, 10));
    assertThrows(
        ConfigInvalida.class,
        () -> recurso.planejar(config(Modo.INCREMENTAL, null, null), e, HOJE));
  }

  @Test
  void concluirNaoAvancaInicioDoBackfillParaDepoisNemRecuaWatermark() {
    Estado e = new Estado();
    e.inicioBackfillContratos = LocalDate.of(2026, 8, 12);
    e.watermarkContratos.put("111/01", LocalDate.of(2026, 11, 12));

    var posterior = config(Modo.BACKFILL, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));
    recurso.concluir(
        posterior, e, HOJE, List.of(resultado("111", "01", "20260901-20261001", Status.OK)));
    assertEquals(LocalDate.of(2026, 8, 12), e.inicioBackfillContratos);
    assertEquals(LocalDate.of(2026, 11, 12), e.watermarkContratos.get("111/01"));

    var anterior = config(Modo.BACKFILL, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));
    recurso.concluir(anterior, e, HOJE, List.of());
    assertEquals(LocalDate.of(2026, 7, 1), e.inicioBackfillContratos);
  }

  @Test
  void concluirAvancaWatermarkPorParInclusiveComFalhaEUsaChaveComCodigoCodificado() {
    Estado e = new Estado();
    e.watermarkContratos.put("111/01", LocalDate.of(2026, 11, 1));
    recurso.concluir(
        config(Modo.INCREMENTAL, null, null),
        e,
        HOJE,
        List.of(
            resultado("111", "01", "20261030-20261112", Status.FALHA),
            resultado("222", "7/A", "20261030-20261112", Status.OK),
            resultado("333", "03", "20261030-20261112", Status.VAZIA)));
    assertEquals(LocalDate.of(2026, 11, 12), e.watermarkContratos.get("111/01"));
    assertEquals(LocalDate.of(2026, 11, 12), e.watermarkContratos.get("222/7%2FA"));
    assertEquals(LocalDate.of(2026, 11, 12), e.watermarkContratos.get("333/03"));
    assertEquals(null, e.inicioBackfillContratos);
  }
}
