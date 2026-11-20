package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.process.DiscoveryEngine;
import procomp.core.process.DiscoveryInput;
import procomp.core.process.DiscoveryResult;
import procomp.core.process.HealthProbe;
import procomp.core.process.InspectionReport;
import procomp.core.process.ManagedRecordView;
import procomp.core.process.ObservedProcess;
import procomp.core.process.ParentProcess;
import procomp.core.process.PortBinding;
import procomp.core.process.ProcessTarget;
import procomp.core.project.GatewayDefinition;
import procomp.core.project.ServiceDefinition;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.HttpClient.HttpResponseData;
import procomp.shell.ports.ManagedProcessRecord;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.PortScanner.PortListener;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProcessRunner.ParentProcessInfo;
import procomp.shell.ports.ProcessRunner.ProcessSnapshot;
import procomp.shell.ports.ProcessRunner.SpawnRequest;
import procomp.shell.ports.ProcessRunner.SpawnedProcess;
import procomp.shell.ports.StateStore;
import procomp.shell.ports.WorkspaceFiles;
import procomp.shell.ports.WorkspaceFiles.ArtifactInfo;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shared package-private helper for read-only Service and Gateway Discovery, health probing,
 * artifact lookup, detached spawning, and conflict diagnostic formatting.
 */
final class TargetDiscoverySupport {

    static final Duration DEFAULT_HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(2);
    static final Duration DEFAULT_STARTUP_POLL_INTERVAL = Duration.ofMillis(200);

    private final StateStore stateStore;
    private final ProcessRunner processRunner;
    private final PortScanner portScanner;
    private final HttpClient httpClient;
    private final WorkspaceFiles workspaceFiles;

    TargetDiscoverySupport(
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        WorkspaceFiles workspaceFiles
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
        this.portScanner = Objects.requireNonNull(portScanner, "portScanner must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.workspaceFiles = Objects.requireNonNull(workspaceFiles, "workspaceFiles must not be null");
    }

    Result<DiscoveryContext, DomainError> discoverService(String projectName, ServiceDefinition service) {
        ProcessTarget target = targetForService(projectName, service);
        return discoverTarget(projectName, ProcessKey.service(service.name()), target, Optional.of(service));
    }

    Result<DiscoveryContext, DomainError> discoverGateway(String projectName, GatewayDefinition gateway) {
        Path outLog = stateStore.logPath(projectName, ProcessKey.gateway(), false);
        Path errLog = stateStore.logPath(projectName, ProcessKey.gateway(), true);
        ProcessTarget target = ProcessTarget.forGateway(gateway, outLog, errLog);
        return discoverTarget(projectName, ProcessKey.gateway(), target, Optional.empty());
    }

    ProcessTarget targetForService(String projectName, ServiceDefinition service) {
        Path outLog = stateStore.logPath(projectName, ProcessKey.service(service.name()), false);
        Path errLog = stateStore.logPath(projectName, ProcessKey.service(service.name()), true);
        return ProcessTarget.forService(service, outLog, errLog);
    }

    Result<DiscoveryContext, DomainError> discoverTarget(
        String projectName,
        ProcessKey key,
        ProcessTarget target,
        Optional<ServiceDefinition> serviceOpt
    ) {
        Result<Optional<ManagedProcessRecord>, DomainError> recRes =
            stateStore.readManagedProcess(projectName, key);
        if (recRes instanceof Result.Err<Optional<ManagedProcessRecord>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        Optional<ManagedProcessRecord> managedRecord = ((Result.Ok<Optional<ManagedProcessRecord>, DomainError>) recRes).value();

        if (managedRecord.isPresent()) {
            ManagedProcessRecord rec = managedRecord.get();
            Result<Optional<ProcessSnapshot>, DomainError> snapRes = processRunner.inspect(rec.pid());
            if (snapRes instanceof Result.Err<Optional<ProcessSnapshot>, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            Optional<ProcessSnapshot> snapshot = ((Result.Ok<Optional<ProcessSnapshot>, DomainError>) snapRes).value();
            Optional<ObservedProcess> observed = snapshot.map(TargetDiscoverySupport::toObservedProcess);

            HealthProbe probe = HealthProbe.None.INSTANCE;
            Optional<Instant> latestArtifactTs = Optional.empty();
            if (observed.filter(ObservedProcess::alive).isPresent()) {
                probe = probeTarget(target, false);
                if (serviceOpt.isPresent()) {
                    Result<Optional<ArtifactInfo>, DomainError> artRes = findLatestArtifact(serviceOpt.get());
                    if (artRes instanceof Result.Err<Optional<ArtifactInfo>, DomainError>(DomainError err)) {
                        return Result.err(err);
                    }
                    latestArtifactTs = ((Result.Ok<Optional<ArtifactInfo>, DomainError>) artRes).value()
                        .map(ArtifactInfo::lastModified);
                }
            }

            DiscoveryResult discovery = DiscoveryEngine.discover(new DiscoveryInput(
                target,
                Optional.of(toManagedView(rec)),
                Optional.empty(),
                observed,
                probe,
                latestArtifactTs
            ));
            return Result.ok(new DiscoveryContext(target, managedRecord, discovery));
        }

        Optional<PortBinding> portBinding = Optional.empty();
        Optional<ObservedProcess> observed = Optional.empty();
        HealthProbe probe = HealthProbe.None.INSTANCE;

        if (target.port().isPresent()) {
            Result<Optional<PortListener>, DomainError> listenerRes = portScanner.findListener(target.port().get());
            if (listenerRes instanceof Result.Err<Optional<PortListener>, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            Optional<PortListener> listenerOpt = ((Result.Ok<Optional<PortListener>, DomainError>) listenerRes).value();
            if (listenerOpt.isPresent()) {
                PortListener listener = listenerOpt.get();
                portBinding = Optional.of(new PortBinding(listener.port(), listener.pid(), listener.localAddress()));
                if (listener.pid().isPresent()) {
                    Result<Optional<ProcessSnapshot>, DomainError> snapRes = processRunner.inspect(listener.pid().get());
                    if (snapRes instanceof Result.Err<Optional<ProcessSnapshot>, DomainError>(DomainError err)) {
                        return Result.err(err);
                    }
                    observed = ((Result.Ok<Optional<ProcessSnapshot>, DomainError>) snapRes).value()
                        .map(TargetDiscoverySupport::toObservedProcess);
                }
                probe = probeTarget(target, true);
            }
        }

        DiscoveryResult discovery = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.empty(),
            portBinding,
            observed,
            probe,
            Optional.empty()
        ));
        return Result.ok(new DiscoveryContext(target, Optional.empty(), discovery));
    }

    Result<SpawnedProcess, DomainError> spawnAndRecord(
        String projectName,
        ProcessKey key,
        SpawnRequest spawnRequest,
        Optional<Instant> artifactTimestamp
    ) {
        Result<SpawnedProcess, DomainError> spawnRes = processRunner.spawnDetached(spawnRequest);
        if (spawnRes instanceof Result.Err<SpawnedProcess, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        SpawnedProcess spawned = ((Result.Ok<SpawnedProcess, DomainError>) spawnRes).value();
        ManagedProcessRecord record = new ManagedProcessRecord(
            spawned.pid(),
            spawned.startedAt(),
            spawnRequest.command(),
            artifactTimestamp,
            spawnRequest.stdoutLog(),
            spawnRequest.stderrLog()
        );
        Result<Unit, DomainError> writeRes = stateStore.writeManagedProcess(projectName, key, record);
        if (writeRes instanceof Result.Err<Unit, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        return Result.ok(spawned);
    }

    HealthProbe probeTarget(ProcessTarget target, boolean listenerConfirmed) {
        if (target.healthEndpoint().isPresent()) {
            URI uri = URI.create(target.healthEndpoint().get());
            return switch (httpClient.get(uri, DEFAULT_HEALTH_PROBE_TIMEOUT)) {
                case Result.Ok<HttpResponseData, DomainError>(HttpResponseData resp) ->
                    new HealthProbe.HttpResult(resp.statusCode(), "");
                case Result.Err<HttpResponseData, DomainError>(DomainError err) ->
                    new HealthProbe.HttpFailure(err.message());
            };
        }
        if (target.port().isPresent()) {
            boolean listening = listenerConfirmed
                || portScanner.isTcpListening(target.port().get(), DEFAULT_HEALTH_PROBE_TIMEOUT);
            return new HealthProbe.TcpResult(listening);
        }
        return HealthProbe.None.INSTANCE;
    }

    Result<Optional<ArtifactInfo>, DomainError> findLatestArtifact(ServiceDefinition service) {
        if (service.artifactGlob().isEmpty()) {
            return Result.ok(Optional.empty());
        }
        String glob = service.artifactGlob().get();
        Result<Optional<ArtifactInfo>, DomainError> fromWorkingDir =
            workspaceFiles.findNewestArtifact(service.workingDir(), glob, service.artifactExclude());
        if (fromWorkingDir.isErr()) {
            return fromWorkingDir;
        }
        Optional<ArtifactInfo> found = ((Result.Ok<Optional<ArtifactInfo>, DomainError>) fromWorkingDir).value();
        if (found.isPresent() || service.repository().isEmpty()) {
            return fromWorkingDir;
        }
        Path repoRoot = service.repository().get().root();
        if (repoRoot.equals(service.workingDir())) {
            return fromWorkingDir;
        }
        return workspaceFiles.findNewestArtifact(repoRoot, glob, service.artifactExclude());
    }

    static ManagedRecordView toManagedView(ManagedProcessRecord rec) {
        return new ManagedRecordView(
            rec.pid(),
            rec.startedAt(),
            rec.commandSummary(),
            rec.artifactTimestamp(),
            rec.stdoutLog(),
            rec.stderrLog()
        );
    }

    static ObservedProcess toObservedProcess(ProcessSnapshot snap) {
        List<ParentProcess> parents = snap.parentChain().stream()
            .map(TargetDiscoverySupport::toParentProcess)
            .toList();
        return new ObservedProcess(
            snap.pid(),
            snap.alive(),
            snap.startTime(),
            snap.commandLine(),
            snap.arguments(),
            parents,
            snap.rssBytes(),
            snap.executable()
        );
    }

    static ParentProcess toParentProcess(ParentProcessInfo info) {
        return new ParentProcess(info.pid(), info.commandLine(), info.executable());
    }

    static Map<String, String> conflictDetails(InspectionReport first, String targetKeyName) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put(targetKeyName, first.service());
        first.port().ifPresent(p -> details.put("port", Integer.toString(p)));
        first.pid().ifPresent(p -> details.put("pid", Long.toString(p)));
        first.commandLine().ifPresent(c -> details.put("commandLine", c));
        if (!first.parentChain().isEmpty()) {
            String parents = first.parentChain().stream()
                .map(p -> p.pid() + ":" + p.commandLine().or(p::executable).orElse("?"))
                .collect(Collectors.joining(" <- "));
            details.put("parentChain", parents);
        }
        first.healthEndpoint().ifPresent(ep -> details.put("healthEndpoint", ep));
        details.put("healthResponse", first.healthResponse());
        details.put("outLogPath", first.outLogPath().toString());
        details.put("errLogPath", first.errLogPath().toString());
        return details;
    }

    record DiscoveryContext(
        ProcessTarget target,
        Optional<ManagedProcessRecord> managedRecord,
        DiscoveryResult discovery
    ) {}
}
