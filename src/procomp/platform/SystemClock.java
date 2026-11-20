package procomp.platform;

import procomp.shell.ports.Clock;

import java.time.Duration;
import java.time.Instant;

/**
 * System UTC clock adapter for {@link Clock}.
 */
public final class SystemClock implements Clock {

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public boolean sleep(Duration duration) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return true;
        }
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
