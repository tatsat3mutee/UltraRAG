package io.groundedrag.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.groundedrag.domain.SpaceId;
import io.groundedrag.domain.TextAnalyzer;

/**
 * Answer cache keyed on (spaces, corpus version of each space, standalone-question embedding).
 *
 * <p>The corpus version is the invalidation strategy: every activation or deletion bumps the
 * space's version, so entries computed against old content simply stop matching. No explicit
 * purge, no race between "ingest finished" and "cache cleared". The key is the standalone
 * question, so "and for annual plans?" can never be served the answer to a different
 * conversation's "and for annual plans?".
 */
public class SemanticCache<V> {

    record Entry<V>(String scope, String normalisedQuestion, float[] embedding, V value, Instant createdAt) {
    }

    private final double similarity;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final Deque<Entry<V>> entries = new ArrayDeque<>();

    public SemanticCache(double similarity, Duration ttl, int maxEntries, Clock clock) {
        this.similarity = similarity;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    public static String scope(Map<SpaceId, Long> corpusVersions) {
        return corpusVersions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey().value() + "@" + e.getValue())
                .collect(Collectors.joining(","));
    }

    public synchronized Optional<V> get(String scope, String question, float[] embedding) {
        Instant cutoff = clock.instant().minus(ttl);
        String normalised = normalise(question);
        Entry<V> best = null;
        double bestScore = similarity;
        for (Iterator<Entry<V>> it = entries.iterator(); it.hasNext(); ) {
            Entry<V> e = it.next();
            if (e.createdAt().isBefore(cutoff)) {
                it.remove();
                continue;
            }
            if (!e.scope().equals(scope)) {
                continue;
            }
            if (e.normalisedQuestion().equals(normalised)) {
                return Optional.of(e.value());
            }
            double s = TextAnalyzer.cosine(e.embedding(), embedding);
            if (s >= bestScore) {
                best = e;
                bestScore = s;
            }
        }
        return Optional.ofNullable(best).map(Entry::value);
    }

    public synchronized void put(String scope, String question, float[] embedding, V value) {
        entries.addFirst(new Entry<>(scope, normalise(question), embedding, value, clock.instant()));
        while (entries.size() > maxEntries) {
            entries.removeLast();
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    private static String normalise(String q) {
        return String.join(" ", TextAnalyzer.terms(q));
    }

    /** Exposed for tests. */
    Set<String> scopes() {
        return entries.stream().map(Entry::scope).collect(Collectors.toSet());
    }
}
