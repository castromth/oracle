package oracle.captura.bronze;

import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.SequencedMap;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import oracle.captura.Json;
import oracle.captura.http.Resposta;
import oracle.captura.recurso.UnidadeCaptura;

/** Grava unidades de captura no bronze: completas ou ausentes. */
public final class EscritorBronze {

  private final Path raiz;
  private final LocalDate dataCaptura;
  private final String runId;

  public EscritorBronze(Path raiz, LocalDate dataCaptura, String runId) {
    this.raiz = raiz;
    this.dataCaptura = dataCaptura;
    this.runId = runId;
  }

  private static String resto(UnidadeCaptura unidade) {
    String id = unidade.id();
    int barra = id.indexOf('/');
    return barra < 0 ? "" : id.substring(barra + 1);
  }

  public Path caminhoFinal(UnidadeCaptura unidade) {
    return raiz.resolve("bronze")
        .resolve("metadados")
        .resolve(unidade.recurso())
        .resolve("data_captura=" + dataCaptura)
        .resolve(resto(unidade))
        .toAbsolutePath();
  }

  public Sessao abrir(UnidadeCaptura unidade) throws IOException {
    Path tmp = raiz.resolve("tmp").resolve(runId).resolve(unidade.id());
    if (Files.exists(tmp)) {
      apagarRecursivo(tmp);
    }
    Files.createDirectories(tmp);
    return new Sessao(tmp, caminhoFinal(unidade));
  }

  public static void limparTmp(Path raiz) throws IOException {
    apagarRecursivo(raiz.resolve("tmp"));
  }

  private static void apagarRecursivo(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> arvore = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) arvore.sorted(Comparator.reverseOrder())::iterator) {
        Files.delete(p);
      }
    }
  }

  public final class Sessao {
    private final Path tmp;
    private final Path destino;

    private Sessao(Path tmp, Path destino) {
      this.tmp = tmp;
      this.destino = destino;
    }

    public void gravarPagina(
        int numero, Resposta resposta, SequencedMap<String, String> parametros)
        throws IOException {
      Path arquivo = tmp.resolve(String.format("pagina-%04d.json.gz", numero));
      try (OutputStream out =
              new GZIPOutputStream(
                  Files.newOutputStream(
                      arquivo, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
          JsonGenerator g = Json.MAPPER.getFactory().createGenerator(out)) {
        g.writeStartObject();
        g.writeStringField("url", resposta.url().toString());
        g.writeObjectFieldStart("parametros");
        for (var e : parametros.entrySet()) {
          g.writeStringField(e.getKey(), e.getValue());
        }
        g.writeEndObject();
        g.writeStringField("capturadoEm", resposta.recebidaEm().toString());
        g.writeNumberField("status", resposta.status());
        g.writeFieldName("corpo");
        g.writeRawValue(new String(resposta.corpo(), StandardCharsets.UTF_8));
        g.writeEndObject();
      }
    }

    /** Move o temporário para o bronze; devolve o caminho relativo à raiz, com {@code /}. */
    public String concluir() throws IOException {
      if (Files.exists(destino)) {
        throw new FileAlreadyExistsException(destino.toString());
      }
      Files.createDirectories(destino.getParent());
      Files.move(tmp, destino, StandardCopyOption.ATOMIC_MOVE);
      return raiz.toAbsolutePath().relativize(destino).toString().replace('\\', '/');
    }

    public void descartar() {
      try {
        apagarRecursivo(tmp);
      } catch (IOException | RuntimeException e) {
        // melhor esforço: tmp/ é limpo na partida
      }
    }
  }
}
