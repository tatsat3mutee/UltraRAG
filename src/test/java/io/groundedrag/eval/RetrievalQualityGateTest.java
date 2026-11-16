package io.groundedrag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.groundedrag.Fixtures;
import io.groundedrag.retrieval.RetrievalMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * CI quality gate. Ingests eval/corpus, runs the golden set and fails the build if retrieval or
 * end-to-end behaviour regresses below the recorded baseline. Uses the offline embedding and
 * extractive generator so it is deterministic and free; the same Evaluator runs against real
 * models with --rag.eval.run=true.
 *
 * <p>Baselines are set slightly under the measured values (docs/EVALUATION.md). A change that
 * drops them must either be fixed or come with a written reason to lower the bar.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalQualityGateTest {

    private final Fixtures f = new Fixtures();
    private Evaluator evaluator;
    private List<EvalCase> cases;

    @BeforeAll
    void ingest() {
        evaluator = new Evaluator(f.store, f.ingestion, f.embedder, f.retriever);
        assertThat(evaluator.ingestCorpus(Path.of("eval/corpus"))).isEqualTo(8);
        cases = Evaluator.loadGoldenSet(Path.of("eval/golden-set.json"));
    }

    @Test
    void retrievalAndAnswersMeetBaseline() throws Exception {
        var hybrid = evaluator.retrieval(cases, RetrievalMode.HYBRID, 6);
        var vector = evaluator.retrieval(cases, RetrievalMode.VECTOR, 6);
        var keyword = evaluator.retrieval(cases, RetrievalMode.KEYWORD, 6);
        var answers = evaluator.answers(cases, f.rag());

        String report = Evaluator.markdown(List.of(hybrid, vector, keyword), answers,
                "Setup: offline CI run, embedding=hashing-v1, reranker=none, generator=extractive, k=6");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/eval-report.md"), report);
        System.out.println(report);

        assertThat(hybrid.recallAtK()).as("hybrid recall@6").isGreaterThanOrEqualTo(BASELINE_RECALL);
        assertThat(hybrid.mrr()).as("hybrid MRR").isGreaterThanOrEqualTo(BASELINE_MRR);
        assertThat(answers.abstentionAccuracy()).as("unanswerable questions declined").isGreaterThanOrEqualTo(1.0);
        assertThat(answers.falseAbstentionRate()).as("answerable questions declined").isLessThanOrEqualTo(BASELINE_FALSE_ABSTAIN);
        assertThat(answers.groundedRate()).as("grounded answers").isGreaterThanOrEqualTo(BASELINE_GROUNDED);
        assertThat(answers.factAccuracy()).as("answers containing expected fact").isGreaterThanOrEqualTo(BASELINE_FACTS);
    }

    // Measured 2026-11: recall 1.000, MRR 0.890, false abstentions 0.088 (3 paraphrases),
    // grounded 1.000, facts 0.824. See docs/EVALUATION.md.
    static final double BASELINE_RECALL = 0.97;
    static final double BASELINE_MRR = 0.85;
    static final double BASELINE_FALSE_ABSTAIN = 0.12;
    static final double BASELINE_GROUNDED = 0.95;
    static final double BASELINE_FACTS = 0.75;
}
