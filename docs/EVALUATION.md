# Evaluation

## What is measured

The golden set (`eval/golden-set.json`) has 37 questions over 8 fictional documents in two spaces
(`eval/corpus/`, written for this repo, with no real data):

| Type | Count | What it tests |
|---|---|---|
| factual | 22 | Plain lookups |
| paraphrase | 9 | Different wording from the source ("new dad" vs "secondary caregiver") |
| identifier | 2 | Exact codes (`NW-4291`), where embeddings are weak |
| cross-space | 2 | Searching two spaces at once (the "any of" filter) |
| unanswerable | 3 | The system must decline |

Each question lists its expected source document(s) and facts the answer must contain.

**Retrieval metrics** are measured at document level for each mode (hybrid, vector, keyword), at k = 6:
- **Recall@k:** an expected document appears in the top k.
- **MRR:** the reciprocal rank of the first expected document.
- **nDCG@k:** a rank-discounted gain with binary relevance.

**End-to-end metrics:**
- **Fact accuracy:** the share of answers that contain every expected fact.
- **Grounded rate:** the share of answers that pass the verifier.
- **False abstention rate:** answerable questions that were declined.
- **Correct abstention rate:** unanswerable questions that were declined.

## Results (offline CI configuration)

The CI configuration uses the hashing-v1 embedding, no reranker, and the extractive generator.

| Mode | Recall@6 | MRR | nDCG@6 | Misses |
|---|---|---|---|---|
| Hybrid | 1.000 | 0.890 | 0.919 | none |
| Vector | 0.941 | 0.777 | 0.821 | h05 (paraphrase), x02 (identifier) |
| Keyword | 1.000 | 0.951 | 0.964 | none |

| End to end | Value |
|---|---|
| Fact accuracy | 0.824 (28/34) |
| Grounded | 1.000 |
| False abstentions | 0.088 (3/34: h05, p04, p06, all paraphrases) |
| Correctly declined | 1.000 (3/3) |

### Reading these honestly
- **Keyword-only scores highest here.** hashing-v1 is a feature-hashing embedding (word unigrams,
  bigrams and character trigrams). It is lexical, not semantic, so it adds little that BM25 doesn't
  already have, and RRF lets its noise push some right answers from rank 1 to rank 2. The harness
  exists so this claim is re-checked with a real model rather than assumed. Expect the vector and
  hybrid rows to move most once semantic embeddings are used.
- **The vector misses are the expected ones.** It misses one paraphrase and one identifier. Hybrid
  recovers both through the keyword leg.
- **Every remaining end-to-end failure is a paraphrase.** No lexical signal can tell "new dad"
  from an off-topic question. This is the measured case for the cross-encoder profile.

## How the confidence thresholds were chosen

We first used the top hit's cosine similarity as confidence. It did not separate the classes: the
unanswerable u01 scored 0.10, higher than 15 answerable questions, and with thresholds at
0.18/0.30/0.45, 50% of answerable questions were declined. RRF scores were useless as well (~0.03
regardless of relevance).

We switched to evidence coverage (ADR 5) and looked at the distribution:

| Coverage band | Questions |
|---|---|
| 0.33 | u01, u03 (unanswerable) |
| 0.38-0.40 | h05, p06, p04 (answerable paraphrases), u02 (unanswerable) |
| 0.43-0.50 | h16, h07, h06, h11 (answerable) |
| ≥ 0.63 | all other answerable questions |

The LOW threshold of 0.42 sits between the two groups. Three paraphrases fall below it alongside
the unanswerable questions, and that overlap is exactly why a semantic signal is needed. MEDIUM
(0.6) and HIGH (0.8) set how much caveat the answer carries, not whether it is given.

The set is small (37 questions), so these thresholds are a starting point, not a fit. Grow the set
with real user questions from traces, especially thumbs-down ones, before tuning further.

## The CI gate

`RetrievalQualityGateTest` runs the full evaluation on every build and fails below these baselines:

| Metric | Gate |
|---|---|
| Recall@6 | ≥ 0.97 |
| MRR | ≥ 0.85 |
| Correctly declined | = 1.0 |
| False abstentions | ≤ 0.12 |
| Grounded | ≥ 0.95 |
| Fact accuracy | ≥ 0.75 |

The report is written to `target/eval-report.md` and appended to the GitHub Actions job summary.
A pull request that lowers a baseline has to say why in the diff.

## Running against real models

```bash
docker compose up -d postgres
```

```bash
OPENAI_API_KEY=sk-... ./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=postgres,openai \
  -Dspring-boot.run.arguments=--rag.eval.run=true
```

Add `,rerank` to the profiles, with the reranker container running, to measure the cross-encoder.
The runner ingests `eval/corpus` into the configured store, writes `target/eval-report.md`, and
exits. Use an empty database; the runner is not meant for production data.

The real-model numbers are not checked in yet. The build environment for this commit had no model
access, so the table above is the offline baseline only. Fill in this table once you've run it:

| Configuration | Recall@6 | MRR | Fact accuracy | False abstentions | Correctly declined |
|---|---|---|---|---|---|
| openai (text-embedding-3-small + gpt-4.1-mini), no reranker | | | | | |
| openai + bge-reranker-base | | | | | |
| ollama (nomic-embed-text + llama3.2) + reranker | | | | | |

## Next steps for the evaluation
- **Grow the set** to 150+ questions, mined from production traces.
- **Add an LLM-as-judge faithfulness score** on a sample (claims supported by the cited sources),
  plus answer relevance.
- **Track metrics per question type,** so a regression on identifiers is not hidden by an
  improvement on paraphrases.
