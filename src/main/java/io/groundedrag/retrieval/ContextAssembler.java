package io.groundedrag.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.store.KnowledgeStore;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * Builds the numbered evidence list under a hard token budget, in rank order.
 *
 * <p>For each hit, the parent passage is used if it still fits the budget (more context per
 * citation); otherwise the child itself. Children whose parent is already included are skipped,
 * so the same paragraph is never sent twice.
 */
public class ContextAssembler {

    private final KnowledgeStore store;
    private final TokenCountEstimator tokens;
    private final int maxTokens;
    private final boolean parentExpansion;

    public ContextAssembler(KnowledgeStore store, TokenCountEstimator tokens, int maxTokens, boolean parentExpansion) {
        this.store = store;
        this.tokens = tokens;
        this.maxTokens = maxTokens;
        this.parentExpansion = parentExpansion;
    }

    public List<ContextSource> assemble(List<RetrievedChunk> ranked) {
        Map<UUID, String> parents = parentExpansion
                ? store.parentTexts(ranked.stream().map(RetrievedChunk::parentId).filter(Objects::nonNull).toList())
                : Map.of();
        List<ContextSource> out = new ArrayList<>();
        Set<UUID> includedParents = new HashSet<>();
        int used = 0;
        for (RetrievedChunk c : ranked) {
            if (c.parentId() != null && includedParents.contains(c.parentId())) {
                continue;
            }
            String parent = parents.get(c.parentId());
            if (parent != null) {
                int cost = tokens.estimate(parent);
                if (used + cost <= maxTokens) {
                    out.add(new ContextSource(out.size() + 1, c, parent, true));
                    includedParents.add(c.parentId());
                    used += cost;
                    continue;
                }
            }
            int cost = tokens.estimate(c.text());
            if (used + cost > maxTokens) {
                continue;
            }
            out.add(new ContextSource(out.size() + 1, c, c.text(), false));
            used += cost;
        }
        return out;
    }
}
