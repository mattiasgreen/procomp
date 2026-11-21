package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.process.PortBinding;

import java.time.Duration;
import java.util.Optional;

/**
 * Local TCP port listener discovery and connect-check port.
 */
public interface PortScanner {

    Result<Optional<PortBinding>, DomainError> findListener(int port);

    boolean isTcpListening(int port, Duration timeout);
}
