# 0003: Hybrid retrieval fused with Reciprocal Rank Fusion

## Context
Dense embeddings handle paraphrase ("new dad" ≈ "secondary caregiver") but are poor at exact
identifiers (error codes, SKUs, plan names). Keyword search is the opposite. Their scores are on
unrelated scales: cosine is in [-1, 1], and ts_rank and BM25 are unbounded.

## Decision
Run both searches (30 candidates each) and fuse them with RRF: `score = Σ 1 / (60 + rank)`.
Drop near-duplicates (Jaccard ≥ 0.9), then rerank. Keyword queries OR the content terms, because
`plainto_tsquery` and `websearch_to_tsquery` AND every word, so natural-language questions would
rarely match. Terms are sanitised to letters and digits, so no tsquery syntax can be injected.

## Alternatives
- **A weighted sum of normalised scores.** Needs per-corpus tuning, and min-max normalisation is
  unstable with few candidates.
- **Vector only.** It measurably misses identifiers in our evaluation (vector-only recall 0.94 vs
  1.00 for hybrid).

## Consequences
- Fused scores (~0.01-0.03) are for ordering only. They are never used as confidence; see ADR 5.
- The evaluation harness can run each mode separately, so the choice is re-checked with every
  embedding model. With the offline lexical embedding, keyword-only scores highest, which we
  report rather than hide (docs/EVALUATION.md).
