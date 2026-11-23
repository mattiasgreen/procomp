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
    private volatile boolean wallClockSleep;

    public FakeClock() {
        this(DEFAULT_EPOCH);
    }

    public FakeClock(Instant initial) {
        this.current = Objects.requireNonNull(initial, "initial must not be null");
    }

    public void setWallClockSleep(boolean enabled) {
        this.wallClockSleep = enabled;
    }

    @Override
    public synchronized Instant now() {
        return current;
    }

    @Override
    public boolean sleep(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        if (wallClockSleep && !duration.isNegative() && !duration.isZero()) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        synchronized (this) {
            if (!duration.isNegative()) {
                current = current.plus(duration);
            }
        }
        return true;
    }

    public synchronized void advanceSeconds(long seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("seconds must not be negative: " + seconds);
        }
        current = current.plusSeconds(seconds);
    }

    public synchronized void advanceMillis(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("millis must not be negative: " + millis);
        }
        current = current.plusMillis(millis);
    }

    public synchronized void reset() {
        this.current = DEFAULT_EPOCH;
    }
}
