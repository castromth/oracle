package oracle.captura.recurso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import oracle.captura.Config;
import oracle.captura.ConfigInvalida;
import oracle.captura.Modo;
import oracle.captura.bronze.EscritorBronze;
import oracle.captura.estado.Estado;
import oracle.captura.estado.RegistroUnidade;
import oracle.captura.estado.Status;
import oracle.captura.http.Resposta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnidadesTest {
  private static final LocalDate HOJE = LocalDate.of(2026, 11, 12);

  @TempDir Path raiz;
  private final Unidades recurso = new Unidades();
  private int sequencia;

  private Config config(Modo modo, String uf) {
    return new Config(
        "unidades", modo, raiz, null, null, uf, List.of(6), 2, 2,
        Duration.ofSeconds(5), 1, Duration.ofMillis(1), Duration.ofMillis(2),
        URI.create("http://localhost"), URI.create("http://localhost"));
  }

  /** Grava uma unidade no bronze e devolve o caminho relativo registrado no estado. */
  private String gravarBronze(UnidadeCaptura unidade, String corpo) throws IOException {
    var sessao = new EscritorBronze(raiz, HOJE, "run" + sequencia++).abrir(unidade);
    sessao.gravarPagina(
        1,
        new Resposta(
            URI.create("http://localhost/x"), 200, corpo.getBytes(StandardCharsets.UTF_8), Instant.now()),
        new LinkedHashMap<>());
    return sessao.concluir();
  }

  private static UnidadeCaptura contratacao(String dia, String modalidade) {
    var chave = new LinkedHashMap<String, String>();
    chave.put("dia", dia);
    chave.put("modalidade", modalidade);
    return new UnidadeCaptura("contratacoes", chave);
  }

  private static UnidadeCaptura orgao(String cnpj) {
    var chave = new LinkedHashMap<String, String>();
    chave.put("cnpj", cnpj);
    return new UnidadeCaptura("unidades", chave);
  }

  private static String compras(String... cnpjs) {
    StringBuilder sb = new StringBuilder("{\"data\":[");
    for (int i = 0; i < cnpjs.length; i++) {
      sb.append(i > 0 ? "," : "")
          .append("{\"orgaoEntidade\":{\"cnpj\":\"")
          .append(cnpjs[i])
          .append("\"}}");
    }
    return sb.append("],\"totalPaginas\":1}").toString();
  }

  private static void registrar(Estado e, UnidadeCaptura u, Status s, String caminho) {
    e.unidadesCaptura.put(
        u.id(), new RegistroUnidade(s, 1, Instant.now(), HOJE, 1, 1, caminho, null));
  }

  /** Duas contratações OK com os CNPJs A e B (A repetido), mais VAZIA e FALHA que citam o CNPJ Z. */
  private Estado estadoComContratacoes() throws IOException {
    Estado e = new Estado();
    var u1 = contratacao("2026-11-10", "6");
    var u2 = contratacao("2026-11-10", "8");
    registrar(e, u1, Status.OK, gravarBronze(u1, compras("A", "B", "A")));
    registrar(e, u2, Status.OK, gravarBronze(u2, compras("A")));
    var vazia = contratacao("2026-11-10", "7");
    var falha = contratacao("2026-11-10", "9");
    String z = gravarBronze(contratacao("2026-11-09", "6"), compras("Z"));
    registrar(e, vazia, Status.VAZIA, z);
    registrar(e, falha, Status.FALHA, z);
    return e;
  }

  @Test
  void backfillPlanejaCnpjsDistintosSoDeContratacoesOk() throws IOException {
    var ids =
        recurso.planejar(config(Modo.BACKFILL, "GO"), estadoComContratacoes(), HOJE).stream()
            .map(UnidadeCaptura::id)
            .toList();
    assertEquals(List.of("unidades/cnpj=A", "unidades/cnpj=B"), ids);
  }

  @Test
  void incrementalIgnoraCnpjJaOkOuVazia() throws IOException {
    Estado e = new Estado();
    var u1 = contratacao("2026-11-10", "6");
    registrar(e, u1, Status.OK, gravarBronze(u1, compras("A", "B", "C", "D")));
    registrar(e, orgao("A"), Status.OK, "x");
    registrar(e, orgao("B"), Status.VAZIA, null);
    registrar(e, orgao("C"), Status.FALHA, null);

    var cfg = config(Modo.INCREMENTAL, "GO");
    assertEquals(
        List.of("unidades/cnpj=C", "unidades/cnpj=D"),
        recurso.planejar(cfg, e, HOJE).stream().map(UnidadeCaptura::id).toList());

    var backfill = recurso.planejar(config(Modo.BACKFILL, "GO"), e, HOJE);
    assertEquals(4, backfill.size());
  }

  @Test
  void bronzeIlegivelDeContratacoesLancaConfigInvalida() {
    Estado e = new Estado();
    registrar(e, contratacao("2026-11-10", "6"), Status.OK, "bronze/inexistente");
    assertThrows(
        ConfigInvalida.class, () -> recurso.planejar(config(Modo.BACKFILL, "GO"), e, HOJE));
  }

  private static String unidadeJson(String cnpj, String codigo, String nome, String uf, boolean ativa) {
    return "{\"orgao\":{\"cnpj\":\""
        + cnpj
        + "\"},\"codigoUnidade\":\""
        + codigo
        + "\",\"nomeUnidade\":\""
        + nome
        + "\",\"municipio\":{\"codigoIbge\":\"5211909\",\"uf\":{\"siglaUF\":\""
        + uf
        + "\"}},\"statusAtivo\":"
        + ativa
        + "}";
  }

  @Test
  void concluirGravaCatalogoSoDaUfConfiguradaEComCodigosDistintos() throws IOException {
    Estado e = new Estado();
    var a = orgao("11111111000111");
    registrar(
        e,
        a,
        Status.OK,
        gravarBronze(
            a,
            "["
                + unidadeJson("11111111000111", "1", "Um", "GO", true)
                + ","
                + unidadeJson("11111111000111", "01", "Zero um", "GO", false)
                + ","
                + unidadeJson("11111111000111", "7", "Fora", "SP", true)
                + "]"));
    var b = orgao("22222222000122");
    registrar(
        e, b, Status.OK, gravarBronze(b, "[" + unidadeJson("22222222000122", "1", "Outro", "GO", true) + "]"));
    var vazio = orgao("33333333000133");
    registrar(e, vazio, Status.OK, gravarBronze(vazio, "[]"));
    registrar(e, orgao("44444444000144"), Status.FALHA, null);
    registrar(e, orgao("55555555000155"), Status.VAZIA, null);

    recurso.concluir(config(Modo.BACKFILL, "GO"), e, HOJE, List.of());

    assertEquals(
        List.of(
            new UnidadeOrgao("11111111000111", "01", "Zero um", "GO", "5211909", false),
            new UnidadeOrgao("11111111000111", "1", "Um", "GO", "5211909", true),
            new UnidadeOrgao("22222222000122", "1", "Outro", "GO", "5211909", true)),
        CatalogoUnidades.ler(raiz));
  }

  @Test
  void contarRegistrosEOTamanhoDoArray() throws IOException {
    assertEquals(
        2, recurso.contarRegistros(com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree("[{},{}]")));
  }
}
