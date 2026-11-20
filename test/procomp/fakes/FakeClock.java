package procomp.fakes;

import procomp.shell.ports.Clock;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntPredicate;

public final class FakeClock implements Clock {

    private Instant current;
    private int sleepCount;
    private IntPredicate sleepHandler = _ -> true;

    public FakeClock() {
        this(Instant.parse("2026-01-01T00:00:00Z"));
    }

    public FakeClock(Instant initial) {
        this.current = Objects.requireNonNull(initial, "initial must not be null");
    }

    public synchronized void setNow(Instant instant) {
        this.current = Objects.requireNonNull(instant, "instant must not be null");
    }

    public synchronized void advance(Duration duration) {
        this.current = this.current.plus(duration);
    }

    public synchronized FakeClock onSleep(IntPredicate sleepHandler) {
        this.sleepHandler = Objects.requireNonNull(sleepHandler, "sleepHandler must not be null");
        return this;
    }

    public synchronized int sleepCount() {
        return sleepCount;
    }

    @Override
    public synchronized Instant now() {
        return current;
    }

    @Override
    public boolean sleep(Duration duration) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        int step;
        IntPredicate handler;
        synchronized (this) {
            sleepCount++;
            step = sleepCount;
            if (duration != null && !duration.isNegative()) {
                this.current = this.current.plus(duration);
            }
            handler = this.sleepHandler;
        }
        return handler.test(step);
    }
}
