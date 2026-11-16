package io.groundedrag.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import io.groundedrag.cache.SemanticCache;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.generation.AnswerGenerator;
import io.groundedrag.generation.GroundingVerifier;
import io.groundedrag.generation.QueryRewriter;
import io.groundedrag.generation.Turn;
import io.groundedrag.generation.Verification;
import io.groundedrag.observability.AnswerTrace;
import io.groundedrag.observability.TraceStore;
import io.groundedrag.retrieval.ConfidenceLevel;
import io.groundedrag.retrieval.ContextAssembler;
import io.groundedrag.retrieval.ContextSource;
import io.groundedrag.retrieval.Embedder;
import io.groundedrag.retrieval.HybridRetriever;
import io.groundedrag.retrieval.Reranker;
import io.groundedrag.retrieval.RetrievalResult;
import io.groundedrag.store.KnowledgeStore;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The query pipeline. Each step is a separate, replaceable component; this class only decides
 * the order and the policy between them:
 *
 * <pre>
 * history -> standalone question -> embed -> cache? -> hybrid retrieve -> (weak? reformulate, retry once)
 *   -> NONE: decline without calling the LLM
 *   -> assemble numbered context under a token budget -> generate -> verify citations & numbers
 *   -> cache only grounded, confident answers -> remember turn -> persist trace
 * </pre>
 */
public class RagService {

    private final KnowledgeStore store;
    private final Embedder embedder;
    private final HybridRetriever retriever;
    private final Reranker reranker;
    private final ContextAssembler assembler;
    private final QueryRewriter rewriter;
    private final AnswerGenerator generator;
    private final GroundingVerifier verifier;
    private final SemanticCache<ChatAnswer> cache;
    private final boolean cacheEnabled;
    private final boolean correctiveRetry;
    private final ConversationMemory memory;
    private final TraceStore traces;
    private final MeterRegistry meters;

    public RagService(KnowledgeStore store, Embedder embedder, HybridRetriever retriever, Reranker reranker,
            ContextAssembler assembler, QueryRewriter rewriter, AnswerGenerator generator, GroundingVerifier verifier,
            SemanticCache<ChatAnswer> cache, boolean cacheEnabled, boolean correctiveRetry, ConversationMemory memory,
            TraceStore traces, MeterRegistry meters) {
        this.store = store;
        this.embedder = embedder;
        this.retriever = retriever;
        this.reranker = reranker;
        this.assembler = assembler;
        this.rewriter = rewriter;
        this.generator = generator;
        this.verifier = verifier;
        this.cache = cache;
        this.cacheEnabled = cacheEnabled;
        this.correctiveRetry = correctiveRetry;
        this.memory = memory;
        this.traces = traces;
        this.meters = meters;
    }

    public record Question(String principal, Set<SpaceId> spaces, String text, String conversationId) {
    }

    /** Callbacks for the streaming endpoint. */
    public interface StreamListener {
        void onMeta(ChatAnswer partial);

        void onToken(String token);

        void onComplete(ChatAnswer complete);
    }

    /** Everything decided before generation starts; shared by the blocking and streaming paths. */
    private record Plan(UUID answerId, String conversationId, List<Turn> history, String standalone,
            float[] embedding, String cacheScope, Optional<ChatAnswer> cached, RetrievalResult retrieval,
            List<ContextSource> sources, Map<String, Long> timings) {
    }

    public ChatAnswer ask(Question q) {
        Plan plan = plan(q);
        if (plan.cached().isPresent()) {
            return finishCached(q, plan);
        }
        if (plan.sources().isEmpty()) {
            return finish(q, plan, decline());
        }
        String answer = timed(plan.timings(), "generate",
                () -> generator.generate(plan.standalone(), plan.history(), plan.sources()));
        return finish(q, plan, answer);
    }

    public void stream(Question q, StreamListener listener) {
        Plan plan = plan(q);
        if (plan.cached().isPresent()) {
            ChatAnswer cached = finishCached(q, plan);
            listener.onMeta(cached);
            listener.onToken(cached.answer());
            listener.onComplete(cached);
            return;
        }
        listener.onMeta(draft(plan));
        if (plan.sources().isEmpty()) {
            String declined = decline();
            listener.onToken(declined);
            listener.onComplete(finish(q, plan, declined));
            return;
        }
        StringBuilder answer = new StringBuilder();
        long start = System.nanoTime();
        for (String token : generator.stream(plan.standalone(), plan.history(), plan.sources()).toIterable()) {
            answer.append(token);
            listener.onToken(token);
        }
        record(plan.timings(), "generate", start);
        listener.onComplete(finish(q, plan, answer.toString()));
    }

    private Plan plan(Question q) {
        Map<String, Long> timings = new LinkedHashMap<>();
        String conversationId = q.conversationId() == null || q.conversationId().isBlank()
                ? UUID.randomUUID().toString() : q.conversationId();
        List<Turn> history = memory.history(q.principal(), conversationId);

        String standalone = timed(timings, "rewrite", () -> rewriter.standalone(history, q.text()));
        float[] embedding = timed(timings, "embed", () -> embedder.embed(standalone));
        String scope = SemanticCache.scope(store.corpusVersions(q.spaces()));

        if (cacheEnabled) {
            Optional<ChatAnswer> hit = timed(timings, "cache", () -> cache.get(scope, standalone, embedding));
            if (hit.isPresent()) {
                return new Plan(UUID.randomUUID(), conversationId, history, standalone, embedding, scope, hit,
                        null, List.of(), timings);
            }
        }

        RetrievalResult retrieval = timed(timings, "retrieve",
                () -> retriever.retrieve(standalone, embedding, q.spaces()));
        if (correctiveRetry && !retrieval.confidence().atLeast(ConfidenceLevel.MEDIUM)) {
            RetrievalResult first = retrieval;
            RetrievalResult retry = timed(timings, "corrective_retry", () -> {
                String alt = rewriter.reformulate(standalone);
                return alt.equalsIgnoreCase(standalone) ? null
                        : retriever.retrieve(alt, embedder.embed(alt), q.spaces());
            });
            if (retry != null && retry.confidenceScore() > first.confidenceScore()) {
                retrieval = retry;
            }
        }

        RetrievalResult finalRetrieval = retrieval;
        List<ContextSource> sources = retrieval.confidence() == ConfidenceLevel.NONE ? List.of()
                : timed(timings, "assemble", () -> assembler.assemble(finalRetrieval.chunks()));
        return new Plan(UUID.randomUUID(), conversationId, history, standalone, embedding, scope, Optional.empty(),
                retrieval, sources, timings);
    }

    private ChatAnswer finish(Question q, Plan plan, String answer) {
        Verification verification = timed(plan.timings(), "verify", () -> verifier.verify(answer, plan.sources()));
        ConfidenceLevel level = plan.retrieval().confidence();
        String notice = null;
        if (!plan.sources().isEmpty() && level == ConfidenceLevel.LOW) {
            notice = "Weak match: check the cited sources before relying on this answer.";
        }
        if (!verification.grounded()) {
            notice = "This answer could not be fully verified against the sources"
                    + (verification.unsupportedNumbers().isEmpty() ? "."
                    : " (figures not found in sources: " + String.join(", ", verification.unsupportedNumbers()) + ").");
        }
        ChatAnswer result = new ChatAnswer(plan.answerId(), plan.conversationId(), answer, notice, plan.standalone(),
                new ChatAnswer.Confidence(level, round(plan.retrieval().confidenceScore())),
                toSources(plan.sources(), verification), verification, false, plan.timings());

        if (cacheEnabled && verification.grounded() && !verification.refusal() && level.atLeast(ConfidenceLevel.MEDIUM)) {
            cache.put(plan.cacheScope(), plan.standalone(), plan.embedding(), result);
        }
        memory.append(q.principal(), plan.conversationId(), new Turn(q.text(), answer));
        persist(q, plan, result);
        return result;
    }

    private ChatAnswer finishCached(Question q, Plan plan) {
        ChatAnswer replay = plan.cached().orElseThrow()
                .forReplay(plan.answerId(), plan.conversationId(), plan.timings());
        memory.append(q.principal(), plan.conversationId(), new Turn(q.text(), replay.answer()));
        persist(q, plan, replay);
        return replay;
    }

    private ChatAnswer draft(Plan plan) {
        return new ChatAnswer(plan.answerId(), plan.conversationId(), "", null, plan.standalone(),
                new ChatAnswer.Confidence(plan.retrieval().confidence(), round(plan.retrieval().confidenceScore())),
                toSources(plan.sources(), null), null, false, plan.timings());
    }

    private void persist(Question q, Plan plan, ChatAnswer a) {
        List<ChatAnswer.Source> src = a.sources();
        traces.save(new AnswerTrace(a.answerId(), q.principal(),
                q.spaces().stream().map(SpaceId::value).sorted().toList(), q.text(), a.standaloneQuestion(),
                src.stream().map(ChatAnswer.Source::chunkId).toList(),
                src.stream().map(ChatAnswer.Source::sourceName).distinct().toList(),
                a.confidence().level().name(), a.confidence().score(),
                a.verification() != null && a.verification().grounded(),
                a.verification() == null ? List.of() : a.verification().unsupportedNumbers(),
                a.cached(), generator.name(), reranker.name(), a.timingsMs(), Instant.now(), null, null));
        meters.counter("rag.answers", "confidence", a.confidence().level().name(),
                "grounded", String.valueOf(a.verification() != null && a.verification().grounded()),
                "cached", String.valueOf(a.cached())).increment();
    }

    private static List<ChatAnswer.Source> toSources(List<ContextSource> sources, Verification v) {
        List<ChatAnswer.Source> out = new ArrayList<>();
        for (ContextSource s : sources) {
            var c = s.chunk();
            out.add(new ChatAnswer.Source(s.number(), c.id(), c.space().value(), c.sourceName(), c.page(),
                    c.section(), round(c.rerankScore()), excerpt(c.text()),
                    v != null && v.citedSources().contains(s.number())));
        }
        return out;
    }

    private static String decline() {
        return "I couldn't find anything in your documents that answers this. "
                + "Try rephrasing with the terms your documents use, or upload the document that covers it.";
    }

    private static String excerpt(String text) {
        return text.length() <= 300 ? text : text.substring(0, 297) + "...";
    }

    private static double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    private <T> T timed(Map<String, Long> timings, String stage, Supplier<T> work) {
        long start = System.nanoTime();
        try {
            return work.get();
        } finally {
            record(timings, stage, start);
        }
    }

    private void record(Map<String, Long> timings, String stage, long startNanos) {
        long nanos = System.nanoTime() - startNanos;
        timings.merge(stage, nanos / 1_000_000, Long::sum);
        meters.timer("rag.stage", "stage", stage).record(java.time.Duration.ofNanos(nanos));
    }

}
