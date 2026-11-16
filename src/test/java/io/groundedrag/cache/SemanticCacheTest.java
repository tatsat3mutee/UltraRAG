package io.groundedrag.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import io.groundedrag.domain.SpaceId;
import io.groundedrag.retrieval.HashingEmbeddingModel;
import org.junit.jupiter.api.Test;

class SemanticCacheTest {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel(384);

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final SemanticCache<String> cache = new SemanticCache<>(0.9, Duration.ofMinutes(10), 3, clock);

    private float[] e(String s) {
        return model.embed(s);
    }

    @Test
    void hitsOnSameQuestionWithDifferentWording() {
        String scope = SemanticCache.scope(Map.of(SpaceId.of("a"), 1L));
        cache.put(scope, "What is the refund window?", e("What is the refund window?"), "30 days");
        assertThat(cache.get(scope, "what's the refund window", e("what's the refund window"))).contains("30 days");
    }

    @Test
    void corpusVersionBumpInvalidatesWithoutExplicitPurge() {
        String v1 = SemanticCache.scope(Map.of(SpaceId.of("a"), 1L));
        String v2 = SemanticCache.scope(Map.of(SpaceId.of("a"), 2L));
        cache.put(v1, "refund window", e("refund window"), "30 days");
        assertThat(cache.get(v2, "refund window", e("refund window"))).isEmpty();
    }

    @Test
    void scopesAreIsolatedBetweenSpaceSets() {
        String a = SemanticCache.scope(Map.of(SpaceId.of("a"), 1L));
        String ab = SemanticCache.scope(Map.of(SpaceId.of("a"), 1L, SpaceId.of("b"), 1L));
        cache.put(a, "refund window", e("refund window"), "30 days");
        assertThat(cache.get(ab, "refund window", e("refund window"))).isEmpty();
    }

    @Test
    void expiresAfterTtlAndEvictsOldestBeyondCapacity() {
        String s = SemanticCache.scope(Map.of(SpaceId.of("a"), 1L));
        cache.put(s, "q1", e("alpha"), "1");
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        assertThat(cache.get(s, "q1", e("alpha"))).isEmpty();

        cache.put(s, "q2", e("q2"), "2");
        cache.put(s, "q3", e("q3"), "3");
        cache.put(s, "q4", e("q4"), "4");
        cache.put(s, "q5", e("q5"), "5");
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.get(s, "q2", e("q2"))).isEmpty();
    }

    @Test
    void unrelatedQuestionMisses() {
        String s = SemanticCache.scope(Map.of(SpaceId.of("a"), 1L));
        cache.put(s, "refund window", e("refund window"), "30 days");
        assertThat(cache.get(s, "api rate limit", e("api rate limit"))).isEmpty();
    }
}
