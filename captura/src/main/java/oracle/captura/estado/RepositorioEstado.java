package oracle.captura.estado;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import oracle.captura.Json;

/** Lê e grava o estado em JSON; a gravação é atômica (tmp + fsync + rename). */
public final class RepositorioEstado {
  private final Path arquivo;

  public RepositorioEstado(Path arquivo) {
    this.arquivo = arquivo;
  }

  /** Arquivo ausente devolve um {@link Estado} vazio. */
  public Estado carregar() throws IOException {
    if (!Files.exists(arquivo)) {
      return new Estado();
    }
    return Json.MAPPER.readValue(Files.readAllBytes(arquivo), Estado.class);
  }

  public void gravar(Estado estado) throws IOException {
    Path dir = arquivo.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    byte[] bytes = Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(estado);
    Path tmp = Files.createTempFile(dir, arquivo.getFileName() + ".", ".tmp");
    try {
      try (FileChannel canal = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
        canal.write(java.nio.ByteBuffer.wrap(bytes));
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
