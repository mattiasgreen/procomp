package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Local TCP port listener discovery and connect-check port.
 */
public interface PortScanner {

    Result<Optional<PortListener>, DomainError> findListener(int port);

    boolean isTcpListening(int port, Duration timeout);

    record PortListener(int port, Optional<Long> pid, String localAddress) {
        public PortListener {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port out of range: " + port);
            }
            Objects.requireNonNull(pid);
            Objects.requireNonNull(localAddress);
        }
    }
}
