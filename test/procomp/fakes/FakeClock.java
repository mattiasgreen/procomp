package procomp.fakes;

import procomp.shell.ports.Clock;

import java.time.Duration;
import java.time.Instant;

public final class FakeClock implements Clock {

    private Instant current;

    public FakeClock() {
        this(Instant.parse("2026-01-01T00:00:00Z"));
    }

    public FakeClock(Instant initial) {
        this.current = initial;
    }

    public void setNow(Instant instant) {
        this.current = instant;
    }

    public void advance(Duration duration) {
        this.current = this.current.plus(duration);
    }

    @Override
    public Instant now() {
        return current;
    }
}
