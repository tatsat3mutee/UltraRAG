package io.groundedrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor(true);

    @Test
    void redactsEmailsPhonesSsnAndValidCards() {
        String out = redactor.redact("Mail jane.doe@acme.io or call +1 415-555-0134. SSN 123-45-6789. "
                + "Card 4111 1111 1111 1111.");
        assertThat(out).contains("[REDACTED_EMAIL]", "[REDACTED_PHONE]", "[REDACTED_SSN]", "[REDACTED_CARD]")
                .doesNotContain("jane.doe", "4111", "6789");
    }

    @Test
    void keepsLongNumbersThatFailLuhnSoOrderIdsSurvive() {
        assertThat(redactor.redact("Order 1234 5678 9012 3456 shipped")).contains("1234 5678 9012 3456");
    }

    @Test
    void keepsPricesDatesAndVersions() {
        String text = "The plan costs 99.00 USD from 2026-01-01 on version 3.2.1.";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void canBeDisabled() {
        assertThat(new PiiRedactor(false).redact("a@b.co")).isEqualTo("a@b.co");
    }
}
