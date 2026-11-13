package oracle.captura.recurso;

import oracle.captura.Config;
import oracle.captura.bronze.EscritorBronze;
import oracle.captura.http.ClientePncp;

public record Contexto(Config config, ClientePncp http, EscritorBronze bronze) {}
