package procomp.fakes;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.shell.ports.Clock;
import procomp.shell.ports.ProcessRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FakeProcessRunner implements ProcessRunner {

    private final Clock clock;
    private long nextPid = 10000;
    private final Map<Long, ProcessSnapshot> processes = new LinkedHashMap<>();
    private final List<SpawnRequest> spawnedRequests = new ArrayList<>();
    private final List<Long> stoppedPids = new ArrayList<>();
    private DomainError spawnError;

    public FakeProcessRunner(Clock clock) {
        this.clock = clock;
    }

    public FakeProcessRunner registerProcess(ProcessSnapshot snapshot) {
        processes.put(snapshot.pid(), snapshot);
        return this;
    }

    public FakeProcessRunner markExited(long pid) {
        processes.remove(pid);
        return this;
    }

    public FakeProcessRunner failNextSpawn(DomainError error) {
        this.spawnError = error;
        return this;
    }

    public List<SpawnRequest> spawnedRequests() {
        return List.copyOf(spawnedRequests);
    }

    public List<Long> stoppedPids() {
        return List.copyOf(stoppedPids);
    }

    @Override
    public Result<SpawnedProcess, DomainError> spawnDetached(SpawnRequest request) {
        if (spawnError != null) {
            DomainError err = spawnError;
            spawnError = null;
            return Result.err(err);
        }
        spawnedRequests.add(request);
        long pid = nextPid++;
        Instant startedAt = clock.now();
        String cmdLine = String.join(" ", request.command());
        processes.put(pid, new ProcessSnapshot(
            pid,
            true,
            Optional.of(startedAt),
            Optional.of(cmdLine),
            request.command().subList(1, request.command().size()),
            List.of(),
            Optional.of(64L * 1024 * 1024),
            Optional.of(request.command().get(0))
        ));
        return Result.ok(new SpawnedProcess(pid, startedAt, cmdLine));
    }

    @Override
    public Result<Optional<ProcessSnapshot>, DomainError> inspect(long pid) {
        return Result.ok(Optional.ofNullable(processes.get(pid)));
    }

    @Override
    public boolean isAlive(long pid) {
        ProcessSnapshot snap = processes.get(pid);
        return snap != null && snap.alive();
    }

    @Override
    public Result<Unit, DomainError> stopTree(long pid, Duration gracefulTimeout) {
        stoppedPids.add(pid);
        processes.remove(pid);
        return Result.ok(Unit.VALUE);
    }
}
