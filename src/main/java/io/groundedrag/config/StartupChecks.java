package io.groundedrag.config;

import io.groundedrag.retrieval.Embedder;
import io.groundedrag.store.KnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Recovers from crashes mid-ingestion and warns when the index was built with another embedding model. */
@Component
class StartupChecks {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    private final KnowledgeStore store;
    private final Embedder embedder;

    StartupChecks(KnowledgeStore store, Embedder embedder) {
        this.store = store;
        this.embedder = embedder;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        int failed = store.failInterruptedIngestions();
        if (failed > 0) {
            log.warn("Marked {} ingestion(s) interrupted by the last shutdown as FAILED; re-upload to retry", failed);
        }
        long stale = store.countChunksWithOtherModel(embedder.modelName());
        if (stale > 0) {
            log.warn("{} active chunks were embedded with a different model than '{}'. Their vectors are not "
                    + "comparable with new queries: re-ingest those documents.", stale, embedder.modelName());
        }
    }
}
