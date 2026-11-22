package procomp.web;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.process.InspectionReport;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.Project;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.CliCommandFormatter;
import procomp.shell.DomainJson;
import procomp.shell.GatewayShell;
import procomp.shell.GatewayShell.GatewayReconcileResult;
import procomp.shell.GatewayShell.GatewayStartRequest;
import procomp.shell.GatewayShell.GatewayStartResult;
import procomp.shell.GatewayShell.GatewayStopRequest;
import procomp.shell.GatewayShell.GatewayStopResult;
import procomp.shell.LogsShell;
import procomp.shell.LogsShell.LogsRequest;
import procomp.shell.LogsShell.LogsResult;
import procomp.shell.ProcessShell;
import procomp.shell.ProcessShell.StartRequest;
import procomp.shell.ProcessShell.StartResult;
import procomp.shell.ProcessShell.StopRequest;
import procomp.shell.ProcessShell.StopResult;
import procomp.shell.ProcompRuntime;
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

    private final ProcompRuntime runtime;
    private final LiveProjectHolder projectHolder;
    private final int boundPort;
    private final Duration logsPollInterval;
    private final ReentrantLock mutationLock = new ReentrantLock();

    public ApiRouter(ProcompRuntime runtime, LiveProjectHolder projectHolder, int boundPort) {
        this(runtime, projectHolder, boundPort, LogsShell.DEFAULT_POLL_INTERVAL);
    }

    public ApiRouter(
        ProcompRuntime runtime,
        LiveProjectHolder projectHolder,
        int boundPort,
        Duration logsPollInterval
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.projectHolder = Objects.requireNonNull(projectHolder, "projectHolder must not be null");
        if (boundPort <= 0 || boundPort > 65535) {
            throw new IllegalArgumentException("boundPort must be in 1..65535: " + boundPort);
        }
        this.boundPort = boundPort;
        this.logsPollInterval = Objects.requireNonNull(logsPollInterval, "logsPollInterval must not be null");
    }

    /**
     * Returns {@code true} when the given HTTP method and path target an SSE streaming endpoint.
     */
    public boolean isSseEndpoint(String method, String path) {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(path, "path must not be null");
        if (!"GET".equalsIgnoreCase(method)) {
            return false;
        }
        if ("/api/services/start/stream".equals(path)) {
            return true;
        }
        return extractServiceSegment(path, "/logs/stream").isPresent();
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
            return methodNotAllowed(method, "GET");
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

        Optional<String> profileUseSegment = extractProfileUseSegment(path);
        if (profileUseSegment.isPresent()) {
            if (!"POST".equals(method)) {
                return methodNotAllowed(method, "POST");
            }
            return handleUseProfile(profileUseSegment.get());
        }

        Optional<String> inspectSegment = extractServiceSegment(path, "/inspect");
        if (inspectSegment.isPresent()) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method, "GET");
            }
            return handleInspect(inspectSegment.get());
        }

        Optional<String> logsSegment = extractServiceSegment(path, "/logs");
        if (logsSegment.isPresent()) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method, "GET");
            }
            return handleLogs(logsSegment.get(), request.rawQuery());
        }

        if (extractServiceSegment(path, "/logs/stream").isPresent()) {
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
            Optional<String> logsStreamSegment = extractServiceSegment(path, "/logs/stream");
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
        String cliCommand = CliCommandFormatter.status(configOverride(), false);
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
        String cliCommand = CliCommandFormatter.inspect(configOverride(), serviceName, false);

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
        return okEnvelope(DomainJson.inspectionReport(project, report), cliCommand);
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

        String cliCommand = CliCommandFormatter.updateRoutes(configOverride(), services, route);

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
        String cliCommand = CliCommandFormatter.useProfile(configOverride(), profileName);

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
        Result<Integer, DomainError> timeoutRes = readOptionalNonNegativeInt(
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
        String cliCommand = CliCommandFormatter.start(configOverride(), services, detach, timeout);

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
    }

    private void handleStartServicesStream(ApiRequest request, SseSink sink) {
        Optional<DomainError> sseCsrfErr = checkOptionalCrossOriginHeaders(request);
        if (sseCsrfErr.isPresent()) {
            sendSseError(sink, sseCsrfErr.get(), "");
            return;
        }

        Result<Map<String, String>, DomainError> queryRes = parseQuery(request.rawQuery());
        if (queryRes instanceof Result.Err<Map<String, String>, DomainError>(DomainError err)) {
            sendSseError(sink, err, "");
            return;
        }
        Map<String, String> query = ((Result.Ok<Map<String, String>, DomainError>) queryRes).value();

        List<String> services = parseCsvList(query.getOrDefault("services", ""));
        Result<Integer, DomainError> timeoutRes = parseOptionalQueryNonNegativeInt(
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

        boolean detach = ((Result.Ok<Boolean, DomainError>) detachRes).value();
        Duration timeout = Duration.ofSeconds(((Result.Ok<Integer, DomainError>) timeoutRes).value());
        String cliCommand = CliCommandFormatter.start(configOverride(), services, detach, timeout);

        sink.send("meta", Json.stringify(Json.obj(Map.of("cliCommand", Json.str(cliCommand)))));

        Result<LoadedProject, DomainError> loadedRes = projectHolder.current();
        if (loadedRes instanceof Result.Err<LoadedProject, DomainError>(DomainError err)) {
            sendSseError(sink, err, cliCommand);
            return;
        }
        Project project = ((Result.Ok<LoadedProject, DomainError>) loadedRes).value().project();

        ProcompRuntime streamRuntime = runtime.withObserver(event ->
            sink.send("progress", Json.stringify(DomainJson.progressEvent(event)))
        );
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
        String cliCommand = CliCommandFormatter.stop(configOverride(), services, all, killConflict);

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
        Result<Integer, DomainError> timeoutRes = readOptionalNonNegativeInt(
            obj,
            "timeoutSeconds",
            (int) GatewayStartRequest.DEFAULT_TIMEOUT.toSeconds()
        );
        if (timeoutRes instanceof Result.Err<Integer, DomainError>(DomainError err)) {
            return errorResponse(400, err, "");
        }

        boolean detach = ((Result.Ok<Boolean, DomainError>) detachRes).value();
        Duration timeout = Duration.ofSeconds(((Result.Ok<Integer, DomainError>) timeoutRes).value());
        String cliCommand = CliCommandFormatter.gatewayStart(configOverride(), detach, timeout);

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
    }

    private ApiResponse handleGatewayStop() {
        String cliCommand = CliCommandFormatter.gatewayStop(configOverride());

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
    }

    private ApiResponse handleGatewayReconcile() {
        String cliCommand = CliCommandFormatter.gatewayReconcile(configOverride());

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

        int lines = ((Result.Ok<Integer, DomainError>) linesRes).value();
        boolean stderr = ((Result.Ok<Boolean, DomainError>) errFlagRes).value();
        String cliCommand = CliCommandFormatter.logs(configOverride(), serviceName, stderr, false, lines, false);

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
            .run(project, new LogsRequest(serviceName, stderr, false, lines, false));
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

        int lines = ((Result.Ok<Integer, DomainError>) linesRes).value();
        boolean stderr = ((Result.Ok<Boolean, DomainError>) errFlagRes).value();
        String cliCommand = CliCommandFormatter.logs(configOverride(), serviceName, stderr, false, lines, true);

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
            .run(project, new LogsRequest(serviceName, stderr, false, lines, true), logsPollInterval, sink::isOpen);
        if (logsRes instanceof Result.Err<LogsResult, DomainError>(DomainError err)) {
            sendSseError(sink, err, cliCommand);
        }
    }

    private Optional<Path> configOverride() {
        return projectHolder.configOverride();
    }

    private Optional<DomainError> checkCsrf(ApiRequest request) {
        Optional<String> hostOpt = request.header("Host");
        if (hostOpt.isEmpty() || !isAllowedHost(hostOpt.get())) {
            return Optional.of(DomainError.of(
                "FORBIDDEN_ORIGIN",
                "Rejected mutating request with missing or non-loopback Host header",
                Map.of("host", hostOpt.orElse("<missing>"))
            ));
        }
        return checkOptionalCrossOriginHeaders(request);
    }

    private Optional<DomainError> checkOptionalCrossOriginHeaders(ApiRequest request) {
        Optional<String> hostOpt = request.header("Host");
        if (hostOpt.isPresent() && !isAllowedHost(hostOpt.get())) {
            return Optional.of(DomainError.of(
                "FORBIDDEN_ORIGIN",
                "Rejected request with non-loopback Host header '" + hostOpt.get() + "'",
                Map.of("host", hostOpt.get())
            ));
        }
        Optional<String> originOpt = request.header("Origin");
        if (originOpt.isPresent() && !originOpt.get().isBlank() && !isAllowedOrigin(originOpt.get())) {
            return Optional.of(DomainError.of(
                "FORBIDDEN_ORIGIN",
                "Rejected cross-origin request from Origin '" + originOpt.get() + "'",
                Map.of("origin", originOpt.get())
            ));
        }
        Optional<String> secFetchSiteOpt = request.header("Sec-Fetch-Site");
        if (secFetchSiteOpt.isPresent() && !secFetchSiteOpt.get().isBlank()) {
            String val = secFetchSiteOpt.get().trim().toLowerCase(Locale.ROOT);
            if (!"same-origin".equals(val) && !"none".equals(val)) {
                return Optional.of(DomainError.of(
                    "FORBIDDEN_ORIGIN",
                    "Rejected request with Sec-Fetch-Site '" + secFetchSiteOpt.get() + "'",
                    Map.of("secFetchSite", secFetchSiteOpt.get())
                ));
            }
        }
        return Optional.empty();
    }

    private boolean isAllowedHost(String rawHost) {
        String host = rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.equals("127.0.0.1:" + boundPort) || host.equals("localhost:" + boundPort)) {
            return true;
        }
        return boundPort == 80 && (host.equals("127.0.0.1") || host.equals("localhost"));
    }

    private boolean isAllowedOrigin(String rawOrigin) {
        String origin = rawOrigin.trim().toLowerCase(Locale.ROOT);
        if (origin.equals("http://127.0.0.1:" + boundPort) || origin.equals("http://localhost:" + boundPort)) {
            return true;
        }
        return boundPort == 80 && (origin.equals("http://127.0.0.1") || origin.equals("http://localhost"));
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

    public static int httpStatusFor(DomainError error) {
        String code = error.code();
        return switch (code) {
            case "FORBIDDEN_ORIGIN" -> 403;
            case "METHOD_NOT_ALLOWED" -> 405;
            case "UNKNOWN_SERVICE", "UNKNOWN_PROFILE", "LOG_NOT_FOUND", "CONFIG_NOT_FOUND", "NOT_FOUND" -> 404;
            case "PORT_CONFLICT", "GATEWAY_CONFLICT", "GATEWAY_UNMANAGED", "GATEWAY_NOT_HEALTHY", "CONFLICT_PID_UNKNOWN" -> 409;
            case "INVALID_REQUEST",
                "INVALID_STOP_OPTIONS",
                "ROUTE_INVALID_VALUE",
                "ROUTE_MISSING_SERVICES",
                "GATEWAY_NOT_CONFIGURED",
                "GATEWAY_COMMAND_MISSING",
                "GATEWAY_MISSING_LOCAL_PORT",
                "GATEWAY_MISSING_REMOTE_URL",
                "WORKING_DIR_NOT_FOUND",
                "ARTIFACT_NOT_FOUND",
                "REPOSITORY_NOT_FOUND",
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

    private static Optional<String> extractServiceSegment(String path, String suffix) {
        if (!path.startsWith(SERVICES_PREFIX) || !path.endsWith(suffix)) {
            return Optional.empty();
        }
        int start = SERVICES_PREFIX.length();
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

    private static Optional<String> extractProfileUseSegment(String path) {
        String suffix = "/use";
        if (!path.startsWith(PROFILES_PREFIX) || !path.endsWith(suffix)) {
            return Optional.empty();
        }
        int start = PROFILES_PREFIX.length();
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
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
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

    private static Result<Integer, DomainError> readOptionalNonNegativeInt(
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
            if (parsed.isPresent() && parsed.getAsInt() >= 0) {
                return Result.ok(parsed.getAsInt());
            }
        }
        return Result.err(DomainError.of("INVALID_REQUEST", "Field '" + field + "' must be a non-negative integer"));
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
