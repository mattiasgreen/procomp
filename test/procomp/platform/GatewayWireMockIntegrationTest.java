package procomp.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.gateway.GatewaySyncStatus;
import procomp.core.gateway.RouteDrift;
import procomp.core.process.HealthState;
import procomp.core.process.Ownership;
import procomp.core.project.Project;
import procomp.core.routing.Route;
import procomp.fakes.RecordingObserver;
import procomp.shell.GatewayShell;
import procomp.shell.ProjectLoader;
import procomp.shell.RoutingShell;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class GatewayWireMockIntegrationTest {

    public static void testReconcileAndStatusAgainstRealHttpServerWireMockStubAndUnmanagedStopRefusal() throws Exception {
        WireMockStubServer stub = new WireMockStubServer(true);
        stub.putRawMapping("user-custom-1", """
            {"id":"user-custom-1","request":{"method":"GET","urlPath":"/custom-health"},"response":{"status":200}}""");
        stub.putRawMapping("stale-acme-1", """
            {"id":"stale-acme-1","request":{"method":"ANY","urlPathPattern":"/retired(/.*)?"},"response":{"proxyBaseUrl":"https://shared-dev.mycorp/retired","proxyUrlPrefixToRemove":"/retired"},"metadata":{"procomp":true,"procompProject":"acme","service":"retired","route":"REMOTE"}}""");
        stub.start();

        Path tmpDir = Files.createTempDirectory("procomp-gw-int");
        try {
            Project project = writeAndLoadProject(tmpDir, stub.port());
            FsStateStore stateStore = new FsStateStore(System.getProperty("os.name", ""), Map.of("PROCOMP_STATE_DIR", tmpDir.resolve("state").toString()), tmpDir);
            GatewayShell gatewayShell = new GatewayShell(
                stateStore, new OsProcessRunner(), new OsPortScanner(), new JdkHttpClient(), new FsProjectFiles(), new SystemClock(), new RecordingObserver()
            );
            RoutingShell routingShell = new RoutingShell(stateStore);

            // 1. gateway start recognizes real HttpServer as UNMANAGED + HEALTHY and reuses without spawning
            GatewayShell.GatewayStartResult startRes = Assert.assertOk(
                gatewayShell.start(project, new GatewayShell.GatewayStartRequest(false, Duration.ofSeconds(5))),
                "gateway start reuses real HttpServer"
            );
            Assert.assertEquals(GatewayShell.GatewayStartDisposition.REUSED_UNMANAGED, startRes.disposition(), "reused unmanaged");
            Assert.assertEquals(Ownership.UNMANAGED, startRes.discovery().ownership(), "ownership UNMANAGED");
            Assert.assertEquals(HealthState.HEALTHY, startRes.discovery().health(), "health HEALTHY");

            // 2. status detects UNSET + stale managed mapping -> DRIFTED
            Assert.assertOk(routingShell.updateRoutes(project, List.of("orders"), Route.LOCAL), "route orders LOCAL");
            GatewayShell.GatewayStatusReport beforeReconcile = Assert.assertOk(gatewayShell.status(project), "status before reconcile");
            Assert.assertEquals(GatewaySyncStatus.DRIFTED, beforeReconcile.drift().syncStatus(), "DRIFTED before reconcile");
            Assert.assertEquals(1, beforeReconcile.drift().staleMappings().size(), "1 stale mapping detected");

            // 3. reconcile deletes stale managed mapping, keeps unrelated user stub, imports orders (LOCAL) & billing (REMOTE) -> IN_SYNC
            GatewayShell.GatewayReconcileResult reconciled = Assert.assertOk(gatewayShell.reconcile(project), "reconcile against HttpServer");
            Assert.assertEquals(2, reconciled.appliedMappings(), "applied 2 mappings");
            Assert.assertEquals(1, reconciled.deletedPriorMappings(), "deleted 1 stale managed mapping");
            Assert.assertFalse(reconciled.usedSingleMappingFallback(), "used /import endpoint");
            Assert.assertEquals(GatewaySyncStatus.IN_SYNC, reconciled.drift().syncStatus(), "IN_SYNC after reconcile");
            Assert.assertTrue(stub.hasMapping("user-custom-1"), "unrelated user stub preserved");
            Assert.assertFalse(stub.hasMapping("stale-acme-1"), "stale mapping removed");

            // 4. Flip route in Routing Table -> status detects exact drift -> reconcile converges
            Assert.assertOk(routingShell.updateRoutes(project, List.of("orders"), Route.REMOTE), "flip orders REMOTE");
            GatewayShell.GatewayStatusReport drifted = Assert.assertOk(gatewayShell.status(project), "status after flip");
            Assert.assertEquals(GatewaySyncStatus.DRIFTED, drifted.drift().syncStatus(), "DRIFTED after route change");
            Assert.assertEquals(List.of(new RouteDrift("orders", Route.REMOTE, "LOCAL")), drifted.drift().drifted(), "orders desired REMOTE live LOCAL");

            Assert.assertOk(gatewayShell.reconcile(project), "second reconcile succeeds");
            Assert.assertEquals(GatewaySyncStatus.IN_SYNC, Assert.assertOk(gatewayShell.status(project), "status after second reconcile").drift().syncStatus(), "IN_SYNC again");

            // 5. gateway stop refuses to kill Unmanaged HttpServer and leaves it serving /__admin/health
            DomainError stopErr = Assert.assertErr(
                gatewayShell.stop(project, new GatewayShell.GatewayStopRequest(Duration.ofSeconds(5))),
                "gateway stop refuses on Unmanaged HttpServer"
            );
            Assert.assertEquals("GATEWAY_UNMANAGED", stopErr.code(), "GATEWAY_UNMANAGED error code");
            var healthAfterStopAttempt = Assert.assertOk(
                new JdkHttpClient().get(URI.create("http://127.0.0.1:" + stub.port() + "/__admin/health"), Duration.ofSeconds(2)),
                "HttpServer still alive after refused stop"
            );
            Assert.assertEquals(200, healthAfterStopAttempt.statusCode(), "HttpServer still responds 200");
        } finally {
            stub.close();
            deleteRecursively(tmpDir);
        }
    }

    public static void testReconcileFallsBackToSingleMappingPostsWhenImportReturns404() throws Exception {
        WireMockStubServer stub = new WireMockStubServer(false);
        stub.start();

        Path tmpDir = Files.createTempDirectory("procomp-gw-fallback");
        try {
            Project project = writeAndLoadProject(tmpDir, stub.port());
            FsStateStore stateStore = new FsStateStore(System.getProperty("os.name", ""), Map.of("PROCOMP_STATE_DIR", tmpDir.resolve("state").toString()), tmpDir);
            GatewayShell gatewayShell = new GatewayShell(
                stateStore, new OsProcessRunner(), new OsPortScanner(), new JdkHttpClient(), new FsProjectFiles(), new SystemClock(), new RecordingObserver()
            );

            GatewayShell.GatewayReconcileResult res = Assert.assertOk(gatewayShell.reconcile(project), "reconcile with 404 fallback succeeds");
            Assert.assertTrue(res.usedSingleMappingFallback(), "used POST /__admin/mappings fallback");
            Assert.assertEquals(2, res.appliedMappings(), "applied 2 mappings individually");
            Assert.assertEquals(GatewaySyncStatus.IN_SYNC, Assert.assertOk(gatewayShell.status(project), "status after fallback").drift().syncStatus(), "IN_SYNC via fallback");
        } finally {
            stub.close();
            deleteRecursively(tmpDir);
        }
    }

    private static Project writeAndLoadProject(Path tmpDir, int gatewayPort) throws IOException {
        Path cfg = tmpDir.resolve("procomp.json");
        Files.writeString(cfg, """
            {
              "project": "acme",
              "defaults": {
                "proxyPathTemplate": "/{{service}}",
                "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}"
              },
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "gateway": {
                "type": "wiremock",
                "port": %d,
                "healthPath": "/__admin/health"
              },
              "services": {
                "orders": { "runtime": "java", "port": 9001, "workingDir": "." },
                "billing": { "runtime": "java", "port": 9002, "workingDir": "." }
              }
            }
            """.formatted(gatewayPort), StandardCharsets.UTF_8);
        return Assert.assertOk(new ProjectLoader(new FsConfigSource()).load(Optional.of(cfg)), "load project");
    }

    private static final class WireMockStubServer implements AutoCloseable {
        private final HttpServer server;
        private final boolean supportImport;
        private final Map<String, JsonValue.JsonObject> mappings = new LinkedHashMap<>();

        WireMockStubServer(boolean supportImport) throws IOException {
            this.supportImport = supportImport;
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/__admin/health", ex -> sendJson(ex, 200, "{\"status\":\"healthy\"}"));
            server.createContext("/__admin/mappings", this::handleMappings);
        }

        void start() {
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        synchronized void putRawMapping(String id, String json) {
            JsonValue.JsonObject obj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(json), "valid stub json");
            mappings.put(id, obj);
        }

        synchronized boolean hasMapping(String id) {
            return mappings.containsKey(id);
        }

        private void handleMappings(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            if ("GET".equals(method) && "/__admin/mappings".equals(path)) {
                String resp;
                synchronized (this) {
                    resp = Json.stringify(Json.obj(Map.of("mappings", Json.arr(new ArrayList<>(mappings.values())))));
                }
                sendJson(ex, 200, resp);
                return;
            }
            if ("POST".equals(method) && "/__admin/mappings/import".equals(path)) {
                if (!supportImport) {
                    sendJson(ex, 404, "{\"error\":\"not found\"}");
                    return;
                }
                JsonValue.JsonObject obj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(body), "import body");
                JsonValue.JsonArray arr = (JsonValue.JsonArray) obj.get("mappings").orElseThrow();
                synchronized (this) {
                    for (JsonValue el : arr.elements()) {
                        JsonValue.JsonObject m = (JsonValue.JsonObject) el;
                        String id = ((JsonValue.JsonString) m.get("id").orElseThrow()).value();
                        mappings.put(id, m);
                    }
                }
                sendJson(ex, 200, "{}");
                return;
            }
            if ("POST".equals(method) && "/__admin/mappings".equals(path)) {
                JsonValue.JsonObject m = (JsonValue.JsonObject) Assert.assertOk(Json.parse(body), "single mapping body");
                String id = ((JsonValue.JsonString) m.get("id").orElseThrow()).value();
                synchronized (this) {
                    mappings.put(id, m);
                }
                sendJson(ex, 201, "{}");
                return;
            }
            if ("DELETE".equals(method) && path.startsWith("/__admin/mappings/")) {
                String id = path.substring("/__admin/mappings/".length());
                boolean removed;
                synchronized (this) {
                    removed = mappings.remove(id) != null;
                }
                sendJson(ex, removed ? 200 : 404, "{}");
                return;
            }
            sendJson(ex, 404, "{\"error\":\"unsupported\"}");
        }

        private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
