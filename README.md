# Votação Assembleia

API Java 17 / Spring Boot 3.5.6 para cadastrar pautas, abrir sessões, registrar votos e consultar resultados. Inclui o contrato de telas JSON do Anexo 1 (`FORMULARIO` e `SELECAO`). Apenas backend: não há aplicativo mobile.

## Executar localmente (Windows, Linux ou macOS)

Pré-requisitos: JDK 17 e Maven 3.9+ no PATH. Confira `java -version` e `mvn -version`.

```sh
mvn clean verify
mvn spring-boot:run
```

A API atende em http://localhost:8080; saúde: http://localhost:8080/actuator/health.
O banco local é H2 **em arquivo**, em `./data/votacao.mv.db`. Encerre e inicie o processo no mesmo diretório para conservar os dados. Não apague `data/`. Um único processo deve usar esse banco local. Não é necessário instalar banco para avaliar a aplicação.

## Execução com PostgreSQL e Docker

Requer Docker com Compose:

```sh
cp .env.example .env
# Windows PowerShell: Copy-Item .env.example .env
# Edite DB_PASSWORD no .env antes de iniciar.
docker compose up --build -d
```

O volume `postgres_data` conserva os dados após `docker compose down`. O comando `docker compose down -v` apaga esse volume e seus dados. Migrações Flyway rodam na inicialização. O build Docker executa os testes.

## Testar o fluxo

Os exemplos usam shell Bash. No PowerShell use `curl.exe`, ajuste as aspas JSON ou use `Invoke-RestMethod`. Substitua `<ID>` pelo UUID retornado no cadastro.

```sh
curl -i -X POST http://localhost:8080/api/v1/pautas -H 'Content-Type: application/json' -d '{"titulo":"Reforma da agência","descricao":"Aprovar investimento"}'
curl -i -X POST http://localhost:8080/api/v1/pautas/<ID>/sessao -H 'Content-Type: application/json' -d '{}'
curl -i -X POST http://localhost:8080/api/v1/pautas/<ID>/votos -H 'Content-Type: application/json' -d '{"associadoId":"associado-1","voto":"SIM"}'
curl http://localhost:8080/api/v1/pautas/<ID>/resultado
```

Exemplo de resultado:

```json
{"pautaId":"...","status":"ABERTA","sim":1,"nao":0,"total":1,"resultado":"APROVADA"}
```

Enquanto a sessão está aberta, os números são parciais. `NAO_ABERTA` indica que a sessão ainda não começou. Com zero votos, a classificação é `EMPATE`; o consumidor deve observar `total` e `status`.

## API REST v1

| Método | Caminho | Resposta de sucesso |
| --- | --- | --- |
| POST | `/api/v1/pautas` | 201 e pauta, cabeçalho Location |
| GET | `/api/v1/pautas?pagina=0` | 200, até 50 pautas |
| GET | `/api/v1/pautas/{id}` | 200 e pauta |
| POST | `/api/v1/pautas/{id}/sessao` | 201 e sessão |
| GET | `/api/v1/pautas/{id}/sessao` | 200 e sessão |
| POST | `/api/v1/pautas/{id}/votos` | 204 |
| GET | `/api/v1/pautas/{id}/resultado` | 200 e contagem |

O arquivo `docs/openapi.json` descreve a API, incluindo o fluxo mobile, e pode ser importado no Swagger Editor ou Postman.
Erros usam `application/problem+json` (Problem Detail). Códigos: 400 entrada inválida; 404 pauta/CPF inexistente; 403 associado não habilitado; 409 sessão ausente/encerrada/reabertura/voto duplicado; 503 validação externa indisponível. Erros inesperados retornam 500 sem expor detalhes internos.

## Contrato mobile do Anexo 1

Início: `GET /api/v1/mobile`. Todas as ações seguintes usam **POST**, inclusive navegação, seguindo `url` e `body` retornados. O app adiciona os valores dos campos pelo seu `id` ao corpo definido pelo botão.

Exemplo de formulário de cadastro:

```json
{
 "tipo":"FORMULARIO", "titulo":"Nova pauta",
 "itens":[
  {"tipo":"INPUT_TEXTO","id":"titulo","titulo":"Título","valor":""},
  {"tipo":"INPUT_TEXTO","id":"descricao","titulo":"Descrição","valor":""}
 ],
 "botaoOk":{"texto":"Cadastrar","url":"http://localhost:8080/api/v1/mobile/pautas","body":{}},
 "botaoCancelar":{"texto":"Voltar","url":"http://localhost:8080/api/v1/mobile","body":{}}
}
```

O servidor usa exatamente os nomes `tipo`, `titulo`, `itens`, `botaoOk`, `botaoCancelar`, `texto`, `url`, `body`, `id`, `valor` do anexo. Tipos utilizados: `TEXTO`, `INPUT_TEXTO`, `INPUT_NUMERO`; `INPUT_DATA` existe no exemplo do anexo, mas não é necessário neste domínio.

Fluxo: início → cadastro/listagem → pauta → abrir sessão/votar/resultado. A lista possui paginação em lotes de 50. Na tela de voto, os dois botões são “Sim” e “Não”, com `body.voto` respectivamente `SIM` e `NAO`. O slot `botaoCancelar` representa o segundo botão: nessa tela ele registra **Não**, não cancela. Isso permite escolher o voto sem criar tipos de campo ausentes do anexo. Erros têm status HTTP e Problem Detail para tratamento pelo cliente, em vez de tela de sucesso.

Configure `CALLBACK_BASE_URL` para o endereço alcançável pelo aparelho (ex.: `http://192.168.0.10:8080`), incluindo eventual prefixo do proxy. Nunca é derivado de cabeçalhos Host enviados pelo usuário. Em produção use a URL HTTPS pública.

## Regras e decisões

- Uma pauta pode ter **uma única sessão**, sem reabertura. O PDF não define reabertura; essa escolha mantém a regra de um voto por pauta simples e previsível.
- Duração em minutos inteiros, padrão 1 quando omitida/nula; intervalo aceito 1 a 10.080 (7 dias), limite operacional explícito.
- Intervalo da sessão: início inclusivo e fim exclusivo. Horários persistidos em UTC. Encerramento é calculado por timestamp; não depende de scheduler nem se perde com restart.
- Identificador do associado: texto de até 100 caracteres, alfanuméricos, `_` ou `-`, sensível a maiúsculas. CPF é dado separado, usado apenas na integração opcional e não armazenado.
- Votos aceitos: `SIM` e `NAO`, equivalentes a Sim/Não do enunciado. Não há alteração ou exclusão de voto.
- Restrição UNIQUE `(pauta_id, associado_id)` garante unicidade sob concorrência e múltiplas instâncias. Abertura também é protegida pela chave primária de sessão.
- Inserção de voto é condicional ao prazo e executada em um único comando SQL; não há sequência ler-contador/gravar-contador. A referência de tempo é o instante amostrado pela API imediatamente antes da inserção. Sincronize os relógios das instâncias em nuvem.
- Contagem é feita no banco com índice `(pauta_id, escolha)`, sem carregar votos em memória. Consulta de resultado usa snapshot transacional. A contagem custa O(votos da pauta): para volume extremo medir antes de adotar contadores assíncronos ou tabelas de agregação.
- Arquitetura deliberadamente pequena: controllers REST/mobile → serviço de domínio → JdbcTemplate. Records para DTOs, Bean Validation na borda, migrações Flyway, Clock injetável, sem Lombok ou camadas genéricas sem uso.
- Logs registram eventos e IDs de pautas, sem CPF e sem identificador do associado. Autenticação abstraída conforme permitido pelo enunciado.
- Versionamento por caminho `/api/v1`. Alterações compatíveis adicionam campos opcionais; mudanças incompatíveis criam `/api/v2`, mantendo v1 durante transição documentada.

## Bônus de CPF

Desativado por padrão para execução independente do serviço externo. Ative `CPF_ENABLED=true` e envie `cpf` com 11 dígitos. O cliente chama `GET {CPF_BASE_URL}/users/{cpf}` (padrão do enunciado: `https://user-info.herokuapp.com`).

1. Quando CPF_ENABLED=false (Padrão)
O envio do campo "cpf" é opcional / ignorado.
A requisição de voto precisa apenas de associadoId e voto:
json
{
  "associadoId": "associado-1",
  "voto": "SIM"
}
Nenhuma chamada externa é disparada.
2. Quando CPF_ENABLED=true (Habilitado)
O campo "cpf" torna-se obrigatório (11 dígitos numéricos):
json
{
  "associadoId": "associado-1",
  "voto": "SIM",
  "cpf": "12345678901"
}

- `ABLE_TO_VOTE`: segue votação; `UNABLE_TO_VOTE`: 403; HTTP 404: CPF inválido.
- Timeout de conexão 2 s e de requisição 3 s; respostas inesperadas, timeout ou indisponibilidade: 503, sem registrar voto.
- Validação ocorre antes da operação de gravação e fora de transação. Prazo é rechecado após a chamada. Sem cache/retry automático porque a resposta do serviço é aleatória segundo o PDF.
- O formato local de 11 dígitos não substitui a validação do serviço. Não existe aprovação automática quando a integração está habilitada e falha.
- Não se presume disponibilidade atual do endpoint Heroku. Os testes usam um servidor HTTP local e respostas controladas.

## Testes e qualidade

```sh
mvn clean verify
```

JUnit 5, MockMvc e testes reais JDBC/Flyway em H2: cadastro, sessão padrão/personalizada, prazo exato, fechamento, duplicidade, reabertura, contagem, concorrência, validações HTTP, telas/callbacks mobile e integração CPF com servidor local. Um teste fecha e reabre contextos Spring no mesmo arquivo de banco para verificar persistência. Relatório JaCoCo em `target/site/jacoco/index.html`; resultados em `target/surefire-reports/`. GitHub Actions executa o mesmo comando em push/PR.

Os testes automatizados padrão usam H2. A execução em PostgreSQL e o container devem ser validados no ambiente de destino; não são cobertos por um teste PostgreSQL neste pacote.

### Carga (bônus de performance)

Requer k6 instalado e API rodando. Crie uma pauta e abra sessão de 60 minutos, então:

```sh
k6 run -e PAUTA_ID=<ID> -e VOTERS=100000 -e VUS=50 performance/votos.js
```

O teste usa IDs distintos, valida HTTP 204 e registra taxa de erro e p95. Rode em uma pauta nova para cada execução. Use uma segunda instância do teste com IDs controlados se quiser medir conflitos deliberadamente. Os limites do script são metas, não resultados medidos. Faça carga em ambiente isolado; compare H2 e PostgreSQL separadamente e monitore CPU, conexões e I/O. O relatório real deve registrar hardware, banco, concorrência, quantidade, throughput e latências. A integração de CPF deve ser desativada ou apontada para stub durante medição de banco.

## Deploy em nuvem

O Dockerfile gera imagem sem privilégios de root, usa `PORT` e expõe `/actuator/health`. Em qualquer provedor com containers, configure PostgreSQL persistente e:

| Variável | Uso |
| --- | --- |
| `DB_URL` | URL JDBC, ex.: `jdbc:postgresql://host:5432/votacao` |
| `DB_USER` / `DB_PASSWORD` | Credenciais injetadas pelo provedor |
| `PORT` | Porta HTTP (padrão 8080) |
| `CALLBACK_BASE_URL` | URL pública alcançável pelo mobile |
| `CPF_ENABLED` / `CPF_BASE_URL` | Integração opcional |

Para nuvem prefira PostgreSQL gerenciado; H2 em filesystem efêmero não conserva dados após substituição do container. Não mantenha senhas no Git. Configure healthcheck para a porta de `PORT` e caminho `/actuator/health`.

## Publicar no GitHub

O pacote inclui fontes, testes, migrações, documentação, Docker e workflow, sem dados locais ou PDF do processo seletivo. Crie um repositório vazio chamado `votacao-assembleia` na sua conta. No terminal, dentro da pasta do projeto:

```sh
git init -b main
git add .
git commit -m "feat: implementar votação cooperativa e contrato mobile"
git remote add origin https://github.com/taureliano/votacao-assembleia.git
git push -u origin main
```

Se trabalhar no repositório Git já inicializado entregue separadamente, pule `git init`, `git add` e `git commit` e configure somente o remoto/push. A autoria Git deve ser configurada com seu nome e e-mail antes de criar novos commits. Sugestão para próximos commits: `fix: ...`, `test: ...`, `docs: ...`, sempre com mudanças pequenas e mensagens descritivas.

## Roteiro de apresentação

Mostre `mvn verify`, um cadastro e votação via REST, depois o JSON de `/api/v1/mobile`. Explique UNIQUE no banco, timestamp de fechamento, persistência após restart, callbacks configuráveis e as escolhas acima. Comente os limites de contagem síncrona e como mediria performance antes de expandir a arquitetura.
