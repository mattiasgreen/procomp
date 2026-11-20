package procomp.platform;

import procomp.shell.ports.Clock;

import java.time.Instant;

/**
 * System UTC clock adapter for {@link Clock}.
 */
public final class SystemClock implements Clock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
