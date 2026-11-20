package procomp.cli;

import procomp.Assert;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeWorkspaceFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.fakes.RecordingObserver;
import procomp.shell.ports.HttpClient.HttpResponseData;
import procomp.shell.ports.ManagedProcessRecord;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner.ParentProcessInfo;
import procomp.shell.ports.ProcessRunner.ProcessSnapshot;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StatusCliTest {

    public static void testStatusTableRendersColumnsGatewaySummariesUnconfiguredAndExitCodes() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var workspaceFiles = new FakeWorkspaceFiles()
            .withDirectory(root.resolve("orders"))
            .withFile(root.resolve("orders/build/libs/orders-1.0.jar"), Instant.parse("2026-11-20T11:55:00Z"));

        // 1. Unconfigured gateway project renders cleanly with 'Gateway: not configured'
        var noGwConfig = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "nogw",
                  "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "orders" }
                  }
                }
                """);
        CliResult noGwRes = runCli(noGwConfig, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "status");
        Assert.assertEquals(0, noGwRes.exitCode(), "status without gateway exits 0; stderr=" + noGwRes.stderr());
        Assert.assertContains(noGwRes.stdout(), "SERVICE", "prints SERVICE header");
        Assert.assertContains(noGwRes.stdout(), "Gateway: not configured", "prints unconfigured gateway summary");

        // 2. Rich multi-service estate with Gateway OFFLINE -> DRIFTED (N) -> IN SYNC
        var richConfig = richConfigSource(root, "top-secret-cli-status");
        Assert.assertEquals(0, runCli(richConfig, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "route", "orders", "local").exitCode(), "route orders local");

        // orders: LOCAL + MANAGED + HEALTHY + STALE
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("orders"), new ManagedProcessRecord(
            4100L, Instant.parse("2026-11-20T11:45:00Z"), List.of("java", "-jar", "orders-1.0.jar"),
            Optional.of(Instant.parse("2026-11-20T11:40:00Z")),
            stateStore.logPath("acme", ProcessKey.service("orders"), false),
            stateStore.logPath("acme", ProcessKey.service("orders"), true)
        )), "seed orders");
        processRunner.registerProcess(new ProcessSnapshot(
            4100L, true, Optional.of(Instant.parse("2026-11-20T11:45:00Z")),
            Optional.of("java -jar orders-1.0.jar"), List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:9001/actuator/health"), 200, "UP");

        // billing: REMOTE + UNMANAGED (IDE) + HEALTHY
        portScanner.bind(9002, 5200L);
        processRunner.registerProcess(new ProcessSnapshot(
            5200L, true, Optional.of(Instant.parse("2026-11-20T11:58:00Z")),
            Optional.of("java -jar billing.jar"), List.of(),
            List.of(new ParentProcessInfo(10L, Optional.of("idea64.exe"), Optional.of("idea64.exe"))),
            Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:9002/actuator/health"), 200, "UP");

        // payments: REMOTE + CONFLICT
        portScanner.bind(9003, 6300L);
        processRunner.registerProcess(new ProcessSnapshot(
            6300L, true, Optional.of(Instant.parse("2026-11-20T11:30:00Z")),
            Optional.of("python -m http.server 9003"), List.of(),
            List.of(new ParentProcessInfo(11L, Optional.of("bash"), Optional.of("bash"))),
            Optional.empty(), Optional.of("python")
        ));

        // inventory: REMOTE + CRASHED
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("inventory"), new ManagedProcessRecord(
            7400L, Instant.parse("2026-11-20T11:10:00Z"), List.of("java", "-jar", "inventory.jar"),
            Optional.empty(),
            stateStore.logPath("acme", ProcessKey.service("inventory"), false),
            stateStore.logPath("acme", ProcessKey.service("inventory"), true)
        )), "seed crashed inventory");

        CliResult offlineRes = runCli(richConfig, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "status");
        Assert.assertEquals(0, offlineRes.exitCode(), "status offline exits 0; stderr=" + offlineRes.stderr());
        String offlineOut = offlineRes.stdout();
        for (String col : List.of("SERVICE", "ROUTE", "OWNER", "HEALTH", "PORT", "PID", "UPTIME", "STALE")) {
            Assert.assertContains(offlineOut, col, "table contains header " + col);
        }
        Assert.assertContains(offlineOut, "orders", "shows orders");
        Assert.assertContains(offlineOut, "MANAGED", "shows MANAGED");
        Assert.assertContains(offlineOut, "15m0s", "shows uptime 15m0s");
        Assert.assertContains(offlineOut, "YES", "shows STALE YES");
        Assert.assertContains(offlineOut, "UNMANAGED (IDE)", "shows UNMANAGED (IDE)");
        Assert.assertContains(offlineOut, "CONFLICT", "shows CONFLICT");
        Assert.assertContains(offlineOut, "CRASHED", "shows CRASHED");
        Assert.assertContains(offlineOut, "Gateway:", "prints Gateway summary line");
        Assert.assertContains(offlineOut, "8080", "Gateway summary includes port");
        Assert.assertContains(offlineOut, "OFFLINE", "Gateway summary shows OFFLINE");
        Assert.assertNotContains(offlineOut, "top-secret-cli-status", "status text never leaks secrets");

        // Gateway running + DRIFTED -> reconcile -> IN SYNC
        Map<String, JsonValue.JsonObject> liveMappings = new LinkedHashMap<>();
        httpClient.stub("GET", URI.create("http://127.0.0.1:8080/__admin/mappings"), _ -> {
            List<JsonValue> arr = new ArrayList<>(liveMappings.values());
            return Result.ok(new HttpResponseData(200, Map.of(), Json.stringify(Json.obj(Map.of("mappings", Json.arr(arr))))));
        });
        httpClient.stub("POST", URI.create("http://127.0.0.1:8080/__admin/mappings/import"), req -> {
            JsonValue.JsonObject payload = (JsonValue.JsonObject) Assert.assertOk(Json.parse(req.body()), "import payload");
            JsonValue.JsonArray arr = (JsonValue.JsonArray) payload.get("mappings").orElseThrow();
            for (JsonValue el : arr.elements()) {
                JsonValue.JsonObject m = (JsonValue.JsonObject) el;
                String id = ((JsonValue.JsonString) m.get("id").orElseThrow()).value();
                liveMappings.put(id, m);
            }
            return Result.ok(new HttpResponseData(200, Map.of(), "{}"));
        });
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.gateway(), new ManagedProcessRecord(
            8100L, Instant.parse("2026-11-20T11:50:00Z"), List.of("java", "-jar", "wiremock.jar"),
            Optional.empty(), stateStore.logPath("acme", ProcessKey.gateway(), false),
            stateStore.logPath("acme", ProcessKey.gateway(), true)
        )), "seed gateway");
        processRunner.registerProcess(new ProcessSnapshot(
            8100L, true, Optional.of(Instant.parse("2026-11-20T11:50:00Z")),
            Optional.of("java -jar wiremock.jar"), List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "{\"status\":\"healthy\"}");

        CliResult driftedRes = runCli(richConfig, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "status");
        Assert.assertEquals(0, driftedRes.exitCode(), "status drifted exits 0");
        Assert.assertContains(driftedRes.stdout(), "DRIFTED (4)", "Gateway summary shows DRIFTED (4)");

        Assert.assertEquals(0, runCli(richConfig, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "gateway", "reconcile").exitCode(), "reconcile");
        CliResult syncedRes = runCli(richConfig, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "status");
        Assert.assertEquals(0, syncedRes.exitCode(), "status synced exits 0");
        Assert.assertContains(syncedRes.stdout(), "IN SYNC", "Gateway summary shows IN SYNC");

        // 3. Domain error -> exit 1
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/mappings"), 500, "err");
        CliResult errRes = runCli(richConfig, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "status");
        Assert.assertEquals(1, errRes.exitCode(), "gateway admin error exits 1");
        Assert.assertContains(errRes.stderr(), "GATEWAY_ADMIN_ERROR", "stderr shows GATEWAY_ADMIN_ERROR");

        // 4. Invalid flags / positional args -> exit 2
        for (String[] badArgs : List.of(
            new String[]{"status", "--bogus"},
            new String[]{"status", "orders"},
            new String[]{"status", "--json", "extra"}
        )) {
            CliResult bad = runCli(richConfig, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, badArgs);
            Assert.assertEquals(2, bad.exitCode(), "bad status args " + String.join(" ", badArgs) + " exit 2");
        }
    }

    public static void testStatusJsonEmitsConfiguredAndUnconfiguredEstatesWithoutLeakingSecrets() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var workspaceFiles = new FakeWorkspaceFiles()
            .withDirectory(root.resolve("orders"))
            .withFile(root.resolve("orders/build/libs/orders-1.0.jar"), Instant.parse("2026-11-20T11:55:00Z"));

        // 1. Unconfigured gateway + empty services -> valid JSON with configured=false and services=[]
        var emptyNoGw = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "empty-nogw",
                  "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } }
                }
                """);
        CliResult emptyJsonRes = runCli(emptyNoGw, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "status", "--json");
        Assert.assertEquals(0, emptyJsonRes.exitCode(), "status --json without gateway exits 0; stderr=" + emptyJsonRes.stderr());
        JsonValue.JsonObject emptyObj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(emptyJsonRes.stdout().trim()), "valid json");
        Assert.assertEquals("empty-nogw", ((JsonValue.JsonString) emptyObj.get("project").orElseThrow()).value(), "project name");
        JsonValue.JsonObject emptyGw = (JsonValue.JsonObject) emptyObj.get("gateway").orElseThrow();
        Assert.assertFalse(((JsonValue.JsonBoolean) emptyGw.get("configured").orElseThrow()).value(), "gateway configured=false");
        Assert.assertEquals(JsonValue.JsonNull.INSTANCE, emptyGw.get("port").orElseThrow(), "gateway port null");
        Assert.assertEquals(JsonValue.JsonNull.INSTANCE, emptyGw.get("syncStatus").orElseThrow(), "gateway syncStatus null");
        Assert.assertEquals(0, ((JsonValue.JsonArray) emptyObj.get("services").orElseThrow()).elements().size(), "empty services array");

        // 2. Rich multi-service estate with Gateway DRIFTED (including stale mapping) -> reconcile -> IN_SYNC
        var richConfig = richConfigSource(root, "top-secret-json-status");
        Assert.assertEquals(0, runCli(richConfig, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "route", "orders", "local").exitCode(), "route orders local");

        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("orders"), new ManagedProcessRecord(
            4100L, Instant.parse("2026-11-20T11:45:00Z"), List.of("java", "-jar", "orders-1.0.jar"),
            Optional.of(Instant.parse("2026-11-20T11:40:00Z")),
            stateStore.logPath("acme", ProcessKey.service("orders"), false),
            stateStore.logPath("acme", ProcessKey.service("orders"), true)
        )), "seed orders");
        processRunner.registerProcess(new ProcessSnapshot(
            4100L, true, Optional.of(Instant.parse("2026-11-20T11:45:00Z")),
            Optional.of("java -jar orders-1.0.jar"), List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:9001/actuator/health"), 200, "UP");

        portScanner.bind(9002, 5200L);
        processRunner.registerProcess(new ProcessSnapshot(
            5200L, true, Optional.of(Instant.parse("2026-11-20T11:58:00Z")),
            Optional.of("java -jar billing.jar"), List.of(),
            List.of(new ParentProcessInfo(10L, Optional.of("idea64.exe"), Optional.of("idea64.exe"))),
            Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:9002/actuator/health"), 200, "UP");

        portScanner.bind(9003, 6300L);
        processRunner.registerProcess(new ProcessSnapshot(
            6300L, true, Optional.of(Instant.parse("2026-11-20T11:30:00Z")),
            Optional.of("python -m http.server 9003"), List.of(),
            List.of(new ParentProcessInfo(11L, Optional.of("bash"), Optional.of("bash"))),
            Optional.empty(), Optional.of("python")
        ));

        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("inventory"), new ManagedProcessRecord(
            7400L, Instant.parse("2026-11-20T11:10:00Z"), List.of("java", "-jar", "inventory.jar"),
            Optional.empty(),
            stateStore.logPath("acme", ProcessKey.service("inventory"), false),
            stateStore.logPath("acme", ProcessKey.service("inventory"), true)
        )), "seed crashed inventory");

        Map<String, JsonValue.JsonObject> liveMappings = new LinkedHashMap<>();
        liveMappings.put("stale-acme-1", (JsonValue.JsonObject) Assert.assertOk(Json.parse("""
            {"id":"stale-acme-1","request":{"method":"ANY","urlPathPattern":"/retired(/.*)?"},"response":{"proxyBaseUrl":"https://shared-dev.mycorp/retired","proxyUrlPrefixToRemove":"/retired"},"metadata":{"procomp":true,"procompProject":"acme","service":"retired","route":"REMOTE"}}"""), "stale mapping"));
        httpClient.stub("GET", URI.create("http://127.0.0.1:8080/__admin/mappings"), _ -> {
            List<JsonValue> arr = new ArrayList<>(liveMappings.values());
            return Result.ok(new HttpResponseData(200, Map.of(), Json.stringify(Json.obj(Map.of("mappings", Json.arr(arr))))));
        });
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.gateway(), new ManagedProcessRecord(
            8100L, Instant.parse("2026-11-20T11:50:00Z"), List.of("java", "-jar", "wiremock.jar"),
            Optional.empty(), stateStore.logPath("acme", ProcessKey.gateway(), false),
            stateStore.logPath("acme", ProcessKey.gateway(), true)
        )), "seed gateway");
        processRunner.registerProcess(new ProcessSnapshot(
            8100L, true, Optional.of(Instant.parse("2026-11-20T11:50:00Z")),
            Optional.of("java -jar wiremock.jar"), List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "{\"status\":\"healthy\"}");

        CliResult jsonRes = runCli(richConfig, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "status", "--json");
        Assert.assertEquals(0, jsonRes.exitCode(), "status --json exits 0; stderr=" + jsonRes.stderr());
        Assert.assertNotContains(jsonRes.stdout(), "top-secret-json-status", "status --json never leaks secrets");

        JsonValue.JsonObject rootObj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(jsonRes.stdout().trim()), "valid json");
        Assert.assertEquals("acme", ((JsonValue.JsonString) rootObj.get("project").orElseThrow()).value(), "project");

        JsonValue.JsonObject gwObj = (JsonValue.JsonObject) rootObj.get("gateway").orElseThrow();
        Assert.assertTrue(((JsonValue.JsonBoolean) gwObj.get("configured").orElseThrow()).value(), "gateway configured=true");
        Assert.assertEquals(8080L, ((JsonValue.JsonNumber) gwObj.get("port").orElseThrow()).asLong().orElseThrow(), "gw port");
        Assert.assertEquals("MANAGED", ((JsonValue.JsonString) gwObj.get("owner").orElseThrow()).value(), "gw owner");
        Assert.assertEquals("procomp", ((JsonValue.JsonString) gwObj.get("provenance").orElseThrow()).value(), "gw provenance");
        Assert.assertEquals("HEALTHY", ((JsonValue.JsonString) gwObj.get("health").orElseThrow()).value(), "gw health");
        Assert.assertEquals(8100L, ((JsonValue.JsonNumber) gwObj.get("pid").orElseThrow()).asLong().orElseThrow(), "gw pid");
        Assert.assertEquals(600L, ((JsonValue.JsonNumber) gwObj.get("uptimeSeconds").orElseThrow()).asLong().orElseThrow(), "gw uptimeSeconds");
        Assert.assertEquals("DRIFTED", ((JsonValue.JsonString) gwObj.get("syncStatus").orElseThrow()).value(), "gw syncStatus DRIFTED");
        Assert.assertEquals(4, ((JsonValue.JsonArray) gwObj.get("drifted").orElseThrow()).elements().size(), "4 drifted services");
        Assert.assertEquals(1, ((JsonValue.JsonArray) gwObj.get("staleMappings").orElseThrow()).elements().size(), "1 stale mapping");

        List<JsonValue> services = ((JsonValue.JsonArray) rootObj.get("services").orElseThrow()).elements();
        Assert.assertEquals(4, services.size(), "4 services in order");

        JsonValue.JsonObject orders = (JsonValue.JsonObject) services.get(0);
        Assert.assertEquals("orders", ((JsonValue.JsonString) orders.get("service").orElseThrow()).value(), "svc 0 orders");
        Assert.assertEquals("LOCAL", ((JsonValue.JsonString) orders.get("route").orElseThrow()).value(), "orders route");
        Assert.assertEquals("MANAGED", ((JsonValue.JsonString) orders.get("owner").orElseThrow()).value(), "orders owner");
        Assert.assertEquals("HEALTHY", ((JsonValue.JsonString) orders.get("health").orElseThrow()).value(), "orders health");
        Assert.assertEquals(9001L, ((JsonValue.JsonNumber) orders.get("port").orElseThrow()).asLong().orElseThrow(), "orders port");
        Assert.assertEquals(4100L, ((JsonValue.JsonNumber) orders.get("pid").orElseThrow()).asLong().orElseThrow(), "orders pid");
        Assert.assertEquals(900L, ((JsonValue.JsonNumber) orders.get("uptimeSeconds").orElseThrow()).asLong().orElseThrow(), "orders uptimeSeconds");
        Assert.assertTrue(((JsonValue.JsonBoolean) orders.get("stale").orElseThrow()).value(), "orders stale");

        JsonValue.JsonObject billing = (JsonValue.JsonObject) services.get(1);
        Assert.assertEquals("billing", ((JsonValue.JsonString) billing.get("service").orElseThrow()).value(), "svc 1 billing");
        Assert.assertEquals("REMOTE", ((JsonValue.JsonString) billing.get("route").orElseThrow()).value(), "billing route");
        Assert.assertEquals("UNMANAGED", ((JsonValue.JsonString) billing.get("owner").orElseThrow()).value(), "billing owner");
        Assert.assertEquals("IDE", ((JsonValue.JsonString) billing.get("provenance").orElseThrow()).value(), "billing provenance IDE");

        JsonValue.JsonObject payments = (JsonValue.JsonObject) services.get(2);
        Assert.assertEquals("CONFLICT", ((JsonValue.JsonString) payments.get("owner").orElseThrow()).value(), "payments CONFLICT");

        JsonValue.JsonObject inventory = (JsonValue.JsonObject) services.get(3);
        Assert.assertEquals("CRASHED", ((JsonValue.JsonString) inventory.get("health").orElseThrow()).value(), "inventory CRASHED");
        Assert.assertEquals(JsonValue.JsonNull.INSTANCE, inventory.get("uptimeSeconds").orElseThrow(), "crashed uptimeSeconds null");
    }

    private static CliResult runCli(
        InMemoryConfigSource cfg,
        InMemoryStateStore state,
        FakeProcessRunner runner,
        FakePortScanner ports,
        FakeHttpClient http,
        FakeWorkspaceFiles files,
        FakeClock clock,
        String... args
    ) {
        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int code = Procomp.run(
            args,
            cfg,
            state,
            runner,
            ports,
            http,
            files,
            clock,
            new RecordingObserver(),
            new PrintWriter(outBuf, true),
            new PrintWriter(errBuf, true)
        );
        return new CliResult(code, outBuf.toString(), errBuf.toString());
    }

    private static InMemoryConfigSource richConfigSource(Path root, String secret) {
        return new InMemoryConfigSource(root)
            .withEnv("STATUS_CLI_SECRET", secret)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "defaults": {
                    "proxyPathTemplate": "/{{service}}",
                    "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}",
                    "env": {
                      "API_TOKEN": "${env:STATUS_CLI_SECRET}"
                    }
                  },
                  "runtimes": {
                    "java": {
                      "command": ["java", "-Dserver.port={{port}}", "-jar", "{{artifact}}"],
                      "healthPath": "/actuator/health",
                      "artifactGlob": "build/libs/*.jar",
                      "artifactExclude": "*-plain.jar"
                    }
                  },
                  "gateway": {
                    "type": "wiremock",
                    "port": 8080,
                    "healthPath": "/__admin/health"
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "orders" },
                    "billing": { "runtime": "java", "port": 9002, "workingDir": "billing" },
                    "payments": { "runtime": "java", "port": 9003, "workingDir": "payments" },
                    "inventory": { "runtime": "java", "port": 9004, "workingDir": "inventory" }
                  }
                }
                """);
    }

    private record CliResult(int exitCode, String stdout, String stderr) {}
}
