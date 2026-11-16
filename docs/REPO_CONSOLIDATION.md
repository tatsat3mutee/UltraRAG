# Repository consolidation

Goal: two RAG projects on the profile, each with a clear purpose, instead of a dozen overlapping ones.

| Keep | Why |
|---|---|
| **grounded-rag** (this repo, Spring AI) | Flagship: production RAG engineering with multi-tenancy, versioned ingestion, verification, an evaluation gate and ADRs |
| **mtech-teleco-multiagent-project** (Python) | The M.Tech thesis: multi-agent RCA with LangGraph, GraphRAG, a critic loop, ablations and MLflow. A different problem (agentic reasoning over anomalies), kept separate on purpose |

Your own repositories:

| Repository | What it is | Recommendation |
|---|---|---|
| AskRag | FAISS + Groq + Streamlit/FastAPI starter (~1.6k lines) | **Archive.** Superseded: everything it does is a subset of grounded-rag |
| DocRag | LangGraph ReAct RAG over a few URLs and a PDF, Streamlit (~700 lines) | **Archive.** Superseded; the agentic angle is covered better by the thesis |
| IntelliQuery | Text-to-SQL, churn ML and document Q&A on Databricks (~8.4k lines). Ships a pickled model and an xlsx, the license says "Proprietary", and the badges point at `yourusername` | **Archive**, or make private. It mixes three products, and the RAG part is basic. If you want an analytics showcase later, extract only the text-to-SQL part |
| multiagent-rag-telecom-billing-rca (private) | Older copy of the thesis (last commit June) with university template PDFs and draft docs | **Archive** after one check: its `tests/` folder has `test_telecom_italia_loader.py` and the loader that the public repo lacks. Copy those over if you still use that dataset |
| mtech-teleco-multiagent-project | Final thesis version (August): final report, viva material, DESIGN.md | **Keep**, pin it, and link it from grounded-rag's README as the agentic counterpart |

Forks with no commits of your own. They add noise and suggest the work is someone else's:

| Fork | Recommendation |
|---|---|
| UltraRAG | **Delete after moving grounded-rag to its own repo.** grounded-rag currently lives on a branch of this fork (see below) |
| ApeRAG, RAG_Techniques, Advanced_Rag_Codes, scalable-rag-pipeline, enterprise_document_chat, enterprise-itsm-graph-rag, arxiv-paper-curator | **Delete** (use a star or a bookmark if you want to keep a reference) |

## Moving grounded-rag into its own repository

The code was developed on branch `claude/youthful-carson-bs5ulu` of the UltraRAG fork. That branch
contains only this project's history, so it can be pushed as-is. Create an empty repository named
`grounded-rag` on GitHub, then:

```bash
git clone --single-branch --branch claude/youthful-carson-bs5ulu https://github.com/tatsat3mutee/UltraRAG grounded-rag
```

```bash
cd grounded-rag && git remote set-url origin https://github.com/tatsat3mutee/grounded-rag.git
```

```bash
git push -u origin claude/youthful-carson-bs5ulu:main
```

Then delete the UltraRAG fork. Archiving is reversible in Settings → General → Danger Zone.
Deletion is not, so archive first if unsure.

## Profile hygiene
- Pin grounded-rag and the thesis repo.
- Give each one a one-line description and topics (`rag`, `spring-ai`, `pgvector`, `llm`).
- Never publish employer code or employer-specific review material. The lessons are written up
  generically in this repo; keep it that way.
