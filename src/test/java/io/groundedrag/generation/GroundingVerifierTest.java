package io.groundedrag.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.retrieval.ContextSource;
import org.junit.jupiter.api.Test;

class GroundingVerifierTest {

    private final GroundingVerifier verifier = new GroundingVerifier();

    private static ContextSource source(int n, String text) {
        RetrievedChunk c = new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(), null, SpaceId.of("s"), text,
                "f.md", null, null, 0.5, 0, 0, 0.5);
        return new ContextSource(n, c, text, false);
    }

    private final List<ContextSource> sources = List.of(
            source(1, "The Team plan costs 99 USD per month and storage overage is 0.08 USD per GB."),
            source(2, "Enterprise customers commit to 1,250 seats minimum."));

    @Test
    void groundedAnswerPasses() {
        Verification v = verifier.verify("Team costs 99 USD per month [1]. Overage is 0.08 USD per GB [1].", sources);
        assertThat(v.grounded()).isTrue();
        assertThat(v.citedSources()).containsExactly(1);
    }

    @Test
    void flagsCitationsToSourcesThatDoNotExist() {
        Verification v = verifier.verify("Team costs 99 USD [3].", sources);
        assertThat(v.invalidCitations()).containsExactly(3);
        assertThat(v.grounded()).isFalse();
    }

    @Test
    void flagsNumbersThatAppearInNoSource() {
        Verification v = verifier.verify("Team costs 89 USD per month [1].", sources);
        assertThat(v.unsupportedNumbers()).containsExactly("89");
        assertThat(v.grounded()).isFalse();
    }

    @Test
    void numberFormattingDifferencesAreNotFalsePositives() {
        assertThat(verifier.verify("The minimum is 1250 seats [2].", sources).grounded()).isTrue();
        assertThat(verifier.verify("It costs 99.00 USD [1].", sources).grounded()).isTrue();
    }

    @Test
    void answerWithoutAnyCitationIsNotGrounded() {
        assertThat(verifier.verify("The Team plan is affordable.", sources).grounded()).isFalse();
    }

    @Test
    void refusalIsGroundedEvenWithoutCitations() {
        Verification v = verifier.verify("I don't know based on the provided documents.", sources);
        assertThat(v.refusal()).isTrue();
        assertThat(v.grounded()).isTrue();
    }
}
