package procomp.core.process;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure representation of a local TCP listener occupying a target port.
 */
public record PortBinding(int port, Optional<Long> pid, String localAddress) {
    public PortBinding {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }
        Objects.requireNonNull(pid, "pid must not be null");
        Objects.requireNonNull(localAddress, "localAddress must not be null");
    }
}
