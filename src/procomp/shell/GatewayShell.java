package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.gateway.GatewayDriftReport;
import procomp.core.gateway.GatewayEngine;
import procomp.core.gateway.GatewayPlan;
import procomp.core.gateway.GatewaySyncStatus;
import procomp.core.gateway.WireMockMapping;
import procomp.core.process.DiscoveryResult;
import procomp.core.process.HealthState;
import procomp.core.project.GatewayDefinition;
import procomp.core.project.Project;
import procomp.core.routing.RoutingTable;
import procomp.shell.ProcessLifecycle.DiscoveryContext;
import procomp.shell.ProcessLifecycle.LaunchPlan;
import procomp.shell.ProcessLifecycle.StartTargetSpec;
import procomp.shell.ProcessLifecycle.TargetRestartOutcome;
import procomp.shell.ProcessLifecycle.TargetStartOutcome;
import procomp.shell.ProcessLifecycle.TargetStopOutcome;
import procomp.shell.ports.Clock;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.HttpClient.HttpRequestData;
import procomp.shell.ports.HttpClient.HttpResponseData;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProcessRunner.SpawnRequest;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.DiagnosticCategory;
import procomp.shell.ports.ProgressObserver.ProgressEvent;
import procomp.shell.ports.ProjectFiles;
import procomp.shell.ports.StateStore;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

    private final HttpClient httpClient;
    private final ProjectFiles projectFiles;
    private final Clock clock;
    private final ProgressObserver observer;
    private final RoutingShell routingShell;
    private final ProcessLifecycle lifecycle;

    public GatewayShell(
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        ProjectFiles projectFiles,
        Clock clock,
        ProgressObserver observer
    ) {
        this(
            httpClient,
            projectFiles,
            clock,
            observer,
            new RoutingShell(stateStore),
            new ProcessLifecycle(stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer)
        );
    }

    GatewayShell(
        HttpClient httpClient,
        ProjectFiles projectFiles,
        Clock clock,
        ProgressObserver observer,
        RoutingShell routingShell,
        ProcessLifecycle lifecycle
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.projectFiles = Objects.requireNonNull(projectFiles, "projectFiles must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.routingShell = Objects.requireNonNull(routingShell, "routingShell must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
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

        StartTargetSpec spec = new StartTargetSpec(
            "gateway",
            ProcessKey.gateway(),
            "target",
            "Gateway",
            "Gateway",
            "Gateway is HEALTHY",
            Optional.empty(),
            () -> lifecycle.discoverGateway(project.name(), gateway),
            () -> prepareGatewayLaunchPlan(project, gateway)
        );

        Result<List<TargetStartOutcome>, DomainError> startRes =
            lifecycle.startTargets(project.name(), List.of(spec), request.detach(), request.timeout());
        if (startRes instanceof Result.Err<List<TargetStartOutcome>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        TargetStartOutcome outcome = ((Result.Ok<List<TargetStartOutcome>, DomainError>) startRes).value().get(0);
        GatewayStartDisposition disposition = switch (outcome.disposition()) {
            case SPAWNED -> GatewayStartDisposition.SPAWNED;
            case REUSED_MANAGED -> GatewayStartDisposition.REUSED_MANAGED;
            case REUSED_UNMANAGED -> GatewayStartDisposition.REUSED_UNMANAGED;
        };
        return Result.ok(new GatewayStartResult(disposition, outcome.discovery(), gateway, request.detach()));
    }

    /**
     * Restarts a Managed Gateway Process (or starts it when Stopped/CRASHED), spares an Unmanaged Gateway,
     * and fails fast on Conflict or STOPPING. Validates launch plan before stopping. Never mutates the Routing Table.
     */
    public Result<GatewayRestartResult, DomainError> restart(Project project, GatewayRestartRequest request) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Result<GatewayDefinition, DomainError> gwRes = requireGateway(project);
        if (gwRes instanceof Result.Err<GatewayDefinition, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayDefinition gateway = ((Result.Ok<GatewayDefinition, DomainError>) gwRes).value();

        StartTargetSpec spec = new StartTargetSpec(
            "gateway",
            ProcessKey.gateway(),
            "target",
            "Gateway",
            "Gateway",
            "Gateway is HEALTHY",
            Optional.empty(),
            () -> lifecycle.discoverGateway(project.name(), gateway),
            () -> prepareGatewayLaunchPlan(project, gateway)
        );

        Result<List<TargetRestartOutcome>, DomainError> restartRes = lifecycle.restartTargets(
            project.name(),
            List.of(spec),
            request.detach(),
            request.timeout(),
            request.gracefulTimeout()
        );
        if (restartRes instanceof Result.Err<List<TargetRestartOutcome>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        TargetRestartOutcome outcome = ((Result.Ok<List<TargetRestartOutcome>, DomainError>) restartRes).value().get(0);
        GatewayRestartDisposition disposition = switch (outcome.disposition()) {
            case RESTARTED -> GatewayRestartDisposition.RESTARTED;
            case SPAWNED -> GatewayRestartDisposition.SPAWNED;
            case SPARED_UNMANAGED -> GatewayRestartDisposition.SPARED_UNMANAGED;
        };
        return Result.ok(new GatewayRestartResult(disposition, outcome.discovery(), gateway, request.detach()));
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

        Result<TargetStopOutcome, DomainError> stopRes =
            lifecycle.stopGateway(project.name(), Optional.of(gateway), request.gracefulTimeout());
        if (stopRes instanceof Result.Err<TargetStopOutcome, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        TargetStopOutcome outcome = ((Result.Ok<TargetStopOutcome, DomainError>) stopRes).value();
        DiscoveryResult disc = outcome.discovery();

        return switch (outcome.disposition()) {
            case ALREADY_STOPPED -> Result.ok(new GatewayStopResult(GatewayStopDisposition.ALREADY_STOPPED, Optional.empty()));
            case STOPPED_MANAGED -> Result.ok(new GatewayStopResult(GatewayStopDisposition.STOPPED_MANAGED, disc.pid()));
            case CLEANED_CRASHED -> Result.ok(new GatewayStopResult(GatewayStopDisposition.CLEANED_CRASHED, disc.pid()));
            case SPARED_UNMANAGED -> {
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
            case SPARED_CONFLICT, KILLED_CONFLICT -> {
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
     * Reports read-only Gateway ownership, Health State, uptime, and live WireMock drift against the Routing Table.
     */
    public Result<GatewayStatusReport, DomainError> status(Project project) {
        Objects.requireNonNull(project, "project must not be null");

        Result<GatewayDefinition, DomainError> gwRes = requireGateway(project);
        if (gwRes instanceof Result.Err<GatewayDefinition, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        Result<RoutingTable, DomainError> routingRes = routingShell.showRoutes(project);
        if (routingRes instanceof Result.Err<RoutingTable, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        return status(project, ((Result.Ok<RoutingTable, DomainError>) routingRes).value());
    }

    /**
     * Package-private overload reusing an already-loaded {@link RoutingTable} so callers such as
     * {@link StatusShell} and {@link ProcessShell#inspect} read the Routing Table only once.
     */
    Result<GatewayStatusReport, DomainError> status(Project project, RoutingTable routing) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(routing, "routing must not be null");

        Result<GatewayDefinition, DomainError> gwRes = requireGateway(project);
        if (gwRes instanceof Result.Err<GatewayDefinition, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayDefinition gateway = ((Result.Ok<GatewayDefinition, DomainError>) gwRes).value();

        Result<DiscoveryContext, DomainError> discRes = lifecycle.discoverGateway(project.name(), gateway);
        if (discRes instanceof Result.Err<DiscoveryContext, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        DiscoveryResult discovery = ((Result.Ok<DiscoveryContext, DomainError>) discRes).value().discovery();

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

        Result<DiscoveryContext, DomainError> discRes = lifecycle.discoverGateway(project.name(), gateway);
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

    private Result<LaunchPlan, DomainError> prepareGatewayLaunchPlan(Project project, GatewayDefinition gateway) {
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
        Path stdoutLog = lifecycle.logPath(project.name(), ProcessKey.gateway(), false);
        Path stderrLog = lifecycle.logPath(project.name(), ProcessKey.gateway(), true);
        SpawnRequest spawnReq = new SpawnRequest(
            gateway.command(),
            workingDir,
            gateway.env().asMap(),
            stdoutLog,
            stderrLog
        );
        return Result.ok(new LaunchPlan(spawnReq, Optional.empty()));
    }

    private Result<String, DomainError> fetchMappingsJson(GatewayDefinition gateway) {
        URI uri = URI.create(gateway.adminUrl() + "/__admin/mappings");
        Instant start = clock.now();
        Result<HttpResponseData, DomainError> res = httpClient.get(uri, DEFAULT_ADMIN_TIMEOUT);
        long elapsedMs = Math.max(0L, Duration.between(start, clock.now()).toMillis());
        if (res instanceof Result.Err<HttpResponseData, DomainError>(DomainError err)) {
            observer.diagnostic(
                DiagnosticCategory.GATEWAY, "GET " + uri + " -> FAILED (" + err.message() + ", " + elapsedMs + "ms)"
            );
            return Result.err(DomainError.of(
                "GATEWAY_ADMIN_ERROR",
                "Failed to query WireMock mappings at " + uri + ": " + err.message(),
                Map.of("uri", uri.toString())
            ));
        }
        HttpResponseData resp = ((Result.Ok<HttpResponseData, DomainError>) res).value();
        observer.diagnostic(
            DiagnosticCategory.GATEWAY, "GET " + uri + " -> HTTP " + resp.statusCode() + " (" + elapsedMs + "ms)"
        );
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
                observer.diagnostic(
                    DiagnosticCategory.GATEWAY, "DELETE " + uri + " -> FAILED (" + err.message() + ")"
                );
                return Result.err(DomainError.of(
                    "GATEWAY_ADMIN_ERROR",
                    "Failed to delete prior WireMock mapping at " + uri + ": " + err.message(),
                    Map.of("uri", uri.toString(), "mappingId", id)
                ));
            }
            HttpResponseData resp = ((Result.Ok<HttpResponseData, DomainError>) delRes).value();
            observer.diagnostic(
                DiagnosticCategory.GATEWAY, "DELETE " + uri + " -> HTTP " + resp.statusCode()
            );
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
        Instant start = clock.now();
        Result<HttpResponseData, DomainError> importRes = httpClient.send(new HttpRequestData(
            "POST",
            importUri,
            Map.of("Content-Type", "application/json"),
            importJson,
            DEFAULT_ADMIN_TIMEOUT
        ));
        long elapsedMs = Math.max(0L, Duration.between(start, clock.now()).toMillis());
        if (importRes instanceof Result.Err<HttpResponseData, DomainError>(DomainError err)) {
            observer.diagnostic(
                DiagnosticCategory.GATEWAY, "POST " + importUri + " (" + plan.mappings().size() + " mappings) -> FAILED (" + err.message() + ", " + elapsedMs + "ms)"
            );
            return Result.err(DomainError.of(
                "GATEWAY_ADMIN_ERROR",
                "Failed to import WireMock mappings at " + importUri + ": " + err.message(),
                Map.of("uri", importUri.toString())
            ));
        }
        HttpResponseData importResp = ((Result.Ok<HttpResponseData, DomainError>) importRes).value();
        observer.diagnostic(
            DiagnosticCategory.GATEWAY, "POST " + importUri + " (" + plan.mappings().size() + " mappings) -> HTTP " + importResp.statusCode() + " (" + elapsedMs + "ms)"
        );
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
                observer.diagnostic(
                    DiagnosticCategory.GATEWAY, "POST " + singleUri + " (" + mapping.service() + ") -> FAILED (" + err.message() + ")"
                );
                return Result.err(DomainError.of(
                    "GATEWAY_ADMIN_ERROR",
                    "Failed to post WireMock mapping for '" + mapping.service() + "' at " + singleUri + ": " + err.message(),
                    Map.of("uri", singleUri.toString(), "service", mapping.service())
                ));
            }
            HttpResponseData postResp = ((Result.Ok<HttpResponseData, DomainError>) postRes).value();
            observer.diagnostic(
                DiagnosticCategory.GATEWAY, "POST " + singleUri + " (" + mapping.service() + ") -> HTTP " + postResp.statusCode()
            );
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

    public record GatewayRestartRequest(boolean detach, Duration timeout, Duration gracefulTimeout) {
        public static final Duration DEFAULT_TIMEOUT = GatewayStartRequest.DEFAULT_TIMEOUT;
        public static final Duration DEFAULT_GRACEFUL_TIMEOUT = GatewayStopRequest.DEFAULT_GRACEFUL_TIMEOUT;

        public GatewayRestartRequest(boolean detach, Duration timeout) {
            this(detach, timeout, DEFAULT_GRACEFUL_TIMEOUT);
        }

        public GatewayRestartRequest {
            Objects.requireNonNull(timeout, "timeout must not be null");
            Objects.requireNonNull(gracefulTimeout, "gracefulTimeout must not be null");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must not be negative");
            }
            if (gracefulTimeout.isNegative()) {
                throw new IllegalArgumentException("gracefulTimeout must not be negative");
            }
        }
    }

    public enum GatewayRestartDisposition {
        RESTARTED,
        SPAWNED,
        SPARED_UNMANAGED
    }

    public record GatewayRestartResult(
        GatewayRestartDisposition disposition,
        DiscoveryResult discovery,
        GatewayDefinition gateway,
        boolean detached
    ) {
        public GatewayRestartResult {
            Objects.requireNonNull(disposition, "disposition must not be null");
            Objects.requireNonNull(discovery, "discovery must not be null");
            Objects.requireNonNull(gateway, "gateway must not be null");
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
