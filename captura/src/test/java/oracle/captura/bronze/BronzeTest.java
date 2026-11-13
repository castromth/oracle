package oracle.captura.bronze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.zip.GZIPInputStream;
import oracle.captura.http.Resposta;
import oracle.captura.recurso.UnidadeCaptura;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BronzeTest {

  @TempDir Path raiz;

  EscritorBronze escritor;
  UnidadeCaptura unidade;

  @BeforeEach
  void preparar() {
    escritor = new EscritorBronze(raiz, LocalDate.parse("2026-11-12"), "run1");
    SequencedMap<String, String> chave = new LinkedHashMap<>();
    chave.put("dia", "2026-11-10");
    chave.put("modalidade", "8");
    unidade = new UnidadeCaptura("contratacoes", chave);
  }

  static Resposta resposta(String corpo) {
    return new Resposta(
        URI.create("http://x/api?pagina=1"),
        200,
        corpo.getBytes(StandardCharsets.UTF_8),
        Instant.parse("2026-11-12T10:00:00Z"));
  }

  static SequencedMap<String, String> params(int pagina) {
    SequencedMap<String, String> p = new LinkedHashMap<>();
    p.put("pagina", String.valueOf(pagina));
    return p;
  }

  static String ler(Path arquivo) throws IOException {
    try (InputStream in = new GZIPInputStream(Files.newInputStream(arquivo))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void duasPaginasConcluidas() throws IOException {
    var sessao = escritor.abrir(unidade);
    sessao.gravarPagina(1, resposta("{\"p\":1}"), params(1));
    sessao.gravarPagina(2, resposta("{\"p\":2}"), params(2));
    String caminho = sessao.concluir();

    Path final_ = escritor.caminhoFinal(unidade);
    assertTrue(final_.isAbsolute());
    assertEquals(
        "bronze/metadados/contratacoes/data_captura=2026-11-12/dia=2026-11-10/modalidade=8",
        caminho);
    assertEquals(raiz.toAbsolutePath().resolve(caminho), final_);
    assertTrue(Files.exists(final_.resolve("pagina-0001.json.gz")));
    assertTrue(Files.exists(final_.resolve("pagina-0002.json.gz")));
    assertFalse(Files.exists(raiz.resolve("tmp/run1/" + unidade.id())));

    List<JsonNode> corpos = LeitorBronze.corpos(final_);
    assertEquals(2, corpos.size());
    assertEquals(1, corpos.get(0).get("p").asInt());
    assertEquals(2, corpos.get(1).get("p").asInt());
  }

  @Test
  void corpoByteAByteEEnvelope() throws IOException {
    var sessao = escritor.abrir(unidade);
    sessao.gravarPagina(1, resposta("{\"a\":1.50,\"b\":1E+2}"), params(1));
    sessao.concluir();

    String texto = ler(escritor.caminhoFinal(unidade).resolve("pagina-0001.json.gz"));
    assertTrue(texto.contains("\"corpo\":{\"a\":1.50,\"b\":1E+2}"), texto);
    assertEquals(
        "{\"url\":\"http://x/api?pagina=1\",\"parametros\":{\"pagina\":\"1\"},"
            + "\"capturadoEm\":\"2026-11-12T10:00:00Z\",\"status\":200,"
            + "\"corpo\":{\"a\":1.50,\"b\":1E+2}}",
        texto);
  }

  @Test
  void nadaNoFinalAntesDeConcluir() throws IOException {
    var sessao = escritor.abrir(unidade);
    sessao.gravarPagina(1, resposta("{}"), params(1));
    assertFalse(Files.exists(escritor.caminhoFinal(unidade)));
    assertTrue(Files.exists(raiz.resolve("tmp/run1/" + unidade.id()).resolve("pagina-0001.json.gz")));
  }

  @Test
  void descartarApagaTemporarioSemCriarFinal() throws IOException {
    var sessao = escritor.abrir(unidade);
    sessao.gravarPagina(1, resposta("{}"), params(1));
    sessao.descartar();
    assertFalse(Files.exists(raiz.resolve("tmp/run1/" + unidade.id())));
    assertFalse(Files.exists(escritor.caminhoFinal(unidade)));
    sessao.descartar(); // idempotente, não lança
  }

  @Test
  void concluirComFinalExistenteLancaESemAlterar() throws IOException {
    var primeira = escritor.abrir(unidade);
    primeira.gravarPagina(1, resposta("{\"v\":\"antigo\"}"), params(1));
    primeira.concluir();
    Path arquivo = escritor.caminhoFinal(unidade).resolve("pagina-0001.json.gz");
    byte[] antes = Files.readAllBytes(arquivo);

    var segunda = escritor.abrir(unidade);
    segunda.gravarPagina(1, resposta("{\"v\":\"novo\"}"), params(1));
    assertThrows(IOException.class, segunda::concluir);

    assertEquals(1, Files.list(escritor.caminhoFinal(unidade)).count());
    assertTrue(java.util.Arrays.equals(antes, Files.readAllBytes(arquivo)));
  }

  @Test
  void limparTmpApagaTudoEToleraAusencia() throws IOException {
    EscritorBronze.limparTmp(raiz); // ausente: não lança
    var sessao = escritor.abrir(unidade);
    sessao.gravarPagina(1, resposta("{}"), params(1));
    EscritorBronze.limparTmp(raiz);
    assertFalse(Files.exists(raiz.resolve("tmp")));
  }
}
