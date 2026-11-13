package oracle.captura.recurso;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import oracle.captura.Json;

/** Catálogo {@code catalogo/unidades.jsonl}: uma {@link UnidadeOrgao} por linha. */
public final class CatalogoUnidades {
  private static final Comparator<UnidadeOrgao> ORDEM =
      Comparator.comparing(UnidadeOrgao::cnpj).thenComparing(UnidadeOrgao::codigoUnidade);

  private CatalogoUnidades() {}

  static Path arquivo(Path raiz) {
    return raiz.resolve("catalogo").resolve("unidades.jsonl");
  }

  /** Arquivo ausente devolve lista vazia. */
  public static List<UnidadeOrgao> ler(Path raiz) throws IOException {
    Path arquivo = arquivo(raiz);
    if (!Files.exists(arquivo)) {
      return List.of();
    }
    List<UnidadeOrgao> unidades = new ArrayList<>();
    for (String linha : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
      if (!linha.isBlank()) {
        unidades.add(Json.MAPPER.readValue(linha, UnidadeOrgao.class));
      }
    }
    return unidades;
  }

  /** Reescreve o catálogo inteiro, ordenado por {@code (cnpj, codigoUnidade)}, de forma atômica. */
  public static void gravar(Path raiz, Collection<UnidadeOrgao> unidades) throws IOException {
    Path arquivo = arquivo(raiz);
    Path dir = arquivo.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    StringBuilder sb = new StringBuilder();
    for (UnidadeOrgao u : unidades.stream().sorted(ORDEM).toList()) {
      sb.append(Json.MAPPER.writeValueAsString(u)).append('\n');
    }
    Path tmp = Files.createTempFile(dir, arquivo.getFileName() + ".", ".tmp");
    try {
      try (FileChannel canal = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
        canal.write(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)));
        canal.force(true);
      }
      Files.move(
          tmp, arquivo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(tmp);
      throw e;
    }
  }
}
