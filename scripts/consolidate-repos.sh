#!/usr/bin/env bash
# One-time GitHub cleanup for the RAG repositories (see docs/REPO_CONSOLIDATION.md).
# Run from your own machine with the GitHub CLI logged in as the owner:
#   gh auth refresh -h github.com -s delete_repo
#   bash scripts/consolidate-repos.sh            # dry run: prints what it would do
#   bash scripts/consolidate-repos.sh --apply    # does it
set -euo pipefail

OWNER="tatsat3mutee"
APPLY="${1:-}"

run() {
  if [[ "$APPLY" == "--apply" ]]; then
    echo "+ $*"; "$@"
  else
    echo "would run: $*"
  fi
}

# 1. Move grounded-rag out of the UltraRAG fork into its own repository, with only its own history.
if ! gh repo view "$OWNER/grounded-rag" >/dev/null 2>&1; then
  run gh repo create "$OWNER/grounded-rag" --public \
    --description "Multi-tenant RAG on Spring AI: verified citations, abstention, versioned ingestion, eval-gated CI"
fi
if [[ "$APPLY" == "--apply" ]]; then
  tmp="$(mktemp -d)"
  git clone --single-branch --branch claude/youthful-carson-bs5ulu "https://github.com/$OWNER/UltraRAG" "$tmp/grounded-rag"
  git -C "$tmp/grounded-rag" push "https://github.com/$OWNER/grounded-rag.git" claude/youthful-carson-bs5ulu:main
  gh repo edit "$OWNER/grounded-rag" --default-branch main \
    --add-topic rag --add-topic spring-ai --add-topic pgvector --add-topic llm --add-topic java
  # Refuse to delete the fork unless the new repository really has the code.
  gh api "repos/$OWNER/grounded-rag/contents/pom.xml" >/dev/null
fi

# 2. Archive superseded projects (reversible: Settings > General > Unarchive).
for repo in AskRag DocRag IntelliQuery multiagent-rag-telecom-billing-rca; do
  run gh repo archive "$OWNER/$repo" --yes
done

# 3. Delete unmodified forks (irreversible; they are copies of upstream projects).
for repo in UltraRAG ApeRAG RAG_Techniques Advanced_Rag_Codes scalable-rag-pipeline \
            enterprise_document_chat enterprise-itsm-graph-rag arxiv-paper-curator; do
  run gh repo delete "$OWNER/$repo" --yes
done

echo "Done. Pin grounded-rag and mtech-teleco-multiagent-project on your profile."
