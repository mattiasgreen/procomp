package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.process.DiscoveryEngine;
import procomp.core.process.InspectionReport;
import procomp.core.process.Ownership;
import procomp.core.process.ProcessPlanner;
import procomp.core.process.ProcessPlanner.StartAction;
import procomp.core.process.ProcessPlanner.StopAction;
import procomp.core.process.ProcessTarget;
import procomp.core.process.DiscoveryResult;
import procomp.core.process.HealthState;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.core.project.ServiceDefinition;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.TargetDiscoverySupport.DiscoveryContext;
import procomp.shell.ports.Clock;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.ManagedProcessRecord;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProcessRunner.SpawnRequest;
import procomp.shell.ports.ProcessRunner.SpawnedProcess;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.ProgressEvent;
import procomp.shell.ports.StateStore;
import procomp.shell.ports.WorkspaceFiles;
import procomp.shell.ports.WorkspaceFiles.ArtifactInfo;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Modality-agnostic orchestrator for read-only Service Discovery, Inspection, and Process lifecycle.
 * Composes across {@code process} and {@code routing} domains strictly inside the shell layer.
 */
public final class ProcessShell {

    public static final Duration DEFAULT_HEALTH_PROBE_TIMEOUT = TargetDiscoverySupport.DEFAULT_HEALTH_PROBE_TIMEOUT;
    public static final Duration DEFAULT_STARTUP_POLL_INTERVAL = TargetDiscoverySupport.DEFAULT_STARTUP_POLL_INTERVAL;

    private final StateStore stateStore;
    private final ProcessRunner processRunner;
    private final WorkspaceFiles workspaceFiles;
    private final Clock clock;
    private final ProgressObserver observer;
    private final RoutingShell routingShell;
    private final TargetDiscoverySupport discoverySupport;

    public ProcessShell(
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        WorkspaceFiles workspaceFiles,
        Clock clock,
        ProgressObserver observer
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
        Objects.requireNonNull(portScanner, "portScanner must not be null");
        Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.workspaceFiles = Objects.requireNonNull(workspaceFiles, "workspaceFiles must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.routingShell = new RoutingShell(stateStore);
        this.discoverySupport = new TargetDiscoverySupport(stateStore, processRunner, portScanner, httpClient, workspaceFiles);
    }

    /**
     * Performs read-only Discovery for a named Service without modifying any state or killing processes.
     */
    public Result<DiscoveryResult, DomainError> discoverService(Project project, String serviceName) {
        Objects.requireNonNull(project, "project must not be null");
        Optional<ServiceDefinition> svcOpt = requireService(project, serviceName);
        if (svcOpt.isEmpty()) {
            return Result.err(unknownServiceError(project, serviceName));
        }
        return discoverySupport.discoverService(project.name(), svcOpt.get()).map(DiscoveryContext::discovery);
    }

    /**
     * Performs deep read-only Inspection for a named Service, composing Discovery with Routing Table desired Route.
     */
    public Result<InspectionReport, DomainError> inspect(Project project, String serviceName) {
        Objects.requireNonNull(project, "project must not be null");
        Optional<ServiceDefinition> svcOpt = requireService(project, serviceName);
        if (svcOpt.isEmpty()) {
            return Result.err(unknownServiceError(project, serviceName));
        }
        ServiceDefinition service = svcOpt.get();

        Result<RoutingTable, DomainError> routingRes = routingShell.showRoutes(project);
        if (routingRes instanceof Result.Err<RoutingTable, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        RoutingTable routing = ((Result.Ok<RoutingTable, DomainError>) routingRes).value();
        Optional<String> desiredRoute = routing.routeOf(service.name()).map(Route::name);

        return discoverySupport.discoverService(project.name(), service)
            .map(ctx -> DiscoveryEngine.inspect(
                ctx.target(),
                ctx.discovery(),
                ctx.managedRecord().map(TargetDiscoverySupport::toManagedView),
                desiredRoute,
                Optional.empty(),
                clock.now()
            ));
    }

    /**
     * Runs pre-start Discovery, spawns missing/crashed Services detached, reuses Managed/Unmanaged Processes,
     * fails fast with Inspection details on Conflict, and waits for HEALTHY unless detached.
     */
    public Result<StartResult, DomainError> start(Project project, StartRequest request) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Result<RoutingTable, DomainError> routingRes = routingShell.showRoutes(project);
        if (routingRes instanceof Result.Err<RoutingTable, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        RoutingTable routing = ((Result.Ok<RoutingTable, DomainError>) routingRes).value();

        boolean explicitTargets = !request.serviceNames().isEmpty();
        Result<List<ServiceDefinition>, DomainError> targetsRes = resolveStartTargets(project, routing, request.serviceNames());
        if (targetsRes instanceof Result.Err<List<ServiceDefinition>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        List<ServiceDefinition> targets = ((Result.Ok<List<ServiceDefinition>, DomainError>) targetsRes).value();
        if (targets.isEmpty()) {
            return Result.ok(new StartResult(explicitTargets, request.detach(), List.of()));
        }

        // 1. Read-only pre-start Discovery across all targets before spawning anything
        Map<String, DiscoveryContext> preDiscovery = new LinkedHashMap<>();
        List<InspectionReport> conflicts = new ArrayList<>();
        for (ServiceDefinition svc : targets) {
            Result<DiscoveryContext, DomainError> discRes = discoverySupport.discoverService(project.name(), svc);
            if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            DiscoveryContext ctx = ((Result.Ok<DiscoveryContext, DomainError>) discRes).value();
            preDiscovery.put(svc.name(), ctx);
            if (ProcessPlanner.planStart(ctx.discovery()) == StartAction.FAIL_CONFLICT) {
                Optional<String> desiredRoute = routing.routeOf(svc.name()).map(Route::name);
                conflicts.add(DiscoveryEngine.inspect(
                    ctx.target(),
                    ctx.discovery(),
                    ctx.managedRecord().map(TargetDiscoverySupport::toManagedView),
                    desiredRoute,
                    Optional.empty(),
                    clock.now()
                ));
            }
        }

        if (!conflicts.isEmpty()) {
            return Result.err(conflictError(conflicts));
        }

        // 2. Lazy validation (Repository, workingDir, {{artifact}}) for all spawn targets before spawning any
        Map<String, LaunchPlan> launchPlans = new LinkedHashMap<>();
        for (ServiceDefinition svc : targets) {
            DiscoveryContext ctx = preDiscovery.get(svc.name());
            if (ProcessPlanner.planStart(ctx.discovery()) == StartAction.SPAWN) {
                Result<LaunchPlan, DomainError> planRes = prepareLaunchPlan(project.name(), svc);
                if (planRes instanceof Result.Err<LaunchPlan, DomainError>(DomainError err)) {
                    return Result.err(err);
                }
                launchPlans.put(svc.name(), ((Result.Ok<LaunchPlan, DomainError>) planRes).value());
            }
        }

        // 3. Execute spawn or reuse decisions
        Map<String, StartDisposition> dispositions = new LinkedHashMap<>();
        List<String> pendingHealth = new ArrayList<>();
        for (ServiceDefinition svc : targets) {
            DiscoveryContext ctx = preDiscovery.get(svc.name());
            StartAction action = ProcessPlanner.planStart(ctx.discovery());
            switch (action) {
                case SPAWN -> {
                    LaunchPlan plan = launchPlans.get(svc.name());
                    Result<SpawnedProcess, DomainError> spawnRes = discoverySupport.spawnAndRecord(
                        project.name(),
                        ProcessKey.service(svc.name()),
                        plan.spawnRequest(),
                        plan.artifactTimestamp()
                    );
                    if (spawnRes instanceof Result.Err<SpawnedProcess, DomainError>(DomainError err)) {
                        return Result.err(err);
                    }
                    SpawnedProcess spawned = ((Result.Ok<SpawnedProcess, DomainError>) spawnRes).value();
                    dispositions.put(svc.name(), StartDisposition.SPAWNED);
                    pendingHealth.add(svc.name());
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        svc.name(),
                        "Spawned Managed Process (PID " + spawned.pid() + ")"
                    ));
                }
                case REUSE_HEALTHY -> {
                    StartDisposition disp = ctx.discovery().ownership() == Ownership.MANAGED
                        ? StartDisposition.REUSED_MANAGED
                        : StartDisposition.REUSED_UNMANAGED;
                    dispositions.put(svc.name(), disp);
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        svc.name(),
                        "Reusing existing " + ctx.discovery().ownership().name()
                            + ctx.discovery().pid().map(p -> " Process (PID " + p + ", HEALTHY)").orElse(" Process (HEALTHY)")
                    ));
                }
                case REUSE_WAIT -> {
                    StartDisposition disp = ctx.discovery().ownership() == Ownership.MANAGED
                        ? StartDisposition.REUSED_MANAGED
                        : StartDisposition.REUSED_UNMANAGED;
                    dispositions.put(svc.name(), disp);
                    pendingHealth.add(svc.name());
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        svc.name(),
                        "Reusing existing " + ctx.discovery().ownership().name()
                            + ctx.discovery().pid().map(p -> " Process (PID " + p + ", STARTING)").orElse(" Process (STARTING)")
                    ));
                }
                case FAIL_CONFLICT -> throw new IllegalStateException("Conflicts should have failed fast earlier");
            }
        }

        // 4. Wait for HEALTHY unless detached
        Map<String, DiscoveryResult> finalDiscoveries = new LinkedHashMap<>();
        if (request.detach()) {
            for (ServiceDefinition svc : targets) {
                Result<DiscoveryContext, DomainError> discRes = discoverySupport.discoverService(project.name(), svc);
                if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
                    return Result.err(err);
                }
                finalDiscoveries.put(svc.name(), ((Result.Ok<DiscoveryContext, DomainError>) discRes).value().discovery());
            }
        } else {
            Instant deadline = clock.now().plus(request.timeout());
            while (!pendingHealth.isEmpty()) {
                List<String> stillPending = new ArrayList<>();
                for (String name : pendingHealth) {
                    ServiceDefinition svc = project.service(name).orElseThrow();
                    Result<DiscoveryContext, DomainError> discRes = discoverySupport.discoverService(project.name(), svc);
                    if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
                        return Result.err(err);
                    }
                    DiscoveryContext ctx = ((Result.Ok<DiscoveryContext, DomainError>) discRes).value();
                    DiscoveryResult disc = ctx.discovery();
                    if (disc.health() == HealthState.CRASHED) {
                        return Result.err(DomainError.of(
                            "PROCESS_CRASHED",
                            "Service '" + name + "' exited unexpectedly while starting"
                                + disc.pid().map(p -> " (PID " + p + ")").orElse("")
                                + ". Check logs: " + ctx.target().stdoutLog() + " / " + ctx.target().stderrLog(),
                            Map.of(
                                "service", name,
                                "pid", disc.pid().map(Object::toString).orElse(""),
                                "stdoutLog", ctx.target().stdoutLog().toString(),
                                "stderrLog", ctx.target().stderrLog().toString()
                            )
                        ));
                    }
                    if (disc.ownership() == Ownership.NONE && disc.health() == HealthState.STOPPED) {
                        return Result.err(DomainError.of(
                            "PROCESS_EXITED",
                            "Reused process for service '" + name + "' exited before becoming HEALTHY",
                            Map.of("service", name)
                        ));
                    }
                    if (disc.health() == HealthState.HEALTHY) {
                        finalDiscoveries.put(name, disc);
                        observer.onEvent(new ProgressEvent.StatusMessage(name, "Service is HEALTHY"));
                    } else {
                        stillPending.add(name);
                    }
                }
                pendingHealth = stillPending;
                if (pendingHealth.isEmpty()) {
                    break;
                }
                if (!clock.now().isBefore(deadline)) {
                    String list = String.join(", ", pendingHealth);
                    return Result.err(DomainError.of(
                        "START_TIMEOUT",
                        "Timed out after " + request.timeout().toSeconds() + "s waiting for service(s) to become HEALTHY: " + list,
                        Map.of("services", list, "timeoutSeconds", Long.toString(request.timeout().toSeconds()))
                    ));
                }
                if (!clock.sleep(DEFAULT_STARTUP_POLL_INTERVAL)) {
                    return Result.err(DomainError.of(
                        "INTERRUPTED",
                        "Interrupted while waiting for service(s) to become HEALTHY: " + String.join(", ", pendingHealth)
                    ));
                }
            }
            for (ServiceDefinition svc : targets) {
                if (!finalDiscoveries.containsKey(svc.name())) {
                    finalDiscoveries.put(svc.name(), preDiscovery.get(svc.name()).discovery());
                }
            }
        }

        List<StartServiceOutcome> outcomes = new ArrayList<>(targets.size());
        for (ServiceDefinition svc : targets) {
            DiscoveryResult disc = finalDiscoveries.get(svc.name());
            Route route = routing.routeOf(svc.name()).orElse(Route.REMOTE);
            outcomes.add(new StartServiceOutcome(svc.name(), dispositions.get(svc.name()), disc, route));
        }
        return Result.ok(new StartResult(explicitTargets, request.detach(), outcomes));
    }

    /**
     * Stops Managed Service Processes (and cleans CRASHED records), spares Unmanaged Processes,
     * kills Conflicts only with explicit target + {@code killConflict} consent, and spares Managed Gateway unless {@code --all}.
     */
    public Result<StopResult, DomainError> stop(Project project, StopRequest request) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");

        if (request.killConflict() && request.serviceNames().isEmpty()) {
            return Result.err(DomainError.of(
                "INVALID_STOP_OPTIONS",
                "--kill-conflict requires one or more explicit <service> targets",
                Map.of("flag", "--kill-conflict")
            ));
        }

        Result<List<ServiceDefinition>, DomainError> targetsRes = resolveStopTargets(project, request.serviceNames());
        if (targetsRes instanceof Result.Err<List<ServiceDefinition>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        List<ServiceDefinition> targets = ((Result.Ok<List<ServiceDefinition>, DomainError>) targetsRes).value();

        List<StopServiceOutcome> outcomes = new ArrayList<>(targets.size());
        for (ServiceDefinition svc : targets) {
            Result<DiscoveryContext, DomainError> discRes = discoverySupport.discoverService(project.name(), svc);
            if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            DiscoveryContext ctx = ((Result.Ok<DiscoveryContext, DomainError>) discRes).value();
            DiscoveryResult disc = ctx.discovery();
            StopAction action = ProcessPlanner.planStop(disc, request.killConflict());

            switch (action) {
                case KILL_MANAGED -> {
                    long pid = disc.pid().orElseThrow();
                    if (disc.health() != HealthState.CRASHED) {
                        Result<procomp.core.common.Unit, DomainError> stopRes =
                            processRunner.stopTree(pid, request.gracefulTimeout());
                        if (stopRes instanceof Result.Err<procomp.core.common.Unit, DomainError>(DomainError err)) {
                            return Result.err(err);
                        }
                    }
                    Result<procomp.core.common.Unit, DomainError> delRes =
                        stateStore.deleteManagedProcess(project.name(), ProcessKey.service(svc.name()));
                    if (delRes instanceof Result.Err<procomp.core.common.Unit, DomainError>(DomainError err)) {
                        return Result.err(err);
                    }
                    StopDisposition disp = disc.health() == HealthState.CRASHED
                        ? StopDisposition.CLEANED_CRASHED
                        : StopDisposition.STOPPED_MANAGED;
                    outcomes.add(new StopServiceOutcome(svc.name(), disp, disc.pid(), disc.provenance()));
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        svc.name(),
                        disp == StopDisposition.CLEANED_CRASHED
                            ? "Cleaned crashed Managed record (PID " + pid + ")"
                            : "Stopped Managed Process (PID " + pid + ")"
                    ));
                }
                case SPARE_UNMANAGED -> {
                    outcomes.add(new StopServiceOutcome(svc.name(), StopDisposition.SPARED_UNMANAGED, disc.pid(), disc.provenance()));
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        svc.name(),
                        "Spared Unmanaged Process" + disc.pid().map(p -> " (PID " + p + ")").orElse("")
                    ));
                }
                case SPARE_CONFLICT -> {
                    outcomes.add(new StopServiceOutcome(svc.name(), StopDisposition.SPARED_CONFLICT, disc.pid(), disc.provenance()));
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        svc.name(),
                        "Spared Conflict Process" + disc.pid().map(p -> " (PID " + p + ")").orElse("")
                    ));
                }
                case KILL_CONFLICT_WITH_CONSENT -> {
                    if (disc.pid().isEmpty()) {
                        return Result.err(DomainError.of(
                            "CONFLICT_PID_UNKNOWN",
                            "Cannot kill Conflict on service '" + svc.name() + "': owning OS PID could not be determined",
                            Map.of("service", svc.name())
                        ));
                    }
                    long pid = disc.pid().get();
                    Result<procomp.core.common.Unit, DomainError> stopRes =
                        processRunner.stopTree(pid, request.gracefulTimeout());
                    if (stopRes instanceof Result.Err<procomp.core.common.Unit, DomainError>(DomainError err)) {
                        return Result.err(err);
                    }
                    outcomes.add(new StopServiceOutcome(svc.name(), StopDisposition.KILLED_CONFLICT, Optional.of(pid), disc.provenance()));
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        svc.name(),
                        "Killed Conflict Process (PID " + pid + ") with explicit consent"
                    ));
                }
                case ALREADY_STOPPED ->
                    outcomes.add(new StopServiceOutcome(svc.name(), StopDisposition.ALREADY_STOPPED, Optional.empty(), Optional.empty()));
            }
        }

        Result<GatewayStopResult, DomainError> gwStopRes = handleGatewayOnStop(project, request);
        if (gwStopRes instanceof Result.Err<GatewayStopResult, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayStopResult gwStop = ((Result.Ok<GatewayStopResult, DomainError>) gwStopRes).value();
        return Result.ok(new StopResult(outcomes, gwStop.outcome(), gwStop.pid()));
    }

    private Result<GatewayStopResult, DomainError> handleGatewayOnStop(Project project, StopRequest request) {
        Result<Optional<ManagedProcessRecord>, DomainError> gwRecRes =
            stateStore.readManagedProcess(project.name(), ProcessKey.gateway());
        if (gwRecRes instanceof Result.Err<Optional<ManagedProcessRecord>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        Optional<ManagedProcessRecord> gwRec = ((Result.Ok<Optional<ManagedProcessRecord>, DomainError>) gwRecRes).value();

        if (!request.all()) {
            if (gwRec.isPresent() && processRunner.isAlive(gwRec.get().pid())) {
                return Result.ok(new GatewayStopResult(GatewayStopOutcome.LEFT_RUNNING, Optional.of(gwRec.get().pid())));
            }
            if (project.gateway().isPresent()) {
                Result<DiscoveryContext, DomainError> gwDiscRes = discoverySupport.discoverGateway(project.name(), project.gateway().get());
                if (gwDiscRes instanceof Result.Ok<DiscoveryContext, DomainError>(DiscoveryContext ctx)
                    && ctx.discovery().ownership() != Ownership.NONE
                    && ctx.discovery().health() != HealthState.CRASHED) {
                    return Result.ok(new GatewayStopResult(GatewayStopOutcome.LEFT_RUNNING, ctx.discovery().pid()));
                }
            }
            return Result.ok(new GatewayStopResult(GatewayStopOutcome.NOT_RUNNING, Optional.empty()));
        }

        if (gwRec.isPresent()) {
            ManagedProcessRecord rec = gwRec.get();
            boolean alive = processRunner.isAlive(rec.pid());
            if (alive) {
                Result<procomp.core.common.Unit, DomainError> stopRes =
                    processRunner.stopTree(rec.pid(), request.gracefulTimeout());
                if (stopRes instanceof Result.Err<procomp.core.common.Unit, DomainError>(DomainError err)) {
                    return Result.err(err);
                }
            }
            Result<procomp.core.common.Unit, DomainError> delRes =
                stateStore.deleteManagedProcess(project.name(), ProcessKey.gateway());
            if (delRes instanceof Result.Err<procomp.core.common.Unit, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            return Result.ok(new GatewayStopResult(
                alive ? GatewayStopOutcome.STOPPED_MANAGED : GatewayStopOutcome.CLEANED_CRASHED,
                Optional.of(rec.pid())
            ));
        }

        if (project.gateway().isPresent()) {
            Result<DiscoveryContext, DomainError> gwDiscRes = discoverySupport.discoverGateway(project.name(), project.gateway().get());
            if (gwDiscRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            DiscoveryResult disc = ((Result.Ok<DiscoveryContext, DomainError>) gwDiscRes).value().discovery();
            if (disc.ownership() == Ownership.UNMANAGED || disc.ownership() == Ownership.CONFLICT) {
                return Result.ok(new GatewayStopResult(GatewayStopOutcome.SPARED_NON_MANAGED, disc.pid()));
            }
        }
        return Result.ok(new GatewayStopResult(GatewayStopOutcome.NOT_RUNNING, Optional.empty()));
    }

    private Result<List<ServiceDefinition>, DomainError> resolveStartTargets(
        Project project,
        RoutingTable routing,
        List<String> requestedNames
    ) {
        if (requestedNames.isEmpty()) {
            List<ServiceDefinition> local = new ArrayList<>();
            for (String svcName : routing.localServices()) {
                project.service(svcName).ifPresent(local::add);
            }
            return Result.ok(local);
        }
        return resolveExplicitTargets(project, requestedNames);
    }

    private Result<List<ServiceDefinition>, DomainError> resolveStopTargets(Project project, List<String> requestedNames) {
        if (requestedNames.isEmpty()) {
            return Result.ok(List.copyOf(project.services().values()));
        }
        return resolveExplicitTargets(project, requestedNames);
    }

    private Result<List<ServiceDefinition>, DomainError> resolveExplicitTargets(
        Project project,
        List<String> requestedNames
    ) {
        Set<String> deduped = new LinkedHashSet<>();
        List<ServiceDefinition> targets = new ArrayList<>(requestedNames.size());
        for (String name : requestedNames) {
            Optional<ServiceDefinition> svc = requireService(project, name);
            if (svc.isEmpty()) {
                return Result.err(unknownServiceError(project, name));
            }
            if (deduped.add(name)) {
                targets.add(svc.get());
            }
        }
        return Result.ok(targets);
    }

    private Result<LaunchPlan, DomainError> prepareLaunchPlan(String projectName, ServiceDefinition service) {
        if (service.repository().isPresent()) {
            Path repoRoot = service.repository().get().root();
            if (!workspaceFiles.directoryExists(repoRoot)) {
                return Result.err(DomainError.of(
                    "REPOSITORY_NOT_FOUND",
                    "Local Repository directory for service '" + service.name() + "' does not exist: " + repoRoot,
                    Map.of("service", service.name(), "repository", repoRoot.toString())
                ));
            }
        }
        if (!workspaceFiles.directoryExists(service.workingDir())) {
            return Result.err(DomainError.of(
                "WORKING_DIR_NOT_FOUND",
                "Working directory for service '" + service.name() + "' does not exist: " + service.workingDir(),
                Map.of("service", service.name(), "workingDir", service.workingDir().toString())
            ));
        }
        Result<Optional<ArtifactInfo>, DomainError> artRes = discoverySupport.findLatestArtifact(service);
        if (artRes instanceof Result.Err<Optional<ArtifactInfo>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        Optional<ArtifactInfo> artifact = ((Result.Ok<Optional<ArtifactInfo>, DomainError>) artRes).value();
        Result<List<String>, DomainError> cmdRes =
            ProjectResolver.resolveServiceCommand(service, artifact.map(ArtifactInfo::path));
        if (cmdRes instanceof Result.Err<List<String>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        List<String> resolvedCmd = ((Result.Ok<List<String>, DomainError>) cmdRes).value();
        Path stdoutLog = stateStore.logPath(projectName, ProcessKey.service(service.name()), false);
        Path stderrLog = stateStore.logPath(projectName, ProcessKey.service(service.name()), true);
        SpawnRequest spawnReq = new SpawnRequest(
            resolvedCmd,
            service.workingDir(),
            service.env().asMap(),
            stdoutLog,
            stderrLog
        );
        return Result.ok(new LaunchPlan(spawnReq, artifact.map(ArtifactInfo::lastModified)));
    }

    private static DomainError conflictError(List<InspectionReport> conflicts) {
        InspectionReport first = conflicts.get(0);
        Map<String, String> details = TargetDiscoverySupport.conflictDetails(first, "service");
        if (conflicts.size() > 1) {
            details.put("allConflicts", conflicts.stream().map(InspectionReport::service).collect(Collectors.joining(", ")));
        }

        StringBuilder msg = new StringBuilder();
        msg.append("Port conflict on '").append(first.service()).append("'");
        first.port().ifPresent(p -> msg.append(" (port ").append(p).append(")"));
        first.pid().ifPresent(p -> msg.append(": occupied by PID ").append(p));
        first.commandLine().ifPresent(c -> msg.append(" [").append(c).append("]"));
        msg.append(". No processes were spawned and Routing Table was not modified. ");
        msg.append("Inspect with 'procomp inspect ").append(first.service())
            .append("' or kill with 'procomp stop ").append(first.service()).append(" --kill-conflict'.");
        return DomainError.of("PORT_CONFLICT", msg.toString(), details);
    }

    private static Optional<ServiceDefinition> requireService(Project project, String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return Optional.empty();
        }
        return project.service(serviceName);
    }

    private static DomainError unknownServiceError(Project project, String serviceName) {
        String display = serviceName == null ? "null" : serviceName;
        return DomainError.of(
            "UNKNOWN_SERVICE",
            "Unknown service '" + display + "' in project '" + project.name() + "'",
            Map.of("service", display, "project", project.name())
        );
    }

    private record LaunchPlan(SpawnRequest spawnRequest, Optional<Instant> artifactTimestamp) {}

    public record StartRequest(List<String> serviceNames, boolean detach, Duration timeout) {
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

        public StartRequest {
            serviceNames = serviceNames == null ? List.of() : List.copyOf(serviceNames);
            Objects.requireNonNull(timeout, "timeout must not be null");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must not be negative");
            }
        }
    }

    public enum StartDisposition {
        SPAWNED,
        REUSED_MANAGED,
        REUSED_UNMANAGED
    }

    public record StartServiceOutcome(
        String service,
        StartDisposition disposition,
        DiscoveryResult discovery,
        Route desiredRoute
    ) {
        public StartServiceOutcome {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("service must be non-blank");
            }
            Objects.requireNonNull(disposition, "disposition must not be null");
            Objects.requireNonNull(discovery, "discovery must not be null");
            Objects.requireNonNull(desiredRoute, "desiredRoute must not be null");
        }
    }

    public record StartResult(
        boolean explicitTargets,
        boolean detached,
        List<StartServiceOutcome> outcomes
    ) {
        public StartResult {
            outcomes = List.copyOf(outcomes);
        }
    }

    public record StopRequest(
        List<String> serviceNames,
        boolean all,
        boolean killConflict,
        Duration gracefulTimeout
    ) {
        public static final Duration DEFAULT_GRACEFUL_TIMEOUT = Duration.ofSeconds(10);

        public StopRequest {
            serviceNames = serviceNames == null ? List.of() : List.copyOf(serviceNames);
            Objects.requireNonNull(gracefulTimeout, "gracefulTimeout must not be null");
            if (gracefulTimeout.isNegative()) {
                throw new IllegalArgumentException("gracefulTimeout must not be negative");
            }
        }
    }

    public enum StopDisposition {
        STOPPED_MANAGED,
        CLEANED_CRASHED,
        SPARED_UNMANAGED,
        SPARED_CONFLICT,
        KILLED_CONFLICT,
        ALREADY_STOPPED
    }

    public record StopServiceOutcome(
        String service,
        StopDisposition disposition,
        Optional<Long> pid,
        Optional<String> provenance
    ) {
        public StopServiceOutcome {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("service must be non-blank");
            }
            Objects.requireNonNull(disposition, "disposition must not be null");
            Objects.requireNonNull(pid, "pid must not be null");
            Objects.requireNonNull(provenance, "provenance must not be null");
        }
    }

    public enum GatewayStopOutcome {
        LEFT_RUNNING,
        STOPPED_MANAGED,
        CLEANED_CRASHED,
        SPARED_NON_MANAGED,
        NOT_RUNNING
    }

    public record StopResult(
        List<StopServiceOutcome> outcomes,
        GatewayStopOutcome gatewayOutcome,
        Optional<Long> gatewayPid
    ) {
        public StopResult {
            outcomes = List.copyOf(outcomes);
            Objects.requireNonNull(gatewayOutcome, "gatewayOutcome must not be null");
            Objects.requireNonNull(gatewayPid, "gatewayPid must not be null");
        }
    }

    private record GatewayStopResult(GatewayStopOutcome outcome, Optional<Long> pid) {}
}
