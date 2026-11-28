package procomp.web;

import procomp.Assert;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.process.ObservedProcess;
import procomp.core.process.ParentProcess;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeProjectFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.platform.FsConfigSource;
import procomp.shell.ProcompRuntime;
import procomp.shell.ProjectLoader;
import procomp.shell.ports.HttpClient.HttpResponseData;
import procomp.shell.ports.ProcessKey;
import procomp.web.ApiRouter.ApiRequest;
import procomp.web.ApiRouter.ApiResponse;
import procomp.web.ApiRouter.SseSink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class ApiRouterTest {

    private static final int PORT = 9090;
    private static final Map<String, String> LOCAL_HEADERS = Map.of(
        "Host", "127.0.0.1:9090",
        "Origin", "http://127.0.0.1:9090",
        "Sec-Fetch-Site", "same-origin"
    );

    private static ApiRequest localGet(String path) {
        return ApiRequest.of("GET", path, "", LOCAL_HEADERS, "");
    }

    private static ApiRequest localGet(String path, String rawQuery) {
        return ApiRequest.of("GET", path, rawQuery, LOCAL_HEADERS, "");
    }

    public static void testStatusAndInspectEndpointsReturnExactWireContractAndCliCommands() {
        Fixture fx = Fixture.create(Optional.of(Path.of("custom/procomp.json")));

        // Seed billing as UNMANAGED (IDE) + HEALTHY
        fx.portScanner.bind(9002, 5200L);
        fx.processRunner.registerProcess(new ObservedProcess(
            5200L, true, Optional.of(Instant.parse("2026-11-20T11:58:00Z")),
            Optional.of("java -jar billing.jar"), List.of("-jar", "billing.jar"),
            List.of(new ParentProcess(10L, Optional.of("idea64.exe"), Optional.of("idea64.exe"))),
            Optional.of(128_000_000L), Optional.of("java")
        ));
        fx.httpClient.stubGet(URI.create("http://127.0.0.1:9002/actuator/health"), 200, "UP");

        ApiResponse statusRes = fx.router.handle(localGet("/api/status"));
        Assert.assertEquals(200, statusRes.statusCode(), "GET /api/status returns 200: " + statusRes.body());
        Assert.assertEquals(ApiRouter.JSON_CONTENT_TYPE, statusRes.contentType(), "JSON content type");

        JsonValue.JsonObject statusRoot = parseObj(statusRes.body());
        Assert.assertEquals(
            "procomp --config " + Path.of("custom/procomp.json") + " status",
            str(statusRoot, "cliCommand"),
            "status cliCommand echoes --config"
        );
        Assert.assertEquals(fx.configPath.toString(), str(statusRoot, "configPath"), "configPath matches loaded path");

        JsonValue.JsonArray profiles = arr(statusRoot, "profiles");
        Assert.assertEquals(2, profiles.elements().size(), "2 profiles returned");
        Assert.assertEquals("dev", str((JsonValue.JsonObject) profiles.elements().get(0), "name"), "first profile dev");

        JsonValue.JsonObject data = obj(statusRoot, "data");
        Assert.assertEquals("acme", str(data, "project"), "project name");
        Assert.assertEquals(3, arr(data, "services").elements().size(), "3 services");
        Assert.assertEquals(
            128_000_000L,
            ((JsonValue.JsonNumber) data.get("totalRssBytes").orElseThrow()).asLong().orElseThrow(),
            "/api/status totalRssBytes"
        );

        // GET /api/services/billing/inspect
        ApiResponse inspectRes = fx.router.handle(localGet("/api/services/billing/inspect"));
        Assert.assertEquals(200, inspectRes.statusCode(), "GET /api/services/billing/inspect returns 200");
        JsonValue.JsonObject inspectRoot = parseObj(inspectRes.body());
        Assert.assertEquals(
            "procomp --config " + Path.of("custom/procomp.json") + " inspect billing",
            str(inspectRoot, "cliCommand"),
            "inspect cliCommand"
        );
        Assert.assertEquals(
            "procomp --config " + Path.of("custom/procomp.json") + " stop billing --kill-conflict",
            str(inspectRoot, "killConflictCliCommand"),
            "inspect killConflictCliCommand echoes --config"
        );
        JsonValue.JsonObject inspectData = obj(inspectRoot, "data");
        Assert.assertEquals("billing", str(inspectData, "service"), "inspected service");
        Assert.assertEquals("UNMANAGED", str(inspectData, "owner"), "owner UNMANAGED");
        Assert.assertEquals("IDE", str(inspectData, "provenance"), "provenance IDE");
        Assert.assertEquals(1, arr(inspectData, "parentChain").elements().size(), "parentChain populated");
        Assert.assertEquals(
            128_000_000L,
            ((JsonValue.JsonNumber) inspectData.get("rssBytes").orElseThrow()).asLong().orElseThrow(),
            "/api/services/billing/inspect rssBytes"
        );
        JsonValue.JsonArray diagCmds = arr(inspectData, "diagnosticCommands");
        Assert.assertEquals(3, diagCmds.elements().size(), "inspect returns 3 JVM diagnostic commands");
        Assert.assertEquals(
            "jcmd 5200 GC.heap_info",
            str((JsonValue.JsonObject) diagCmds.elements().get(0), "command"),
            "first diagnostic command uses live PID"
        );

        // Unknown service -> 404
        ApiResponse missingRes = fx.router.handle(localGet("/api/services/ghost/inspect"));
        Assert.assertEquals(404, missingRes.statusCode(), "unknown service returns 404");
        JsonValue.JsonObject missingRoot = parseObj(missingRes.body());
        Assert.assertEquals("UNKNOWN_SERVICE", str(obj(missingRoot, "error"), "code"), "UNKNOWN_SERVICE code");
    }

    public static void testRouteAndProfileMutationsPreserveUnixSeparationAndEchoCliCommands() {
        Fixture fx = Fixture.create(Optional.empty());

        // PUT /api/routes -> updates routing table only, spawns zero processes, makes zero HTTP calls
        ApiResponse routeRes = fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/routes",
            LOCAL_HEADERS,
            "{\"services\":[\"orders\"],\"route\":\"LOCAL\"}"
        ));
        Assert.assertEquals(200, routeRes.statusCode(), "PUT /api/routes returns 200: " + routeRes.body());
        JsonValue.JsonObject routeRoot = parseObj(routeRes.body());
        Assert.assertEquals("procomp route orders local", str(routeRoot, "cliCommand"), "route cliCommand");
        JsonValue.JsonArray routesArr = arr(obj(routeRoot, "data"), "routes");
        Assert.assertEquals("LOCAL", str((JsonValue.JsonObject) routesArr.elements().get(0), "route"), "orders is now LOCAL");
        Assert.assertEquals(0, fx.processRunner.spawnedRequests().size(), "UNIX separation: route never spawns processes");
        Assert.assertEquals(0, fx.httpClient.recordedRequests().size(), "UNIX separation: route never touches gateway");

        // POST /api/profiles/dev/use -> applies profile only
        ApiResponse profileRes = fx.router.handle(ApiRequest.of(
            "POST",
            "/api/profiles/dev/use",
            LOCAL_HEADERS,
            ""
        ));
        Assert.assertEquals(200, profileRes.statusCode(), "POST /api/profiles/dev/use returns 200: " + profileRes.body());
        JsonValue.JsonObject profileRoot = parseObj(profileRes.body());
        Assert.assertEquals("procomp profile use dev", str(profileRoot, "cliCommand"), "profile cliCommand");
        Assert.assertEquals(0, fx.processRunner.spawnedRequests().size(), "UNIX separation: profile use never spawns processes");
        Assert.assertEquals(0, fx.httpClient.recordedRequests().size(), "UNIX separation: profile use never touches gateway");

        // Unknown profile -> 404
        ApiResponse badProfile = fx.router.handle(ApiRequest.of("POST", "/api/profiles/nope/use", LOCAL_HEADERS, ""));
        Assert.assertEquals(404, badProfile.statusCode(), "unknown profile returns 404");
        Assert.assertEquals("UNKNOWN_PROFILE", str(obj(parseObj(badProfile.body()), "error"), "code"), "UNKNOWN_PROFILE");

        // Invalid route value -> 400
        ApiResponse badRoute = fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/routes",
            LOCAL_HEADERS,
            "{\"services\":[\"orders\"],\"route\":\"SIDEWAYS\"}"
        ));
        Assert.assertEquals(400, badRoute.statusCode(), "invalid route returns 400");
        Assert.assertEquals("ROUTE_INVALID_VALUE", str(obj(parseObj(badRoute.body()), "error"), "code"), "ROUTE_INVALID_VALUE");
    }

    public static void testCsrfGuardRejectsCrossOriginAndMissingHostOnMutations() {
        Fixture fx = Fixture.create(Optional.empty());
        String body = "{\"services\":[\"orders\"],\"route\":\"LOCAL\"}";

        List<Map<String, String>> rejectedHeaders = List.of(
            Map.of(),
            Map.of("Host", "evil.com:9090"),
            Map.of("Host", "127.0.0.1:9999"),
            Map.of("Host", "127.0.0.1:9090"),
            Map.of("Host", "127.0.0.1:9090", "Origin", "https://evil.com"),
            Map.of("Host", "127.0.0.1:9090", "Origin", "http://127.0.0.1:9999"),
            Map.of("Host", "127.0.0.1:9090", "Origin", "null"),
            Map.of("Host", "127.0.0.1:9090", "Sec-Fetch-Site", "cross-site"),
            Map.of("Host", "127.0.0.1:9090", "Sec-Fetch-Site", "same-site"),
            Map.of("Host", "127.0.0.1:9090", "Sec-Fetch-Site", "none")
        );

        for (Map<String, String> headers : rejectedHeaders) {
            ApiResponse res = fx.router.handle(ApiRequest.of("PUT", "/api/routes", headers, body));
            Assert.assertEquals(403, res.statusCode(), "Expected 403 for headers " + headers + ", got " + res.statusCode());
            Assert.assertEquals("FORBIDDEN_ORIGIN", str(obj(parseObj(res.body()), "error"), "code"), "FORBIDDEN_ORIGIN");

            Optional<ApiResponse> ssePreflight = fx.router.preflightSse(
                ApiRequest.of("POST", "/api/services/start/stream", headers, "{\"services\":[\"orders\"]}")
            );
            Assert.assertTrue(ssePreflight.isPresent(), "Expected SSE preflight rejection for headers " + headers);
            Assert.assertEquals(403, ssePreflight.get().statusCode(), "SSE preflight returns 403 for headers " + headers);
            Assert.assertEquals(
                "FORBIDDEN_ORIGIN",
                str(obj(parseObj(ssePreflight.get().body()), "error"), "code"),
                "SSE preflight FORBIDDEN_ORIGIN"
            );
        }

        // DNS rebinding guard: GET /api/status also rejects non-loopback Host
        ApiResponse badHostGet = fx.router.handle(ApiRequest.of("GET", "/api/status", Map.of("Host", "evil.com:9090"), ""));
        Assert.assertEquals(403, badHostGet.statusCode(), "non-loopback Host rejected on GET /api/status");

        // Valid localhost Host with Content-Type: application/json (e.g. curl) and with localhost Origin are accepted
        ApiResponse jsonCurl = fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/routes",
            Map.of("Host", "localhost:9090", "Content-Type", "application/json"),
            body
        ));
        Assert.assertEquals(200, jsonCurl.statusCode(), "localhost Host with application/json allowed");

        ApiResponse localhostOrigin = fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/routes",
            Map.of("Host", "localhost:9090", "Origin", "http://localhost:9090", "Sec-Fetch-Site", "same-origin"),
            body
        ));
        Assert.assertEquals(200, localhostOrigin.statusCode(), "matching localhost Origin allowed");

        Optional<ApiResponse> allowedSse = fx.router.preflightSse(
            ApiRequest.of("POST", "/api/services/start/stream", LOCAL_HEADERS, "{\"services\":[\"orders\"]}")
        );
        Assert.assertTrue(allowedSse.isEmpty(), "same-origin POST SSE start stream passes preflight");
    }

    public static void testStartStopConflictKillGatingAndStartSseStream() {
        Fixture fx = Fixture.create(Optional.empty());
        fx.httpClient.stubGet(URI.create("http://127.0.0.1:9001/actuator/health"), 200, "UP");

        // SSE start stream: POST /api/services/start/stream (GET is rejected with 405)
        Assert.assertFalse(fx.router.isSseEndpoint("GET", "/api/services/start/stream"), "GET not allowed on start stream");
        Assert.assertTrue(fx.router.isSseEndpoint("POST", "/api/services/start/stream"), "POST recognized as SSE endpoint");
        RecordingSseSink sink = new RecordingSseSink();
        fx.router.handleSse(
            ApiRequest.of(
                "POST",
                "/api/services/start/stream",
                LOCAL_HEADERS,
                "{\"services\":[\"orders\"],\"timeoutSeconds\":15}"
            ),
            sink
        );

        Assert.assertFalse(sink.isOpen(), "SSE sink closed after completion");
        Assert.assertTrue(sink.events().size() >= 3, "expected meta + progress + done events, got " + sink.events().size());
        Assert.assertEquals("meta", sink.events().get(0).event(), "first event is meta");
        Assert.assertEquals("procomp start orders --timeout 15", str(parseObj(sink.events().get(0).data()), "cliCommand"), "meta cliCommand");

        boolean sawProgress = sink.events().stream().anyMatch(e -> "progress".equals(e.event()));
        Assert.assertTrue(sawProgress, "SSE stream emitted at least one progress event");

        SseEvent last = sink.events().get(sink.events().size() - 1);
        Assert.assertEquals("done", last.event(), "final event is done");
        JsonValue.JsonObject doneRoot = parseObj(last.data());
        Assert.assertEquals("procomp start orders --timeout 15", str(doneRoot, "cliCommand"), "done cliCommand");
        Assert.assertEquals(1, arr(obj(doneRoot, "data"), "outcomes").elements().size(), "1 started service");

        // Conflict on payments port 9003
        fx.portScanner.bind(9003, 6300L);
        fx.processRunner.registerProcess(new ObservedProcess(
            6300L, true, Optional.of(Instant.parse("2026-11-20T11:30:00Z")),
            Optional.of("python -m http.server 9003"), List.of(),
            List.of(), Optional.empty(), Optional.of("python")
        ));

        // GET /api/services/payments/inspect returns plain killConflictCliCommand (without --config)
        ApiResponse conflictInspect = fx.router.handle(localGet("/api/services/payments/inspect"));
        Assert.assertEquals(200, conflictInspect.statusCode(), "conflict inspect returns 200");
        Assert.assertEquals(
            "procomp stop payments --kill-conflict",
            str(parseObj(conflictInspect.body()), "killConflictCliCommand"),
            "plain inspect killConflictCliCommand"
        );

        // POST /api/services/start on conflicting port -> 409 PORT_CONFLICT
        ApiResponse conflictStart = fx.router.handle(ApiRequest.of(
            "POST",
            "/api/services/start",
            LOCAL_HEADERS,
            "{\"services\":[\"payments\"],\"detach\":false,\"timeoutSeconds\":10}"
        ));
        Assert.assertEquals(409, conflictStart.statusCode(), "conflict start returns 409: " + conflictStart.body());
        Assert.assertEquals("PORT_CONFLICT", str(obj(parseObj(conflictStart.body()), "error"), "code"), "PORT_CONFLICT");

        // POST /api/services/stop with killConflict=false spares conflict
        ApiResponse spareRes = fx.router.handle(ApiRequest.of(
            "POST",
            "/api/services/stop",
            LOCAL_HEADERS,
            "{\"services\":[\"payments\"],\"all\":false,\"killConflict\":false}"
        ));
        Assert.assertEquals(200, spareRes.statusCode(), "stop without killConflict returns 200");
        JsonValue.JsonObject spareRoot = parseObj(spareRes.body());
        Assert.assertEquals("procomp stop payments", str(spareRoot, "cliCommand"), "stop cliCommand");
        JsonValue.JsonObject spareOutcome = (JsonValue.JsonObject) arr(obj(spareRoot, "data"), "outcomes").elements().get(0);
        Assert.assertEquals("SPARED_CONFLICT", str(spareOutcome, "disposition"), "conflict spared");

        // POST /api/services/stop with killConflict=true kills conflict and echoes --kill-conflict
        ApiResponse killRes = fx.router.handle(ApiRequest.of(
            "POST",
            "/api/services/stop",
            LOCAL_HEADERS,
            "{\"services\":[\"payments\"],\"all\":false,\"killConflict\":true}"
        ));
        Assert.assertEquals(200, killRes.statusCode(), "stop with killConflict returns 200");
        JsonValue.JsonObject killRoot = parseObj(killRes.body());
        Assert.assertEquals("procomp stop payments --kill-conflict", str(killRoot, "cliCommand"), "kill-conflict cliCommand");
        JsonValue.JsonObject killOutcome = (JsonValue.JsonObject) arr(obj(killRoot, "data"), "outcomes").elements().get(0);
        Assert.assertEquals("KILLED_CONFLICT", str(killOutcome, "disposition"), "conflict killed");
    }

    public static void testGatewayStartStopAndReconcileEndpoints() {
        Fixture fx = Fixture.create(Optional.empty());
        Map<String, JsonValue.JsonObject> liveMappings = new LinkedHashMap<>();
        fx.httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "{\"status\":\"healthy\"}");
        fx.httpClient.stub("GET", URI.create("http://127.0.0.1:8080/__admin/mappings"), _ -> {
            List<JsonValue> arr = new ArrayList<>(liveMappings.values());
            return Result.ok(new HttpResponseData(200, Map.of(), Json.stringify(Json.obj(Map.of("mappings", Json.arr(arr))))));
        });
        fx.httpClient.stub("POST", URI.create("http://127.0.0.1:8080/__admin/mappings/import"), req -> {
            JsonValue.JsonObject payload = parseObj(req.body());
            for (JsonValue el : arr(payload, "mappings").elements()) {
                JsonValue.JsonObject m = (JsonValue.JsonObject) el;
                liveMappings.put(str(m, "id"), m);
            }
            return Result.ok(new HttpResponseData(200, Map.of(), "{}"));
        });

        // POST /api/gateway/start
        ApiResponse startRes = fx.router.handle(ApiRequest.of(
            "POST",
            "/api/gateway/start",
            LOCAL_HEADERS,
            "{\"detach\":false,\"timeoutSeconds\":30}"
        ));
        Assert.assertEquals(200, startRes.statusCode(), "gateway start 200: " + startRes.body());
        JsonValue.JsonObject startRoot = parseObj(startRes.body());
        Assert.assertEquals("procomp gateway start", str(startRoot, "cliCommand"), "gateway start cliCommand");
        Assert.assertEquals("SPAWNED", str(obj(startRoot, "data"), "disposition"), "gateway SPAWNED");

        // POST /api/gateway/reconcile
        ApiResponse reconcileRes = fx.router.handle(ApiRequest.of("POST", "/api/gateway/reconcile", LOCAL_HEADERS, ""));
        Assert.assertEquals(200, reconcileRes.statusCode(), "gateway reconcile 200: " + reconcileRes.body());
        JsonValue.JsonObject reconcileRoot = parseObj(reconcileRes.body());
        Assert.assertEquals("procomp gateway reconcile", str(reconcileRoot, "cliCommand"), "gateway reconcile cliCommand");
        Assert.assertEquals("IN_SYNC", str(obj(reconcileRoot, "data"), "syncStatus"), "gateway IN_SYNC");

        // POST /api/gateway/restart
        ApiResponse gwRestartRes = fx.router.handle(ApiRequest.of(
            "POST",
            "/api/gateway/restart",
            LOCAL_HEADERS,
            "{\"detach\":false,\"timeoutSeconds\":30}"
        ));
        Assert.assertEquals(200, gwRestartRes.statusCode(), "gateway restart 200: " + gwRestartRes.body());
        JsonValue.JsonObject gwRestartRoot = parseObj(gwRestartRes.body());
        Assert.assertEquals("procomp gateway restart", str(gwRestartRoot, "cliCommand"), "gateway restart cliCommand");
        Assert.assertEquals("RESTARTED", str(obj(gwRestartRoot, "data"), "disposition"), "gateway RESTARTED");

        // POST /api/gateway/stop
        ApiResponse stopRes = fx.router.handle(ApiRequest.of("POST", "/api/gateway/stop", LOCAL_HEADERS, ""));
        Assert.assertEquals(200, stopRes.statusCode(), "gateway stop 200: " + stopRes.body());
        JsonValue.JsonObject stopRoot = parseObj(stopRes.body());
        Assert.assertEquals("procomp gateway stop", str(stopRoot, "cliCommand"), "gateway stop cliCommand");
        Assert.assertEquals("STOPPED_MANAGED", str(obj(stopRoot, "data"), "disposition"), "gateway STOPPED_MANAGED");

        // POST /api/services/restart & POST /api/services/restart/stream + 409 PROCESS_STOPPING
        fx.httpClient.stubGet(URI.create("http://127.0.0.1:9001/actuator/health"), 200, "OK");
        ApiResponse svcRestartRes = fx.router.handle(ApiRequest.of(
            "POST",
            "/api/services/restart",
            LOCAL_HEADERS,
            "{\"services\":[\"orders\"],\"detach\":true,\"timeoutSeconds\":30}"
        ));
        Assert.assertEquals(200, svcRestartRes.statusCode(), "services restart 200: " + svcRestartRes.body());
        JsonValue.JsonObject svcRestartRoot = parseObj(svcRestartRes.body());
        Assert.assertEquals("procomp restart orders -d", str(svcRestartRoot, "cliCommand"), "services restart cliCommand");
        Assert.assertEquals("SPAWNED", str((JsonValue.JsonObject) arr(obj(svcRestartRoot, "data"), "outcomes").elements().get(0), "disposition"), "stopped orders SPAWNED");

        Assert.assertTrue(fx.router.isSseEndpoint("POST", "/api/services/restart/stream"), "restart stream is POST SSE");
        ByteArrayOutputStream restartSseOut = new ByteArrayOutputStream();
        SseStream restartSse = new SseStream(restartSseOut);
        fx.router.handleSse(ApiRequest.of(
            "POST",
            "/api/services/restart/stream",
            LOCAL_HEADERS,
            "{\"services\":[\"orders\"],\"detach\":false,\"timeoutSeconds\":30}"
        ), restartSse);
        String restartWire = restartSseOut.toString(StandardCharsets.UTF_8);
        Assert.assertContains(restartWire, "event: meta\n", "restart SSE meta");
        Assert.assertContains(restartWire, "procomp restart orders", "restart SSE cliCommand");
        Assert.assertContains(restartWire, "event: done\n", "restart SSE done");
        Assert.assertContains(restartWire, "\"disposition\":\"RESTARTED\"", "restart SSE RESTARTED");

        // Mark orders STOPPING -> POST /api/services/restart returns 409 PROCESS_STOPPING
        procomp.core.process.ManagedProcessRecord ordersRec = Assert.assertOk(
            fx.stateStore.readManagedProcess("acme", ProcessKey.service("orders")), "read orders"
        ).orElseThrow();
        Assert.assertOk(
            fx.stateStore.writeManagedProcess("acme", ProcessKey.service("orders"), ordersRec.requestStop(fx.clock.now())),
            "mark orders stopping"
        );
        ApiResponse stoppingRestart = fx.router.handle(ApiRequest.of(
            "POST",
            "/api/services/restart",
            LOCAL_HEADERS,
            "{\"services\":[\"orders\"],\"detach\":true,\"timeoutSeconds\":30}"
        ));
        Assert.assertEquals(409, stoppingRestart.statusCode(), "restart while STOPPING returns 409");
        Assert.assertEquals("PROCESS_STOPPING", str(obj(parseObj(stoppingRestart.body()), "error"), "code"), "PROCESS_STOPPING code");

        // Unmanaged gateway stop -> 409 GATEWAY_UNMANAGED
        fx.portScanner.bind(8080, 9999L);
        fx.processRunner.registerProcess(new ObservedProcess(
            9999L, true, Optional.of(Instant.parse("2026-11-20T11:50:00Z")),
            Optional.of("java -jar wiremock.jar --port 8080"), List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));
        ApiResponse unmanagedStop = fx.router.handle(ApiRequest.of("POST", "/api/gateway/stop", LOCAL_HEADERS, ""));
        Assert.assertEquals(409, unmanagedStop.statusCode(), "unmanaged gateway stop returns 409");
        Assert.assertEquals("GATEWAY_UNMANAGED", str(obj(parseObj(unmanagedStop.body()), "error"), "code"), "GATEWAY_UNMANAGED");
    }

    public static void testLogsAndLogsSseStreamWithDisconnectCancellation() {
        Fixture fx = Fixture.create(Optional.empty());
        Path outLog = fx.stateStore.logPath("acme", ProcessKey.service("orders"), false);
        fx.stateStore.appendLog(outLog, "line1\nline2\nline3\n");

        // GET /api/services/orders/logs?lines=2&err=false
        ApiResponse logsRes = fx.router.handle(localGet("/api/services/orders/logs", "lines=2&err=false"));
        Assert.assertEquals(200, logsRes.statusCode(), "logs 200: " + logsRes.body());
        JsonValue.JsonObject logsRoot = parseObj(logsRes.body());
        Assert.assertEquals("procomp logs orders -n 2", str(logsRoot, "cliCommand"), "logs cliCommand");
        Assert.assertEquals("line2\nline3\n", str(obj(logsRoot, "data"), "text"), "tailed 2 lines");
        Assert.assertEquals(outLog.toString(), str(obj(logsRoot, "data"), "path"), "log path");

        // GET /api/services/orders/logs/stream?lines=2&err=false via SseStream that fails after 3 writes
        Assert.assertTrue(fx.router.isSseEndpoint("GET", "/api/services/orders/logs/stream"), "logs stream is SSE");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AtomicInteger writes = new AtomicInteger(0);
        OutputStream disconnectAfter3 = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                captured.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (writes.incrementAndGet() > 3) {
                    throw new IOException("client disconnected");
                }
                captured.write(b, off, len);
                // Append another log line after initial tail so follow loop sees new output and hits disconnect
                if (writes.get() == 3) {
                    fx.stateStore.appendLog(outLog, "line4\n");
                }
            }
        };

        SseStream sseStream = new SseStream(disconnectAfter3);
        fx.router.handleSse(localGet("/api/services/orders/logs/stream", "lines=2&err=false"), sseStream);

        Assert.assertFalse(sseStream.isOpen(), "SseStream marked closed after client disconnect");
        String wire = captured.toString(StandardCharsets.UTF_8);
        Assert.assertContains(wire, "event: meta\n", "emitted meta frame");
        Assert.assertContains(wire, "procomp logs orders -n 2 -f", "meta contains follow cliCommand");
        Assert.assertContains(wire, "\"startOffset\":6", "meta contains startOffset");
        Assert.assertContains(wire, "\"hasOlder\":true", "meta contains hasOlder");
        Assert.assertContains(wire, "event: log\n", "emitted log frames");
        Assert.assertContains(wire, "\"line\":\"line2\\n\"", "emitted line2");
        Assert.assertContains(wire, "\"line\":\"line3\\n\"", "emitted line3");
    }

    public static void testLogsBackwardPaginationTruncationAndValidation() {
        Fixture fx = Fixture.create(Optional.empty());
        Path outLog = fx.stateStore.logPath("acme", ProcessKey.service("orders"), false);
        fx.stateStore.appendLog(outLog, "l1\nl2\nl3\nl4\nl5\n");

        ApiResponse page1Res = fx.router.handle(localGet("/api/services/orders/logs", "lines=2"));
        Assert.assertEquals(200, page1Res.statusCode(), "page 1 200");
        JsonValue.JsonObject page1 = obj(parseObj(page1Res.body()), "data");
        Assert.assertEquals("l4\nl5\n", str(page1, "text"), "page 1 text");
        Assert.assertEquals(9L, num(page1, "startOffset"), "page 1 startOffset");
        Assert.assertEquals(15L, num(page1, "endOffset"), "page 1 endOffset");
        Assert.assertTrue(bool(page1, "hasOlder"), "page 1 hasOlder");
        Assert.assertFalse(bool(page1, "truncated"), "page 1 not truncated");

        ApiResponse page2Res = fx.router.handle(localGet("/api/services/orders/logs", "lines=2&beforeOffset=9"));
        Assert.assertEquals(200, page2Res.statusCode(), "page 2 200");
        JsonValue.JsonObject page2 = obj(parseObj(page2Res.body()), "data");
        Assert.assertEquals("l2\nl3\n", str(page2, "text"), "page 2 text");
        Assert.assertEquals(3L, num(page2, "startOffset"), "page 2 startOffset");
        Assert.assertEquals(9L, num(page2, "endOffset"), "page 2 endOffset");
        Assert.assertTrue(bool(page2, "hasOlder"), "page 2 hasOlder");

        ApiResponse page3Res = fx.router.handle(localGet("/api/services/orders/logs", "lines=2&beforeOffset=3"));
        Assert.assertEquals(200, page3Res.statusCode(), "page 3 200");
        JsonValue.JsonObject page3 = obj(parseObj(page3Res.body()), "data");
        Assert.assertEquals("l1\n", str(page3, "text"), "page 3 text");
        Assert.assertEquals(0L, num(page3, "startOffset"), "page 3 startOffset 0");
        Assert.assertEquals(3L, num(page3, "endOffset"), "page 3 endOffset");
        Assert.assertFalse(bool(page3, "hasOlder"), "page 3 hasOlder false");

        fx.stateStore.replaceLog(outLog, "new\n");
        ApiResponse truncRes = fx.router.handle(localGet("/api/services/orders/logs", "lines=2&beforeOffset=9"));
        Assert.assertEquals(200, truncRes.statusCode(), "truncated returns 200");
        JsonValue.JsonObject trunc = obj(parseObj(truncRes.body()), "data");
        Assert.assertEquals("", str(trunc, "text"), "truncated text empty");
        Assert.assertEquals(0L, num(trunc, "startOffset"), "truncated startOffset 0");
        Assert.assertFalse(bool(trunc, "hasOlder"), "truncated hasOlder false");
        Assert.assertTrue(bool(trunc, "truncated"), "truncated flag true");

        ApiResponse negRes = fx.router.handle(localGet("/api/services/orders/logs", "beforeOffset=-1"));
        Assert.assertEquals(400, negRes.statusCode(), "negative beforeOffset returns 400");
        ApiResponse badRes = fx.router.handle(localGet("/api/services/orders/logs", "beforeOffset=abc"));
        Assert.assertEquals(400, badRes.statusCode(), "non-numeric beforeOffset returns 400");

        // Build Run SSE meta emits startOffset and hasOlder
        Path buildOut = fx.stateStore.logPath("acme", ProcessKey.build("orders"), false);
        fx.stateStore.appendLog(buildOut, "b1\nb2\nb3\n");
        ByteArrayOutputStream buildWire = new ByteArrayOutputStream();
        AtomicInteger buildWrites = new AtomicInteger(0);
        SseStream buildSink = new SseStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (buildWrites.incrementAndGet() > 2) {
                    throw new IOException("client disconnected");
                }
                buildWire.write(b, off, len);
            }
        });
        fx.router.handleSse(localGet("/api/services/orders/logs/stream", "lines=2&build=true"), buildSink);
        String buildText = buildWire.toString(StandardCharsets.UTF_8);
        Assert.assertContains(buildText, "\"startOffset\":3", "build SSE meta contains startOffset");
        Assert.assertContains(buildText, "\"hasOlder\":true", "build SSE meta contains hasOlder");
    }

    public static void testLiveProjectHolderAutoReloadsAndRecoversFromInvalidConfig() throws IOException {
        Path tempDir = Files.createTempDirectory("procomp-live-reload-test");
        try {
            Path configFile = tempDir.resolve("procomp.json");
            Files.writeString(configFile, """
                {
                  "project": "reload-demo",
                  "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
                  "services": {
                    "alpha": { "runtime": "java", "port": 9101, "workingDir": "." }
                  }
                }
                """, StandardCharsets.UTF_8);

            ProcompRuntime runtime = ProcompRuntime.builder()
                .configSource(new FsConfigSource())
                .stateStore(new InMemoryStateStore())
                .processRunner(new FakeProcessRunner(new FakeClock(Instant.parse("2026-11-20T12:00:00Z"))))
                .portScanner(new FakePortScanner())
                .httpClient(new FakeHttpClient())
                .projectFiles(new FakeProjectFiles().withDirectory(tempDir))
                .clock(new FakeClock(Instant.parse("2026-11-20T12:00:00Z")))
                .build();
            ProjectLoader loader = Assert.assertOk(runtime.projectLoader(), "projectLoader");
            LiveProjectHolder holder = new LiveProjectHolder(loader, Optional.of(configFile));
            ApiRouter router = new ApiRouter(runtime, holder, PORT);

            // 1. Initial load sees 1 service
            ApiResponse res1 = router.handle(localGet("/api/status"));
            Assert.assertEquals(200, res1.statusCode(), "initial status 200");
            Assert.assertEquals(1, arr(obj(parseObj(res1.body()), "data"), "services").elements().size(), "1 service initially");

            // 2. Edit procomp.json on disk to add beta -> reloads automatically
            Files.writeString(configFile, """
                {
                  "project": "reload-demo",
                  "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
                  "services": {
                    "alpha": { "runtime": "java", "port": 9101, "workingDir": "." },
                    "beta": { "runtime": "java", "port": 9102, "workingDir": "." }
                  }
                }
                """, StandardCharsets.UTF_8);
            ApiResponse res2 = router.handle(localGet("/api/status"));
            Assert.assertEquals(200, res2.statusCode(), "reloaded status 200");
            Assert.assertEquals(2, arr(obj(parseObj(res2.body()), "data"), "services").elements().size(), "2 services after reload");

            // 3. Save invalid JSON -> returns 400 DomainError without crashing
            Files.writeString(configFile, "{ invalid json", StandardCharsets.UTF_8);
            ApiResponse resInvalid = router.handle(localGet("/api/status"));
            Assert.assertEquals(400, resInvalid.statusCode(), "invalid config returns 400");
            Assert.assertEquals("JSON_PARSE_ERROR", str(obj(parseObj(resInvalid.body()), "error"), "code"), "JSON_PARSE_ERROR");

            // 4. Fix procomp.json -> recovers cleanly
            Files.writeString(configFile, """
                {
                  "project": "reload-demo",
                  "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
                  "services": {
                    "alpha": { "runtime": "java", "port": 9101, "workingDir": "." }
                  }
                }
                """, StandardCharsets.UTF_8);
            ApiResponse resRecovered = router.handle(localGet("/api/status"));
            Assert.assertEquals(200, resRecovered.statusCode(), "recovered status 200");
            Assert.assertEquals(1, arr(obj(parseObj(resRecovered.body()), "data"), "services").elements().size(), "recovered 1 service");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public static void testConcurrentRouteMutationsAreSerializedByPerProjectLock() throws Exception {
        Fixture fx = Fixture.create(Optional.empty());
        int threads = 8;
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<ApiResponse>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    if (idx % 2 == 0) {
                        return fx.router.handle(ApiRequest.of(
                            "PUT",
                            "/api/routes",
                            LOCAL_HEADERS,
                            "{\"services\":[\"orders\"],\"route\":\"LOCAL\"}"
                        ));
                    }
                    return fx.router.handle(ApiRequest.of("POST", "/api/profiles/dev/use", LOCAL_HEADERS, ""));
                }));
            }
            startGate.countDown();
            for (Future<ApiResponse> f : futures) {
                ApiResponse res = f.get();
                Assert.assertEquals(200, res.statusCode(), "concurrent mutation succeeded: " + res.body());
            }
        }
    }

    private record SseEvent(String event, String data) {}

    private static final class RecordingSseSink implements SseSink {
        private final List<SseEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean open = new AtomicBoolean(true);

        @Override
        public boolean send(String event, String data) {
            if (!open.get()) {
                return false;
            }
            events.add(new SseEvent(event, data));
            return true;
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public void close() {
            open.set(false);
        }

        List<SseEvent> events() {
            return events;
        }
    }

    public static void testProfileSaveAndDeleteEndpointsRoundTripAndEnforceCsrf() {
        Fixture fx = Fixture.create(Optional.empty());

        // 1. CSRF rejected on PUT and DELETE without same-origin headers
        ApiResponse csrfPut = fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/profiles/custom",
            Map.of("Host", "127.0.0.1:9090", "Origin", "https://evil.example"),
            "{\"local\":[\"orders\"]}"
        ));
        Assert.assertEquals(403, csrfPut.statusCode(), "PUT /api/profiles/{name} enforces CSRF");

        ApiResponse csrfDelete = fx.router.handle(ApiRequest.of(
            "DELETE",
            "/api/profiles/dev",
            Map.of("Host", "127.0.0.1:9090", "Origin", "https://evil.example"),
            ""
        ));
        Assert.assertEquals(403, csrfDelete.statusCode(), "DELETE /api/profiles/{name} enforces CSRF");

        // 2. PUT explicit local services -> 200, echoes CLI command, visible immediately in GET /api/status
        ApiResponse saveExplicit = fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/profiles/payments-only",
            LOCAL_HEADERS,
            "{\"local\":[\"payments\"]}"
        ));
        Assert.assertEquals(200, saveExplicit.statusCode(), "PUT explicit returns 200: " + saveExplicit.body());
        JsonValue.JsonObject saveExplicitRoot = parseObj(saveExplicit.body());
        Assert.assertEquals("procomp profile save payments-only payments", str(saveExplicitRoot, "cliCommand"), "explicit cliCommand");
        Assert.assertEquals(3, arr(obj(saveExplicitRoot, "data"), "profiles").elements().size(), "3 profiles returned");

        // 3. PUT with empty local list -> echoes --none
        ApiResponse saveEmpty = fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/profiles/zero-local",
            LOCAL_HEADERS,
            "{\"local\":[]}"
        ));
        Assert.assertEquals(200, saveEmpty.statusCode(), "PUT empty returns 200: " + saveEmpty.body());
        Assert.assertEquals("procomp profile save zero-local --none", str(parseObj(saveEmpty.body()), "cliCommand"), "--none cliCommand");

        // 4. PUT with fromCurrentRoutes: true snapshots current Routing Table
        Assert.assertEquals(200, fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/routes",
            LOCAL_HEADERS,
            "{\"services\":[\"orders\"],\"route\":\"LOCAL\"}"
        )).statusCode(), "set orders LOCAL");

        ApiResponse saveSnap = fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/profiles/snap-slice",
            LOCAL_HEADERS,
            "{\"fromCurrentRoutes\":true}"
        ));
        Assert.assertEquals(200, saveSnap.statusCode(), "PUT snapshot returns 200: " + saveSnap.body());
        JsonValue.JsonObject saveSnapRoot = parseObj(saveSnap.body());
        Assert.assertEquals("procomp profile save snap-slice", str(saveSnapRoot, "cliCommand"), "snapshot cliCommand");
        JsonValue.JsonArray snapLocal = arr(obj(obj(saveSnapRoot, "data"), "profile"), "local");
        Assert.assertEquals(1, snapLocal.elements().size(), "snapshot captured 1 LOCAL service");
        Assert.assertEquals("orders", ((JsonValue.JsonString) snapLocal.elements().get(0)).value(), "snapshot captured orders");

        // 5. GET /api/status immediately reflects saved profiles
        ApiResponse statusRes = fx.router.handle(localGet("/api/status"));
        Assert.assertEquals(200, statusRes.statusCode(), "status returns 200");
        Assert.assertEquals(5, arr(parseObj(statusRes.body()), "profiles").elements().size(), "status reflects 5 profiles");

        // 6. DELETE removes profile and returns 200; deleting again returns 404 UNKNOWN_PROFILE
        ApiResponse delRes = fx.router.handle(ApiRequest.of("DELETE", "/api/profiles/snap-slice", LOCAL_HEADERS, ""));
        Assert.assertEquals(200, delRes.statusCode(), "DELETE returns 200: " + delRes.body());
        JsonValue.JsonObject delRoot = parseObj(delRes.body());
        Assert.assertEquals("procomp profile delete snap-slice", str(delRoot, "cliCommand"), "delete cliCommand");
        Assert.assertEquals(4, arr(obj(delRoot, "data"), "profiles").elements().size(), "4 profiles remain");

        ApiResponse delMissing = fx.router.handle(ApiRequest.of("DELETE", "/api/profiles/snap-slice", LOCAL_HEADERS, ""));
        Assert.assertEquals(404, delMissing.statusCode(), "DELETE missing returns 404");
        Assert.assertEquals("UNKNOWN_PROFILE", str(obj(parseObj(delMissing.body()), "error"), "code"), "UNKNOWN_PROFILE code");

        // 7. Invalid profile name -> 400 PROFILE_INVALID_NAME; unknown service -> 404 UNKNOWN_SERVICE
        ApiResponse badName = fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/profiles/-flag",
            LOCAL_HEADERS,
            "{\"local\":[\"orders\"]}"
        ));
        Assert.assertEquals(400, badName.statusCode(), "leading dash returns 400");
        Assert.assertEquals("PROFILE_INVALID_NAME", str(obj(parseObj(badName.body()), "error"), "code"), "PROFILE_INVALID_NAME code");

        ApiResponse badSvc = fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/profiles/bad-svc",
            LOCAL_HEADERS,
            "{\"local\":[\"ghost\"]}"
        ));
        Assert.assertEquals(404, badSvc.statusCode(), "unknown service returns 404");
        Assert.assertEquals("UNKNOWN_SERVICE", str(obj(parseObj(badSvc.body()), "error"), "code"), "UNKNOWN_SERVICE code");

        ApiResponse ambiguous = fx.router.handle(ApiRequest.of(
            "PUT",
            "/api/profiles/ambiguous",
            LOCAL_HEADERS,
            "{\"fromCurrentRoutes\":true,\"local\":[\"orders\"]}"
        ));
        Assert.assertEquals(400, ambiguous.statusCode(), "ambiguous body returns 400");
        Assert.assertEquals("INVALID_REQUEST", str(obj(parseObj(ambiguous.body()), "error"), "code"), "INVALID_REQUEST code");
    }

    private static final class Fixture {
        final Path root;
        final Path configPath;
        final InMemoryConfigSource configSource;
        final InMemoryStateStore stateStore;
        final FakeClock clock;
        final FakeProcessRunner processRunner;
        final FakePortScanner portScanner;
        final FakeHttpClient httpClient;
        final FakeProjectFiles projectFiles;
        final ProcompRuntime runtime;
        final ApiRouter router;

        private Fixture(
            Path root,
            Path configPath,
            InMemoryConfigSource configSource,
            InMemoryStateStore stateStore,
            FakeClock clock,
            FakeProcessRunner processRunner,
            FakePortScanner portScanner,
            FakeHttpClient httpClient,
            FakeProjectFiles projectFiles,
            ProcompRuntime runtime,
            ApiRouter router
        ) {
            this.root = root;
            this.configPath = configPath;
            this.configSource = configSource;
            this.stateStore = stateStore;
            this.clock = clock;
            this.processRunner = processRunner;
            this.portScanner = portScanner;
            this.httpClient = httpClient;
            this.projectFiles = projectFiles;
            this.runtime = runtime;
            this.router = router;
        }

        static Fixture create(Optional<Path> configOverride) {
            Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
            Path configPath = configOverride.map(p -> root.resolve(p).normalize())
                .orElse(root.resolve("procomp.json"));
            InMemoryConfigSource configSource = new InMemoryConfigSource(root)
                .withFile(configPath, """
                    {
                      "project": "acme",
                      "defaults": {
                        "proxyPathTemplate": "/{{service}}",
                        "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}"
                      },
                      "runtimes": {
                        "java": {
                          "command": ["java", "-Dserver.port={{port}}", "-jar", "app.jar"],
                          "healthPath": "/actuator/health"
                        }
                      },
                      "gateway": {
                        "type": "wiremock",
                        "port": 8080,
                        "healthPath": "/__admin/health",
                        "command": ["java", "-jar", "wiremock.jar", "--port", "8080"]
                      },
                      "profiles": {
                        "dev": { "local": ["orders", "billing"] },
                        "remote": { "local": [] }
                      },
                      "services": {
                        "orders": { "runtime": "java", "port": 9001, "workingDir": "orders" },
                        "billing": { "runtime": "java", "port": 9002, "workingDir": "billing" },
                        "payments": { "runtime": "java", "port": 9003, "workingDir": "payments" }
                      }
                    }
                    """);
            InMemoryStateStore stateStore = new InMemoryStateStore();
            FakeClock clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
            FakeProcessRunner processRunner = new FakeProcessRunner(clock);
            FakePortScanner portScanner = new FakePortScanner();
            FakeHttpClient httpClient = new FakeHttpClient();
            FakeProjectFiles projectFiles = new FakeProjectFiles()
                .withDirectory(root)
                .withDirectory(root.resolve("orders"))
                .withDirectory(root.resolve("billing"))
                .withDirectory(root.resolve("payments"));

            ProcompRuntime runtime = ProcompRuntime.builder()
                .configSource(configSource)
                .stateStore(stateStore)
                .processRunner(processRunner)
                .portScanner(portScanner)
                .httpClient(httpClient)
                .projectFiles(projectFiles)
                .clock(clock)
                .build();
            ProjectLoader loader = Assert.assertOk(runtime.projectLoader(), "projectLoader");
            LiveProjectHolder holder = new LiveProjectHolder(loader, configOverride);
            ApiRouter router = new ApiRouter(runtime, holder, PORT, Duration.ofMillis(1));
            return new Fixture(
                root, configPath, configSource, stateStore, clock, processRunner, portScanner, httpClient, projectFiles, runtime, router
            );
        }
    }

    private static JsonValue.JsonObject parseObj(String json) {
        return (JsonValue.JsonObject) Assert.assertOk(Json.parse(json), "valid json: " + json);
    }

    private static JsonValue.JsonObject obj(JsonValue.JsonObject parent, String key) {
        return (JsonValue.JsonObject) parent.get(key).orElseThrow(() -> new AssertionError("missing object key: " + key));
    }

    private static JsonValue.JsonArray arr(JsonValue.JsonObject parent, String key) {
        return (JsonValue.JsonArray) parent.get(key).orElseThrow(() -> new AssertionError("missing array key: " + key));
    }

    private static String str(JsonValue.JsonObject parent, String key) {
        return ((JsonValue.JsonString) parent.get(key).orElseThrow(() -> new AssertionError("missing string key: " + key))).value();
    }

    private static long num(JsonValue.JsonObject parent, String key) {
        return ((JsonValue.JsonNumber) parent.get(key).orElseThrow(() -> new AssertionError("missing number key: " + key)))
            .asLong()
            .orElseThrow(() -> new AssertionError("non-integer number key: " + key));
    }

    private static boolean bool(JsonValue.JsonObject parent, String key) {
        return ((JsonValue.JsonBoolean) parent.get(key).orElseThrow(() -> new AssertionError("missing boolean key: " + key))).value();
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
