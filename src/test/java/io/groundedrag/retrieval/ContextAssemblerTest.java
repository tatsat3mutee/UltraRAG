package io.groundedrag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.groundedrag.Fixtures;
import org.junit.jupiter.api.Test;

class ContextAssemblerTest {

    @Test
    void expandsToParentOnceAndRespectsBudget() {
        Fixtures f = new Fixtures();
        StringBuilder doc = new StringBuilder("# Billing\n");
        for (int i = 0; i < 30; i++) {
            doc.append("Invoice rule ").append(i).append(" says overage is billed monthly on the next invoice. ");
        }
        f.ingest("p", "billing.md", doc.toString());
        String q = "overage billed next invoice";
        var r = f.retriever.retrieve(q, f.embedder.embed(q), Fixtures.spaces("p"));

        var generous = new ContextAssembler(f.store, Fixtures.TOKENS, 5000, true).assemble(r.chunks());
        assertThat(generous).allMatch(ContextSource::expandedToParent);
        assertThat(generous.stream().map(s -> s.chunk().parentId()).distinct().count()).isEqualTo(generous.size());

        var tight = new ContextAssembler(f.store, Fixtures.TOKENS, 200, true).assemble(r.chunks());
        int used = tight.stream().mapToInt(s -> Fixtures.TOKENS.estimate(s.text())).sum();
        assertThat(used).isLessThanOrEqualTo(200);
        assertThat(tight).isNotEmpty().noneMatch(ContextSource::expandedToParent);
        assertThat(tight).extracting(ContextSource::number).startsWith(1);
    }
}
