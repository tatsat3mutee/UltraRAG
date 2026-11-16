# 0002: Postgres + pgvector rather than a dedicated vector database

## Context
We need dense search, keyword search, metadata filters (space, active version) and transactional
document versioning. Corpus size per deployment is in the millions of chunks, not billions.

## Decision
Use Postgres with pgvector (HNSW, cosine) and built-in full-text search (a generated `tsvector`
with a GIN index) in the same table. Flyway owns the schema, including indexes.

## Alternatives
- **Pinecone, Weaviate or Qdrant.** Better at very large scale and with built-in hybrid search,
  but a second datastore that must stay consistent with document metadata, and no transaction
  across the two. The versioned swap would need a two-phase dance.
- **Elasticsearch or OpenSearch.** Strong BM25 and kNN, but heavy to operate for this scale and
  again outside the transaction.

## Consequences
- Activation (old version out, new version in, corpus version incremented) is one ACID transaction.
- Filtered ANN can under-return for small spaces. This is mitigated with pgvector ≥ 0.8
  `hnsw.iterative_scan`. Beyond roughly 50M chunks, partition by space or move to a dedicated engine
  behind the existing `KnowledgeStore` port.
- `ts_rank_cd` is not true BM25. RRF only uses ranks, so this matters less than it would for a
  weighted score sum.
