# 0009: Quality is enforced by the build, not by convention

## Context
Architecture diagrams, coverage targets and "we evaluate retrieval" claims tend to decay quietly
once the person who wrote them moves on. In a RAG system, small changes such as a prompt edit, a
new chunk size or a dependency bump can degrade answers without any test failing.

## Decision
`./mvnw verify`, which CI runs on every push, fails when any of these regress:

| Gate | Tool | What it protects |
|---|---|---|
| Layering | ArchUnit (`ArchitectureTest`) | Domain packages have no Spring wiring, web or security types; only the store touches JDBC; retrieval doesn't know about generation; no package cycles |
| Behaviour parity | `KnowledgeStoreContract`, `TraceStoreContract` | The in-memory and Postgres adapters behave identically (run against real pgvector through Testcontainers) |
| Retrieval and answer quality | `RetrievalQualityGateTest` | Recall@6, MRR, abstention and grounded-rate baselines on the golden set |
| Coverage | JaCoCo check | Line coverage ≥ 85% (currently about 93%) |
| Streaming over a real socket | `StreamingOverHttpTest` | The class of bug that MockMvc cannot see |

## Consequences
- The first run of the architecture test found two real cycles (`retrieval → config` and
  `security → config`). They were fixed by giving each package its own settings types, mapped in
  the composition root.
- **Lowering a baseline is an explicit change in the diff,** with a reason, not drift.
- **Some checks are deliberately not in the gate:** the latency benchmark (`LatencyBenchmark`,
  run on demand) and the LLM-judge faithfulness score (it needs a model and has its own error
  rate). Both are documented with the commands to run them.
