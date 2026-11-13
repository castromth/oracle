package oracle.captura.recurso;

import oracle.captura.estado.Status;

public record ResultadoUnidade(
    UnidadeCaptura unidade, Status status, int paginas, int registros, String caminho, String erro) {}
