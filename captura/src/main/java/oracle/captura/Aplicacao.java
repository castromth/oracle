package oracle.captura;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import oracle.captura.bronze.EscritorBronze;
import oracle.captura.estado.RepositorioEstado;
import oracle.captura.http.ClientePncp;
import oracle.captura.recurso.Contexto;
import oracle.captura.recurso.Recurso;
import oracle.captura.recurso.Recursos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Aplicacao {

  private static final Logger LOG = LoggerFactory.getLogger(Aplicacao.class);

  private Aplicacao() {}

  /** 0 = concluído (mesmo com unidades FALHA); 1 = erro fatal. */
  public static int executar(Config config, Clock relogio) {
    try {
      Recurso recurso = Recursos.porNome(config.recurso());
      validar(config, recurso);

      Path estadoDir = config.raiz().resolve("estado");
      Files.createDirectories(estadoDir);
      try (FileChannel canal =
          FileChannel.open(
              estadoDir.resolve("captura.lock"),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE)) {
        FileLock tentativa;
        try {
          tentativa = canal.tryLock();
        } catch (OverlappingFileLockException e) {
          tentativa = null;
        }
        if (tentativa == null) {
          LOG.error("Outro run está ativo: trava {} ocupada", estadoDir.resolve("captura.lock"));
          return 1;
        }
        try (FileLock trava = tentativa) {
          EscritorBronze.limparTmp(config.raiz());
          RepositorioEstado repositorio = new RepositorioEstado(estadoDir.resolve("captura.json"));
          repositorio.carregar();
          LocalDate hoje = LocalDate.now(relogio);
          String runId = UUID.randomUUID().toString();
          ClientePncp http =
              new ClientePncp(
                  config.timeout(),
                  config.maxTentativasHttp(),
                  config.backoffBase(),
                  config.backoffTeto());
          Contexto contexto =
              new Contexto(config, http, new EscritorBronze(config.raiz(), hoje, runId));
          Coordenador.Resumo resumo =
              new Coordenador(config, recurso, repositorio, contexto, hoje).executar();
          LOG.info(
              "Run {} concluído: recurso={} modo={} ok={} vazias={} falhas={}",
              runId,
              config.recurso(),
              config.modo(),
              resumo.ok(),
              resumo.vazias(),
              resumo.falhas());
          return 0;
        }
      }
    } catch (ConfigInvalida e) {
      LOG.error("Configuração inválida: {}", e.getMessage());
      return 1;
    } catch (IOException e) {
      LOG.error("Erro de E/S: {}", e.getMessage(), e);
      return 1;
    }
  }

  private static void validar(Config config, Recurso recurso) {
    boolean temJanela = config.janelaInicio() != null && config.janelaFim() != null;
    if (!recurso.usaJanela()) {
      if (temJanela) {
        throw new ConfigInvalida(
            "Recurso " + recurso.nome() + " não usa janela; remova CAPTURA_JANELA_INICIO/FIM");
      }
      return;
    }
    if (config.modo() == Modo.BACKFILL && !temJanela) {
      throw new ConfigInvalida(
          "Backfill de " + recurso.nome() + " exige CAPTURA_JANELA_INICIO e CAPTURA_JANELA_FIM");
    }
    if (config.modo() == Modo.INCREMENTAL && temJanela) {
      throw new ConfigInvalida("Incremental não aceita janela");
    }
  }
}
