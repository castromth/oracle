package oracle.captura.recurso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;

class UnidadeCapturaTest {

  private static UnidadeCaptura unidade(String recurso, String... paresKV) {
    SequencedMap<String, String> chave = new LinkedHashMap<>();
    for (int i = 0; i < paresKV.length; i += 2) {
      chave.put(paresKV[i], paresKV[i + 1]);
    }
    return new UnidadeCaptura(recurso, chave);
  }

  @Test
  void idPreservaOrdemDasChaves() {
    assertEquals(
        "contratacoes/dia=2026-11-10/modalidade=8",
        unidade("contratacoes", "dia", "2026-11-10", "modalidade", "8").id());
    assertEquals(
        "contratacoes/modalidade=8/dia=2026-11-10",
        unidade("contratacoes", "modalidade", "8", "dia", "2026-11-10").id());
  }

  @Test
  void idaEVoltaPreservaUnidade() {
    List<UnidadeCaptura> casos =
        List.of(
            unidade("contratacoes", "dia", "2026-11-10", "modalidade", "8"),
            unidade("contratos", "cnpj", "01165729000180", "unidade", "01", "janela", "20260101-20260131"),
            unidade("contratos", "cnpj", "01165729000180", "unidade", "1", "janela", "20260101-20260131"),
            unidade("contratos", "cnpj", "01165729000180", "unidade", "A/B 1=2%", "janela", "x"),
            unidade("unidades", "cnpj", "01165729000180"));
    for (UnidadeCaptura u : casos) {
      UnidadeCaptura volta = UnidadeCaptura.deId(u.id());
      assertEquals(u, volta, u.id());
      assertEquals(List.copyOf(u.chave().entrySet()), List.copyOf(volta.chave().entrySet()));
    }
  }

  @Test
  void valorComBarraNaoViraNovoSegmento() {
    UnidadeCaptura u = unidade("contratos", "unidade", "A/B");
    assertEquals("contratos/unidade=A%2FB", u.id());
    assertEquals("A/B", UnidadeCaptura.deId(u.id()).chave().get("unidade"));
  }

  @Test
  void zeroAEsquerdaNaoSeConfundeComSemZero() {
    assertEquals("01", UnidadeCaptura.deId("contratos/unidade=01").chave().get("unidade"));
    assertEquals("1", UnidadeCaptura.deId("contratos/unidade=1").chave().get("unidade"));
  }

  @Test
  void copiaImutavelDoMapa() {
    SequencedMap<String, String> original = new LinkedHashMap<>();
    original.put("dia", "2026-11-10");
    UnidadeCaptura u = new UnidadeCaptura("contratacoes", original);
    original.put("modalidade", "8");

    assertEquals("contratacoes/dia=2026-11-10", u.id());
    assertThrows(UnsupportedOperationException.class, () -> u.chave().put("x", "y"));
  }

  @Test
  void idMalformadoLancaIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () -> UnidadeCaptura.deId("contratos/semigual"));
    assertThrows(IllegalArgumentException.class, () -> UnidadeCaptura.deId("/dia=1"));
  }
}
