package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.process.DiscoveryResult;
import procomp.core.process.HealthState;
import procomp.core.process.InspectionReport;
import procomp.core.process.Ownership;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.core.project.ServiceDefinition;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.ProcessLifecycle.DiscoveryContext;
import procomp.shell.ProcessLifecycle.LaunchPlan;
import procomp.shell.ProcessLifecycle.StartTargetSpec;
import procomp.shell.ProcessLifecycle.TargetStartOutcome;
import procomp.shell.ProcessLifecycle.TargetStopOutcome;
import procomp.shell.ports.Clock;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProcessRunner.SpawnRequest;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProjectFiles;
import procomp.shell.ports.ProjectFiles.ArtifactInfo;
import procomp.shell.ports.StateStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Modality-agnostic orchestrator for read-only Service Discovery, Inspection, and Process lifecycle.
 * Composes across {@code process} and {@code routing} domains strictly inside the shell layer.
 */
public final class ProcessShell {

    public static final Duration DEFAULT_HEALTH_CHECK_TIMEOUT = ProcessLifecycle.DEFAULT_HEALTH_CHECK_TIMEOUT;
    public static final Duration DEFAULT_HEALTH_CHECK_INTERVAL = ProcessLifecycle.DEFAULT_HEALTH_CHECK_INTERVAL;

    private final ProjectFiles projectFiles;
    private final RoutingShell routingShell;
    private final GatewayShell gatewayShell;
    private final ProcessLifecycle lifecycle;

    public ProcessShell(
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        ProjectFiles projectFiles,
        Clock clock,
        ProgressObserver observer
    ) {
        this(
            projectFiles,
            new RoutingShell(stateStore),
            new ProcessLifecycle(stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer),
            httpClient,
            clock,
            observer
        );
    }

    private ProcessShell(
        ProjectFiles projectFiles,
        RoutingShell routingShell,
        ProcessLifecycle lifecycle,
        HttpClient httpClient,
        Clock clock,
        ProgressObserver observer
    ) {
        this(
            projectFiles,
            routingShell,
            new GatewayShell(httpClient, projectFiles, clock, observer, routingShell, lifecycle),
            lifecycle
        );
    }

    ProcessShell(
        ProjectFiles projectFiles,
        RoutingShell routingShell,
        GatewayShell gatewayShell,
        ProcessLifecycle lifecycle
    ) {
        this.projectFiles = Objects.requireNonNull(projectFiles, "projectFiles must not be null");
        this.routingShell = Objects.requireNonNull(routingShell, "routingShell must not be null");
        this.gatewayShell = Objects.requireNonNull(gatewayShell, "gatewayShell must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
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
        return lifecycle.discoverService(project.name(), svcOpt.get()).map(DiscoveryContext::discovery);
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

        Optional<String> liveGatewayRoute = Optional.empty();
        if (project.gateway().isPresent()) {
            Result<GatewayShell.GatewayStatusReport, DomainError> gwStatusRes = gatewayShell.status(project, routing);
            if (gwStatusRes instanceof Result.Err<GatewayShell.GatewayStatusReport, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            GatewayShell.GatewayStatusReport gwStatus =
                ((Result.Ok<GatewayShell.GatewayStatusReport, DomainError>) gwStatusRes).value();
            liveGatewayRoute = Optional.ofNullable(gwStatus.drift().liveRoutes().get(service.name()));
        }

        Optional<String> resolvedLiveGatewayRoute = liveGatewayRoute;
        return lifecycle.discoverService(project.name(), service)
            .map(ctx -> lifecycle.inspect(ctx, desiredRoute, resolvedLiveGatewayRoute));
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

        List<StartTargetSpec> specs = new ArrayList<>(targets.size());
        for (ServiceDefinition svc : targets) {
            specs.add(new StartTargetSpec(
                svc.name(),
                ProcessKey.service(svc.name()),
                "service",
                "Service '" + svc.name() + "'",
                "Process",
                "Service is HEALTHY",
                routing.routeOf(svc.name()).map(Route::name),
                () -> lifecycle.discoverService(project.name(), svc),
                () -> prepareLaunchPlan(project.name(), svc)
            ));
        }

        Result<List<TargetStartOutcome>, DomainError> startRes =
            lifecycle.startTargets(project.name(), specs, request.detach(), request.timeout());
        if (startRes instanceof Result.Err<List<TargetStartOutcome>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        List<TargetStartOutcome> rawOutcomes = ((Result.Ok<List<TargetStartOutcome>, DomainError>) startRes).value();
        List<StartServiceOutcome> outcomes = new ArrayList<>(rawOutcomes.size());
        for (TargetStartOutcome raw : rawOutcomes) {
            StartDisposition disp = switch (raw.disposition()) {
                case SPAWNED -> StartDisposition.SPAWNED;
                case REUSED_MANAGED -> StartDisposition.REUSED_MANAGED;
                case REUSED_UNMANAGED -> StartDisposition.REUSED_UNMANAGED;
            };
            Route route = routing.routeOf(raw.id()).orElse(Route.REMOTE);
            outcomes.add(new StartServiceOutcome(raw.id(), disp, raw.discovery(), route));
        }
        return Result.ok(new StartResult(explicitTargets, request.detach(), outcomes));
    }

    /**
     * Stops Managed Service Processes (and cleans CRASHED records), spares Unmanaged Processes,
     * kills Conflicts only with explicit target + {@code killConflict} consent, and spares Managed Gateway unless {@code all} is true.
     */
    public Result<StopResult, DomainError> stop(Project project, StopRequest request) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");

        if (request.killConflict() && request.serviceNames().isEmpty()) {
            return Result.err(DomainError.of(
                "INVALID_STOP_OPTIONS",
                "Killing a Conflict Process requires one or more explicit target Services",
                Map.of("reason", "killConflictRequiresExplicitService")
            ));
        }

        Result<List<ServiceDefinition>, DomainError> targetsRes = resolveStopTargets(project, request.serviceNames());
        if (targetsRes instanceof Result.Err<List<ServiceDefinition>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        List<ServiceDefinition> targets = ((Result.Ok<List<ServiceDefinition>, DomainError>) targetsRes).value();

        List<StopServiceOutcome> outcomes = new ArrayList<>(targets.size());
        for (ServiceDefinition svc : targets) {
            Result<TargetStopOutcome, DomainError> stopRes =
                lifecycle.stopService(project.name(), svc, request.killConflict(), request.gracefulTimeout());
            if (stopRes instanceof Result.Err<TargetStopOutcome, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            TargetStopOutcome outcome = ((Result.Ok<TargetStopOutcome, DomainError>) stopRes).value();
            DiscoveryResult disc = outcome.discovery();
            StopDisposition disp = switch (outcome.disposition()) {
                case STOPPED_MANAGED -> StopDisposition.STOPPED_MANAGED;
                case CLEANED_CRASHED -> StopDisposition.CLEANED_CRASHED;
                case SPARED_UNMANAGED -> StopDisposition.SPARED_UNMANAGED;
                case SPARED_CONFLICT -> StopDisposition.SPARED_CONFLICT;
                case KILLED_CONFLICT -> StopDisposition.KILLED_CONFLICT;
                case ALREADY_STOPPED -> StopDisposition.ALREADY_STOPPED;
            };
            outcomes.add(new StopServiceOutcome(
                svc.name(),
                disp,
                disp == StopDisposition.ALREADY_STOPPED ? Optional.empty() : disc.pid(),
                disp == StopDisposition.ALREADY_STOPPED ? Optional.empty() : disc.provenance()
            ));
        }

        if (request.all()) {
            Result<TargetStopOutcome, DomainError> gwStopRes =
                lifecycle.stopGateway(project.name(), project.gateway(), request.gracefulTimeout());
            if (gwStopRes instanceof Result.Err<TargetStopOutcome, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            TargetStopOutcome gwStop = ((Result.Ok<TargetStopOutcome, DomainError>) gwStopRes).value();
            GatewayStopOutcome gwOutcome = switch (gwStop.disposition()) {
                case STOPPED_MANAGED -> GatewayStopOutcome.STOPPED_MANAGED;
                case CLEANED_CRASHED -> GatewayStopOutcome.CLEANED_CRASHED;
                case SPARED_UNMANAGED, SPARED_CONFLICT -> GatewayStopOutcome.SPARED_NON_MANAGED;
                case ALREADY_STOPPED -> GatewayStopOutcome.NOT_RUNNING;
                case KILLED_CONFLICT -> throw new IllegalStateException("Gateway conflict kill is never requested");
            };
            Optional<Long> gwPid = gwOutcome == GatewayStopOutcome.NOT_RUNNING
                ? Optional.empty()
                : gwStop.discovery().pid();
            return Result.ok(new StopResult(outcomes, gwOutcome, gwPid));
        }

        Result<DiscoveryContext, DomainError> gwDiscRes =
            lifecycle.discoverGateway(project.name(), project.gateway());
        if (gwDiscRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        DiscoveryResult gwDisc = ((Result.Ok<DiscoveryContext, DomainError>) gwDiscRes).value().discovery();
        if (gwDisc.ownership() != Ownership.NONE && gwDisc.health() != HealthState.CRASHED) {
            return Result.ok(new StopResult(outcomes, GatewayStopOutcome.LEFT_RUNNING, gwDisc.pid()));
        }
        return Result.ok(new StopResult(outcomes, GatewayStopOutcome.NOT_RUNNING, Optional.empty()));
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
            if (!projectFiles.directoryExists(repoRoot)) {
                return Result.err(DomainError.of(
                    "REPOSITORY_NOT_FOUND",
                    "Local Repository directory for service '" + service.name() + "' does not exist: " + repoRoot,
                    Map.of("service", service.name(), "repository", repoRoot.toString())
                ));
            }
        }
        if (!projectFiles.directoryExists(service.workingDir())) {
            return Result.err(DomainError.of(
                "WORKING_DIR_NOT_FOUND",
                "Working directory for service '" + service.name() + "' does not exist: " + service.workingDir(),
                Map.of("service", service.name(), "workingDir", service.workingDir().toString())
            ));
        }
        Result<Optional<ArtifactInfo>, DomainError> artRes = lifecycle.findLatestArtifact(service);
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
        Path stdoutLog = lifecycle.logPath(projectName, ProcessKey.service(service.name()), false);
        Path stderrLog = lifecycle.logPath(projectName, ProcessKey.service(service.name()), true);
        SpawnRequest spawnReq = new SpawnRequest(
            resolvedCmd,
            service.workingDir(),
            service.env().asMap(),
            stdoutLog,
            stderrLog
        );
        return Result.ok(new LaunchPlan(spawnReq, artifact.map(ArtifactInfo::lastModified)));
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
}
