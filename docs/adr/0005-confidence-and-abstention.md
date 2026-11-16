# 0005: Confidence policy and abstention without an LLM call

## Context
A RAG system that always answers will answer off-topic questions with confident nonsense. The
confidence signal must separate "evidence answers this" from "nothing relevant". We measured the
obvious candidates on the golden set:

- **RRF score.** Rank-based, so it is near-constant (~0.03) whether or not the hits are relevant.
  Useless for confidence.
- **Raw cosine of the top hit.** With the offline embedding, an unanswerable question scored 0.10
  while 15 answerable ones scored lower. Its scale also changes with every embedding model.
- **Term-overlap-based rerank scores.** These mark good paraphrases as low confidence.

## Decision
- **Separate scores.** Every hit keeps vector, keyword, fused and rerank scores separately.
- **With a cross-encoder reranker:** confidence is the top rerank score, which is calibrated
  (sigmoid).
- **Without a reranker:** confidence is evidence coverage, the share of the question's content
  terms present in the top 3 chunks. Terms containing digits (identifiers, figures) weigh double,
  and question fillers are ignored. It is model-independent and explainable.
- **Policy:**
  - HIGH or MEDIUM: answer.
  - LOW: one corrective retrieval with a reformulated query, then answer with a "weak match" notice.
  - NONE: decline with guidance and do not call the LLM. That saves cost and removes the chance of
    a fabricated answer.

## Consequences
- **Measured offline:** 3 of 3 unanswerable questions declined, and 3 of 34 answerable questions
  wrongly declined, all paraphrases. That last figure is the quantified argument for turning on
  the cross-encoder profile.
- **Calibration:** thresholds live in configuration. The rerank profile has its own, because its
  score has a different distribution.
