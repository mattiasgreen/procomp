package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.gateway.GatewayDriftReport;
import procomp.core.gateway.GatewayEngine;
import procomp.core.gateway.GatewayPlan;
import procomp.core.gateway.GatewaySyncStatus;
import procomp.core.gateway.WireMockMapping;
import procomp.core.process.DiscoveryEngine;
import procomp.core.process.DiscoveryResult;
import procomp.core.process.HealthState;
import procomp.core.process.InspectionReport;
import procomp.core.process.Ownership;
import procomp.core.process.ProcessPlanner;
import procomp.core.process.ProcessPlanner.StartAction;
import procomp.core.process.ProcessPlanner.StopAction;
import procomp.core.project.GatewayDefinition;
import procomp.core.project.Project;
import procomp.core.routing.RoutingTable;
import procomp.shell.TargetLifecycleSupport.DiscoveryContext;
import procomp.shell.TargetLifecycleSupport.HealthWaitTarget;
import procomp.shell.ports.Clock;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.HttpClient.HttpRequestData;
import procomp.shell.ports.HttpClient.HttpResponseData;
import procomp.shell.ports.ManagedProcessRecord;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProcessRunner.SpawnRequest;
import procomp.shell.ports.ProcessRunner.SpawnedProcess;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.ProgressEvent;
import procomp.shell.ports.ProjectFiles;
import procomp.shell.ports.StateStore;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Modality-agnostic orchestrator for Gateway lifecycle ({@code start}, {@code stop}),
 * status/drift inspection, and WireMock Admin API reconciliation.
 */
public final class GatewayShell {

    public static final Duration DEFAULT_ADMIN_TIMEOUT = Duration.ofSeconds(5);

    private final StateStore stateStore;
    private final ProcessRunner processRunner;
    private final HttpClient httpClient;
    private final ProjectFiles projectFiles;
    private final Clock clock;
    private final ProgressObserver observer;
    private final RoutingShell routingShell;
    private final TargetLifecycleSupport lifecycleSupport;

    public GatewayShell(
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
        Objects.requireNonNull(portScanner, "portScanner must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.projectFiles = Objects.requireNonNull(projectFiles, "projectFiles must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.routingShell = new RoutingShell(stateStore);
        this.lifecycleSupport = new TargetLifecycleSupport(stateStore, processRunner, portScanner, httpClient, projectFiles);
    }

    /**
     * Runs Discovery on the configured Gateway port, spawns a Managed Gateway if NONE/CRASHED,
     * reuses an existing Managed/Unmanaged Gateway without duplicating, and fails fast on Conflict.
     */
    public Result<GatewayStartResult, DomainError> start(Project project, GatewayStartRequest request) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Result<GatewayDefinition, DomainError> gwRes = requireGateway(project);
        if (gwRes instanceof Result.Err<GatewayDefinition, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayDefinition gateway = ((Result.Ok<GatewayDefinition, DomainError>) gwRes).value();

        Result<DiscoveryContext, DomainError> discRes = lifecycleSupport.discoverGateway(project.name(), gateway);
        if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        DiscoveryContext preCtx = ((Result.Ok<DiscoveryContext, DomainError>) discRes).value();
        StartAction action = ProcessPlanner.planStart(preCtx.discovery());

        if (action == StartAction.FAIL_CONFLICT) {
            InspectionReport report = DiscoveryEngine.inspect(
                preCtx.target(),
                preCtx.discovery(),
                preCtx.managedRecord().map(TargetLifecycleSupport::toManagedView),
                Optional.empty(),
                Optional.empty(),
                clock.now()
            );
            return Result.err(gatewayConflictError(report));
        }

        GatewayStartDisposition disposition;
        boolean needWait;
        if (action == StartAction.SPAWN) {
            if (gateway.command().isEmpty()) {
                return Result.err(DomainError.of(
                    "GATEWAY_COMMAND_MISSING",
                    "Cannot start Managed Gateway: 'gateway.command' is not configured in procomp.json",
                    Map.of("project", project.name(), "port", Integer.toString(gateway.port()))
                ));
            }
            Path workingDir = gateway.workingDir().orElse(project.configDir());
            if (!projectFiles.directoryExists(workingDir)) {
                return Result.err(DomainError.of(
                    "WORKING_DIR_NOT_FOUND",
                    "Working directory for gateway does not exist: " + workingDir,
                    Map.of("workingDir", workingDir.toString())
                ));
            }
            Path stdoutLog = stateStore.logPath(project.name(), ProcessKey.gateway(), false);
            Path stderrLog = stateStore.logPath(project.name(), ProcessKey.gateway(), true);
            SpawnRequest spawnReq = new SpawnRequest(
                gateway.command(),
                workingDir,
                gateway.env().asMap(),
                stdoutLog,
                stderrLog
            );
            Result<SpawnedProcess, DomainError> spawnRes =
                lifecycleSupport.spawnAndRecord(project.name(), ProcessKey.gateway(), spawnReq, Optional.empty());
            if (spawnRes instanceof Result.Err<SpawnedProcess, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            SpawnedProcess spawned = ((Result.Ok<SpawnedProcess, DomainError>) spawnRes).value();
            disposition = GatewayStartDisposition.SPAWNED;
            needWait = !request.detach();
            observer.onEvent(new ProgressEvent.StatusMessage(
                "gateway",
                "Spawned Managed Gateway (PID " + spawned.pid() + ")"
            ));
        } else {
            disposition = preCtx.discovery().ownership() == Ownership.MANAGED
                ? GatewayStartDisposition.REUSED_MANAGED
                : GatewayStartDisposition.REUSED_UNMANAGED;
            needWait = action == StartAction.REUSE_WAIT && !request.detach();
            observer.onEvent(new ProgressEvent.StatusMessage(
                "gateway",
                "Reusing existing " + preCtx.discovery().ownership().name()
                    + preCtx.discovery().pid().map(p -> " Gateway (PID " + p + ", " + preCtx.discovery().health().name() + ")")
                        .orElse(" Gateway (" + preCtx.discovery().health().name() + ")")
            ));
        }

        DiscoveryResult finalDiscovery;
        if (request.detach() && action == StartAction.SPAWN) {
            Result<DiscoveryContext, DomainError> postRes = lifecycleSupport.discoverGateway(project.name(), gateway);
            if (postRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            finalDiscovery = ((Result.Ok<DiscoveryContext, DomainError>) postRes).value().discovery();
        } else if (needWait) {
            Result<Map<String, DiscoveryResult>, DomainError> waitRes = lifecycleSupport.waitUntilHealthy(
                clock,
                observer,
                request.timeout(),
                List.of(new HealthWaitTarget(
                    "gateway",
                    "target",
                    "Gateway",
                    "Gateway is HEALTHY",
                    () -> lifecycleSupport.discoverGateway(project.name(), gateway)
                ))
            );
            if (waitRes instanceof Result.Err<Map<String, DiscoveryResult>, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            finalDiscovery = ((Result.Ok<Map<String, DiscoveryResult>, DomainError>) waitRes).value().get("gateway");
        } else {
            finalDiscovery = preCtx.discovery();
        }

        return Result.ok(new GatewayStartResult(disposition, finalDiscovery, gateway, request.detach()));
    }

    /**
     * Stops a Managed Gateway Process (or cleans a CRASHED Managed record), and refuses with an explicit
     * error without killing when an Unmanaged or Conflict process occupies the Gateway port.
     */
    public Result<GatewayStopResult, DomainError> stop(Project project, GatewayStopRequest request) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Result<GatewayDefinition, DomainError> gwRes = requireGateway(project);
        if (gwRes instanceof Result.Err<GatewayDefinition, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayDefinition gateway = ((Result.Ok<GatewayDefinition, DomainError>) gwRes).value();

        Result<DiscoveryContext, DomainError> discRes = lifecycleSupport.discoverGateway(project.name(), gateway);
        if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        DiscoveryContext ctx = ((Result.Ok<DiscoveryContext, DomainError>) discRes).value();
        DiscoveryResult disc = ctx.discovery();
        StopAction action = ProcessPlanner.planStop(disc, false);

        return switch (action) {
            case ALREADY_STOPPED -> Result.ok(new GatewayStopResult(GatewayStopDisposition.ALREADY_STOPPED, Optional.empty()));
            case KILL_MANAGED -> {
                long pid = disc.pid().orElseThrow();
                boolean alive = disc.health() != HealthState.CRASHED;
                Result<GatewayStopDisposition, DomainError> stopRes =
                    stopRecordedManagedGateway(project.name(), pid, alive, request.gracefulTimeout());
                yield stopRes.map(disp -> new GatewayStopResult(disp, Optional.of(pid)));
            }
            case SPARE_UNMANAGED -> {
                String pidText = disc.pid().map(p -> " (PID " + p + ")").orElse("");
                String provText = disc.provenance().map(p -> " [" + p + "]").orElse("");
                yield Result.err(DomainError.of(
                    "GATEWAY_UNMANAGED",
                    "Cannot stop Unmanaged Gateway" + pidText + provText + ": Unmanaged Processes are never terminated.",
                    Map.of(
                        "port", Integer.toString(gateway.port()),
                        "pid", disc.pid().map(Object::toString).orElse("")
                    )
                ));
            }
            case SPARE_CONFLICT, KILL_CONFLICT_WITH_CONSENT -> {
                String pidText = disc.pid().map(p -> " (PID " + p + ")").orElse("");
                yield Result.err(DomainError.of(
                    "GATEWAY_CONFLICT",
                    "Cannot stop Conflict process occupying Gateway port " + gateway.port() + pidText
                        + ": unrecognized Conflict Processes are not terminated.",
                    Map.of(
                        "port", Integer.toString(gateway.port()),
                        "pid", disc.pid().map(Object::toString).orElse("")
                    )
                ));
            }
        };
    }

    /**
     * Observes whether any Gateway Process is currently running (used by {@link ProcessShell} when {@code all} is false).
     */
    Result<GatewayTeardownResult, DomainError> observeRunningGateway(Project project) {
        Result<Optional<ManagedProcessRecord>, DomainError> gwRecRes =
            stateStore.readManagedProcess(project.name(), ProcessKey.gateway());
        if (gwRecRes instanceof Result.Err<Optional<ManagedProcessRecord>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        Optional<ManagedProcessRecord> gwRec = ((Result.Ok<Optional<ManagedProcessRecord>, DomainError>) gwRecRes).value();
        if (gwRec.isPresent() && processRunner.isAlive(gwRec.get().pid())) {
            return Result.ok(new GatewayTeardownResult(GatewayTeardownOutcome.LEFT_RUNNING, Optional.of(gwRec.get().pid())));
        }
        if (project.gateway().isPresent()) {
            Result<DiscoveryContext, DomainError> gwDiscRes =
                lifecycleSupport.discoverGateway(project.name(), project.gateway().get());
            if (gwDiscRes instanceof Result.Ok<DiscoveryContext, DomainError>(DiscoveryContext ctx)
                && ctx.discovery().ownership() != Ownership.NONE
                && ctx.discovery().health() != HealthState.CRASHED) {
                return Result.ok(new GatewayTeardownResult(GatewayTeardownOutcome.LEFT_RUNNING, ctx.discovery().pid()));
            }
        }
        return Result.ok(new GatewayTeardownResult(GatewayTeardownOutcome.NOT_RUNNING, Optional.empty()));
    }

    /**
     * Stops a Managed Gateway Process (or cleans a CRASHED record) if present, while sparing any
     * non-Managed Gateway without failing (used by {@link ProcessShell} when {@code all} is true).
     */
    Result<GatewayTeardownResult, DomainError> stopManagedGateway(Project project, Duration gracefulTimeout) {
        Result<Optional<ManagedProcessRecord>, DomainError> gwRecRes =
            stateStore.readManagedProcess(project.name(), ProcessKey.gateway());
        if (gwRecRes instanceof Result.Err<Optional<ManagedProcessRecord>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        Optional<ManagedProcessRecord> gwRec = ((Result.Ok<Optional<ManagedProcessRecord>, DomainError>) gwRecRes).value();
        if (gwRec.isPresent()) {
            ManagedProcessRecord rec = gwRec.get();
            boolean alive = processRunner.isAlive(rec.pid());
            Result<GatewayStopDisposition, DomainError> stopRes =
                stopRecordedManagedGateway(project.name(), rec.pid(), alive, gracefulTimeout);
            return stopRes.map(disp -> new GatewayTeardownResult(
                disp == GatewayStopDisposition.STOPPED_MANAGED
                    ? GatewayTeardownOutcome.STOPPED_MANAGED
                    : GatewayTeardownOutcome.CLEANED_CRASHED,
                Optional.of(rec.pid())
            ));
        }

        if (project.gateway().isPresent()) {
            Result<DiscoveryContext, DomainError> gwDiscRes =
                lifecycleSupport.discoverGateway(project.name(), project.gateway().get());
            if (gwDiscRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            DiscoveryResult disc = ((Result.Ok<DiscoveryContext, DomainError>) gwDiscRes).value().discovery();
            if (disc.ownership() == Ownership.UNMANAGED || disc.ownership() == Ownership.CONFLICT) {
                return Result.ok(new GatewayTeardownResult(GatewayTeardownOutcome.SPARED_NON_MANAGED, disc.pid()));
            }
        }
        return Result.ok(new GatewayTeardownResult(GatewayTeardownOutcome.NOT_RUNNING, Optional.empty()));
    }

    private Result<GatewayStopDisposition, DomainError> stopRecordedManagedGateway(
        String projectName,
        long pid,
        boolean alive,
        Duration gracefulTimeout
    ) {
        Result<Unit, DomainError> stopRes = lifecycleSupport.stopManagedProcess(
            projectName, ProcessKey.gateway(), pid, alive, gracefulTimeout
        );
        if (stopRes instanceof Result.Err<Unit, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayStopDisposition disp = alive
            ? GatewayStopDisposition.STOPPED_MANAGED
            : GatewayStopDisposition.CLEANED_CRASHED;
        observer.onEvent(new ProgressEvent.StatusMessage(
            "gateway",
            disp == GatewayStopDisposition.CLEANED_CRASHED
                ? "Cleaned crashed Managed Gateway record (PID " + pid + ")"
                : "Stopped Managed Gateway (PID " + pid + ")"
        ));
        return Result.ok(disp);
    }

    /**
     * Reports read-only Gateway ownership, Health State, uptime, and live WireMock drift against the Routing Table.
     */
    public Result<GatewayStatusReport, DomainError> status(Project project) {
        Objects.requireNonNull(project, "project must not be null");

        Result<GatewayDefinition, DomainError> gwRes = requireGateway(project);
        if (gwRes instanceof Result.Err<GatewayDefinition, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayDefinition gateway = ((Result.Ok<GatewayDefinition, DomainError>) gwRes).value();

        Result<DiscoveryContext, DomainError> discRes = lifecycleSupport.discoverGateway(project.name(), gateway);
        if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        DiscoveryResult discovery = ((Result.Ok<DiscoveryContext, DomainError>) discRes).value().discovery();

        Result<RoutingTable, DomainError> routingRes = routingShell.showRoutes(project);
        if (routingRes instanceof Result.Err<RoutingTable, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        RoutingTable routing = ((Result.Ok<RoutingTable, DomainError>) routingRes).value();

        String mappingsBody = "";
        boolean healthy = discovery.health() == HealthState.HEALTHY;
        if (healthy) {
            Result<String, DomainError> fetchRes = fetchMappingsJson(gateway);
            if (fetchRes instanceof Result.Err<String, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            mappingsBody = ((Result.Ok<String, DomainError>) fetchRes).value();
        }

        Result<GatewayDriftReport, DomainError> driftRes = GatewayEngine.computeDrift(project, routing, healthy, mappingsBody);
        if (driftRes instanceof Result.Err<GatewayDriftReport, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayDriftReport drift = ((Result.Ok<GatewayDriftReport, DomainError>) driftRes).value();
        return Result.ok(new GatewayStatusReport(
            project.name(),
            gateway,
            discovery,
            discovery.uptime(clock.now()),
            drift
        ));
    }

    /**
     * Converges live WireMock mappings to match the Project's Routing Table.
     * Fails fast when the Gateway is not HEALTHY or when a Service lacks a required local port / remoteUrl.
     */
    public Result<GatewayReconcileResult, DomainError> reconcile(Project project) {
        Objects.requireNonNull(project, "project must not be null");

        Result<GatewayDefinition, DomainError> gwRes = requireGateway(project);
        if (gwRes instanceof Result.Err<GatewayDefinition, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayDefinition gateway = ((Result.Ok<GatewayDefinition, DomainError>) gwRes).value();

        Result<DiscoveryContext, DomainError> discRes = lifecycleSupport.discoverGateway(project.name(), gateway);
        if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        DiscoveryResult discovery = ((Result.Ok<DiscoveryContext, DomainError>) discRes).value().discovery();
        if (discovery.health() != HealthState.HEALTHY) {
            return Result.err(DomainError.of(
                "GATEWAY_NOT_HEALTHY",
                "Gateway is " + discovery.health().name() + " (" + discovery.ownership().name()
                    + "); Gateway must be HEALTHY before Reconciliation.",
                Map.of("health", discovery.health().name(), "owner", discovery.ownership().name())
            ));
        }

        Result<RoutingTable, DomainError> routingRes = routingShell.showRoutes(project);
        if (routingRes instanceof Result.Err<RoutingTable, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        RoutingTable routing = ((Result.Ok<RoutingTable, DomainError>) routingRes).value();

        // Build and validate entire plan before deleting or modifying any WireMock mappings
        Result<GatewayPlan, DomainError> planRes = GatewayEngine.buildPlan(project, routing);
        if (planRes instanceof Result.Err<GatewayPlan, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayPlan plan = ((Result.Ok<GatewayPlan, DomainError>) planRes).value();

        Result<String, DomainError> initialMappingsRes = fetchMappingsJson(gateway);
        if (initialMappingsRes instanceof Result.Err<String, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        String initialJson = ((Result.Ok<String, DomainError>) initialMappingsRes).value();

        Result<List<String>, DomainError> priorIdsRes = GatewayEngine.findManagedMappingIds(project, initialJson);
        if (priorIdsRes instanceof Result.Err<List<String>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        List<String> priorIds = ((Result.Ok<List<String>, DomainError>) priorIdsRes).value();

        Result<Integer, DomainError> deleteRes = deletePriorManagedMappings(gateway, priorIds);
        if (deleteRes instanceof Result.Err<Integer, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        int deletedCount = ((Result.Ok<Integer, DomainError>) deleteRes).value();

        boolean usedFallback = false;
        if (!plan.mappings().isEmpty()) {
            Result<Boolean, DomainError> applyRes = applyMappings(gateway, plan);
            if (applyRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            usedFallback = ((Result.Ok<Boolean, DomainError>) applyRes).value();
        }

        Result<String, DomainError> verifyMappingsRes = fetchMappingsJson(gateway);
        if (verifyMappingsRes instanceof Result.Err<String, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        Result<GatewayDriftReport, DomainError> driftRes = GatewayEngine.computeDrift(
            project, routing, true, ((Result.Ok<String, DomainError>) verifyMappingsRes).value()
        );
        if (driftRes instanceof Result.Err<GatewayDriftReport, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayDriftReport drift = ((Result.Ok<GatewayDriftReport, DomainError>) driftRes).value();
        if (drift.syncStatus() != GatewaySyncStatus.IN_SYNC) {
            return Result.err(DomainError.of(
                "GATEWAY_RECONCILE_FAILED",
                "Gateway mappings remain DRIFTED after reconcile (" + drift.drifted().size()
                    + " drifted, " + drift.staleMappings().size() + " stale)",
                Map.of(
                    "driftedCount", Integer.toString(drift.drifted().size()),
                    "staleCount", Integer.toString(drift.staleMappings().size())
                )
            ));
        }

        observer.onEvent(new ProgressEvent.StatusMessage(
            "gateway",
            "Reconciled " + plan.mappings().size() + " route mapping(s) on " + gateway.adminUrl()
        ));
        return Result.ok(new GatewayReconcileResult(
            project.name(),
            gateway,
            plan.mappings().size(),
            deletedCount,
            usedFallback,
            drift
        ));
    }

    private Result<String, DomainError> fetchMappingsJson(GatewayDefinition gateway) {
        URI uri = URI.create(gateway.adminUrl() + "/__admin/mappings");
        Result<HttpResponseData, DomainError> res = httpClient.get(uri, DEFAULT_ADMIN_TIMEOUT);
        if (res instanceof Result.Err<HttpResponseData, DomainError>(DomainError err)) {
            return Result.err(DomainError.of(
                "GATEWAY_ADMIN_ERROR",
                "Failed to query WireMock mappings at " + uri + ": " + err.message(),
                Map.of("uri", uri.toString())
            ));
        }
        HttpResponseData resp = ((Result.Ok<HttpResponseData, DomainError>) res).value();
        if (!resp.is2xx()) {
            return Result.err(DomainError.of(
                "GATEWAY_ADMIN_ERROR",
                "WireMock GET " + uri + " returned HTTP " + resp.statusCode(),
                Map.of("uri", uri.toString(), "status", Integer.toString(resp.statusCode()))
            ));
        }
        return Result.ok(resp.body());
    }

    private Result<Integer, DomainError> deletePriorManagedMappings(GatewayDefinition gateway, List<String> priorIds) {
        int deleted = 0;
        for (String id : priorIds) {
            String encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
            URI uri = URI.create(gateway.adminUrl() + "/__admin/mappings/" + encodedId);
            Result<HttpResponseData, DomainError> delRes = httpClient.send(
                new HttpRequestData("DELETE", uri, Map.of(), "", DEFAULT_ADMIN_TIMEOUT)
            );
            if (delRes instanceof Result.Err<HttpResponseData, DomainError>(DomainError err)) {
                return Result.err(DomainError.of(
                    "GATEWAY_ADMIN_ERROR",
                    "Failed to delete prior WireMock mapping at " + uri + ": " + err.message(),
                    Map.of("uri", uri.toString(), "mappingId", id)
                ));
            }
            HttpResponseData resp = ((Result.Ok<HttpResponseData, DomainError>) delRes).value();
            if (!resp.is2xx() && resp.statusCode() != 404) {
                return Result.err(DomainError.of(
                    "GATEWAY_ADMIN_ERROR",
                    "WireMock DELETE " + uri + " returned HTTP " + resp.statusCode(),
                    Map.of("uri", uri.toString(), "status", Integer.toString(resp.statusCode()))
                ));
            }
            if (resp.is2xx()) {
                deleted++;
            }
        }
        return Result.ok(deleted);
    }

    private Result<Boolean, DomainError> applyMappings(GatewayDefinition gateway, GatewayPlan plan) {
        URI importUri = URI.create(gateway.adminUrl() + "/__admin/mappings/import");
        String importJson = GatewayEngine.toImportPayloadJson(plan);
        Result<HttpResponseData, DomainError> importRes = httpClient.send(new HttpRequestData(
            "POST",
            importUri,
            Map.of("Content-Type", "application/json"),
            importJson,
            DEFAULT_ADMIN_TIMEOUT
        ));
        if (importRes instanceof Result.Err<HttpResponseData, DomainError>(DomainError err)) {
            return Result.err(DomainError.of(
                "GATEWAY_ADMIN_ERROR",
                "Failed to import WireMock mappings at " + importUri + ": " + err.message(),
                Map.of("uri", importUri.toString())
            ));
        }
        HttpResponseData importResp = ((Result.Ok<HttpResponseData, DomainError>) importRes).value();
        if (importResp.is2xx()) {
            return Result.ok(false);
        }
        if (importResp.statusCode() != 404) {
            return Result.err(DomainError.of(
                "GATEWAY_ADMIN_ERROR",
                "WireMock POST " + importUri + " returned HTTP " + importResp.statusCode(),
                Map.of("uri", importUri.toString(), "status", Integer.toString(importResp.statusCode()))
            ));
        }

        // Fallback for servers without /__admin/mappings/import
        URI singleUri = URI.create(gateway.adminUrl() + "/__admin/mappings");
        for (WireMockMapping mapping : plan.mappings()) {
            Result<HttpResponseData, DomainError> postRes = httpClient.send(new HttpRequestData(
                "POST",
                singleUri,
                Map.of("Content-Type", "application/json"),
                GatewayEngine.toMappingJson(mapping),
                DEFAULT_ADMIN_TIMEOUT
            ));
            if (postRes instanceof Result.Err<HttpResponseData, DomainError>(DomainError err)) {
                return Result.err(DomainError.of(
                    "GATEWAY_ADMIN_ERROR",
                    "Failed to post WireMock mapping for '" + mapping.service() + "' at " + singleUri + ": " + err.message(),
                    Map.of("uri", singleUri.toString(), "service", mapping.service())
                ));
            }
            HttpResponseData postResp = ((Result.Ok<HttpResponseData, DomainError>) postRes).value();
            if (!postResp.is2xx()) {
                return Result.err(DomainError.of(
                    "GATEWAY_ADMIN_ERROR",
                    "WireMock POST " + singleUri + " for '" + mapping.service() + "' returned HTTP " + postResp.statusCode(),
                    Map.of("uri", singleUri.toString(), "service", mapping.service(), "status", Integer.toString(postResp.statusCode()))
                ));
            }
        }
        return Result.ok(true);
    }

    private static Result<GatewayDefinition, DomainError> requireGateway(Project project) {
        if (project.gateway().isEmpty()) {
            return Result.err(DomainError.of(
                "GATEWAY_NOT_CONFIGURED",
                "No 'gateway' section configured in project '" + project.name() + "'",
                Map.of("project", project.name())
            ));
        }
        return Result.ok(project.gateway().get());
    }

    private static DomainError gatewayConflictError(InspectionReport report) {
        Map<String, String> details = TargetLifecycleSupport.conflictDetails(report, "target");
        StringBuilder msg = new StringBuilder();
        msg.append("Port conflict on gateway");
        report.port().ifPresent(p -> msg.append(" (port ").append(p).append(")"));
        report.pid().ifPresent(p -> msg.append(": occupied by PID ").append(p));
        report.commandLine().ifPresent(c -> msg.append(" [").append(c).append("]"));
        msg.append(". No processes were spawned. Stop the conflicting process or change 'gateway.port' in procomp.json.");
        return DomainError.of("PORT_CONFLICT", msg.toString(), details);
    }

    enum GatewayTeardownOutcome {
        LEFT_RUNNING,
        STOPPED_MANAGED,
        CLEANED_CRASHED,
        SPARED_NON_MANAGED,
        NOT_RUNNING
    }

    record GatewayTeardownResult(GatewayTeardownOutcome outcome, Optional<Long> pid) {}

    public record GatewayStartRequest(boolean detach, Duration timeout) {
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

        public GatewayStartRequest {
            Objects.requireNonNull(timeout, "timeout must not be null");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must not be negative");
            }
        }
    }

    public enum GatewayStartDisposition {
        SPAWNED,
        REUSED_MANAGED,
        REUSED_UNMANAGED
    }

    public record GatewayStartResult(
        GatewayStartDisposition disposition,
        DiscoveryResult discovery,
        GatewayDefinition gateway,
        boolean detached
    ) {
        public GatewayStartResult {
            Objects.requireNonNull(disposition, "disposition must not be null");
            Objects.requireNonNull(discovery, "discovery must not be null");
            Objects.requireNonNull(gateway, "gateway must not be null");
        }
    }

    public record GatewayStopRequest(Duration gracefulTimeout) {
        public static final Duration DEFAULT_GRACEFUL_TIMEOUT = Duration.ofSeconds(10);

        public GatewayStopRequest {
            Objects.requireNonNull(gracefulTimeout, "gracefulTimeout must not be null");
            if (gracefulTimeout.isNegative()) {
                throw new IllegalArgumentException("gracefulTimeout must not be negative");
            }
        }
    }

    public enum GatewayStopDisposition {
        STOPPED_MANAGED,
        CLEANED_CRASHED,
        ALREADY_STOPPED
    }

    public record GatewayStopResult(GatewayStopDisposition disposition, Optional<Long> pid) {
        public GatewayStopResult {
            Objects.requireNonNull(disposition, "disposition must not be null");
            Objects.requireNonNull(pid, "pid must not be null");
        }
    }

    public record GatewayStatusReport(
        String projectName,
        GatewayDefinition gateway,
        DiscoveryResult discovery,
        Optional<Duration> uptime,
        GatewayDriftReport drift
    ) {
        public GatewayStatusReport {
            if (projectName == null || projectName.isBlank()) {
                throw new IllegalArgumentException("projectName must be non-blank");
            }
            Objects.requireNonNull(gateway, "gateway must not be null");
            Objects.requireNonNull(discovery, "discovery must not be null");
            Objects.requireNonNull(uptime, "uptime must not be null");
            Objects.requireNonNull(drift, "drift must not be null");
        }
    }

    public record GatewayReconcileResult(
        String projectName,
        GatewayDefinition gateway,
        int appliedMappings,
        int deletedPriorMappings,
        boolean usedSingleMappingFallback,
        GatewayDriftReport drift
    ) {
        public GatewayReconcileResult {
            if (projectName == null || projectName.isBlank()) {
                throw new IllegalArgumentException("projectName must be non-blank");
            }
            Objects.requireNonNull(gateway, "gateway must not be null");
            Objects.requireNonNull(drift, "drift must not be null");
        }
    }
}
