package procomp.shell.ports;

import java.time.Duration;
import java.time.Instant;

/**
 * Time source port for deterministic testing, sleep intervals, and uptime calculation.
 */
public interface Clock {

    Instant now();

    /**
     * Sleeps for {@code duration}, returning {@code false} if interrupted or cancelled.
     */
    boolean sleep(Duration duration);
}
