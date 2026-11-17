# Performance

## What is measured, and what is not

`LatencyBenchmark` (run on demand, not part of the build):

1. Indexes a synthetic corpus into real Postgres + pgvector (Testcontainers) and into the in-memory
   store. The corpus is the 8 evaluation documents varied per copy, spread across 4 spaces.
2. Runs the 37 golden questions × 5 rounds, after 3 warm-up rounds, through the retriever and the
   full answer path. Questions are made unique per round, so the answer cache never serves them.

It uses the offline models (hashing-v1 embeddings, extractive answers). The numbers are therefore
this service's own overhead: search, fusion, deduplication, confidence, context assembly,
verification and tracing. With a real LLM, generation adds roughly 0.5-3 s and dominates.
Embedding the question adds about 50-150 ms for a hosted model. The point of measuring the
overhead is to keep it a rounding error next to those.

```bash
./mvnw test -Dtest=LatencyBenchmark -Dbench.docs=2000 -Djacoco.skip=true
```

Add `-Dbench.explain=true` to print the iterative-scan `EXPLAIN ANALYZE` comparison. The results
are written to `target/benchmark.md`.

## Results (4 CPUs, JDK 21, pgvector 0.8.7)

| Store | Docs | Chunks | Ingest | Retrieve p50 / p95 / p99 | Full answer p50 / p95 / p99 |
|---|---|---|---|---|---|
| Postgres (HNSW + GIN) | 2,000 | 9,250 | 382 chunks/s | 12.5 / 36.3 / 45.9 ms | 18.5 / 57.1 / 86.5 ms |
| In-memory (exact scan) | 2,000 | 9,250 | 4,165 chunks/s | 10.4 / 16.3 / 21.3 ms | 27.5 / 44.2 / 48.1 ms |
| Postgres (HNSW + GIN) | 10,000 | 46,250 | 282 chunks/s | 52.0 / 170.0 / 194.0 ms | 76.2 / 283.6 / 428.4 ms |
| In-memory (exact scan) | 10,000 | 46,250 | 2,379 chunks/s | 85.9 / 122.2 / 142.0 ms | 231.6 / 294.4 / 343.6 ms |

| Store | Vector search p50 / p95 | Keyword search p50 / p95 |
|---|---|---|
| Postgres, 9,250 chunks | 2.1 / 5.2 ms | 9.5 / 30.4 ms |
| In-memory, 9,250 chunks | 5.2 / 5.8 ms | 7.2 / 9.7 ms |
| Postgres, 46,250 chunks | 6.0 / 33.5 ms | **39.5 / 151.0 ms** |
| In-memory, 46,250 chunks | 44.1 / 64.9 ms | 81.0 / 121.5 ms |

**Reading the 46k run:**
- **Vector search scales well,** from 2.1 to 6.0 ms p50 for 5x the data, as expected from HNSW.
- **Keyword search becomes the bottleneck.** An OR query has to score every matching row before
  it can take the top 30, and this synthetic corpus is a worst case: every document is a variant of
  one of 8 sources, so a common term matches about 1 in 8 of all chunks. Real corpora have far more
  varied vocabulary, but the scaling shape is real. See the capacity notes for the fix path.
- **The in-memory store degrades linearly,** as an exact scan with BM25 statistics recomputed per
  query should. It is a development store.

## Findings from profiling

The first measurement was 36 ms p50 retrieval on Postgres. Splitting it by leg and running
`EXPLAIN ANALYZE` found the following.

**1. Keyword search computed cosine distance for every matched row (38 ms → about 10 ms).**
OR-ed full-text queries are deliberate (ADR 3), and a question like "refund window annual plan"
matches thousands of rows. The query also selected `1 - (embedding <=> ?)` for every matched row,
before sorting by `ts_rank_cd` and taking 30. The fix ranks in a subquery, keeps the top k, and
computes the distance only for those rows.

**2. The two search legs ran one after the other.** They are independent, so they now run
concurrently on virtual threads, and retrieval costs about the slower leg rather than the sum.
In-memory retrieval went from 15.7 to 10.4 ms p50.

**3. One "slow vector search" was a benchmark artefact.** Postgres vector search first measured
22 ms p50, but the same query repeated took 2.5 ms, and `EXPLAIN ANALYZE` showed about 1 ms of
database time. The store measured first was absorbing JIT warm-up. After proper warm-up it is
2.1 ms. The benchmark now warms every measured path before timing.

**4. Without iterative scan, filtered HNSW silently returns nothing.** The same session shows why
`JdbcKnowledgeStore` sets `hnsw.iterative_scan = relaxed_order`:

| `hnsw.iterative_scan` | Rows returned (LIMIT 30) | Rows removed by the space filter | Time |
|---|---|---|---|
| `off` (pgvector default) | **0** | 40 | 0.56 ms |
| `relaxed_order` | 30 | 486 | 2.2 ms |

HNSW hands back its `ef_search` (40) nearest neighbours, and the space filter is applied
afterwards. When those neighbours belong to other tenants, a small tenant gets zero results. No
error is raised; the answer just becomes "I couldn't find anything".

## Capacity notes

- **Ingestion is HNSW-insert-bound** (about 380 chunks/s per worker on 4 CPUs). For a bulk
  backfill, load with the index dropped and `CREATE INDEX CONCURRENTLY` afterwards, use `COPY`,
  and raise `maintenance_work_mem`. Normal uploads go through the bounded worker pool, so a large
  upload never slows queries.
- **Keyword search is the leg to fix first at scale** (the 46k run shows it). `ts_rank_cd` must
  score every row the OR query matches. Options, in order of effort:
  1. Drop very high-frequency terms from the OR query. They add almost nothing to ranking, and they
     are what inflates the match set.
  2. Switch to `ts_rank`, which is cheaper than cover density, and compare on the golden set.
  3. Use a real BM25 index with top-k pruning (block-max WAND), such as ParadeDB `pg_search` inside
     Postgres, or OpenSearch. These return the top k without scoring every match. It is a new
     `keywordSearch` implementation behind the same `KnowledgeStore` port, validated by the same
     contract test and evaluation gate.
- **The in-memory store is for development and tests.** It scans every chunk, and its BM25
  statistics are recomputed per query.
- **Per-request memory is bounded** by `candidate-k`, `top-k` and `context.max-tokens`, not by
  corpus size.
