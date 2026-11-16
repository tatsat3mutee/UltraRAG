package io.groundedrag.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.groundedrag.Fixtures;
import io.groundedrag.generation.AnswerGenerator;
import io.groundedrag.generation.ExtractiveAnswerGenerator;
import io.groundedrag.generation.HeuristicQueryRewriter;
import io.groundedrag.generation.LlmAnswerGenerator;
import io.groundedrag.generation.ScriptedChatModel;
import io.groundedrag.generation.Turn;
import io.groundedrag.retrieval.ConfidenceLevel;
import io.groundedrag.retrieval.ContextSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

class RagServiceTest {

    private Fixtures f;

    @BeforeEach
    void setUp() {
        f = new Fixtures();
        f.ingest("product", "refunds.md", """
                # Refund window
                New customers on an annual plan can request a full refund within 30 days of the first payment.
                Monthly plans are not eligible for refunds.
                """);
        f.ingest("product", "pricing.md", """
                # Plans
                The Team plan costs 99 USD per month. Annual billing gives a 20% discount.
                """);
    }

    private RagService.Question q(String text, String conversation) {
        return new RagService.Question("tester", Fixtures.spaces("product"), text, conversation);
    }

    /** Counts calls so tests can prove when the model is (not) invoked. */
    static class CountingGenerator implements AnswerGenerator {
        final AtomicInteger calls = new AtomicInteger();
        final ExtractiveAnswerGenerator delegate = new ExtractiveAnswerGenerator();

        @Override
        public String generate(String question, List<Turn> history, List<ContextSource> sources) {
            calls.incrementAndGet();
            return delegate.generate(question, history, sources);
        }

        @Override
        public Flux<String> stream(String question, List<Turn> history, List<ContextSource> sources) {
            calls.incrementAndGet();
            return delegate.stream(question, history, sources);
        }

        @Override
        public String name() {
            return "counting";
        }
    }

    @Test
    void answersWithVerifiedCitationsAndATrace() {
        ChatAnswer a = f.rag().ask(q("How long is the refund window for annual plans?", null));
        assertThat(a.answer()).contains("30 days").contains("[1]");
        assertThat(a.verification().grounded()).isTrue();
        assertThat(a.sources().get(0).sourceName()).isEqualTo("refunds.md");
        assertThat(a.sources().get(0).cited()).isTrue();
        assertThat(a.timingsMs()).containsKeys("rewrite", "embed", "retrieve", "generate", "verify");

        var trace = f.traces.find(a.answerId()).orElseThrow();
        assertThat(trace.chunkIds()).isNotEmpty();
        assertThat(trace.confidence()).isEqualTo(a.confidence().level().name());
        assertThat(trace.grounded()).isTrue();
    }

    @Test
    void declinesWithoutCallingTheModelWhenNothingRelevantExists() {
        CountingGenerator gen = new CountingGenerator();
        ChatAnswer a = f.rag(gen, new HeuristicQueryRewriter()).ask(q("Which pizza toppings are at the party?", null));
        assertThat(a.confidence().level()).isEqualTo(ConfidenceLevel.NONE);
        assertThat(a.sources()).isEmpty();
        assertThat(a.answer()).contains("couldn't find");
        assertThat(gen.calls).hasValue(0);
        assertThat(a.timingsMs()).containsKey("corrective_retry");
    }

    @Test
    void secondIdenticalQuestionIsServedFromCacheUntilTheCorpusChanges() {
        CountingGenerator gen = new CountingGenerator();
        RagService rag = f.rag(gen, new HeuristicQueryRewriter());
        String question = "How much does the Team plan cost per month?";

        ChatAnswer first = rag.ask(q(question, null));
        ChatAnswer second = rag.ask(q(question, null));
        assertThat(second.cached()).isTrue();
        assertThat(second.answerId()).isNotEqualTo(first.answerId());
        assertThat(gen.calls).hasValue(1);

        f.ingest("product", "pricing.md", "# Plans\nThe Team plan costs 119 USD per month.");
        ChatAnswer third = rag.ask(q(question, null));
        assertThat(third.cached()).isFalse();
        assertThat(third.answer()).contains("119");
        assertThat(gen.calls).hasValue(2);
    }

    @Test
    void followUpsAreRewrittenUsingConversationHistory() {
        var model = new ScriptedChatModel(p -> {
            String user = ScriptedChatModel.user(p);
            if (user.startsWith("Conversation:")) {
                return "Are monthly plans eligible for refunds?";
            }
            return "Monthly plans are not eligible for refunds [1].";
        });
        var rewriter = new io.groundedrag.generation.LlmQueryRewriter(ChatClient.create(model));
        RagService rag = f.rag(new LlmAnswerGenerator(ChatClient.create(model)), rewriter);

        ChatAnswer first = rag.ask(q("What is the refund window for annual plans?", "c1"));
        ChatAnswer followUp = rag.ask(q("what about monthly ones?", first.conversationId()));

        assertThat(followUp.standaloneQuestion()).isEqualTo("Are monthly plans eligible for refunds?");
        assertThat(followUp.sources()).anyMatch(s -> s.sourceName().equals("refunds.md"));
        // A different principal with the same conversation id gets no history.
        ChatAnswer other = rag.ask(new RagService.Question("someone-else", Fixtures.spaces("product"),
                "what about monthly ones?", first.conversationId()));
        assertThat(other.standaloneQuestion()).isEqualTo("what about monthly ones?");
    }

    @Test
    void unverifiableAnswerIsFlaggedAndNotCached() {
        var model = new ScriptedChatModel(p -> "The Team plan costs 89 USD per month [1].");
        RagService rag = f.rag(new LlmAnswerGenerator(ChatClient.create(model)), new HeuristicQueryRewriter());
        ChatAnswer a = rag.ask(q("Team plan monthly price", null));
        assertThat(a.verification().grounded()).isFalse();
        assertThat(a.verification().unsupportedNumbers()).containsExactly("89");
        assertThat(a.notice()).contains("could not be fully verified").contains("89");
        assertThat(rag.ask(q("Team plan monthly price", null)).cached()).isFalse();
    }

    @Test
    void streamingSendsSourcesBeforeTokensAndAVerifiedFinalAnswer() {
        List<String> events = new ArrayList<>();
        StringBuilder tokens = new StringBuilder();
        f.rag().stream(q("How long is the refund window for annual plans?", null), new RagService.StreamListener() {
            @Override
            public void onMeta(ChatAnswer partial) {
                events.add("meta:" + partial.sources().size());
            }

            @Override
            public void onToken(String token) {
                if (events.get(events.size() - 1).startsWith("meta")) {
                    events.add("token");
                }
                tokens.append(token);
            }

            @Override
            public void onComplete(ChatAnswer complete) {
                events.add("done:" + complete.verification().grounded());
                assertThat(complete.answer()).isEqualTo(tokens.toString());
            }
        });
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).startsWith("meta:").isNotEqualTo("meta:0");
        assertThat(events).endsWith("done:true");
    }
}
