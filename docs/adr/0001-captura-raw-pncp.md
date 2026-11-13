# ADR 0001: Captura raw (bronze) de dados do PNCP

- Status: Proposto
- Data: 2026-11-09
- Revisão: 2026-11-12 (escopo inicial só com contratações e contratos por data de publicação; contratos filtrados por unidade)

## Contexto

Pipeline para consumir a API Consulta do PNCP (`https://pncp.gov.br/api/consulta`) e a API PNCP (`https://pncp.gov.br/api/pncp`, usada só em GET, para arquivos e para unidades de órgãos). O destino final é ML. Este ADR cobre **apenas a captura bruta**. Prata e ouro ficam para ADRs posteriores.

Restrições: máquina local, um único mantenedor, frescor diário do dado (o modelo de execução está em Stack), escopo GO, backfill de 3 meses.

### Fatos medidos na API (2026-11-09)

- GET sem autenticação, inclusive nos endpoints de `arquivos`.
- `contratacoes/{publicacao,atualizacao}` exigem `codigoModalidadeContratacao`, aceitam `uf` e `codigoMunicipioIbge` (código IBGE de 7 dígitos, mesmo formato de `unidadeOrgao.codigoIbge`) e limitam `tamanhoPagina` a **50**. `contratacoes/proposta` também aceita ambos, mas não exige modalidade.
- Filtro de localização em `contratacoes/publicacao` verificado em 2026-11-12 (modalidade 6, publicação de 01/11 a 12/11/2026, 1ª página de 10 registros por consulta): sem filtro 13.660 registros; `uf=GO` 394, todos de GO; `codigoMunicipioIbge=5208707` (Goiânia) 68, todos de Goiânia; `uf=GO&codigoMunicipioIbge=5208707` também 68. O município sozinho basta; `uf` é redundante quando o IBGE é informado.
- `contratos`, `atas` e `pca` **ignoram `uf`/`ufSigla`** (o total nacional não muda). `tamanhoPagina` máximo é 500.
- Contratos nacionais na janela de 3 meses: ~530 mil registros (~1.062 páginas de 500). Amostra de 5 páginas: 4,3% são de GO (~23 mil). Amostra pequena, margem grande.
- Contratações GO em 3 meses: ~24 mil (modalidade 8: 16.603; 9: 3.911; 6: 2.644; 4: 463; 7: 359; 5: 52; 13: 15; 1: 13; 3: 4; 10: 2).
- A API devolve **204** (sem corpo) para filtro sem dados (modalidades 2 e 14) e **429** sob carga leve.
- Os DTOs de metadados trazem `numeroControlePNCP`, `dataAtualizacaoGlobal` e `unidadeOrgao` (`ufSigla`, `municipioNome`, `codigoIbge`, `codigoUnidade`).
- Arquivos: `GET /api/pncp/v1/orgaos/{cnpj}/{contratos|compras}/{ano}/{seq}/arquivos` lista; `.../arquivos/{seqDoc}` baixa o binário. Existem rotas equivalentes para atas, termos e IRP. A listagem **não traz localização**.

### Fatos medidos na API (2026-11-12)

- `contratacoes/publicacao` e `contratos` filtram por data de publicação; as variantes `/atualizacao`, por data de atualização global. Datas em `yyyyMMdd`. `pca/atualizacao` usa `dataInicio`/`dataFim` em vez de `dataInicial`/`dataFinal`.
- A resposta paginada traz `data`, `totalRegistros`, `totalPaginas`, `numeroPagina`, `paginasRestantes` e `empty`.
- `contratos` aceita `cnpjOrgao` e `codigoUnidadeAdministrativa`. O código de unidade sozinho devolve **422** ("Obrigatório que o CNPJ do órgão seja informado"); `cnpjOrgao` sozinho funciona.
- `codigoUnidade` **não é único entre órgãos** (`1`, `2`, `10` se repetem em vários CNPJs). A chave é `(cnpj, codigoUnidade)`.
- **Não há endpoint documentado que liste órgãos ou unidades por município ou UF.** `GET /api/pncp/v1/orgaos/{cnpj}/unidades` lista todas as unidades de um órgão, com `municipio.codigoIbge`, `municipio.uf.siglaUF` e `statusAtivo`, sem paginação (0,1 a 0,4 s).
- Unidades por órgão: Município de Jataí, 37 (todas em Jataí); Estado de Goiás, 443 (440 em GO, em 181 municípios; 125 em Goiânia); CODEVASF, 19 (1 em GO); EBSERH (CNPJ 15126437000305), 43 (2 em GO).
- Uma cidade tem vários órgãos: em Jataí (`codigoMunicipioIbge=5211909`, modalidade 6, 3 meses, 76 registros), a primeira página de 50 traz 7 pares `(cnpj, codigoUnidade)` de 6 órgãos, entre municipais e federais.
- Jataí (CNPJ 01165729000180), `contratos` em 3 meses: 319 por CNPJ; 133 na unidade 2985; 30 na unidade 6. Os códigos vistos nos contratos (6, 8, 10, 11, 32) e nas contratações (2985) constam todos em `/unidades`.
- `contratos` nacional entre 2026-08-12 e 2026-11-12: 541.526 registros (~5,9 mil por dia). A janela de 92 dias é aceita numa única consulta.
- Latência: `contratos` com janela de 3 meses leva 20 a 38 s por requisição, com ou sem filtro; com janela de 1 dia e filtro, ~5 s. `contratacoes/publicacao` leva 14 a 18 s.
- API de busca do portal (`/api/search/`, não documentada): filtra contratos por `ufs` e `municipios` (id interno, não IBGE) e devolve `orgao_cnpj`, `unidade_codigo` e `municipio_nome`. Limita `pagina × tam_pagina` a 10.000 (Goiânia tem 21.040 contratos) e derrubou a conexão em cerca de metade das chamadas sequenciais. Não é usada.

## Decisões

### Escopo
- GO. Escopo inicial: **contratações e contratos**, com metadados e arquivos, consultados por **data de publicação**.
- Adiados: atas, PCA e os endpoints `/atualizacao`.
- Backfill: últimos 3 meses. Depois, incremental diário.

### Fases

1. **Contratações.** `contratacoes/publicacao` com `uf=GO`, por modalidade.
2. **Catálogo de unidades.** Lê o bronze de contratações, extrai os CNPJs distintos e consulta `/api/pncp/v1/orgaos/{cnpj}/unidades` para cada um. Guarda a resposta bruta e seleciona as unidades com `municipio.uf.siglaUF = GO`, gravando o catálogo `(cnpj, codigoUnidade, nomeUnidade, UF, IBGE, statusAtivo)`. As unidades de uma cidade são as linhas do catálogo com aquele IBGE. O catálogo é cumulativo entre runs.
3. **Contratos.** `contratos` por `(cnpjOrgao, codigoUnidadeAdministrativa)` do catálogo. Não há varredura nacional.
4. **Fila de arquivos.** Lê o bronze de contratações e contratos e grava a fila `(tipo, cnpj, ano, seq, UF, IBGE, codigoUnidade)`. É seleção, não transformação de prata.
5. **Arquivos.** 5a: lista arquivos por item da fila e faz `HEAD` em cada um, somando bytes. 5b: baixa, somente se a estimativa couber no disco (~50 GB).

### Unidade de captura e idempotência
- Uma resposta de página por arquivo `.json.gz`, com envelope: URL, parâmetros, timestamp, HTTP status.
- Unidades idempotentes; cada uma pode ser refeita sozinha:
  - contratações: `(contratacoes, dia, modalidade)`;
  - catálogo: `(unidades, cnpj)`;
  - contratos: `(contratos, janela, cnpj, codigoUnidade)`. A janela é a do run: os 3 meses numa única consulta no backfill; do watermark menos a sobreposição até hoje no incremental. Unidade por dia multiplicaria as requisições por 90 no backfill.
- Snapshot imutável e datado; nada é sobrescrito nem removido localmente.

### Incremental
- Janela deslizante por data de publicação (`contratacoes/publicacao` e `contratos`), com watermark persistido e sobreposição de 1 a 2 dias para cobrir atrasos de publicação.
- Sem `/atualizacao`, alterações posteriores à publicação em registros já capturados não são recapturadas.
- O watermark de contratos é por par `(cnpj, codigoUnidade)`: um par que entra no catálogo depois é capturado desde o início do backfill.

### Robustez
- Concorrência de 2 a 4 requisições.
- Retry com backoff exponencial e jitter em 429 e 5xx.
- 204 é "unidade vazia" e fica registrada como tal; não é erro.
- Falha persistente: a unidade é marcada como `FALHA` e o run segue. O run seguinte reprocessa só as unidades com falha.

### Estado
- **Arquivo de estado** (JSON), sem banco. Contém, por unidade: status (`PENDENTE|OK|VAZIA|FALHA`), tentativas, timestamp e caminho do bronze gerado; e os watermarks (por recurso em contratações, por par `(cnpj, codigoUnidade)` em contratos).
- **Escritor único**: as threads de captura reportam o resultado a um coordenador; só ele escreve o arquivo.
- Escrita atômica: grava em arquivo temporário, `fsync` e `rename`.
- Se o volume de unidades tornar o arquivo único incômodo, reavaliar SQLite em outro ADR.

### Layout do bronze

```
bronze/metadados/{recurso}/data_captura=YYYY-MM-DD/{parametros}/pagina-0001.json.gz
bronze/arquivos/uf=GO/ibge={codigoIbge}/{tipo}/cnpj={cnpj}/{ano}/{seq}/{seqDoc}-{titulo}
bronze/manifesto/arquivos.parquet
```

- Hierarquia dos arquivos: UF → município (IBGE) → tipo de recurso → CNPJ → ano → sequencial → arquivo.
- O caminho serve para navegação. O **manifesto** é a fonte de verdade: `numeroControlePNCP`, caminho, UF, IBGE, CNPJ, `sha256`, tamanho, data da captura. Motivo: o caminho fixa um atributo (município) que a origem pode corrigir.
- Todos os tipos de documento são baixados.

### Modelo de execução
- **Aplicação Java iniciada sob demanda pelo Dagster, executa o trabalho e termina (run-to-completion).** Não é serviço residente. Decidido porque o custo de subir a JVM/aplicação (segundos) é irrisório frente a runs de minutos a horas.
- **Não é "CLI" como produto.** Não há interface de linha de comando a projetar nem comandos de usuário. A aplicação é um consumer: o Dagster apenas a dispara com parâmetros de run (recurso, janela, modo backfill/incremental) via configuração/variáveis de ambiente, e lê o resultado pelo estado e pelo sistema de arquivos.
- **Não é "batch" no sentido de ETL em lote.** "Diário" é a frescura do dado, não o modelo de execução. A captura é incremental por unidade idempotente.
- **Não é serviço sempre no ar** (sem scheduler interno, sem endpoint HTTP, sem estado em memória entre runs).

### Stack
- Captura em Java, executada sob demanda: o Dagster inicia o processo a cada run diário e ele termina ao fim. Não há serviço residente.
- Prata, ouro e orquestração em Python (Dagster). A fronteira entre as duas é o sistema de arquivos.
- Motivo: o custo de subir a JVM (segundos) é desprezível frente a runs de minutos a horas, com ~1 s por chamada HTTP. Reavaliar só se a frequência passar para minutos.
- Storage: arquivos + Parquet + DuckDB, sem servidor.

## Consequências

- Positivas: replay sem depender da API; unidades refazíveis; localização presente em todo documento; sem serviços para manter.
- Negativas: duas toolchains para um único mantenedor; o arquivo de estado único exige escritor único.
- Negativas do filtro por unidade: os contratos dependem do catálogo, então um órgão sem contratação publicada na janela observada não entra e seus contratos não são capturados; o número de requisições passa a ser proporcional ao número de pares `(cnpj, codigoUnidade)`, não ao volume de dados. A varredura nacional custaria ~1.083 páginas no backfill e ~12 por dia; só o Estado de Goiás tem 440 pares.
- Negativa de adiar `/atualizacao`: retificações e mudanças de situação não chegam ao bronze.
- Riscos: 429 em carga baixa pode alongar o backfill; espaço dos PDFs ainda não medido; com milhares de pares, o incremental diário faz milhares de requisições (a maioria 204) e o arquivo de estado cresce no mesmo ritmo, o que antecipa a reavaliação de SQLite.

## Em aberto

1. **Espaço em disco dos PDFs.** Só a etapa 5a (listagem + `HEAD`) responde se ~50 GB bastam.
2. **Lista válida de modalidades de contratação.** Há códigos que devolvem 204 (2 e 14). Decidir se o job usa lista fixa ou descobre dinamicamente.
3. **Política de 429.** Verificar se a API envia `Retry-After`; ainda não observado.
4. **Tamanho do catálogo de GO.** O número de pares `(cnpj, codigoUnidade)` não foi medido; o backfill de contratações mais a fase 2 respondem. Se for inviável consultar por par, as alternativas são consultar só por `cnpjOrgao` quando todas as unidades do órgão estão em GO, ou voltar à varredura nacional com seleção local.
5. **Cobertura do catálogo.** Medir quantos contratos de GO pertencem a órgãos que não publicaram contratação na janela observada.
6. **Atas (adiado): janela.** `/atas` filtra por vigência e `/atas/atualizacao` por atualização global. Definir qual usar no backfill e qual no incremental quando atas entrarem no escopo.

## Fatos medidos na API (2026-11-13)

Backfill real de contratações (publicação de 2026-08-13 a 2026-11-12, `uf=GO`, 14 modalidades) e de unidades, com `CAPTURA_CONCORRENCIA=2` (o mínimo aceito).

- **Contratações:** 24.826 registros, todos `unidadeOrgao.ufSigla=GO`, sem `numeroControlePNCP` repetido. 1.302 unidades de captura: 473 `OK`, 829 `VAZIA`, nenhuma `PENDENTE` ou `FALHA` ao fim. Um incremental refez 3 `FALHA` (dia 2026-11-03).
- **Modalidades:** 2, 11 e 14 devolveram só 204 nos 93 dias. Registros por modalidade: 8: 17.067; 9: 3.863; 6: 2.664; 4: 466; 7: 354; 12: 322; 5: 55; 13: 17; 1: 12; 3: 4; 10: 2. As modalidades 1, 3 e 10 têm dado em 11, 2 e 1 dia, respectivamente.
- **CNPJs distintos nas contratações:** 1.103. Pares `(cnpj, codigoUnidade)` distintos nas contratações: 1.678.
- **Unidades:** 1.103 requisições a `/orgaos/{cnpj}/unidades`, todas 200 (nenhum 204), em 2 min 15 s (~490 por minuto, com o processo limitado a 20% do tempo por `SIGSTOP`/`SIGCONT`), sem nenhum 429. As respostas somam 8.186 unidades; 3.358 estão fora de GO (RJ 821, DF 771, SP 191, RS 176, PA 124, AM 116, BA 113, PE 101). 34 órgãos têm pelo menos uma unidade fora de GO; nenhum tem todas fora de GO.
- **Catálogo de GO:** 4.828 linhas (pares `(cnpj, codigoUnidade)` distintos, 4.780 com `statusAtivo`), de 1.103 CNPJs, em 237 municípios. Os 1.678 pares vistos nas contratações constam todos do catálogo; os outros 3.150 não tiveram contratação na janela. Dez maiores municípios, em pares: Goiânia 253, Caldas Novas 156, 5205497 142 (nome não lido), Águas Lindas de Goiás 116, Goianésia 86, Morrinhos 84, Catalão 80, Niquelândia 76, Ipameri 75, Inhumas 75.
- **429 em `contratacoes/publicacao`:** a API não enviou `Retry-After` em nenhuma das 3.329 linhas de log com 429 (uma linha por tentativa, somando todos os runs). O limite parece ser de taxa: com `curl` sequencial, 12 requisições em 50 s passaram sem 429; com dois fluxos paralelos, as primeiras 5 a 6 requisições em ~10 s passaram e as seguintes receberam 429 em ~0,1 s. A recuperação levou cerca de 1,5 a 2 min sem requisições.
- **Efeito no backfill:** nos primeiros 40 min (latência de ~30 s por requisição), dois fluxos avançaram a ~6 unidades por minuto sem 429. Depois, com `curl` medindo 0,7 a 10 s por requisição, o mesmo cliente passou a receber 429 em sequência, esgotou as 6 tentativas e marcou `FALHA` (143 em 13 min com concorrência 3; 378 em 50 min com concorrência 2). [INFERÊNCIA] A latência menor elevou a taxa acima do limite. Reiniciar o processo após 4 ou 15 min de pausa também recebeu 429 em menos de 40 s. O que funcionou foi limitar o processo, de fora, alternando `SIGSTOP`/`SIGCONT` (3 s ativo, 12 s parado): o run que concluiu o backfill levou 2 h 17 min, a 6 a 7 unidades por minuto, com 31 respostas 429 no log. A captura não tem como configurar um intervalo mínimo entre requisições, e a concorrência mínima aceita é 2.
- **Duração total:** 5 h 22 min de relógio (08:52 a 14:15), dos quais 2 h 17 min no run que concluiu o backfill de contratações; o restante foi gasto em runs interrompidos por 429, em pausas e em sondagens com `curl`.
