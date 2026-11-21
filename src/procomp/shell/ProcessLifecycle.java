package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.process.DiscoveryEngine;
import procomp.core.process.DiscoveryInput;
import procomp.core.process.DiscoveryResult;
import procomp.core.process.HealthCheck;
import procomp.core.process.HealthState;
import procomp.core.process.InspectionReport;
import procomp.core.process.ManagedProcessRecord;
import procomp.core.process.ObservedProcess;
import procomp.core.process.Ownership;
import procomp.core.process.PortBinding;
import procomp.core.process.ProcessPlanner;
import procomp.core.process.ProcessPlanner.StartAction;
import procomp.core.process.ProcessPlanner.StopAction;
import procomp.core.process.ProcessTarget;
import procomp.core.project.GatewayDefinition;
import procomp.core.project.ServiceDefinition;
import procomp.shell.ports.Clock;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.HttpClient.HttpResponseData;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProcessRunner.SpawnRequest;
import procomp.shell.ports.ProcessRunner.SpawnedProcess;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.ProgressEvent;
import procomp.shell.ports.ProjectFiles;
import procomp.shell.ports.ProjectFiles.ArtifactInfo;
import procomp.shell.ports.StateStore;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Package-private, target-agnostic lifecycle orchestrator for Service and Gateway Processes:
 * read-only Discovery, Inspection, 4-phase start orchestration (Discovery, fail-fast conflict
 * Inspection, launch-plan validation, spawn/reuse, and health waiting), and stop orchestration.
 */
final class ProcessLifecycle {

    static final Duration DEFAULT_HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(2);
    static final Duration DEFAULT_HEALTH_CHECK_INTERVAL = Duration.ofMillis(200);

    private final StateStore stateStore;
    private final ProcessRunner processRunner;
    private final PortScanner portScanner;
    private final HttpClient httpClient;
    private final ProjectFiles projectFiles;
    private final Clock clock;
    private final ProgressObserver observer;

    ProcessLifecycle(
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        ProjectFiles projectFiles,
        Clock clock,
        ProgressObserver observer
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
        this.portScanner = Objects.requireNonNull(portScanner, "portScanner must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.projectFiles = Objects.requireNonNull(projectFiles, "projectFiles must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    Path logPath(String projectName, ProcessKey key, boolean stderr) {
        return stateStore.logPath(projectName, key, stderr);
    }

    ProcessTarget targetForService(String projectName, ServiceDefinition service) {
        Path outLog = stateStore.logPath(projectName, ProcessKey.service(service.name()), false);
        Path errLog = stateStore.logPath(projectName, ProcessKey.service(service.name()), true);
        return ProcessTarget.forService(service, outLog, errLog);
    }

    ProcessTarget targetForGateway(String projectName, Optional<GatewayDefinition> gateway) {
        Path outLog = stateStore.logPath(projectName, ProcessKey.gateway(), false);
        Path errLog = stateStore.logPath(projectName, ProcessKey.gateway(), true);
        return gateway
            .map(gw -> ProcessTarget.forGateway(gw, outLog, errLog))
            .orElseGet(() -> new ProcessTarget("gateway", Optional.empty(), Optional.empty(), outLog, errLog));
    }

    Result<DiscoveryContext, DomainError> discoverService(String projectName, ServiceDefinition service) {
        ProcessTarget target = targetForService(projectName, service);
        return discoverTarget(projectName, ProcessKey.service(service.name()), target, Optional.of(service));
    }

    Result<DiscoveryContext, DomainError> discoverGateway(String projectName, GatewayDefinition gateway) {
        return discoverGateway(projectName, Optional.of(gateway));
    }

    Result<DiscoveryContext, DomainError> discoverGateway(String projectName, Optional<GatewayDefinition> gateway) {
        ProcessTarget target = targetForGateway(projectName, gateway);
        return discoverTarget(projectName, ProcessKey.gateway(), target, Optional.empty());
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
            Result<Optional<ObservedProcess>, DomainError> snapRes = processRunner.inspect(rec.pid());
            if (snapRes instanceof Result.Err<Optional<ObservedProcess>, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            Optional<ObservedProcess> observed = ((Result.Ok<Optional<ObservedProcess>, DomainError>) snapRes).value();

            HealthCheck healthCheck = HealthCheck.None.INSTANCE;
            Optional<Instant> latestArtifactTs = Optional.empty();
            if (observed.filter(ObservedProcess::alive).isPresent()) {
                healthCheck = checkHealth(target, false);
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
                managedRecord,
                Optional.empty(),
                observed,
                healthCheck,
                latestArtifactTs
            ));
            return Result.ok(new DiscoveryContext(target, managedRecord, discovery));
        }

        Optional<PortBinding> portBinding = Optional.empty();
        Optional<ObservedProcess> observed = Optional.empty();
        HealthCheck healthCheck = HealthCheck.None.INSTANCE;

        if (target.port().isPresent()) {
            Result<Optional<PortBinding>, DomainError> listenerRes = portScanner.findListener(target.port().get());
            if (listenerRes instanceof Result.Err<Optional<PortBinding>, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            portBinding = ((Result.Ok<Optional<PortBinding>, DomainError>) listenerRes).value();
            if (portBinding.isPresent()) {
                PortBinding listener = portBinding.get();
                if (listener.pid().isPresent()) {
                    Result<Optional<ObservedProcess>, DomainError> snapRes = processRunner.inspect(listener.pid().get());
                    if (snapRes instanceof Result.Err<Optional<ObservedProcess>, DomainError>(DomainError err)) {
                        return Result.err(err);
                    }
                    observed = ((Result.Ok<Optional<ObservedProcess>, DomainError>) snapRes).value();
                }
                healthCheck = checkHealth(target, true);
            }
        }

        DiscoveryResult discovery = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.empty(),
            portBinding,
            observed,
            healthCheck,
            Optional.empty()
        ));
        return Result.ok(new DiscoveryContext(target, Optional.empty(), discovery));
    }

    InspectionReport inspect(
        DiscoveryContext ctx,
        Optional<String> desiredRoute,
        Optional<String> liveGatewayRoute
    ) {
        return DiscoveryEngine.inspect(
            ctx.target(),
            ctx.discovery(),
            ctx.managedRecord(),
            desiredRoute,
            liveGatewayRoute,
            clock.now()
        );
    }

    /**
     * Unified 4-phase start orchestration:
     * 1. Pre-start Discovery + fail-fast Conflict Inspection across all targets.
     * 2. Pre-spawn launch-plan validation across all SPAWN targets before spawning any.
     * 3. Execute spawn or reuse decisions with progress notifications.
     * 4. Re-discover (when detached) or wait until HEALTHY.
     */
    Result<List<TargetStartOutcome>, DomainError> startTargets(
        String projectName,
        List<StartTargetSpec> targets,
        boolean detach,
        Duration timeout
    ) {
        Objects.requireNonNull(projectName, "projectName must not be null");
        Objects.requireNonNull(targets, "targets must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        if (targets.isEmpty()) {
            return Result.ok(List.of());
        }

        // 1. Read-only pre-start Discovery across all targets before spawning anything
        Map<String, DiscoveryContext> preDiscovery = new LinkedHashMap<>();
        List<InspectionReport> conflicts = new ArrayList<>();
        StartTargetSpec firstConflictSpec = null;
        for (StartTargetSpec spec : targets) {
            Result<DiscoveryContext, DomainError> discRes = spec.discoverer().get();
            if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            DiscoveryContext ctx = ((Result.Ok<DiscoveryContext, DomainError>) discRes).value();
            preDiscovery.put(spec.id(), ctx);
            if (ProcessPlanner.planStart(ctx.discovery()) == StartAction.FAIL_CONFLICT) {
                if (firstConflictSpec == null) {
                    firstConflictSpec = spec;
                }
                conflicts.add(inspect(ctx, spec.desiredRoute(), Optional.empty()));
            }
        }

        if (!conflicts.isEmpty()) {
            return Result.err(conflictError(conflicts, firstConflictSpec));
        }

        // 2. Pre-spawn validation & launch plan preparation for all SPAWN targets before spawning any
        Map<String, LaunchPlan> launchPlans = new LinkedHashMap<>();
        for (StartTargetSpec spec : targets) {
            DiscoveryContext ctx = preDiscovery.get(spec.id());
            if (ProcessPlanner.planStart(ctx.discovery()) == StartAction.SPAWN) {
                Result<LaunchPlan, DomainError> planRes = spec.launchPlanner().get();
                if (planRes instanceof Result.Err<LaunchPlan, DomainError>(DomainError err)) {
                    return Result.err(err);
                }
                launchPlans.put(spec.id(), ((Result.Ok<LaunchPlan, DomainError>) planRes).value());
            }
        }

        // 3. Execute spawn or reuse decisions
        Map<String, TargetStartDisposition> dispositions = new LinkedHashMap<>();
        List<StartTargetSpec> pendingHealth = new ArrayList<>();
        for (StartTargetSpec spec : targets) {
            DiscoveryContext ctx = preDiscovery.get(spec.id());
            StartAction action = ProcessPlanner.planStart(ctx.discovery());
            switch (action) {
                case SPAWN -> {
                    LaunchPlan plan = launchPlans.get(spec.id());
                    Result<SpawnedProcess, DomainError> spawnRes = spawnAndRecord(
                        projectName,
                        spec.key(),
                        plan.spawnRequest(),
                        plan.artifactTimestamp()
                    );
                    if (spawnRes instanceof Result.Err<SpawnedProcess, DomainError>(DomainError err)) {
                        return Result.err(err);
                    }
                    SpawnedProcess spawned = ((Result.Ok<SpawnedProcess, DomainError>) spawnRes).value();
                    dispositions.put(spec.id(), TargetStartDisposition.SPAWNED);
                    pendingHealth.add(spec);
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        spec.id(),
                        "Spawned Managed " + spec.processNoun() + " (PID " + spawned.pid() + ")"
                    ));
                }
                case REUSE_HEALTHY -> {
                    TargetStartDisposition disp = ctx.discovery().ownership() == Ownership.MANAGED
                        ? TargetStartDisposition.REUSED_MANAGED
                        : TargetStartDisposition.REUSED_UNMANAGED;
                    dispositions.put(spec.id(), disp);
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        spec.id(),
                        "Reusing existing " + ctx.discovery().ownership().name()
                            + ctx.discovery().pid()
                                .map(p -> " " + spec.processNoun() + " (PID " + p + ", HEALTHY)")
                                .orElse(" " + spec.processNoun() + " (HEALTHY)")
                    ));
                }
                case REUSE_WAIT -> {
                    TargetStartDisposition disp = ctx.discovery().ownership() == Ownership.MANAGED
                        ? TargetStartDisposition.REUSED_MANAGED
                        : TargetStartDisposition.REUSED_UNMANAGED;
                    dispositions.put(spec.id(), disp);
                    pendingHealth.add(spec);
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        spec.id(),
                        "Reusing existing " + ctx.discovery().ownership().name()
                            + ctx.discovery().pid()
                                .map(p -> " " + spec.processNoun() + " (PID " + p + ", STARTING)")
                                .orElse(" " + spec.processNoun() + " (STARTING)")
                    ));
                }
                case FAIL_CONFLICT -> throw new IllegalStateException("Conflicts should have failed fast earlier");
            }
        }

        // 4. Wait for HEALTHY unless detached
        Map<String, DiscoveryResult> finalDiscoveries = new LinkedHashMap<>();
        if (detach) {
            for (StartTargetSpec spec : targets) {
                Result<DiscoveryContext, DomainError> discRes = spec.discoverer().get();
                if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
                    return Result.err(err);
                }
                finalDiscoveries.put(spec.id(), ((Result.Ok<DiscoveryContext, DomainError>) discRes).value().discovery());
            }
        } else {
            if (!pendingHealth.isEmpty()) {
                List<HealthWaitTarget> waitTargets = new ArrayList<>(pendingHealth.size());
                for (StartTargetSpec spec : pendingHealth) {
                    waitTargets.add(new HealthWaitTarget(
                        spec.id(),
                        spec.detailKey(),
                        spec.displayLabel(),
                        spec.healthyMessage(),
                        spec.discoverer()
                    ));
                }
                Result<Map<String, DiscoveryResult>, DomainError> waitRes =
                    waitUntilHealthy(timeout, waitTargets);
                if (waitRes instanceof Result.Err<Map<String, DiscoveryResult>, DomainError>(DomainError err)) {
                    return Result.err(err);
                }
                finalDiscoveries.putAll(((Result.Ok<Map<String, DiscoveryResult>, DomainError>) waitRes).value());
            }
            for (StartTargetSpec spec : targets) {
                if (!finalDiscoveries.containsKey(spec.id())) {
                    finalDiscoveries.put(spec.id(), preDiscovery.get(spec.id()).discovery());
                }
            }
        }

        List<TargetStartOutcome> outcomes = new ArrayList<>(targets.size());
        for (StartTargetSpec spec : targets) {
            outcomes.add(new TargetStartOutcome(
                spec.id(),
                dispositions.get(spec.id()),
                finalDiscoveries.get(spec.id())
            ));
        }
        return Result.ok(outcomes);
    }

    Result<TargetStopOutcome, DomainError> stopService(
        String projectName,
        ServiceDefinition service,
        boolean killConflict,
        Duration gracefulTimeout
    ) {
        ProcessTarget target = targetForService(projectName, service);
        return stopTarget(
            projectName,
            ProcessKey.service(service.name()),
            target,
            Optional.of(service),
            killConflict,
            gracefulTimeout,
            true
        );
    }

    Result<TargetStopOutcome, DomainError> stopGateway(
        String projectName,
        Optional<GatewayDefinition> gateway,
        Duration gracefulTimeout
    ) {
        ProcessTarget target = targetForGateway(projectName, gateway);
        return stopTarget(
            projectName,
            ProcessKey.gateway(),
            target,
            Optional.empty(),
            false,
            gracefulTimeout,
            false
        );
    }

    Result<TargetStopOutcome, DomainError> stopTarget(
        String projectName,
        ProcessKey key,
        ProcessTarget target,
        Optional<ServiceDefinition> serviceOpt,
        boolean killConflict,
        Duration gracefulTimeout,
        boolean emitSparedEvents
    ) {
        Result<DiscoveryContext, DomainError> discRes = discoverTarget(projectName, key, target, serviceOpt);
        if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        DiscoveryResult disc = ((Result.Ok<DiscoveryContext, DomainError>) discRes).value().discovery();
        StopAction action = ProcessPlanner.planStop(disc, killConflict);
        boolean isGateway = key instanceof ProcessKey.Gateway;

        return switch (action) {
            case KILL_MANAGED -> {
                long pid = disc.pid().orElseThrow();
                boolean alive = disc.health() != HealthState.CRASHED;
                Result<Unit, DomainError> stopRes = stopManagedProcess(
                    projectName, key, pid, alive, gracefulTimeout
                );
                if (stopRes instanceof Result.Err<Unit, DomainError>(DomainError err)) {
                    yield Result.err(err);
                }
                TargetStopDisposition disp = alive
                    ? TargetStopDisposition.STOPPED_MANAGED
                    : TargetStopDisposition.CLEANED_CRASHED;
                String message = isGateway
                    ? (disp == TargetStopDisposition.CLEANED_CRASHED
                        ? "Cleaned crashed Managed Gateway record (PID " + pid + ")"
                        : "Stopped Managed Gateway (PID " + pid + ")")
                    : (disp == TargetStopDisposition.CLEANED_CRASHED
                        ? "Cleaned crashed Managed record (PID " + pid + ")"
                        : "Stopped Managed Process (PID " + pid + ")");
                observer.onEvent(new ProgressEvent.StatusMessage(target.name(), message));
                yield Result.ok(new TargetStopOutcome(disp, disc));
            }
            case SPARE_UNMANAGED -> {
                if (emitSparedEvents) {
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        target.name(),
                        "Spared Unmanaged Process" + disc.pid().map(p -> " (PID " + p + ")").orElse("")
                    ));
                }
                yield Result.ok(new TargetStopOutcome(TargetStopDisposition.SPARED_UNMANAGED, disc));
            }
            case SPARE_CONFLICT -> {
                if (emitSparedEvents) {
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        target.name(),
                        "Spared Conflict Process" + disc.pid().map(p -> " (PID " + p + ")").orElse("")
                    ));
                }
                yield Result.ok(new TargetStopOutcome(TargetStopDisposition.SPARED_CONFLICT, disc));
            }
            case KILL_CONFLICT_WITH_CONSENT -> {
                if (disc.pid().isEmpty()) {
                    yield Result.err(DomainError.of(
                        "CONFLICT_PID_UNKNOWN",
                        "Cannot kill Conflict on service '" + target.name() + "': owning OS PID could not be determined",
                        Map.of("service", target.name())
                    ));
                }
                long pid = disc.pid().get();
                Result<Unit, DomainError> stopRes = processRunner.stopTree(pid, gracefulTimeout);
                if (stopRes instanceof Result.Err<Unit, DomainError>(DomainError err)) {
                    yield Result.err(err);
                }
                observer.onEvent(new ProgressEvent.StatusMessage(
                    target.name(),
                    "Killed Conflict Process (PID " + pid + ") with explicit consent"
                ));
                yield Result.ok(new TargetStopOutcome(TargetStopDisposition.KILLED_CONFLICT, disc));
            }
            case ALREADY_STOPPED ->
                Result.ok(new TargetStopOutcome(TargetStopDisposition.ALREADY_STOPPED, disc));
        };
    }

    Result<Optional<ArtifactInfo>, DomainError> findLatestArtifact(ServiceDefinition service) {
        if (service.artifactGlob().isEmpty()) {
            return Result.ok(Optional.empty());
        }
        String glob = service.artifactGlob().get();
        Result<Optional<ArtifactInfo>, DomainError> fromWorkingDir =
            projectFiles.findNewestArtifact(service.workingDir(), glob, service.artifactExclude());
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
        return projectFiles.findNewestArtifact(repoRoot, glob, service.artifactExclude());
    }

    private Result<SpawnedProcess, DomainError> spawnAndRecord(
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

    private Result<Map<String, DiscoveryResult>, DomainError> waitUntilHealthy(
        Duration timeout,
        List<HealthWaitTarget> waitTargets
    ) {
        Map<String, DiscoveryResult> resolved = new LinkedHashMap<>();
        List<HealthWaitTarget> pending = List.copyOf(waitTargets);
        Instant deadline = clock.now().plus(timeout);

        while (!pending.isEmpty()) {
            List<HealthWaitTarget> stillPending = new ArrayList<>();
            for (HealthWaitTarget target : pending) {
                Result<DiscoveryContext, DomainError> discRes = target.discoverer().get();
                if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
                    return Result.err(err);
                }
                DiscoveryContext ctx = ((Result.Ok<DiscoveryContext, DomainError>) discRes).value();
                DiscoveryResult disc = ctx.discovery();

                if (disc.health() == HealthState.CRASHED) {
                    return Result.err(DomainError.of(
                        "PROCESS_CRASHED",
                        target.displayLabel() + " exited unexpectedly while starting"
                            + disc.pid().map(p -> " (PID " + p + ")").orElse("")
                            + ". Check logs: " + ctx.target().stdoutLog() + " / " + ctx.target().stderrLog(),
                        Map.of(
                            target.detailKey(), target.id(),
                            "pid", disc.pid().map(Object::toString).orElse(""),
                            "stdoutLog", ctx.target().stdoutLog().toString(),
                            "stderrLog", ctx.target().stderrLog().toString()
                        )
                    ));
                }
                if (disc.ownership() == Ownership.NONE && disc.health() == HealthState.STOPPED) {
                    return Result.err(DomainError.of(
                        "PROCESS_EXITED",
                        "Reused process for " + target.displayLabel() + " exited before becoming HEALTHY",
                        Map.of(target.detailKey(), target.id())
                    ));
                }
                if (disc.health() == HealthState.HEALTHY) {
                    resolved.put(target.id(), disc);
                    observer.onEvent(new ProgressEvent.StatusMessage(target.id(), target.healthyMessage()));
                } else {
                    stillPending.add(target);
                }
            }
            pending = stillPending;
            if (pending.isEmpty()) {
                break;
            }
            if (!clock.now().isBefore(deadline)) {
                String list = pending.stream().map(HealthWaitTarget::id).collect(Collectors.joining(", "));
                String detailKey = pending.size() == 1 ? pending.get(0).detailKey() : "services";
                String subject = pending.size() == 1
                    ? pending.get(0).displayLabel()
                    : "service(s): " + list;
                return Result.err(DomainError.of(
                    "START_TIMEOUT",
                    "Timed out after " + timeout.toSeconds() + "s waiting for " + subject + " to become HEALTHY",
                    Map.of(detailKey, list, "timeoutSeconds", Long.toString(timeout.toSeconds()))
                ));
            }
            if (!clock.sleep(DEFAULT_HEALTH_CHECK_INTERVAL)) {
                String list = pending.stream().map(HealthWaitTarget::id).collect(Collectors.joining(", "));
                return Result.err(DomainError.of(
                    "INTERRUPTED",
                    "Interrupted while waiting for " + list + " to become HEALTHY"
                ));
            }
        }
        return Result.ok(resolved);
    }

    private Result<Unit, DomainError> stopManagedProcess(
        String projectName,
        ProcessKey key,
        long pid,
        boolean stopOsTree,
        Duration gracefulTimeout
    ) {
        if (stopOsTree) {
            Result<Unit, DomainError> stopRes = processRunner.stopTree(pid, gracefulTimeout);
            if (stopRes.isErr()) {
                return stopRes;
            }
        }
        return stateStore.deleteManagedProcess(projectName, key);
    }

    private HealthCheck checkHealth(ProcessTarget target, boolean listenerConfirmed) {
        if (target.healthEndpoint().isPresent()) {
            URI uri = URI.create(target.healthEndpoint().get());
            return switch (httpClient.get(uri, DEFAULT_HEALTH_CHECK_TIMEOUT)) {
                case Result.Ok<HttpResponseData, DomainError>(HttpResponseData resp) ->
                    new HealthCheck.HttpResult(resp.statusCode(), "");
                case Result.Err<HttpResponseData, DomainError>(DomainError err) ->
                    new HealthCheck.HttpFailure(err.message());
            };
        }
        if (target.port().isPresent()) {
            boolean listening = listenerConfirmed
                || portScanner.isTcpListening(target.port().get(), DEFAULT_HEALTH_CHECK_TIMEOUT);
            return new HealthCheck.TcpResult(listening);
        }
        return HealthCheck.None.INSTANCE;
    }

    private static DomainError conflictError(List<InspectionReport> conflicts, StartTargetSpec firstSpec) {
        InspectionReport first = conflicts.get(0);
        Map<String, String> details = conflictDetails(first, firstSpec.detailKey());
        if (conflicts.size() > 1) {
            details.put("allConflicts", conflicts.stream().map(InspectionReport::service).collect(Collectors.joining(", ")));
        }

        boolean isGateway = firstSpec.key() instanceof ProcessKey.Gateway;
        StringBuilder msg = new StringBuilder();
        msg.append(isGateway ? "Port conflict on gateway" : "Port conflict on '" + first.service() + "'");
        first.port().ifPresent(p -> msg.append(" (port ").append(p).append(")"));
        first.pid().ifPresent(p -> msg.append(": occupied by PID ").append(p));
        first.commandLine().ifPresent(c -> msg.append(" [").append(c).append("]"));
        if (isGateway) {
            msg.append(". No processes were spawned. Stop the conflicting process or change 'gateway.port' in procomp.json.");
        } else {
            msg.append(". No processes were spawned and Routing Table was not modified. ");
            msg.append("Run Inspection on '").append(first.service())
                .append("' for details, or stop the Conflict Process with explicit consent.");
        }
        return DomainError.of("PORT_CONFLICT", msg.toString(), details);
    }

    private static Map<String, String> conflictDetails(InspectionReport first, String targetKeyName) {
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

    record LaunchPlan(SpawnRequest spawnRequest, Optional<Instant> artifactTimestamp) {}

    record StartTargetSpec(
        String id,
        ProcessKey key,
        String detailKey,
        String displayLabel,
        String processNoun,
        String healthyMessage,
        Optional<String> desiredRoute,
        Supplier<Result<DiscoveryContext, DomainError>> discoverer,
        Supplier<Result<LaunchPlan, DomainError>> launchPlanner
    ) {}

    enum TargetStartDisposition {
        SPAWNED,
        REUSED_MANAGED,
        REUSED_UNMANAGED
    }

    record TargetStartOutcome(
        String id,
        TargetStartDisposition disposition,
        DiscoveryResult discovery
    ) {}

    enum TargetStopDisposition {
        STOPPED_MANAGED,
        CLEANED_CRASHED,
        SPARED_UNMANAGED,
        SPARED_CONFLICT,
        KILLED_CONFLICT,
        ALREADY_STOPPED
    }

    record TargetStopOutcome(
        TargetStopDisposition disposition,
        DiscoveryResult discovery
    ) {}

    private record HealthWaitTarget(
        String id,
        String detailKey,
        String displayLabel,
        String healthyMessage,
        Supplier<Result<DiscoveryContext, DomainError>> discoverer
    ) {}
}
