package oracle.captura.estado;

import java.time.Instant;
import java.time.LocalDate;

public record RegistroUnidade(
    Status status,
    int tentativas,
    Instant atualizadoEm,
    LocalDate dataCaptura,
    int paginas,
    int registros,
    String caminho,
    String erro) {}
