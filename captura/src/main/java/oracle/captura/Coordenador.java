package oracle.captura;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import oracle.captura.bronze.LeitorBronze;
import oracle.captura.estado.Estado;
import oracle.captura.estado.RegistroUnidade;
import oracle.captura.estado.RepositorioEstado;
import oracle.captura.estado.Status;
import oracle.captura.recurso.Contexto;
import oracle.captura.recurso.Recurso;
import oracle.captura.recurso.ResultadoUnidade;
import oracle.captura.recurso.UnidadeCaptura;
import com.fasterxml.jackson.databind.JsonNode;

/** Roda na thread chamadora; é o único que altera e grava o estado. */
public final class Coordenador {

  public record Resumo(int ok, int vazias, int falhas) {}

  private final Config config;
  private final Recurso recurso;
  private final RepositorioEstado repositorio;
  private final Contexto contexto;
  private final LocalDate hoje;

  public Coordenador(
      Config config,
      Recurso recurso,
      RepositorioEstado repositorio,
      Contexto contexto,
      LocalDate hoje) {
    this.config = config;
    this.recurso = recurso;
    this.repositorio = repositorio;
    this.contexto = contexto;
    this.hoje = hoje;
  }

  public Resumo executar() throws IOException {
    Estado estado = repositorio.carregar();

    // 1-3: janela ∪ pendências, menos o que já foi feito hoje.
    Map<String, UnidadeCaptura> plano = new LinkedHashMap<>();
    for (UnidadeCaptura u : recurso.planejar(config, estado, hoje)) {
      plano.putIfAbsent(u.id(), u);
    }
    String prefixo = recurso.nome() + "/";
    for (Map.Entry<String, RegistroUnidade> e : estado.unidadesCaptura.entrySet()) {
      Status s = e.getValue().status();
      if (e.getKey().startsWith(prefixo) && (s == Status.FALHA || s == Status.PENDENTE)) {
        plano.putIfAbsent(e.getKey(), UnidadeCaptura.deId(e.getKey()));
      }
    }
    plano.entrySet().removeIf(e -> feitaHoje(estado.unidadesCaptura.get(e.getKey())));

    // 4: marca tudo como PENDENTE, preservando tentativas.
    Instant agora = Instant.now();
    for (String id : plano.keySet()) {
      RegistroUnidade atual = estado.unidadesCaptura.get(id);
      int tentativas = atual == null ? 0 : atual.tentativas();
      estado.unidadesCaptura.put(
          id, new RegistroUnidade(Status.PENDENTE, tentativas, agora, null, 0, 0, null, null));
    }
    repositorio.gravar(estado);

    List<ResultadoUnidade> resultados = new ArrayList<>();

    // 5: reconciliação com o bronze de hoje.
    List<UnidadeCaptura> despachar = new ArrayList<>();
    for (UnidadeCaptura u : plano.values()) {
      Path dir = contexto.bronze().caminhoFinal(u);
      if (Files.isDirectory(dir)) {
        ResultadoUnidade r = reconciliar(u, dir);
        resultados.add(r);
        registrar(estado, r);
      } else {
        despachar.add(u);
      }
    }
    if (resultados.size() > 0) {
      repositorio.gravar(estado);
    }

    // 6-7: pool fixo; cada resultado atualiza e grava o estado.
    if (!despachar.isEmpty()) {
      ExecutorService pool = Executors.newFixedThreadPool(config.concorrencia());
      try {
        ExecutorCompletionService<ResultadoUnidade> ecs = new ExecutorCompletionService<>(pool);
        Map<Future<ResultadoUnidade>, UnidadeCaptura> futuros = new LinkedHashMap<>();
        for (UnidadeCaptura u : despachar) {
          Callable<ResultadoUnidade> tarefa = () -> capturarSeguro(u);
          futuros.put(ecs.submit(tarefa), u);
        }
        for (int i = 0; i < despachar.size(); i++) {
          ResultadoUnidade r;
          try {
            Future<ResultadoUnidade> f = ecs.take();
            try {
              r = f.get();
            } catch (ExecutionException e) {
              r = falha(futuros.get(f), e.getCause());
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Coordenador interrompido", e);
          }
          resultados.add(r);
          registrar(estado, r);
          repositorio.gravar(estado);
        }
      } finally {
        pool.shutdownNow();
      }
    }

    // 8
    recurso.concluir(config, estado, hoje, resultados);
    repositorio.gravar(estado);

    int ok = 0;
    int vazias = 0;
    int falhas = 0;
    for (ResultadoUnidade r : resultados) {
      switch (r.status()) {
        case OK -> ok++;
        case VAZIA -> vazias++;
        default -> falhas++;
      }
    }
    return new Resumo(ok, vazias, falhas);
  }

  private boolean feitaHoje(RegistroUnidade r) {
    return r != null
        && (r.status() == Status.OK || r.status() == Status.VAZIA)
        && hoje.equals(r.dataCaptura());
  }

  private ResultadoUnidade capturarSeguro(UnidadeCaptura u) {
    try {
      ResultadoUnidade r = recurso.capturar(u, contexto);
      return r != null ? r : falha(u, new IllegalStateException("capturar devolveu nulo"));
    } catch (Throwable t) {
      return falha(u, t);
    }
  }

  private static ResultadoUnidade falha(UnidadeCaptura u, Throwable t) {
    String msg = t == null ? "erro desconhecido" : t.getClass().getSimpleName() + ": " + t.getMessage();
    return new ResultadoUnidade(u, Status.FALHA, 0, 0, null, msg);
  }

  private ResultadoUnidade reconciliar(UnidadeCaptura u, Path dir) throws IOException {
    List<JsonNode> corpos = LeitorBronze.corpos(dir);
    int registros = 0;
    for (JsonNode c : corpos) {
      registros += recurso.contarRegistros(c);
    }
    String caminho = config.raiz().toAbsolutePath().relativize(dir.toAbsolutePath()).toString();
    return new ResultadoUnidade(u, Status.OK, corpos.size(), registros, caminho, null);
  }

  private void registrar(Estado estado, ResultadoUnidade r) {
    String id = r.unidade().id();
    RegistroUnidade atual = estado.unidadesCaptura.get(id);
    int tentativas = (atual == null ? 0 : atual.tentativas()) + 1;
    boolean concluida = r.status() == Status.OK || r.status() == Status.VAZIA;
    estado.unidadesCaptura.put(
        id,
        new RegistroUnidade(
            r.status(),
            tentativas,
            Instant.now(),
            concluida ? hoje : null,
            r.paginas(),
            r.registros(),
            r.status() == Status.OK ? r.caminho() : null,
            r.status() == Status.FALHA ? r.erro() : null));
  }
}
