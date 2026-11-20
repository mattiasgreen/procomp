package procomp.fakes;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.PortScanner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class FakePortScanner implements PortScanner {

    private final Map<Integer, PortListener> listeners = new LinkedHashMap<>();

    public FakePortScanner bind(int port, long pid) {
        listeners.put(port, new PortListener(port, Optional.of(pid), "127.0.0.1:" + port));
        return this;
    }

    public FakePortScanner bindWithoutPid(int port) {
        listeners.put(port, new PortListener(port, Optional.empty(), "127.0.0.1:" + port));
        return this;
    }

    public FakePortScanner unbind(int port) {
        listeners.remove(port);
        return this;
    }

    @Override
    public Result<Optional<PortListener>, DomainError> findListener(int port) {
        return Result.ok(Optional.ofNullable(listeners.get(port)));
    }

    @Override
    public boolean isTcpListening(int port, Duration timeout) {
        return listeners.containsKey(port);
    }
}
