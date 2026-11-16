package io.groundedrag.ingestion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes common personal data before anything is embedded, stored or sent to a model provider.
 * Redacting at ingest (not at answer time) means PII never reaches the index, the cache or the LLM.
 *
 * <p>Card numbers are only redacted when they pass the Luhn check, so order numbers and other
 * long digit runs survive.
 */
public class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    /** International (+CC ...) or North-American (xxx) xxx-xxxx forms; bare digit groups are left alone. */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w+])(?:\\+\\d{1,3}[ .-]?\\(?\\d{1,4}\\)?[ .-]?\\d{3,4}[ .-]?\\d{3,4}"
                    + "|\\(?\\d{3}\\)?[ .-]\\d{3}[ .-]\\d{4})(?!\\d)");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?\\b");

    private final boolean enabled;

    public PiiRedactor(boolean enabled) {
        this.enabled = enabled;
    }

    public String redact(String text) {
        if (!enabled || text == null) {
            return text;
        }
        String out = EMAIL.matcher(text).replaceAll("[REDACTED_EMAIL]");
        out = IBAN.matcher(out).replaceAll("[REDACTED_IBAN]");
        out = SSN.matcher(out).replaceAll("[REDACTED_SSN]");
        Matcher cards = CARD.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (cards.find()) {
            String digits = cards.group().replaceAll("[ -]", "");
            cards.appendReplacement(sb, luhn(digits) ? "[REDACTED_CARD]" : Matcher.quoteReplacement(cards.group()));
        }
        cards.appendTail(sb);
        return PHONE.matcher(sb.toString()).replaceAll("[REDACTED_PHONE]");
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return digits.length() >= 13 && sum % 10 == 0;
    }
}
