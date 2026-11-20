package procomp.shell.ports;

import java.time.Instant;

/**
 * Time source port for deterministic testing and uptime calculation.
 */
public interface Clock {

    Instant now();
}
