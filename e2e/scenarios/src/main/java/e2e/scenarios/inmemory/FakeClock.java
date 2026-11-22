package e2e.scenarios.inmemory;

import procomp.shell.ports.Clock;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic virtual clock for {@link InMemoryWorld}.
 */
public final class FakeClock implements Clock {

    public static final Instant DEFAULT_EPOCH = Instant.parse("2026-01-01T10:00:00Z");

    private Instant current;

    public FakeClock() {
        this(DEFAULT_EPOCH);
    }

    public FakeClock(Instant initial) {
        this.current = Objects.requireNonNull(initial, "initial must not be null");
    }

    @Override
    public Instant now() {
        return current;
    }

    @Override
    public boolean sleep(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (!duration.isNegative()) {
            current = current.plus(duration);
        }
        return true;
    }

    public void advanceSeconds(long seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("seconds must not be negative: " + seconds);
        }
        current = current.plusSeconds(seconds);
    }

    public void advanceMillis(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("millis must not be negative: " + millis);
        }
        current = current.plusMillis(millis);
    }

    public void reset() {
        this.current = DEFAULT_EPOCH;
    }
}
