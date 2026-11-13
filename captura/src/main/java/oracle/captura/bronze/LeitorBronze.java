package oracle.captura.bronze;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import oracle.captura.Json;

public final class LeitorBronze {

  private LeitorBronze() {}

  /** Campo {@code corpo} de cada {@code pagina-*.json.gz} do diretório, em ordem de nome. */
  public static List<JsonNode> corpos(Path diretorioUnidade) throws IOException {
    List<Path> arquivos;
    try (Stream<Path> s = Files.list(diretorioUnidade)) {
      arquivos =
          s.filter(
                  p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("pagina-") && n.endsWith(".json.gz");
                  })
              .sorted()
              .toList();
    }
    List<JsonNode> corpos = new ArrayList<>();
    for (Path arquivo : arquivos) {
      try (InputStream in = new GZIPInputStream(Files.newInputStream(arquivo))) {
        corpos.add(Json.MAPPER.readTree(in).path("corpo"));
      }
    }
    return corpos;
  }
}
