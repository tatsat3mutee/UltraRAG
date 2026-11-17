package io.groundedrag.security;

import java.util.function.LongSupplier;

/** Gives tests in other packages access to the clock-injecting constructor. */
public final class RateLimitFilterProbe {

    private RateLimitFilterProbe() {
    }

    public static RateLimitFilter create(int perMinute, int burst, LongSupplier clock) {
        return new RateLimitFilter(perMinute, burst, clock);
    }
}
