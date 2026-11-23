package e2e.scenarios.engine;

import java.time.Duration;

/**
 * Configurable simulated latency profile for {@link ScenarioWorld} operations.
 */
public enum LagProfile {
    NONE(0),
    FAST(150),
    REALISTIC(1200);

    private final long delayMillis;

    LagProfile(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    public long delayMillis() {
        return delayMillis;
    }

    public Duration delayDuration() {
        return Duration.ofMillis(delayMillis);
    }
}
