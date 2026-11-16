package io.groundedrag.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A knowledge space (tenant partition). Every document and chunk belongs to exactly one space.
 *
 * <p>Normalisation lives here and only here, so ingest and query can never disagree about
 * casing or whitespace (a real production bug: upper-cased at query time, stored as typed at
 * ingest time, documents silently invisible).
 */
public record SpaceId(@JsonValue String value) implements Comparable<SpaceId> {

    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");

    public SpaceId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid space id: " + value);
        }
    }

    public static SpaceId of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Space id is required");
        }
        return new SpaceId(raw.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public int compareTo(SpaceId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
