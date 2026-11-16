# Grounded RAG

A multi-tenant document question-answering service built on **Spring Boot 4 and Spring AI 2**.
Every answer cites its sources, is checked against them before it is returned, and comes with a
confidence level. When the documents do not contain the answer, the service says so instead of
guessing, and it decides that without spending an LLM call.

![Chat UI: a cited answer, a high-confidence answer, and a declined off-topic question](docs/images/ui.png)

## Why this exists

Most RAG demos stop at "embed, top-k, prompt". The failures that hurt in production come after
that: answers that cite sources that don't exist, numbers the model made up, follow-up questions
that retrieve nothing, caches serving answers about deleted documents, one tenant reading
another's files, and a re-upload that leaves a document half-indexed. This project is built around
those failure modes. Each one is handled in code, covered by a test, and explained in a design
record (ADR).

| Failure mode | How it's handled | Where |
|---|---|---|
| Model cites a source that doesn't exist, or invents a figure | Every answer is verified: citations must map to real sources, and every number must appear in the evidence | `GroundingVerifier`, [ADR 7](docs/adr/0007-verify-every-answer.md) |
| Answers off-topic questions with confident nonsense | Confidence policy: decline without calling the LLM when evidence is missing; caveat when it is weak; one corrective re-query first | `RagService`, [ADR 5](docs/adr/0005-confidence-and-abstention.md) |
| Follow-up questions ("and for annual plans?") retrieve nothing | Follow-ups are rewritten into standalone questions before retrieval and caching | `QueryRewriter` |
| Cache serves answers about content that changed | Cache key includes a per-space corpus version that every ingest/delete bumps | `SemanticCache` |
| Tenant isolation trusts a request header | Spaces come from the authenticated key; requests for other spaces get 403 (fails closed) | `SpaceAuthorizer`, [ADR 8](docs/adr/0008-tenancy-from-credentials.md) |
| Multi-space search silently ANDs filters | `= ANY(...)` filters, covered by a contract test on both stores | `KnowledgeStoreContract` |
| Re-upload deletes the old version before the new one is ready | Versioned ingestion: the old version serves until the new one is fully indexed, then one transaction swaps them | `IngestionService`, [ADR 6](docs/adr/0006-versioned-ingestion-and-state.md) |
| Embedding model changed, old vectors silently incomparable | Model name stored per document version; startup check warns; dimension mismatch fails fast | `Embedder`, `StartupChecks` |
| PII ends up in the index, the cache and the LLM provider | Redacted at ingest (cards only when Luhn-valid) | `PiiRedactor` |
| Config flags that do nothing | Typed `@ConfigurationProperties`; every value is read by code | `RagProperties` |
| "Quality dashboard" with placeholder values | Each answer persists a trace with real chunk ids, scores, verification and per-stage timings; feedback attaches to it | `AnswerTrace` |
| Nobody knows if a change made retrieval worse | A golden-set evaluation runs in CI and fails the build below baseline | `RetrievalQualityGateTest`, [EVALUATION](docs/EVALUATION.md) |

## Architecture

```mermaid
flowchart LR
  subgraph Ingest["Ingest (async, bounded queue)"]
    U[Upload] --> H[SHA-256 dedup] --> P[Parse<br/>PDF pages / Markdown sections / Tika]
    P --> R[PII redaction] --> C[Parent-child chunking]
    C --> E[Embed children<br/>with contextual header] --> S[(Store new version)]
    S --> A[Activate: swap versions<br/>+ bump corpus version]
  end
  subgraph Query
    Q[Question + history] --> W[Standalone rewrite] --> QE[Embed]
    QE --> K{Semantic cache<br/>keyed by corpus version}
    K -- hit --> OUT
    K -- miss --> HY[Hybrid search<br/>pgvector + full-text, RRF]
    HY --> RR[Rerank + confidence]
    RR -- weak --> CR[Reformulate, retry once] --> RR
    RR -- none --> DEC[Decline, no LLM call] --> OUT
    RR -- ok --> CTX[Parent expansion<br/>under token budget] --> G[Generate with<br/>numbered sources]
    G --> V[Verify citations + numbers] --> OUT[Answer + sources + trace]
  end
  A -.-> K
```

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Design decisions: [docs/adr](docs/adr).

## Run it

**Zero setup** (in-memory store, offline embeddings, extractive answers; no API key, no Docker):

```bash
./mvnw spring-boot:run
```

Open http://localhost:8080, enter the dev key `dev-key-change-me-0001`, upload files from
`eval/corpus/`, and ask questions.

**Production-like** (Postgres + pgvector with a Flyway-managed schema):

```bash
docker compose up --build
```

**With a real LLM.** Any OpenAI-compatible endpoint works, including OpenAI, Groq, OpenRouter and vLLM:

```bash
OPENAI_API_KEY=sk-... ./mvnw spring-boot:run -Dspring-boot.run.profiles=openai
```

**Fully local LLM** (Ollama) plus a cross-encoder reranker:

```bash
APP_PROFILES=postgres,ollama,rerank docker compose --profile ollama --profile rerank up --build
```

Then pull the two Ollama models into the running container:

```bash
docker compose exec ollama ollama pull llama3.2
```

```bash
docker compose exec ollama ollama pull nomic-embed-text
```

All profiles and properties: [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/spaces/{space}/documents` | Upload (multipart `file`). `202` with a QUEUED version to poll; `200` if the bytes are already indexed |
| `GET` | `/api/v1/spaces/{space}/documents[/{id}]` | Versions and their status (QUEUED, INDEXING, ACTIVE, SUPERSEDED, FAILED + reason) |
| `DELETE` | `/api/v1/spaces/{space}/documents/{id}` | Delete all versions of that source |
| `POST` | `/api/v1/chat` | `{question, conversationId?, spaces?}` returns answer, sources[], confidence, verification, timings |
| `POST` | `/api/v1/chat/stream` | Same, as server-sent events: `meta` (sources first), `token`*, `done` |
| `GET` | `/api/v1/answers/{id}` | The trace behind an answer (chunk ids, scores, timings), owner only |
| `POST` | `/api/v1/answers/{id}/feedback` | `{rating: 1 or -1, comment?}` |
| `GET` | `/actuator/health`, `/actuator/prometheus` | Probes and metrics (`rag.stage`, `rag.answers`, `rag.ingest`) |

All `/api` calls need an `X-API-Key` header. Errors are RFC 9457 problem details.

## Evaluation

The golden set has 37 questions over 8 fictional policy and product documents: plain lookups,
paraphrases, exact identifiers like error codes, cross-space questions and unanswerable ones.
It runs on every build.

| Retrieval (offline CI, k=6) | Recall@6 | MRR | nDCG@6 |
|---|---|---|---|
| Hybrid (RRF) | 1.000 | 0.890 | 0.919 |
| Vector only | 0.941 | 0.777 | 0.821 |
| Keyword only | 1.000 | 0.951 | 0.964 |

| End to end (offline CI) | Value |
|---|---|
| Unanswerable questions correctly declined | 3 / 3 |
| Answerable questions wrongly declined | 3 / 34 (all paraphrases) |
| Answers that passed verification | 34 / 34 |
| Answers containing the expected fact | 28 / 34 |

These numbers use the offline hashing embedding, which is lexical, so keyword-only search wins
here. That is expected, and it is reported as measured rather than hidden. The same harness runs
against real models with one flag. Method, the confidence-calibration analysis, and how to
reproduce: [docs/EVALUATION.md](docs/EVALUATION.md).

## Tests

80 tests run with `./mvnw verify` (the store contract runs twice):

- **Unit tests** for each pipeline component.
- **One store contract** run against both the in-memory store and real Postgres + pgvector (Testcontainers, which also exercises the Flyway migration).
- **API tests** through MockMvc.
- **A streaming test over a real socket.** It catches the SSE/async-dispatch security bug that MockMvc cannot.
- **The evaluation quality gate.**

## Project layout

```
src/main/java/io/groundedrag/
  domain/        SpaceId, document/chunk records, the shared TextAnalyzer
  ingestion/     parse -> redact -> chunk -> embed -> versioned activate
  store/         KnowledgeStore port, in-memory and JDBC (pgvector) adapters
  retrieval/     embedder, hybrid retriever (RRF), rerankers, context assembler
  generation/    query rewriting, answer generation (LLM / extractive), verification
  chat/          RagService pipeline, conversation memory, API response model
  cache/         semantic cache with corpus-version invalidation
  observability/ answer traces and feedback
  security/      API-key authentication, space authorisation
  eval/          golden-set evaluator and runner
  config/        composition root and typed properties
  web/           REST + SSE controllers, problem details
eval/            golden set and fictional corpus
docs/            architecture, ADRs, evaluation, configuration, interview guide
```

## Known limitations

- Conversation memory and the answer cache are in-process. That suits a single instance; the
  seam for Redis is one interface each ([ADR 6](docs/adr/0006-versioned-ingestion-and-state.md)).
- Scanned PDFs need OCR, which is not included.
- The offline profile cannot handle paraphrases. That is the measured case for running the
  cross-encoder profile ([EVALUATION](docs/EVALUATION.md)).
- API keys are configured statically. For SSO, swap the filter for an OAuth2 resource server
  and map a token claim to spaces; nothing downstream changes.
