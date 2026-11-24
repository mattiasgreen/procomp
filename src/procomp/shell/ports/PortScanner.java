package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.process.PortBinding;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Local TCP port listener discovery and connect-check port.
 */
public interface PortScanner {

    Result<Optional<PortBinding>, DomainError> findListener(int port);

    /**
     * Batch-discovers listeners for all requested ports in a single OS snapshot where supported.
     */
    default Result<Map<Integer, PortBinding>, DomainError> findListeners(Set<Integer> ports) {
        Objects.requireNonNull(ports, "ports must not be null");
        Map<Integer, PortBinding> found = new LinkedHashMap<>();
        for (int port : ports) {
            Result<Optional<PortBinding>, DomainError> res = findListener(port);
            if (res.isErr()) {
                return res.castError();
            }
            ((Result.Ok<Optional<PortBinding>, DomainError>) res).value().ifPresent(b -> found.put(port, b));
        }
        return Result.ok(Collections.unmodifiableMap(found));
    }

    boolean isTcpListening(int port, Duration timeout);
}
