package oracle.captura.recurso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogoUnidadesTest {
  @TempDir Path raiz;

  private static UnidadeOrgao u(String cnpj, String codigo, String nome) {
    return new UnidadeOrgao(cnpj, codigo, nome, "GO", "5211909", true);
  }

  @Test
  void arquivoAusenteDevolveListaVazia() throws IOException {
    assertTrue(CatalogoUnidades.ler(raiz).isEmpty());
  }

  @Test
  void idaEVoltaOrdenaPorCnpjECodigoEMantemCodigosDistintos() throws IOException {
    var b1 = u("22222222000122", "1", "Secretaria \"Á\"");
    var a01 = u("11111111000111", "01", "Zero um");
    var a1 = u("11111111000111", "1", "Um");
    var inativa = new UnidadeOrgao("11111111000111", "10", "Dez", "GO", "5208707", false);

    CatalogoUnidades.gravar(raiz, List.of(b1, a1, inativa, a01));

    assertEquals(List.of(a01, a1, inativa, b1), CatalogoUnidades.ler(raiz));
    assertEquals("11111111000111/01", a01.chavePar());
    assertEquals("11111111000111/1", a1.chavePar());
  }

  @Test
  void gravarReescreveOArquivoInteiroSemDeixarTemporarios() throws IOException {
    CatalogoUnidades.gravar(raiz, List.of(u("1", "1", "a"), u("1", "2", "b")));
    CatalogoUnidades.gravar(raiz, List.of(u("3", "1", "c")));

    assertEquals(List.of(u("3", "1", "c")), CatalogoUnidades.ler(raiz));
    try (Stream<Path> s = Files.list(raiz.resolve("catalogo"))) {
      assertEquals(List.of("unidades.jsonl"), s.map(p -> p.getFileName().toString()).toList());
    }
  }

  @Test
  void chavePar_codificaCodigoComCaracterEspecial() {
    assertEquals("1/a%2Fb", u("1", "a/b", "x").chavePar());
  }
}
