package procomp.web;

import procomp.core.build.ObservedBuildRun;
import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.process.InspectionReport;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.Project;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.BuildShell;
import procomp.shell.BuildShell.BuildRequest;
import procomp.shell.BuildShell.CancelResult;
import procomp.shell.CliCommandFormatter;
import procomp.shell.DomainJson;
import procomp.shell.GatewayShell;
import procomp.shell.GatewayShell.GatewayReconcileResult;
import procomp.shell.GatewayShell.GatewayStartRequest;
import procomp.shell.GatewayShell.GatewayStartResult;
import procomp.shell.GatewayShell.GatewayStopRequest;
import procomp.shell.GatewayShell.GatewayStopResult;
import procomp.shell.GitShell;
import procomp.shell.LogsShell;
import procomp.shell.LogsShell.GatewayLogsRequest;
import procomp.shell.LogsShell.LogsRequest;
import procomp.shell.LogsShell.LogsResult;
import procomp.shell.ProcessShell;
import procomp.shell.ProcessShell.StartRequest;
import procomp.shell.ProcessShell.StartResult;
import procomp.shell.ProcessShell.StopRequest;
import procomp.shell.ProcessShell.StopResult;
import procomp.shell.ProcompRuntime;
import procomp.shell.ProfileConfigShell;
import procomp.shell.ProfileConfigShell.DeleteProfileResult;
import procomp.shell.ProfileConfigShell.SaveProfileResult;
import procomp.shell.ProjectLoader.LoadedProject;
import procomp.shell.RoutingShell;
import procomp.shell.StatusShell;
import procomp.shell.StatusShell.EstateStatus;
import procomp.shell.ports.ProgressObserver.ProgressEvent;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HTTP API router for the Web UI modality, decoupled from {@code com.sun.net.httpserver.HttpExchange}
 * via the {@link ApiRequest} / {@link ApiResponse} / {@link SseSink} seam for deterministic Level 2 testing.
 *
 * <p>Enforces localhost CSRF protection on mutating requests, serializes state-file mutations with a
 * lightweight per-Project {@link ReentrantLock}, and echoes verbatim CLI commands from
 * {@link CliCommandFormatter} on every response.</p>
 */
public final class ApiRouter {

    public static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private static final String SERVICES_PREFIX = "/api/services/";
    private static final String PROFILES_PREFIX = "/api/profiles/";
    private static final String GIT_BRANCHES_PREFIX = "/api/git/branches/";

    private final ProcompRuntime runtime;
    private final LiveProjectHolder projectHolder;
    private final CliCommandFormatter cli;
    private final int boundPort;
    private final Duration logsPollInterval;
    private final ReentrantLock mutationLock = new ReentrantLock();
    private final ConcurrentHashMap<String, Boolean> launchingBuilds = new ConcurrentHashMap<>();
    private final Executor buildExecutor;

    public ApiRouter(ProcompRuntime runtime, LiveProjectHolder projectHolder, int boundPort) {
        this(runtime, projectHolder, boundPort, LogsShell.DEFAULT_POLL_INTERVAL);
    }

    public ApiRouter(
        ProcompRuntime runtime,
        LiveProjectHolder projectHolder,
        int boundPort,
        Duration logsPollInterval
    ) {
        this(runtime, projectHolder, boundPort, logsPollInterval, defaultBuildExecutor());
    }

    public ApiRouter(
        ProcompRuntime runtime,
        LiveProjectHolder projectHolder,
        int boundPort,
        Duration logsPollInterval,
        Executor buildExecutor
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.projectHolder = Objects.requireNonNull(projectHolder, "projectHolder must not be null");
        this.cli = projectHolder.configOverride()
            .map(CliCommandFormatter::withConfig)
            .orElseGet(CliCommandFormatter::plain);
        if (boundPort <= 0 || boundPort > 65535) {
            throw new IllegalArgumentException("boundPort must be in 1..65535: " + boundPort);
        }
        this.boundPort = boundPort;
        this.logsPollInterval = Objects.requireNonNull(logsPollInterval, "logsPollInterval must not be null");
        this.buildExecutor = Objects.requireNonNull(buildExecutor, "buildExecutor must not be null");
    }

    private static Executor defaultBuildExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "procomp-web-build");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Returns {@code true} when the given HTTP method and path target an SSE streaming endpoint.
     */
    public boolean isSseEndpoint(String method, String path) {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(path, "path must not be null");
        if ("/api/services/start/stream".equals(path)) {
            return "POST".equalsIgnoreCase(method);
        }
        if ("GET".equalsIgnoreCase(method)) {
            return "/api/gateway/logs/stream".equals(path)
                || extractSegment(path, SERVICES_PREFIX, "/logs/stream").isPresent();
        }
        return false;
    }

    /**
     * Pre-flight validation for SSE endpoints before HTTP {@code 200 OK} streaming headers are sent.
     * Enforces loopback {@code Host} validation on all SSE streams and full CSRF protection on
     * mutating SSE streams ({@code POST /api/services/start/stream}).
     */
    public Optional<ApiResponse> preflightSse(ApiRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if ("/api/services/start/stream".equals(request.path())) {
            Optional<DomainError> csrfErr = checkCsrf(request);
            if (csrfErr.isPresent()) {
                return Optional.of(errorResponse(403, csrfErr.get(), ""));
            }
            return Optional.empty();
        }
        Optional<DomainError> hostErr = checkHost(request);
        if (hostErr.isPresent()) {
            return Optional.of(errorResponse(403, hostErr.get(), ""));
        }
        return Optional.empty();
    }

    /**
     * Handles a non-SSE {@code /api/*} request and returns a typed {@link ApiResponse}.
     */
    public ApiResponse handle(ApiRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String method = request.method();
        String path = request.path();

        if (isMutatingMethod(method)) {
            Optional<DomainError> csrfErr = checkCsrf(request);
            if (csrfErr.isPresent()) {
                return errorResponse(403, csrfErr.get(), "");
            }
        } else {
            Optional<DomainError> hostErr = checkHost(request);
            if (hostErr.isPresent()) {
                return errorResponse(403, hostErr.get(), "");
            }
        }

        if ("/api/status".equals(path)) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method, "GET");
            }
            return handleStatus();
        }

        if ("/api/routes".equals(path)) {
            if (!"PUT".equals(method)) {
                return methodNotAllowed(method, "PUT");
            }
            return handleUpdateRoutes(request);
        }

        if ("/api/services/start".equals(path)) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleStartServices(request);
        }

        if ("/api/services/start/stream".equals(path)) {
            return methodNotAllowed(method, "POST");
        }

        if ("/api/services/stop".equals(path)) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleStopServices(request);
        }

        if ("/api/gateway/start".equals(path)) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleGatewayStart(request);
        }

        if ("/api/gateway/stop".equals(path)) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleGatewayStop();
        }

        if ("/api/gateway/reconcile".equals(path)) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleGatewayReconcile();
        }

        if ("/api/gateway/logs".equals(path)) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method, "GET");
            }
            return handleGatewayLogs(request.rawQuery());
        }

        if ("/api/gateway/logs/stream".equals(path)) {
            return methodNotAllowed(method, "GET");
        }

        if ("/api/git/status".equals(path)) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method, "GET");
            }
            return handleGitStatus(request.rawQuery());
        }

        if (path.startsWith(GIT_BRANCHES_PREFIX) && path.length() > GIT_BRANCHES_PREFIX.length()) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method, "GET");
            }
            return handleGitBranches(path.substring(GIT_BRANCHES_PREFIX.length()));
        }

        if ("/api/git/clone".equals(path)) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleGitBatchOp("clone", request);
        }

        if ("/api/git/fetch".equals(path)) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleGitBatchOp("fetch", request);
        }

        if ("/api/git/pull".equals(path)) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleGitBatchOp("pull", request);
        }

        if ("/api/git/switch".equals(path)) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleGitSwitch(request);
        }

        if ("/api/git/stash".equals(path)) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleGitStash(request);
        }

        if ("/api/git/cancel".equals(path)) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleGitCancel(request);
        }

        Optional<String> profileUseSegment = extractSegment(path, PROFILES_PREFIX, "/use");
        if (profileUseSegment.isPresent()) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleUseProfile(profileUseSegment.get());
        }

        Optional<String> profileSegment = extractSegment(path, PROFILES_PREFIX, "");
        if (profileSegment.isPresent()) {
            if ("PUT".equals(method)) {
                return handleSaveProfile(profileSegment.get(), request);
            }
            if ("DELETE".equals(method)) {
                return handleDeleteProfile(profileSegment.get());
            }
            return methodNotAllowed(method, "PUT, DELETE");
        }

        Optional<String> buildCancelSegment = extractSegment(path, SERVICES_PREFIX, "/build/cancel");
        if (buildCancelSegment.isPresent()) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleCancelBuild(buildCancelSegment.get());
        }

        Optional<String> buildSegment = extractSegment(path, SERVICES_PREFIX, "/build");
        if (buildSegment.isPresent()) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleBuildService(buildSegment.get(), request);
        }

        Optional<String> inspectSegment = extractSegment(path, SERVICES_PREFIX, "/inspect");
        if (inspectSegment.isPresent()) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method, "GET");
            }
            return handleInspect(inspectSegment.get());
        }

        Optional<String> logsSegment = extractSegment(path, SERVICES_PREFIX, "/logs");
        if (logsSegment.isPresent()) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method, "GET");
            }
            return handleLogs(logsSegment.get(), request.rawQuery());
        }

        if (extractSegment(path, SERVICES_PREFIX, "/logs/stream").isPresent()) {
            return methodNotAllowed(method, "GET");
        }

        return errorResponse(
            404,
            DomainError.of("NOT_FOUND", "Unknown API endpoint: " + method + " " + path, Map.of("path", path)),
            ""
        );
    }

    /**
     * Handles an SSE streaming endpoint ({@code GET /api/services/start/stream} or
     * {@code GET /api/services/{name}/logs/stream}), closing {@code sink} when finished.
     */
    public void handleSse(ApiRequest request, SseSink sink) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        String path = request.path();
        try {
            if ("/api/services/start/stream".equals(path)) {
                handleStartServicesStream(request, sink);
                return;
            }
            if ("/api/gateway/logs/stream".equals(path)) {
                handleGatewayLogsStream(request, sink);
                return;
            }
            Optional<String> logsStreamSegment = extractSegment(path, SERVICES_PREFIX, "/logs/stream");
            if (logsStreamSegment.isPresent()) {
                handleLogsStream(logsStreamSegment.get(), request, sink);
                return;
            }
            sendSseError(
                sink,
                DomainError.of("NOT_FOUND", "Unknown SSE endpoint: " + path, Map.of("path", path)),
                ""
            );
        } finally {
            sink.close();
        }
    }

    private ApiResponse handleStatus() {
        String cliCommand = cli.status(false);
        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        LoadedProject loaded = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value();
        Project project = loaded.project();

        Result<StatusShell, DomainError> statusShellRes = runtime.statusShell();
        if (statusShellRes instanceof Result.Err<StatusShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        StatusShell statusShell = ((Result.Ok<StatusShell, DomainError>) statusShellRes).value();

        Result<EstateStatus, DomainError> estateRes = statusShell.status(project);
        if (estateRes instanceof Result.Err<EstateStatus, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        EstateStatus estate = ((Result.Ok<EstateStatus, DomainError>) estateRes).value();

        List<ProfileDefinition> profiles = List.copyOf(project.profiles().values());
        Map<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("data", DomainJson.estateStatus(estate));
        envelope.put("profiles", DomainJson.profilesArray(profiles));
        envelope.put("configPath", Json.str(loaded.configPath().toString()));
        envelope.put("cliCommand", Json.str(cliCommand));
        return ApiResponse.json(200, Json.obj(envelope));
    }

    private ApiResponse handleInspect(String rawName) {
        Result<String, DomainError> nameRes = decodeSegment(rawName, "service");
        if (nameRes instanceof Result.Err<String, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        String serviceName = ((Result.Ok<String, DomainError>) nameRes).value();
        String cliCommand = cli.inspect(serviceName, false);
        String killConflictCliCommand = cli.stop(List.of(serviceName), false, true);

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

        Result<ProcessShell, DomainError> shellRes = runtime.processShell();
        if (shellRes instanceof Result.Err<ProcessShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Result<InspectionReport, DomainError> inspectRes =
            ((Result.Ok<ProcessShell, DomainError>) shellRes).value().inspect(project, serviceName);
        if (inspectRes instanceof Result.Err<InspectionReport, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        InspectionReport report = ((Result.Ok<InspectionReport, DomainError>) inspectRes).value();
        Result<ObservedBuildRun, DomainError> buildRes =
            runtime.buildShell().flatMap(b -> b.inspectBuild(project, serviceName));
        Optional<ObservedBuildRun> buildRun = buildRes instanceof Result.Ok<ObservedBuildRun, DomainError>(ObservedBuildRun obs)
            ? Optional.of(obs)
            : Optional.empty();
        Optional<GitShell.RepositoryStatusRow> gitRow = Optional.empty();
        var repoOpt = project.service(serviceName).flatMap(procomp.core.project.ServiceDefinition::repository);
        if (repoOpt.isPresent()) {
            Result<GitShell.RepositoryStatusRow, DomainError> gitRes =
                runtime.gitShell().flatMap(g -> g.observeRepository(project, repoOpt.get()));
            if (gitRes instanceof Result.Ok<GitShell.RepositoryStatusRow, DomainError>(var gr)) {
                gitRow = Optional.of(gr);
            }
        }
        Map<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("data", DomainJson.inspectionReport(project, report, buildRun, gitRow));
        envelope.put("cliCommand", Json.str(cliCommand));
        envelope.put("killConflictCliCommand", Json.str(killConflictCliCommand));
        return ApiResponse.json(200, Json.obj(envelope));
    }

    private ApiResponse handleBuildService(String rawName, ApiRequest request) {
        Result<String, DomainError> nameRes = decodeSegment(rawName, "service");
        if (nameRes instanceof Result.Err<String, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        String serviceName = ((Result.Ok<String, DomainError>) nameRes).value();

        Result<JsonValue.JsonObject, DomainError> objRes = parseJsonObject(request.body(), true);
        if (objRes instanceof Result.Err<JsonValue.JsonObject, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        JsonValue.JsonObject obj = ((Result.Ok<JsonValue.JsonObject, DomainError>) objRes).value();
        Result<Boolean, DomainError> forceRes = readOptionalBoolean(obj, "force", false);
        if (forceRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        boolean force = ((Result.Ok<Boolean, DomainError>) forceRes).value();
        String cliCommand = cli.build(List.of(serviceName), force);

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

        Result<BuildShell, DomainError> shellRes = runtime.buildShell();
        if (shellRes instanceof Result.Err<BuildShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        BuildShell buildShell = ((Result.Ok<BuildShell, DomainError>) shellRes).value();

        Result<Unit, DomainError> validRes = buildShell.validateCanBuild(project, serviceName, force);
        if (validRes instanceof Result.Err<Unit, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }

        String launchKey = project.name() + "\0" + serviceName;
        if (!force && launchingBuilds.putIfAbsent(launchKey, Boolean.TRUE) != null) {
            return domainErrorResponse(
                DomainError.of("BUILD_ALREADY_RUNNING", "Build Run for service '" + serviceName + "' is already starting"),
                cliCommand
            );
        }
        if (force) {
            launchingBuilds.put(launchKey, Boolean.TRUE);
        }

        buildExecutor.execute(() -> {
            try {
                buildShell.build(project, new BuildRequest(List.of(serviceName), force, Optional.empty()));
            } finally {
                launchingBuilds.remove(launchKey);
            }
        });

        Map<String, JsonValue> data = new LinkedHashMap<>();
        data.put("service", Json.str(serviceName));
        data.put("status", Json.str("ACCEPTED"));
        Map<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("data", Json.obj(data));
        envelope.put("cliCommand", Json.str(cliCommand));
        return ApiResponse.json(202, Json.obj(envelope));
    }

    private ApiResponse handleCancelBuild(String rawName) {
        Result<String, DomainError> nameRes = decodeSegment(rawName, "service");
        if (nameRes instanceof Result.Err<String, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        String serviceName = ((Result.Ok<String, DomainError>) nameRes).value();
        String cliCommand = cli.buildCancel(serviceName);

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

        Result<BuildShell, DomainError> shellRes = runtime.buildShell();
        if (shellRes instanceof Result.Err<BuildShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Result<CancelResult, DomainError> cancelRes =
            ((Result.Ok<BuildShell, DomainError>) shellRes).value().cancel(project, serviceName);
        if (cancelRes instanceof Result.Err<CancelResult, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        return okEnvelope(
            DomainJson.cancelResult(project, ((Result.Ok<CancelResult, DomainError>) cancelRes).value()),
            cliCommand
        );
    }

    private ApiResponse handleUpdateRoutes(ApiRequest request) {
        Result<JsonValue.JsonObject, DomainError> objRes = parseJsonObject(request.body(), false);
        if (objRes instanceof Result.Err<JsonValue.JsonObject, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        JsonValue.JsonObject obj = ((Result.Ok<JsonValue.JsonObject, DomainError>) objRes).value();

        Result<List<String>, DomainError> servicesRes = readStringList(obj, "services", true);
        if (servicesRes instanceof Result.Err<List<String>, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        List<String> services = ((Result.Ok<List<String>, DomainError>) servicesRes).value();

        Result<Route, DomainError> routeRes = readRoute(obj, "route");
        if (routeRes instanceof Result.Err<Route, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Route route = ((Result.Ok<Route, DomainError>) routeRes).value();

        String cliCommand = cli.updateRoutes(services, route);

        mutationLock.lock();
        try {
            Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
            if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

            Result<RoutingShell, DomainError> shellRes = runtime.routingShell();
            if (shellRes instanceof Result.Err<RoutingShell, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Result<RoutingTable, DomainError> updateRes =
                ((Result.Ok<RoutingShell, DomainError>) shellRes).value().updateRoutes(project, services, route);
            if (updateRes instanceof Result.Err<RoutingTable, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            RoutingTable updated = ((Result.Ok<RoutingTable, DomainError>) updateRes).value();
            return okEnvelope(DomainJson.routingTable(project, updated), cliCommand);
        } finally {
            mutationLock.unlock();
        }
    }

    private ApiResponse handleUseProfile(String rawName) {
        Result<String, DomainError> nameRes = decodeSegment(rawName, "profile");
        if (nameRes instanceof Result.Err<String, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        String profileName = ((Result.Ok<String, DomainError>) nameRes).value();
        String cliCommand = cli.useProfile(profileName);

        mutationLock.lock();
        try {
            Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
            if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

            Result<RoutingShell, DomainError> shellRes = runtime.routingShell();
            if (shellRes instanceof Result.Err<RoutingShell, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Result<RoutingTable, DomainError> useRes =
                ((Result.Ok<RoutingShell, DomainError>) shellRes).value().useProfile(project, profileName);
            if (useRes instanceof Result.Err<RoutingTable, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            RoutingTable updated = ((Result.Ok<RoutingTable, DomainError>) useRes).value();
            return okEnvelope(DomainJson.routingTable(project, updated), cliCommand);
        } finally {
            mutationLock.unlock();
        }
    }

    private ApiResponse handleSaveProfile(String rawName, ApiRequest request) {
        Result<String, DomainError> nameRes = decodeSegment(rawName, "profile");
        if (nameRes instanceof Result.Err<String, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        String profileName = ((Result.Ok<String, DomainError>) nameRes).value();

        Result<JsonValue.JsonObject, DomainError> objRes = parseJsonObject(request.body(), false);
        if (objRes instanceof Result.Err<JsonValue.JsonObject, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        JsonValue.JsonObject obj = ((Result.Ok<JsonValue.JsonObject, DomainError>) objRes).value();

        Result<Boolean, DomainError> fromCurrentRes = readOptionalBoolean(obj, "fromCurrentRoutes", false);
        if (fromCurrentRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        boolean fromCurrentRoutes = ((Result.Ok<Boolean, DomainError>) fromCurrentRes).value();

        Optional<List<String>> explicitLocal;
        String cliCommand;
        if (fromCurrentRoutes) {
            if (obj.get("local").isPresent() && !(obj.get("local").get() instanceof JsonValue.JsonNull)) {
                return errorResponse(
                    400,
                    DomainError.of("INVALID_REQUEST", "Request body cannot specify both 'fromCurrentRoutes': true and 'local'"),
                    ""
                );
            }
            explicitLocal = Optional.empty();
            cliCommand = cli.saveProfileSnapshot(profileName);
        } else {
            if (obj.get("local").isEmpty()) {
                return errorResponse(
                    400,
                    DomainError.of("INVALID_REQUEST", "Request body must specify 'local' array or 'fromCurrentRoutes': true"),
                    ""
                );
            }
            Result<List<String>, DomainError> localRes = readStringList(obj, "local", false);
            if (localRes instanceof Result.Err<List<String>, DomainError>(DomainError err)) {
                return errorResponse(400, err, "");
            }
            List<String> locals = ((Result.Ok<List<String>, DomainError>) localRes).value();
            explicitLocal = Optional.of(locals);
            cliCommand = cli.saveProfile(profileName, locals);
        }

        mutationLock.lock();
        try {
            Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
            if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            LoadedProject loaded = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value();

            List<String> localServices;
            if (explicitLocal.isPresent()) {
                localServices = explicitLocal.get();
            } else {
                Result<RoutingShell, DomainError> routingShellRes = runtime.routingShell();
                if (routingShellRes instanceof Result.Err<RoutingShell, DomainError>(DomainError err)) {
                    return domainErrorResponse(err, cliCommand);
                }
                Result<RoutingTable, DomainError> tableRes =
                    ((Result.Ok<RoutingShell, DomainError>) routingShellRes).value().showRoutes(loaded.project());
                if (tableRes instanceof Result.Err<RoutingTable, DomainError>(DomainError err)) {
                    return domainErrorResponse(err, cliCommand);
                }
                localServices = ((Result.Ok<RoutingTable, DomainError>) tableRes).value().localServices();
            }

            Result<ProfileConfigShell, DomainError> shellRes = runtime.profileConfigShell();
            if (shellRes instanceof Result.Err<ProfileConfigShell, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Result<SaveProfileResult, DomainError> saveRes =
                ((Result.Ok<ProfileConfigShell, DomainError>) shellRes).value().saveProfile(loaded, profileName, localServices);
            if (saveRes instanceof Result.Err<SaveProfileResult, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            SaveProfileResult saved = ((Result.Ok<SaveProfileResult, DomainError>) saveRes).value();
            projectHolder.invalidate();

            Map<String, JsonValue> profileObj = new LinkedHashMap<>();
            profileObj.put("name", Json.str(saved.profile().name()));
            List<JsonValue> localVals = new ArrayList<>(saved.profile().localServices().size());
            for (String svc : saved.profile().localServices()) {
                localVals.add(Json.str(svc));
            }
            profileObj.put("local", Json.arr(localVals));

            Map<String, JsonValue> data = new LinkedHashMap<>();
            data.put("profile", Json.obj(profileObj));
            data.put("profiles", DomainJson.profilesArray(List.copyOf(saved.loaded().project().profiles().values())));
            return okEnvelope(Json.obj(data), cliCommand);
        } finally {
            mutationLock.unlock();
        }
    }

    private ApiResponse handleDeleteProfile(String rawName) {
        Result<String, DomainError> nameRes = decodeSegment(rawName, "profile");
        if (nameRes instanceof Result.Err<String, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        String profileName = ((Result.Ok<String, DomainError>) nameRes).value();
        String cliCommand = cli.deleteProfile(profileName);

        mutationLock.lock();
        try {
            Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
            if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            LoadedProject loaded = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value();

            Result<ProfileConfigShell, DomainError> shellRes = runtime.profileConfigShell();
            if (shellRes instanceof Result.Err<ProfileConfigShell, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Result<DeleteProfileResult, DomainError> delRes =
                ((Result.Ok<ProfileConfigShell, DomainError>) shellRes).value().deleteProfile(loaded, profileName);
            if (delRes instanceof Result.Err<DeleteProfileResult, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            DeleteProfileResult deleted = ((Result.Ok<DeleteProfileResult, DomainError>) delRes).value();
            projectHolder.invalidate();

            Map<String, JsonValue> data = new LinkedHashMap<>();
            data.put("deleted", Json.str(deleted.deletedName()));
            data.put("profiles", DomainJson.profilesArray(List.copyOf(deleted.loaded().project().profiles().values())));
            return okEnvelope(Json.obj(data), cliCommand);
        } finally {
            mutationLock.unlock();
        }
    }

    private ApiResponse handleStartServices(ApiRequest request) {
        Result<JsonValue.JsonObject, DomainError> objRes = parseJsonObject(request.body(), true);
        if (objRes instanceof Result.Err<JsonValue.JsonObject, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        JsonValue.JsonObject obj = ((Result.Ok<JsonValue.JsonObject, DomainError>) objRes).value();

        Result<List<String>, DomainError> servicesRes = readStringList(obj, "services", false);
        if (servicesRes instanceof Result.Err<List<String>, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> detachRes = readOptionalBoolean(obj, "detach", false);
        if (detachRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Integer, DomainError> timeoutRes = readOptionalPositiveInt(
            obj,
            "timeoutSeconds",
            (int) StartRequest.DEFAULT_TIMEOUT.toSeconds()
        );
        if (timeoutRes instanceof Result.Err<Integer, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }

        List<String> services = ((Result.Ok<List<String>, DomainError>) servicesRes).value();
        boolean detach = ((Result.Ok<Boolean, DomainError>) detachRes).value();
        Duration timeout = Duration.ofSeconds(((Result.Ok<Integer, DomainError>) timeoutRes).value());
        String cliCommand = cli.start(services, detach, timeout);

        mutationLock.lock();
        try {
            Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
            if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

            Result<ProcessShell, DomainError> shellRes = runtime.processShell();
            if (shellRes instanceof Result.Err<ProcessShell, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Result<StartResult, DomainError> startRes = ((Result.Ok<ProcessShell, DomainError>) shellRes).value()
                .start(project, new StartRequest(services, detach, timeout));
            if (startRes instanceof Result.Err<StartResult, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            StartResult result = ((Result.Ok<StartResult, DomainError>) startRes).value();
            return okEnvelope(DomainJson.startResult(result), cliCommand);
        } finally {
            mutationLock.unlock();
        }
    }

    private void handleStartServicesStream(ApiRequest request, SseSink sink) {
        Optional<DomainError> sseCsrfErr = checkCsrf(request);
        if (sseCsrfErr.isPresent()) {
            sendSseError(sink, sseCsrfErr.get(), "");
            return;
        }

        List<String> services;
        boolean detach;
        int timeoutSeconds;
        if (!request.body().isBlank()) {
            Result<JsonValue.JsonObject, DomainError> objRes = parseJsonObject(request.body(), false);
            if (objRes instanceof Result.Err<JsonValue.JsonObject, DomainError>(DomainError err)) {
                sendSseError(sink, err, "");
                return;
            }
            JsonValue.JsonObject obj = ((Result.Ok<JsonValue.JsonObject, DomainError>) objRes).value();
            Result<List<String>, DomainError> servicesRes = readStringList(obj, "services", false);
            if (servicesRes instanceof Result.Err<List<String>, DomainError>(DomainError err)) {
                sendSseError(sink, err, "");
                return;
            }
            Result<Boolean, DomainError> detachRes = readOptionalBoolean(obj, "detach", false);
            if (detachRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
                sendSseError(sink, err, "");
                return;
            }
            Result<Integer, DomainError> timeoutRes = readOptionalPositiveInt(
                obj,
                "timeoutSeconds",
                (int) StartRequest.DEFAULT_TIMEOUT.toSeconds()
            );
            if (timeoutRes instanceof Result.Err<Integer, DomainError>(DomainError err)) {
                sendSseError(sink, err, "");
                return;
            }
            services = ((Result.Ok<List<String>, DomainError>) servicesRes).value();
            detach = ((Result.Ok<Boolean, DomainError>) detachRes).value();
            timeoutSeconds = ((Result.Ok<Integer, DomainError>) timeoutRes).value();
        } else {
            Result<Map<String, String>, DomainError> queryRes = parseQuery(request.rawQuery());
            if (queryRes instanceof Result.Err<Map<String, String>, DomainError>(DomainError err)) {
                sendSseError(sink, err, "");
                return;
            }
            Map<String, String> query = ((Result.Ok<Map<String, String>, DomainError>) queryRes).value();

            services = parseCsvList(query.getOrDefault("services", ""));
            Result<Integer, DomainError> timeoutRes = parseOptionalQueryPositiveInt(
                query,
                "timeout",
                (int) StartRequest.DEFAULT_TIMEOUT.toSeconds()
            );
            if (timeoutRes instanceof Result.Err<Integer, DomainError>(DomainError err)) {
                sendSseError(sink, err, "");
                return;
            }
            Result<Boolean, DomainError> detachRes = parseOptionalQueryBoolean(query, "detach", false);
            if (detachRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
                sendSseError(sink, err, "");
                return;
            }
            detach = ((Result.Ok<Boolean, DomainError>) detachRes).value();
            timeoutSeconds = ((Result.Ok<Integer, DomainError>) timeoutRes).value();
        }

        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        String cliCommand = cli.start(services, detach, timeout);

        sink.send("meta", Json.stringify(Json.obj(Map.of("cliCommand", Json.str(cliCommand)))));

        mutationLock.lock();
        try {
            Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
            if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
                sendSseError(sink, err, cliCommand);
                return;
            }
            Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

            ProcompRuntime streamRuntime = runtime.withObserver(event -> {
                if (event instanceof ProgressEvent.StatusMessage) {
                    sink.send("progress", Json.stringify(DomainJson.progressEvent(event)));
                }
            });
            Result<ProcessShell, DomainError> shellRes = streamRuntime.processShell();
            if (shellRes instanceof Result.Err<ProcessShell, DomainError>(DomainError err)) {
                sendSseError(sink, err, cliCommand);
                return;
            }
            Result<StartResult, DomainError> startRes = ((Result.Ok<ProcessShell, DomainError>) shellRes).value()
                .start(project, new StartRequest(services, detach, timeout));
            if (startRes instanceof Result.Err<StartResult, DomainError>(DomainError err)) {
                sendSseError(sink, err, cliCommand);
                return;
            }
            StartResult result = ((Result.Ok<StartResult, DomainError>) startRes).value();
            Map<String, JsonValue> doneObj = new LinkedHashMap<>();
            doneObj.put("data", DomainJson.startResult(result));
            doneObj.put("cliCommand", Json.str(cliCommand));
            sink.send("done", Json.stringify(Json.obj(doneObj)));
        } finally {
            mutationLock.unlock();
        }
    }

    private ApiResponse handleStopServices(ApiRequest request) {
        Result<JsonValue.JsonObject, DomainError> objRes = parseJsonObject(request.body(), true);
        if (objRes instanceof Result.Err<JsonValue.JsonObject, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        JsonValue.JsonObject obj = ((Result.Ok<JsonValue.JsonObject, DomainError>) objRes).value();

        Result<List<String>, DomainError> servicesRes = readStringList(obj, "services", false);
        if (servicesRes instanceof Result.Err<List<String>, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> allRes = readOptionalBoolean(obj, "all", false);
        if (allRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> killConflictRes = readOptionalBoolean(obj, "killConflict", false);
        if (killConflictRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }

        List<String> services = ((Result.Ok<List<String>, DomainError>) servicesRes).value();
        boolean all = ((Result.Ok<Boolean, DomainError>) allRes).value();
        boolean killConflict = ((Result.Ok<Boolean, DomainError>) killConflictRes).value();
        String cliCommand = cli.stop(services, all, killConflict);

        mutationLock.lock();
        try {
            Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
            if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

            Result<ProcessShell, DomainError> shellRes = runtime.processShell();
            if (shellRes instanceof Result.Err<ProcessShell, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Result<StopResult, DomainError> stopRes = ((Result.Ok<ProcessShell, DomainError>) shellRes).value()
                .stop(project, new StopRequest(services, all, killConflict, StopRequest.DEFAULT_GRACEFUL_TIMEOUT));
            if (stopRes instanceof Result.Err<StopResult, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            StopResult result = ((Result.Ok<StopResult, DomainError>) stopRes).value();
            return okEnvelope(DomainJson.stopResult(result), cliCommand);
        } finally {
            mutationLock.unlock();
        }
    }

    private ApiResponse handleGatewayStart(ApiRequest request) {
        Result<JsonValue.JsonObject, DomainError> objRes = parseJsonObject(request.body(), true);
        if (objRes instanceof Result.Err<JsonValue.JsonObject, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        JsonValue.JsonObject obj = ((Result.Ok<JsonValue.JsonObject, DomainError>) objRes).value();

        Result<Boolean, DomainError> detachRes = readOptionalBoolean(obj, "detach", false);
        if (detachRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Integer, DomainError> timeoutRes = readOptionalPositiveInt(
            obj,
            "timeoutSeconds",
            (int) GatewayStartRequest.DEFAULT_TIMEOUT.toSeconds()
        );
        if (timeoutRes instanceof Result.Err<Integer, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }

        boolean detach = ((Result.Ok<Boolean, DomainError>) detachRes).value();
        Duration timeout = Duration.ofSeconds(((Result.Ok<Integer, DomainError>) timeoutRes).value());
        String cliCommand = cli.gatewayStart(detach, timeout);

        mutationLock.lock();
        try {
            Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
            if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

            Result<GatewayShell, DomainError> shellRes = runtime.gatewayShell();
            if (shellRes instanceof Result.Err<GatewayShell, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Result<GatewayStartResult, DomainError> startRes = ((Result.Ok<GatewayShell, DomainError>) shellRes).value()
                .start(project, new GatewayStartRequest(detach, timeout));
            if (startRes instanceof Result.Err<GatewayStartResult, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            GatewayStartResult result = ((Result.Ok<GatewayStartResult, DomainError>) startRes).value();
            return okEnvelope(DomainJson.gatewayStartResult(result), cliCommand);
        } finally {
            mutationLock.unlock();
        }
    }

    private ApiResponse handleGatewayStop() {
        String cliCommand = cli.gatewayStop();

        mutationLock.lock();
        try {
            Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
            if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

            Result<GatewayShell, DomainError> shellRes = runtime.gatewayShell();
            if (shellRes instanceof Result.Err<GatewayShell, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Result<GatewayStopResult, DomainError> stopRes = ((Result.Ok<GatewayShell, DomainError>) shellRes).value()
                .stop(project, new GatewayStopRequest(GatewayStopRequest.DEFAULT_GRACEFUL_TIMEOUT));
            if (stopRes instanceof Result.Err<GatewayStopResult, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            GatewayStopResult result = ((Result.Ok<GatewayStopResult, DomainError>) stopRes).value();
            return okEnvelope(DomainJson.gatewayStopResult(result), cliCommand);
        } finally {
            mutationLock.unlock();
        }
    }

    private ApiResponse handleGatewayReconcile() {
        String cliCommand = cli.gatewayReconcile();

        mutationLock.lock();
        try {
            Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
            if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

            Result<GatewayShell, DomainError> shellRes = runtime.gatewayShell();
            if (shellRes instanceof Result.Err<GatewayShell, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            Result<GatewayReconcileResult, DomainError> reconcileRes =
                ((Result.Ok<GatewayShell, DomainError>) shellRes).value().reconcile(project);
            if (reconcileRes instanceof Result.Err<GatewayReconcileResult, DomainError>(DomainError err)) {
                return domainErrorResponse(err, cliCommand);
            }
            GatewayReconcileResult result = ((Result.Ok<GatewayReconcileResult, DomainError>) reconcileRes).value();
            return okEnvelope(DomainJson.gatewayReconcileResult(result), cliCommand);
        } finally {
            mutationLock.unlock();
        }
    }

    private ApiResponse handleLogs(String rawName, String rawQuery) {
        Result<String, DomainError> nameRes = decodeSegment(rawName, "service");
        if (nameRes instanceof Result.Err<String, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        String serviceName = ((Result.Ok<String, DomainError>) nameRes).value();

        Result<Map<String, String>, DomainError> queryRes = parseQuery(rawQuery);
        if (queryRes instanceof Result.Err<Map<String, String>, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Map<String, String> query = ((Result.Ok<Map<String, String>, DomainError>) queryRes).value();

        Result<Integer, DomainError> linesRes = parseOptionalQueryNonNegativeInt(query, "lines", LogsRequest.DEFAULT_LINES);
        if (linesRes instanceof Result.Err<Integer, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> errFlagRes = parseOptionalQueryBoolean(query, "err", false);
        if (errFlagRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> buildFlagRes = parseOptionalQueryBoolean(query, "build", false);
        if (buildFlagRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> gitFlagRes = parseOptionalQueryBoolean(query, "git", false);
        if (gitFlagRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }

        int lines = ((Result.Ok<Integer, DomainError>) linesRes).value();
        boolean stderr = ((Result.Ok<Boolean, DomainError>) errFlagRes).value();
        boolean build = ((Result.Ok<Boolean, DomainError>) buildFlagRes).value();
        boolean git = ((Result.Ok<Boolean, DomainError>) gitFlagRes).value();
        if (build && git) {
            return errorResponse(400, DomainError.of("INVALID_REQUEST", "Cannot combine 'build=true' and 'git=true'"), "");
        }
        String cliCommand = git
            ? cli.gitLogs(serviceName, stderr, false, lines, false)
            : cli.logs(serviceName, stderr, false, lines, false, build);

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

        Result<LogsShell, DomainError> shellRes = runtime.logsShell();
        if (shellRes instanceof Result.Err<LogsShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Result<LogsResult, DomainError> logsRes = ((Result.Ok<LogsShell, DomainError>) shellRes).value()
            .run(project, new LogsRequest(serviceName, stderr, false, lines, false, build, git));
        if (logsRes instanceof Result.Err<LogsResult, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        LogsResult result = ((Result.Ok<LogsResult, DomainError>) logsRes).value();
        if (!(result instanceof LogsResult.RawTail(Path path, String text))) {
            return errorResponse(
                500,
                DomainError.of("LOGS_UNEXPECTED_RESULT", "Unexpected LogsShell result for non-follow request"),
                cliCommand
            );
        }

        Map<String, JsonValue> data = new LinkedHashMap<>();
        data.put("path", Json.str(path.toString()));
        data.put("text", Json.str(text));
        return okEnvelope(Json.obj(data), cliCommand);
    }

    private void handleLogsStream(String rawName, ApiRequest request, SseSink sink) {
        Result<String, DomainError> nameRes = decodeSegment(rawName, "service");
        if (nameRes instanceof Result.Err<String, DomainError>(DomainError err)) {
            sendSseError(sink, err, "");
            return;
        }
        String serviceName = ((Result.Ok<String, DomainError>) nameRes).value();

        Result<Map<String, String>, DomainError> queryRes = parseQuery(request.rawQuery());
        if (queryRes instanceof Result.Err<Map<String, String>, DomainError>(DomainError err)) {
            sendSseError(sink, err, "");
            return;
        }
        Map<String, String> query = ((Result.Ok<Map<String, String>, DomainError>) queryRes).value();

        Result<Integer, DomainError> linesRes = parseOptionalQueryNonNegativeInt(query, "lines", LogsRequest.DEFAULT_LINES);
        if (linesRes instanceof Result.Err<Integer, DomainError>(DomainError err)) {
            sendSseError(sink, err, "");
            return;
        }
        Result<Boolean, DomainError> errFlagRes = parseOptionalQueryBoolean(query, "err", false);
        if (errFlagRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            sendSseError(sink, err, "");
            return;
        }
        Result<Boolean, DomainError> buildFlagRes = parseOptionalQueryBoolean(query, "build", false);
        if (buildFlagRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            sendSseError(sink, err, "");
            return;
        }
        Result<Boolean, DomainError> gitFlagRes = parseOptionalQueryBoolean(query, "git", false);
        if (gitFlagRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            sendSseError(sink, err, "");
            return;
        }

        int lines = ((Result.Ok<Integer, DomainError>) linesRes).value();
        boolean stderr = ((Result.Ok<Boolean, DomainError>) errFlagRes).value();
        boolean build = ((Result.Ok<Boolean, DomainError>) buildFlagRes).value();
        boolean git = ((Result.Ok<Boolean, DomainError>) gitFlagRes).value();
        if (build && git) {
            sendSseError(sink, DomainError.of("INVALID_REQUEST", "Cannot combine 'build=true' and 'git=true'"), "");
            return;
        }
        String cliCommand = git
            ? cli.gitLogs(serviceName, stderr, false, lines, true)
            : cli.logs(serviceName, stderr, false, lines, true, build);

        sink.send("meta", Json.stringify(Json.obj(Map.of("cliCommand", Json.str(cliCommand)))));

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            sendSseError(sink, err, cliCommand);
            return;
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

        ProcompRuntime streamRuntime = runtime.withObserver(event -> {
            if (event instanceof ProgressEvent.LogLine) {
                sink.send("log", Json.stringify(DomainJson.progressEvent(event)));
            }
        });
        Result<LogsShell, DomainError> shellRes = streamRuntime.logsShell();
        if (shellRes instanceof Result.Err<LogsShell, DomainError>(DomainError err)) {
            sendSseError(sink, err, cliCommand);
            return;
        }
        Result<LogsResult, DomainError> logsRes = ((Result.Ok<LogsShell, DomainError>) shellRes).value()
            .run(project, new LogsRequest(serviceName, stderr, false, lines, true, build, git), logsPollInterval, sink::isOpen);
        if (logsRes instanceof Result.Err<LogsResult, DomainError>(DomainError err)) {
            sendSseError(sink, err, cliCommand);
        }
    }

    private ApiResponse handleGatewayLogs(String rawQuery) {
        Result<Map<String, String>, DomainError> queryRes = parseQuery(rawQuery);
        if (queryRes instanceof Result.Err<Map<String, String>, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Map<String, String> query = ((Result.Ok<Map<String, String>, DomainError>) queryRes).value();

        Result<Integer, DomainError> linesRes = parseOptionalQueryNonNegativeInt(query, "lines", LogsRequest.DEFAULT_LINES);
        if (linesRes instanceof Result.Err<Integer, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> errFlagRes = parseOptionalQueryBoolean(query, "err", false);
        if (errFlagRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }

        int lines = ((Result.Ok<Integer, DomainError>) linesRes).value();
        boolean stderr = ((Result.Ok<Boolean, DomainError>) errFlagRes).value();
        String cliCommand = cli.gatewayLogs(stderr, false, lines, false);

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

        Result<LogsShell, DomainError> shellRes = runtime.logsShell();
        if (shellRes instanceof Result.Err<LogsShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Result<LogsResult, DomainError> logsRes = ((Result.Ok<LogsShell, DomainError>) shellRes).value()
            .runGateway(project, new GatewayLogsRequest(stderr, false, lines, false));
        if (logsRes instanceof Result.Err<LogsResult, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        LogsResult result = ((Result.Ok<LogsResult, DomainError>) logsRes).value();
        if (!(result instanceof LogsResult.RawTail(Path path, String text))) {
            return errorResponse(
                500,
                DomainError.of("LOGS_UNEXPECTED_RESULT", "Unexpected LogsShell result for non-follow request"),
                cliCommand
            );
        }

        Map<String, JsonValue> data = new LinkedHashMap<>();
        data.put("path", Json.str(path.toString()));
        data.put("text", Json.str(text));
        return okEnvelope(Json.obj(data), cliCommand);
    }

    private void handleGatewayLogsStream(ApiRequest request, SseSink sink) {
        Result<Map<String, String>, DomainError> queryRes = parseQuery(request.rawQuery());
        if (queryRes instanceof Result.Err<Map<String, String>, DomainError>(DomainError err)) {
            sendSseError(sink, err, "");
            return;
        }
        Map<String, String> query = ((Result.Ok<Map<String, String>, DomainError>) queryRes).value();

        Result<Integer, DomainError> linesRes = parseOptionalQueryNonNegativeInt(query, "lines", LogsRequest.DEFAULT_LINES);
        if (linesRes instanceof Result.Err<Integer, DomainError>(DomainError err)) {
            sendSseError(sink, err, "");
            return;
        }
        Result<Boolean, DomainError> errFlagRes = parseOptionalQueryBoolean(query, "err", false);
        if (errFlagRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            sendSseError(sink, err, "");
            return;
        }

        int lines = ((Result.Ok<Integer, DomainError>) linesRes).value();
        boolean stderr = ((Result.Ok<Boolean, DomainError>) errFlagRes).value();
        String cliCommand = cli.gatewayLogs(stderr, false, lines, true);

        sink.send("meta", Json.stringify(Json.obj(Map.of("cliCommand", Json.str(cliCommand)))));

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            sendSseError(sink, err, cliCommand);
            return;
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

        ProcompRuntime streamRuntime = runtime.withObserver(event -> {
            if (event instanceof ProgressEvent.LogLine) {
                sink.send("log", Json.stringify(DomainJson.progressEvent(event)));
            }
        });
        Result<LogsShell, DomainError> shellRes = streamRuntime.logsShell();
        if (shellRes instanceof Result.Err<LogsShell, DomainError>(DomainError err)) {
            sendSseError(sink, err, cliCommand);
            return;
        }
        Result<LogsResult, DomainError> logsRes = ((Result.Ok<LogsShell, DomainError>) shellRes).value()
            .runGateway(project, new GatewayLogsRequest(stderr, false, lines, true), logsPollInterval, sink::isOpen);
        if (logsRes instanceof Result.Err<LogsResult, DomainError>(DomainError err)) {
            sendSseError(sink, err, cliCommand);
        }
    }

    private Optional<DomainError> checkHost(ApiRequest request) {
        Optional<String> hostOpt = request.header("Host");
        if (hostOpt.isEmpty() || !isAllowedHost(hostOpt.get())) {
            return Optional.of(DomainError.of(
                "FORBIDDEN_ORIGIN",
                "Rejected request with missing or non-loopback Host header",
                Map.of("host", hostOpt.orElse("<missing>"))
            ));
        }
        return Optional.empty();
    }

    private Optional<DomainError> checkCsrf(ApiRequest request) {
        Optional<DomainError> hostErr = checkHost(request);
        if (hostErr.isPresent()) {
            return hostErr;
        }
        Optional<String> originOpt = request.header("Origin");
        boolean hasValidOrigin = false;
        if (originOpt.isPresent() && !originOpt.get().isBlank()) {
            if (!isAllowedOrigin(originOpt.get())) {
                return Optional.of(DomainError.of(
                    "FORBIDDEN_ORIGIN",
                    "Rejected cross-origin request from Origin '" + originOpt.get() + "'",
                    Map.of("origin", originOpt.get())
                ));
            }
            hasValidOrigin = true;
        }
        Optional<String> secFetchSiteOpt = request.header("Sec-Fetch-Site");
        boolean hasSameOriginFetch = false;
        if (secFetchSiteOpt.isPresent() && !secFetchSiteOpt.get().isBlank()) {
            String val = secFetchSiteOpt.get().trim().toLowerCase(Locale.ROOT);
            if (!"same-origin".equals(val)) {
                return Optional.of(DomainError.of(
                    "FORBIDDEN_ORIGIN",
                    "Rejected mutating request with Sec-Fetch-Site '" + secFetchSiteOpt.get() + "'",
                    Map.of("secFetchSite", secFetchSiteOpt.get())
                ));
            }
            hasSameOriginFetch = true;
        }
        boolean hasJsonContentType = request.header("Content-Type")
            .map(ct -> ct.trim().toLowerCase(Locale.ROOT).startsWith("application/json"))
            .orElse(false);
        boolean hasCustomHeader = request.header("X-Procomp-Request")
            .map(v -> !v.isBlank())
            .orElse(false);
        if (!hasValidOrigin && !hasSameOriginFetch && !hasJsonContentType && !hasCustomHeader) {
            return Optional.of(DomainError.of(
                "FORBIDDEN_ORIGIN",
                "Rejected mutating request lacking same-origin or JSON/custom-header CSRF proof",
                Map.of("method", request.method(), "path", request.path())
            ));
        }
        return Optional.empty();
    }

    boolean isAllowedHost(String rawHost) {
        return isAllowedLoopbackAuthority(rawHost.trim().toLowerCase(Locale.ROOT));
    }

    private boolean isAllowedOrigin(String rawOrigin) {
        String origin = rawOrigin.trim().toLowerCase(Locale.ROOT);
        if (!origin.startsWith("http://")) {
            return false;
        }
        return isAllowedLoopbackAuthority(origin.substring("http://".length()));
    }

    private boolean isAllowedLoopbackAuthority(String authority) {
        if (authority.equals("127.0.0.1:" + boundPort) || authority.equals("localhost:" + boundPort)) {
            return true;
        }
        return boundPort == 80 && (authority.equals("127.0.0.1") || authority.equals("localhost"));
    }

    private static boolean isMutatingMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method) || "PATCH".equals(method);
    }

    private static ApiResponse okEnvelope(JsonValue data, String cliCommand) {
        Map<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("data", data);
        envelope.put("cliCommand", Json.str(cliCommand));
        return ApiResponse.json(200, Json.obj(envelope));
    }

    private static ApiResponse domainErrorResponse(DomainError error, String cliCommand) {
        return errorResponse(httpStatusFor(error), error, cliCommand);
    }

    private static ApiResponse errorResponse(int statusCode, DomainError error, String cliCommand) {
        Map<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("error", DomainJson.domainError(error));
        envelope.put("cliCommand", Json.str(cliCommand));
        return ApiResponse.json(statusCode, Json.obj(envelope));
    }

    private static ApiResponse methodNotAllowed(String actualMethod, String allowedMethod) {
        return errorResponse(
            405,
            DomainError.of(
                "METHOD_NOT_ALLOWED",
                "Method " + actualMethod + " is not allowed; expected " + allowedMethod,
                Map.of("method", actualMethod, "allowed", allowedMethod)
            ),
            ""
        );
    }

    private static void sendSseError(SseSink sink, DomainError error, String cliCommand) {
        Map<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("error", DomainJson.domainError(error));
        envelope.put("cliCommand", Json.str(cliCommand));
        sink.send("error", Json.stringify(Json.obj(envelope)));
    }

    private ApiResponse handleGitStatus(String rawQuery) {
        Result<Map<String, String>, DomainError> queryRes = parseQuery(rawQuery);
        if (queryRes instanceof Result.Err<Map<String, String>, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Map<String, String> query = ((Result.Ok<Map<String, String>, DomainError>) queryRes).value();
        Result<Boolean, DomainError> localRes = parseOptionalQueryBoolean(query, "local", false);
        if (localRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> fetchRes = parseOptionalQueryBoolean(query, "fetch", false);
        if (fetchRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> allRes = parseOptionalQueryBoolean(query, "all", false);
        if (allRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        boolean localOnly = ((Result.Ok<Boolean, DomainError>) localRes).value();
        boolean fetch = ((Result.Ok<Boolean, DomainError>) fetchRes).value();
        boolean all = ((Result.Ok<Boolean, DomainError>) allRes).value();
        String cliCommand = cli.gitStatus(localOnly, fetch, all, false);

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();
        Result<GitShell, DomainError> shellRes = runtime.gitShell();
        if (shellRes instanceof Result.Err<GitShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Result<GitShell.GitStatusReport, DomainError> statusRes = ((Result.Ok<GitShell, DomainError>) shellRes).value()
            .status(project, List.of(), localOnly, fetch, all, false, Optional.empty());
        if (statusRes instanceof Result.Err<GitShell.GitStatusReport, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        return okEnvelope(DomainJson.gitStatusReport(((Result.Ok<GitShell.GitStatusReport, DomainError>) statusRes).value()), cliCommand);
    }

    private ApiResponse handleGitBranches(String rawTarget) {
        Result<String, DomainError> targetRes = decodeSegment(rawTarget, "target");
        if (targetRes instanceof Result.Err<String, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        String target = ((Result.Ok<String, DomainError>) targetRes).value();
        String cliCommand = cli.gitBranches(target, false);

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();
        Result<GitShell, DomainError> shellRes = runtime.gitShell();
        if (shellRes instanceof Result.Err<GitShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Result<GitShell.BranchesReport, DomainError> brRes = ((Result.Ok<GitShell, DomainError>) shellRes).value()
            .branches(project, target);
        if (brRes instanceof Result.Err<GitShell.BranchesReport, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        return okEnvelope(DomainJson.gitBranchesReport(((Result.Ok<GitShell.BranchesReport, DomainError>) brRes).value()), cliCommand);
    }

    private ApiResponse handleGitBatchOp(String op, ApiRequest request) {
        Result<JsonValue.JsonObject, DomainError> bodyRes = parseJsonObject(request.body(), true);
        if (bodyRes instanceof Result.Err<JsonValue.JsonObject, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        JsonValue.JsonObject body = ((Result.Ok<JsonValue.JsonObject, DomainError>) bodyRes).value();
        Result<List<String>, DomainError> targetsRes = readStringList(body, "targets", false);
        if (targetsRes instanceof Result.Err<List<String>, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> allRes = readOptionalBoolean(body, "all", false);
        if (allRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> forceRes = readOptionalBoolean(body, "force", false);
        if (forceRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        List<String> targets = ((Result.Ok<List<String>, DomainError>) targetsRes).value();
        boolean all = ((Result.Ok<Boolean, DomainError>) allRes).value();
        boolean force = ((Result.Ok<Boolean, DomainError>) forceRes).value();
        String cliCommand = cli.gitOp(op, targets, all, force);

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();
        Result<GitShell, DomainError> shellRes = runtime.gitShell();
        if (shellRes instanceof Result.Err<GitShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        GitShell shell = ((Result.Ok<GitShell, DomainError>) shellRes).value();
        Result<GitShell.GitBatchReport, DomainError> batchRes = switch (op) {
            case "clone" -> shell.cloneRepositories(project, targets, all, force, Optional.empty());
            case "fetch" -> shell.fetch(project, targets, all, force, Optional.empty());
            case "pull" -> shell.pull(project, targets, all, force, Optional.empty());
            default -> Result.err(DomainError.of("INVALID_REQUEST", "Unsupported git op: " + op));
        };
        if (batchRes instanceof Result.Err<GitShell.GitBatchReport, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        GitShell.GitBatchReport report = ((Result.Ok<GitShell.GitBatchReport, DomainError>) batchRes).value();
        int httpStatus = report.hasFailures() ? 409 : 200;
        Map<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("data", DomainJson.gitBatchReport(report));
        envelope.put("cliCommand", Json.str(cliCommand));
        return ApiResponse.json(httpStatus, Json.obj(envelope));
    }

    private ApiResponse handleGitSwitch(ApiRequest request) {
        Result<JsonValue.JsonObject, DomainError> bodyRes = parseJsonObject(request.body(), true);
        if (bodyRes instanceof Result.Err<JsonValue.JsonObject, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        JsonValue.JsonObject body = ((Result.Ok<JsonValue.JsonObject, DomainError>) bodyRes).value();
        Result<List<String>, DomainError> targetsRes = readStringList(body, "targets", false);
        if (targetsRes instanceof Result.Err<List<String>, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> useDefaultRes = readOptionalBoolean(body, "default", false);
        if (useDefaultRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> allRes = readOptionalBoolean(body, "all", false);
        if (allRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> forceRes = readOptionalBoolean(body, "force", false);
        if (forceRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Optional<String> branch = Optional.empty();
        Optional<JsonValue> branchVal = body.get("branch");
        if (branchVal.isPresent() && !(branchVal.get() instanceof JsonValue.JsonNull)) {
            if (!(branchVal.get() instanceof JsonValue.JsonString(String b)) || b.isBlank()) {
                return errorResponse(400, DomainError.of("INVALID_REQUEST", "Field 'branch' must be a non-blank string"), "");
            }
            branch = Optional.of(b);
        }
        List<String> targets = ((Result.Ok<List<String>, DomainError>) targetsRes).value();
        boolean useDefault = ((Result.Ok<Boolean, DomainError>) useDefaultRes).value();
        boolean all = ((Result.Ok<Boolean, DomainError>) allRes).value();
        boolean force = ((Result.Ok<Boolean, DomainError>) forceRes).value();
        if (useDefault == branch.isPresent()) {
            return errorResponse(400, DomainError.of("INVALID_REQUEST", "Provide either 'branch' or 'default: true'"), "");
        }
        String cliCommand = cli.gitSwitch(branch.orElse(""), useDefault, targets, all, force);

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();
        Result<GitShell, DomainError> shellRes = runtime.gitShell();
        if (shellRes instanceof Result.Err<GitShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Result<GitShell.GitBatchReport, DomainError> res = ((Result.Ok<GitShell, DomainError>) shellRes).value()
            .switchBranch(project, branch, useDefault, targets, all, force, Optional.empty());
        if (res instanceof Result.Err<GitShell.GitBatchReport, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        GitShell.GitBatchReport report = ((Result.Ok<GitShell.GitBatchReport, DomainError>) res).value();
        int httpStatus = report.hasFailures() ? 409 : 200;
        Map<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("data", DomainJson.gitBatchReport(report));
        envelope.put("cliCommand", Json.str(cliCommand));
        return ApiResponse.json(httpStatus, Json.obj(envelope));
    }

    private ApiResponse handleGitStash(ApiRequest request) {
        Result<JsonValue.JsonObject, DomainError> bodyRes = parseJsonObject(request.body(), true);
        if (bodyRes instanceof Result.Err<JsonValue.JsonObject, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        JsonValue.JsonObject body = ((Result.Ok<JsonValue.JsonObject, DomainError>) bodyRes).value();
        Result<List<String>, DomainError> targetsRes = readStringList(body, "targets", false);
        if (targetsRes instanceof Result.Err<List<String>, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> allRes = readOptionalBoolean(body, "all", false);
        if (allRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> includeUntrackedRes = readOptionalBoolean(body, "includeUntracked", true);
        if (includeUntrackedRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> forceRes = readOptionalBoolean(body, "force", false);
        if (forceRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Optional<String> message = Optional.empty();
        Optional<JsonValue> msgVal = body.get("message");
        if (msgVal.isPresent() && !(msgVal.get() instanceof JsonValue.JsonNull)) {
            if (!(msgVal.get() instanceof JsonValue.JsonString(String m))) {
                return errorResponse(400, DomainError.of("INVALID_REQUEST", "Field 'message' must be a string"), "");
            }
            if (!m.isBlank()) {
                message = Optional.of(m);
            }
        }
        List<String> targets = ((Result.Ok<List<String>, DomainError>) targetsRes).value();
        boolean all = ((Result.Ok<Boolean, DomainError>) allRes).value();
        boolean includeUntracked = ((Result.Ok<Boolean, DomainError>) includeUntrackedRes).value();
        boolean force = ((Result.Ok<Boolean, DomainError>) forceRes).value();
        String cliCommand = cli.gitStashPush(targets, all, message.orElse(""), includeUntracked, force);

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();
        Result<GitShell, DomainError> shellRes = runtime.gitShell();
        if (shellRes instanceof Result.Err<GitShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Result<GitShell.GitBatchReport, DomainError> res = ((Result.Ok<GitShell, DomainError>) shellRes).value()
            .stashPush(project, targets, all, message, includeUntracked, force, Optional.empty());
        if (res instanceof Result.Err<GitShell.GitBatchReport, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        GitShell.GitBatchReport report = ((Result.Ok<GitShell.GitBatchReport, DomainError>) res).value();
        int httpStatus = report.hasFailures() ? 409 : 200;
        Map<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("data", DomainJson.gitBatchReport(report));
        envelope.put("cliCommand", Json.str(cliCommand));
        return ApiResponse.json(httpStatus, Json.obj(envelope));
    }

    private ApiResponse handleGitCancel(ApiRequest request) {
        Result<JsonValue.JsonObject, DomainError> bodyRes = parseJsonObject(request.body(), true);
        if (bodyRes instanceof Result.Err<JsonValue.JsonObject, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        JsonValue.JsonObject body = ((Result.Ok<JsonValue.JsonObject, DomainError>) bodyRes).value();
        Result<List<String>, DomainError> targetsRes = readStringList(body, "targets", false);
        if (targetsRes instanceof Result.Err<List<String>, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        Result<Boolean, DomainError> allRes = readOptionalBoolean(body, "all", false);
        if (allRes instanceof Result.Err<Boolean, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }
        List<String> targets = ((Result.Ok<List<String>, DomainError>) targetsRes).value();
        boolean all = ((Result.Ok<Boolean, DomainError>) allRes).value();
        String cliCommand = cli.gitCancel(targets, all);

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();
        Result<GitShell, DomainError> shellRes = runtime.gitShell();
        if (shellRes instanceof Result.Err<GitShell, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        Result<GitShell.GitCancelReport, DomainError> res = ((Result.Ok<GitShell, DomainError>) shellRes).value()
            .cancel(project, targets, all);
        if (res instanceof Result.Err<GitShell.GitCancelReport, DomainError>(DomainError err)) {
            return domainErrorResponse(err, cliCommand);
        }
        GitShell.GitCancelReport report = ((Result.Ok<GitShell.GitCancelReport, DomainError>) res).value();
        int httpStatus = report.hasFailures() ? 409 : 200;
        Map<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("data", DomainJson.gitCancelReport(report));
        envelope.put("cliCommand", Json.str(cliCommand));
        return ApiResponse.json(httpStatus, Json.obj(envelope));
    }

    public static int httpStatusFor(DomainError error) {
        String code = error.code();
        return switch (code) {
            case "FORBIDDEN_ORIGIN" -> 403;
            case "METHOD_NOT_ALLOWED" -> 405;
            case "UNKNOWN_SERVICE", "UNKNOWN_PROFILE", "LOG_NOT_FOUND", "CONFIG_NOT_FOUND", "NOT_FOUND", "GIT_UNKNOWN_TARGET" -> 404;
            case "PORT_CONFLICT", "GATEWAY_CONFLICT", "GATEWAY_UNMANAGED", "GATEWAY_NOT_HEALTHY", "CONFLICT_PID_UNKNOWN",
                "BUILD_ALREADY_RUNNING", "BUILD_RUN_UNKNOWN",
                "GIT_RUN_IN_PROGRESS", "GIT_RUN_UNKNOWN_STATE", "GIT_DIRTY_WORKING_TREE", "GIT_NOT_CLONED", "GIT_NOT_A_REPOSITORY" -> 409;
            case "INVALID_REQUEST",
                "INVALID_STOP_OPTIONS",
                "PROFILE_INVALID_NAME",
                "NO_BUILDER_CONFIGURED",
                "ROUTE_INVALID_VALUE",
                "ROUTE_MISSING_SERVICES",
                "GATEWAY_NOT_CONFIGURED",
                "GATEWAY_COMMAND_MISSING",
                "GATEWAY_MISSING_LOCAL_PORT",
                "GATEWAY_MISSING_REMOTE_URL",
                "WORKING_DIR_NOT_FOUND",
                "ARTIFACT_NOT_FOUND",
                "REPOSITORY_NOT_FOUND",
                "GIT_NO_REPOSITORY",
                "GIT_NO_REMOTE_URL",
                "GIT_INVALID_BRANCH",
                "GIT_INVALID_ARGUMENTS",
                "GIT_DEFAULT_BRANCH_UNKNOWN",
                "JSON_PARSE_ERROR",
                "ENV_FILE_PARSE_ERROR" -> 400;
            default -> {
                if (code.startsWith("CONFIG_") || code.startsWith("CATALOG_")) {
                    yield 400;
                }
                yield 500;
            }
        };
    }

    private static Optional<String> extractSegment(String path, String prefix, String suffix) {
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return Optional.empty();
        }
        int start = prefix.length();
        int end = path.length() - suffix.length();
        if (end <= start) {
            return Optional.empty();
        }
        String segment = path.substring(start, end);
        if (segment.indexOf('/') >= 0) {
            return Optional.empty();
        }
        return Optional.of(segment);
    }

    private static Result<String, DomainError> decodeSegment(String raw, String label) {
        try {
            String decoded = StaticAssets.percentDecodePath(raw);
            if (decoded.isBlank()) {
                return Result.err(DomainError.of("INVALID_REQUEST", label + " name must not be blank"));
            }
            return Result.ok(decoded);
        } catch (IllegalArgumentException e) {
            return Result.err(DomainError.of("INVALID_REQUEST", "Malformed URL encoding for " + label + " name"));
        }
    }

    private static Result<JsonValue.JsonObject, DomainError> parseJsonObject(String rawBody, boolean allowEmpty) {
        if (rawBody.isBlank()) {
            if (allowEmpty) {
                return Result.ok(Json.obj(Map.of()));
            }
            return Result.err(DomainError.of("INVALID_REQUEST", "Request body must be a JSON object"));
        }
        Result<JsonValue, DomainError> parsed = Json.parse(rawBody);
        if (parsed instanceof Result.Err<JsonValue, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        JsonValue val = ((Result.Ok<JsonValue, DomainError>) parsed).value();
        if (!(val instanceof JsonValue.JsonObject obj)) {
            return Result.err(DomainError.of("INVALID_REQUEST", "Request body must be a JSON object"));
        }
        return Result.ok(obj);
    }

    private static Result<List<String>, DomainError> readStringList(
        JsonValue.JsonObject obj,
        String field,
        boolean requiredNonEmpty
    ) {
        Optional<JsonValue> valOpt = obj.get(field);
        if (valOpt.isEmpty() || valOpt.get() instanceof JsonValue.JsonNull) {
            if (requiredNonEmpty) {
                return Result.err(DomainError.of("INVALID_REQUEST", "Missing required field '" + field + "'"));
            }
            return Result.ok(List.of());
        }
        if (!(valOpt.get() instanceof JsonValue.JsonArray arr)) {
            return Result.err(DomainError.of("INVALID_REQUEST", "Field '" + field + "' must be an array of strings"));
        }
        List<String> result = new ArrayList<>(arr.elements().size());
        for (JsonValue el : arr.elements()) {
            if (!(el instanceof JsonValue.JsonString(String s)) || s.isBlank()) {
                return Result.err(DomainError.of("INVALID_REQUEST", "Field '" + field + "' must contain non-blank strings"));
            }
            result.add(s);
        }
        if (requiredNonEmpty && result.isEmpty()) {
            return Result.err(DomainError.of("INVALID_REQUEST", "Field '" + field + "' must not be empty"));
        }
        return Result.ok(List.copyOf(result));
    }

    private static Result<Route, DomainError> readRoute(JsonValue.JsonObject obj, String field) {
        Optional<JsonValue> valOpt = obj.get(field);
        if (valOpt.isEmpty() || !(valOpt.get() instanceof JsonValue.JsonString(String raw))) {
            return Result.err(DomainError.of("INVALID_REQUEST", "Field '" + field + "' must be 'LOCAL' or 'REMOTE'"));
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "LOCAL" -> Result.ok(Route.LOCAL);
            case "REMOTE" -> Result.ok(Route.REMOTE);
            default -> Result.err(DomainError.of(
                "ROUTE_INVALID_VALUE",
                "Invalid route '" + raw + "': expected LOCAL or REMOTE",
                Map.of("route", raw)
            ));
        };
    }

    private static Result<Boolean, DomainError> readOptionalBoolean(
        JsonValue.JsonObject obj,
        String field,
        boolean defaultValue
    ) {
        Optional<JsonValue> valOpt = obj.get(field);
        if (valOpt.isEmpty() || valOpt.get() instanceof JsonValue.JsonNull) {
            return Result.ok(defaultValue);
        }
        if (valOpt.get() instanceof JsonValue.JsonBoolean(boolean b)) {
            return Result.ok(b);
        }
        return Result.err(DomainError.of("INVALID_REQUEST", "Field '" + field + "' must be a boolean"));
    }

    private static Result<Integer, DomainError> readOptionalPositiveInt(
        JsonValue.JsonObject obj,
        String field,
        int defaultValue
    ) {
        Optional<JsonValue> valOpt = obj.get(field);
        if (valOpt.isEmpty() || valOpt.get() instanceof JsonValue.JsonNull) {
            return Result.ok(defaultValue);
        }
        if (valOpt.get() instanceof JsonValue.JsonNumber num) {
            OptionalInt parsed = num.asInt();
            if (parsed.isPresent() && parsed.getAsInt() >= 1) {
                return Result.ok(parsed.getAsInt());
            }
        }
        return Result.err(DomainError.of("INVALID_REQUEST", "Field '" + field + "' must be a positive integer"));
    }

    private static Result<Map<String, String>, DomainError> parseQuery(String rawQuery) {
        if (rawQuery.isBlank()) {
            return Result.ok(Map.of());
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String rawVal = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                String val = URLDecoder.decode(rawVal, StandardCharsets.UTF_8);
                params.put(key, val);
            } catch (IllegalArgumentException e) {
                return Result.err(DomainError.of("INVALID_REQUEST", "Malformed URL encoding in query string"));
            }
        }
        return Result.ok(params);
    }

    private static List<String> parseCsvList(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : raw.split(",", -1)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return List.copyOf(items);
    }

    private static Result<Integer, DomainError> parseOptionalQueryPositiveInt(
        Map<String, String> query,
        String key,
        int defaultValue
    ) {
        String raw = query.get(key);
        if (raw == null || raw.isBlank()) {
            return Result.ok(defaultValue);
        }
        try {
            int val = Integer.parseInt(raw.trim());
            if (val < 1) {
                return Result.err(DomainError.of("INVALID_REQUEST", "Query parameter '" + key + "' must be a positive integer"));
            }
            return Result.ok(val);
        } catch (NumberFormatException e) {
            return Result.err(DomainError.of("INVALID_REQUEST", "Query parameter '" + key + "' must be an integer"));
        }
    }

    private static Result<Integer, DomainError> parseOptionalQueryNonNegativeInt(
        Map<String, String> query,
        String key,
        int defaultValue
    ) {
        String raw = query.get(key);
        if (raw == null || raw.isBlank()) {
            return Result.ok(defaultValue);
        }
        try {
            int val = Integer.parseInt(raw.trim());
            if (val < 0) {
                return Result.err(DomainError.of("INVALID_REQUEST", "Query parameter '" + key + "' must be non-negative"));
            }
            return Result.ok(val);
        } catch (NumberFormatException e) {
            return Result.err(DomainError.of("INVALID_REQUEST", "Query parameter '" + key + "' must be an integer"));
        }
    }

    private static Result<Boolean, DomainError> parseOptionalQueryBoolean(
        Map<String, String> query,
        String key,
        boolean defaultValue
    ) {
        String raw = query.get(key);
        if (raw == null || raw.isBlank()) {
            return Result.ok(defaultValue);
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(lower)) {
            return Result.ok(true);
        }
        if ("false".equals(lower)) {
            return Result.ok(false);
        }
        return Result.err(DomainError.of("INVALID_REQUEST", "Query parameter '" + key + "' must be 'true' or 'false'"));
    }

    public record ApiRequest(
        String method,
        String path,
        String rawQuery,
        Map<String, String> headers,
        String body
    ) {
        public ApiRequest {
            Objects.requireNonNull(method, "method must not be null");
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(rawQuery, "rawQuery must not be null");
            Objects.requireNonNull(headers, "headers must not be null");
            Objects.requireNonNull(body, "body must not be null");
            method = method.toUpperCase(Locale.ROOT);
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                copy.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
            headers = Collections.unmodifiableMap(copy);
        }

        public static ApiRequest get(String path) {
            return new ApiRequest("GET", path, "", Map.of(), "");
        }

        public static ApiRequest get(String path, String rawQuery) {
            return new ApiRequest("GET", path, rawQuery, Map.of(), "");
        }

        public static ApiRequest of(String method, String path, Map<String, String> headers, String body) {
            return new ApiRequest(method, path, "", headers, body);
        }

        public static ApiRequest of(
            String method,
            String path,
            String rawQuery,
            Map<String, String> headers,
            String body
        ) {
            return new ApiRequest(method, path, rawQuery, headers, body);
        }

        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
        }
    }

    public record ApiResponse(int statusCode, String contentType, String body) {
        public ApiResponse {
            Objects.requireNonNull(contentType, "contentType must not be null");
            Objects.requireNonNull(body, "body must not be null");
        }

        public static ApiResponse json(int statusCode, JsonValue value) {
            return new ApiResponse(statusCode, JSON_CONTENT_TYPE, Json.stringify(value));
        }
    }

    public interface SseSink extends AutoCloseable {

        /**
         * Sends an SSE event frame. Returns {@code false} when the client has disconnected.
         */
        boolean send(String event, String data);

        /**
         * Returns {@code true} while the SSE stream remains open.
         */
        boolean isOpen();

        @Override
        void close();
    }
}
