# 0007: Deterministic verification on every answer; LLM judge offline

## Context
Prompting the model to cite and to stay within the sources helps, but it is not a guarantee.
The most damaging hallucinations in support and policy domains are wrong numbers: prices, limits,
dates and durations. Online LLM-as-judge on every answer roughly doubles cost and latency.

## Decision
Run two cheap, deterministic checks on every answer:
1. Every `[n]` must reference an existing source. An answer with no citations is not grounded
   unless it is a refusal.
2. Every number in the answer must appear in the evidence. Formatting is normalised, so
   `1,250` = `1250` and `99.00` = `99`, and single digits are ignored as noise.

A failed check adds a user-visible notice, keeps the answer out of the cache, and is recorded in
the trace (`grounded=false`, `unsupportedNumbers`). Semantic faithfulness scoring (an LLM judge)
belongs in the offline evaluation, where cost and latency don't reach users.

## Consequences
- These checks catch invented figures and fake citations, the two highest-impact failure types,
  at near-zero cost.
- They do not catch an unsupported claim with no number in it. That is the job of the offline
  judge and of user feedback, which is linked to the exact chunks through the trace.
