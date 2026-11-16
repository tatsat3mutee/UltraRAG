# 0004: Parent-child ("small-to-big") chunking

## Context
Small chunks give precise similarity matches but lose the surrounding context the model needs.
Large chunks keep the context but blur the embedding. A common production bug is computing
parent links at split time and then never storing or using them.

## Decision
- **Parents:** about 700 tokens. Stored, never embedded.
- **Children:** about 180 tokens, overlapping by about 40 tokens on sentence boundaries. These are
  the only things embedded.
- **No crossing boundaries:** a parent never spans two pages or Markdown sections, so citations
  keep the right page and section.
- **Assembly:** each child hit is replaced by its parent when the parent fits the token budget.
  Siblings of a parent that is already included are skipped.
- **Embedded text:** each child is embedded with a contextual header (`source > section`).

## Consequences
- `parent_chunks` is a real table with a foreign key, and the expansion path is covered by tests.
- The context budget is a hard token limit (JTokkit), so prompt size is bounded no matter how many
  hits there are.
