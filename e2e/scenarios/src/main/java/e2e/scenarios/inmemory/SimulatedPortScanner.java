package e2e.scenarios.inmemory;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.process.PortBinding;
import procomp.shell.ports.PortScanner;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Stateful in-memory implementation of {@link PortScanner} backed by {@link VirtualOsState}.
 */
public final class SimulatedPortScanner implements PortScanner {

    private final VirtualOsState os;

    SimulatedPortScanner(VirtualOsState os) {
        this.os = Objects.requireNonNull(os, "os must not be null");
    }

    @Override
    public Result<Optional<PortBinding>, DomainError> findListener(int port) {
        Optional<PortBinding> binding = os.aliveListenerOnPort(port)
            .map(proc -> new PortBinding(port, Optional.of(proc.pid), "127.0.0.1"));
        return Result.ok(binding);
    }

    @Override
    public boolean isTcpListening(int port, Duration timeout) {
        return os.aliveListenerOnPort(port).isPresent();
    }
}
