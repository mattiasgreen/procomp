package procomp.shell;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.gateway.GatewaySyncStatus;
import procomp.core.process.HealthState;
import procomp.core.process.Ownership;
import procomp.core.project.Project;
import procomp.core.routing.Route;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeProjectFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.fakes.RecordingObserver;
import procomp.shell.ports.HttpClient.HttpResponseData;
import procomp.core.process.ManagedProcessRecord;
import procomp.core.process.ObservedProcess;
import procomp.core.process.ParentProcess;
import procomp.shell.ports.ProcessKey;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StatusShellTest {

    public static void testStatusHandlesProjectWithoutGatewayAndPreservesServiceOrder() {
        Path root = Path.of("C:/work/nogw").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "nogw",
                  "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                  },
                  "services": {
                    "zebra": { "runtime": "java", "port": 9001, "workingDir": "." },
                    "alpha": { "runtime": "java", "port": 9002, "workingDir": "." }
                  }
                }
                """);
        Project project = Assert.assertOk(new ProjectLoader(configSource).load(Optional.empty()), "project loads");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var projectFiles = new FakeProjectFiles();

        Assert.assertOk(new RoutingShell(stateStore).updateRoutes(project, List.of("alpha"), Route.LOCAL), "route alpha local");

        StatusShell statusShell = new StatusShell(stateStore, processRunner, portScanner, httpClient, projectFiles, clock);
        StatusShell.EstateStatus status = Assert.assertOk(statusShell.status(project), "status without gateway succeeds");

        Assert.assertEquals("nogw", status.project(), "project name");
        Assert.assertFalse(status.gateway().configured(), "gateway not configured");
        Assert.assertEquals(Optional.empty(), status.gateway().report(), "empty gateway report");

        Assert.assertEquals(2, status.services().size(), "two services in order");
        StatusShell.ServiceStatusRow row0 = status.services().get(0);
        Assert.assertEquals("zebra", row0.service(), "preserves project order: zebra first");
        Assert.assertEquals(Route.REMOTE, row0.route(), "zebra route REMOTE");
        Assert.assertEquals(Ownership.NONE, row0.ownership(), "zebra owner NONE");
        Assert.assertEquals(HealthState.STOPPED, row0.health(), "zebra health STOPPED");
        Assert.assertEquals(Optional.of(9001), row0.port(), "zebra port");
        Assert.assertEquals(Optional.empty(), row0.pid(), "zebra pid empty");
        Assert.assertEquals(Optional.empty(), row0.uptime(), "zebra uptime empty");
        Assert.assertFalse(row0.stale(), "zebra not stale");

        StatusShell.ServiceStatusRow row1 = status.services().get(1);
        Assert.assertEquals("alpha", row1.service(), "alpha second");
        Assert.assertEquals(Route.LOCAL, row1.route(), "alpha route LOCAL");
    }

    public static void testStatusComposesMultiServiceEstateAndGatewaySyncStatesReadOnly() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Project project = loadRichProject(root, "top-secret-estate");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var projectFiles = new FakeProjectFiles()
            .withDirectory(root.resolve("orders"))
            .withDirectory(root.resolve("billing"))
            .withFile(root.resolve("orders/build/libs/orders-1.0.jar"), Instant.parse("2026-11-20T11:55:00Z"));
        var observer = new RecordingObserver();

        // Routing: orders & payments LOCAL; billing & gateway-uncloned REMOTE
        Assert.assertOk(new RoutingShell(stateStore).updateRoutes(project, List.of("orders", "payments"), Route.LOCAL), "route local slice");

        // 1. orders: LOCAL + MANAGED + HEALTHY + STALE (started 11:45 with artifact 11:40; newer jar 11:55)
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("orders"), new ManagedProcessRecord.Running(
            4100L,
            Instant.parse("2026-11-20T11:45:00Z"),
            List.of("java", "-jar", "orders-1.0.jar"),
            Optional.of(Instant.parse("2026-11-20T11:40:00Z")),
            stateStore.logPath("acme", ProcessKey.service("orders"), false),
            stateStore.logPath("acme", ProcessKey.service("orders"), true)
        )), "seed orders managed");
        processRunner.registerProcess(new ObservedProcess(
            4100L, true, Optional.of(Instant.parse("2026-11-20T11:45:00Z")),
            Optional.of("java -jar orders-1.0.jar"), List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:9001/actuator/health"), 200, "UP");

        // 2. billing: REMOTE + UNMANAGED (IDE) + HEALTHY
        portScanner.bind(9002, 5200L);
        processRunner.registerProcess(new ObservedProcess(
            5200L, true, Optional.of(Instant.parse("2026-11-20T11:58:00Z")),
            Optional.of("java -jar billing.jar"), List.of(),
            List.of(new ParentProcess(10L, Optional.of("idea64.exe"), Optional.of("idea64.exe"))),
            Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:9002/actuator/health"), 200, "UP");

        // 3. payments: LOCAL + CONFLICT (non-IDE listener failing health check)
        portScanner.bind(9003, 6300L);
        processRunner.registerProcess(new ObservedProcess(
            6300L, true, Optional.of(Instant.parse("2026-11-20T11:30:00Z")),
            Optional.of("python -m http.server 9003"), List.of(),
            List.of(new ParentProcess(11L, Optional.of("bash"), Optional.of("bash"))),
            Optional.empty(), Optional.of("python")
        ));

        // 4. inventory: REMOTE + CRASHED (Managed record whose PID is dead)
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("inventory"), new ManagedProcessRecord.Running(
            7400L,
            Instant.parse("2026-11-20T11:10:00Z"),
            List.of("java", "-jar", "inventory.jar"),
            Optional.empty(),
            stateStore.logPath("acme", ProcessKey.service("inventory"), false),
            stateStore.logPath("acme", ProcessKey.service("inventory"), true)
        )), "seed crashed inventory");

        // 5. shipping: REMOTE + NONE + STOPPED (and uncloned repo directory absent on disk)

        StatusShell statusShell = new StatusShell(stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer);

        // Phase A: Gateway OFFLINE
        StatusShell.EstateStatus offline = Assert.assertOk(statusShell.status(project), "status with offline gateway");
        Assert.assertTrue(offline.gateway().configured(), "gateway configured");
        GatewayShell.GatewayStatusReport offlineGw = offline.gateway().report().orElseThrow();
        Assert.assertEquals(8080, offlineGw.gateway().port(), "gateway port 8080");
        Assert.assertEquals(Ownership.NONE, offlineGw.discovery().ownership(), "gateway owner NONE");
        Assert.assertEquals(HealthState.STOPPED, offlineGw.discovery().health(), "gateway STOPPED");
        Assert.assertEquals(GatewaySyncStatus.OFFLINE, offlineGw.drift().syncStatus(), "gateway OFFLINE");
        Assert.assertEquals(5, offline.services().size(), "all 5 services reported in order");

        StatusShell.ServiceStatusRow ordersRow = offline.services().get(0);
        Assert.assertEquals("orders", ordersRow.service(), "orders first");
        Assert.assertEquals(Route.LOCAL, ordersRow.route(), "orders LOCAL");
        Assert.assertEquals(Ownership.MANAGED, ordersRow.ownership(), "orders MANAGED");
        Assert.assertEquals(HealthState.HEALTHY, ordersRow.health(), "orders HEALTHY");
        Assert.assertEquals(Optional.of(9001), ordersRow.port(), "orders port");
        Assert.assertEquals(Optional.of(4100L), ordersRow.pid(), "orders pid");
        Assert.assertEquals(Optional.of(Duration.ofMinutes(15)), ordersRow.uptime(), "orders uptime");
        Assert.assertTrue(ordersRow.stale(), "orders stale");

        StatusShell.ServiceStatusRow billingRow = offline.services().get(1);
        Assert.assertEquals("billing", billingRow.service(), "billing second");
        Assert.assertEquals(Route.REMOTE, billingRow.route(), "billing REMOTE");
        Assert.assertEquals(Ownership.UNMANAGED, billingRow.ownership(), "billing UNMANAGED");
        Assert.assertEquals(Optional.of("IDE"), billingRow.provenance(), "billing provenance IDE");
        Assert.assertEquals(HealthState.HEALTHY, billingRow.health(), "billing HEALTHY");
        Assert.assertEquals(Optional.of(Duration.ofMinutes(2)), billingRow.uptime(), "billing uptime");
        Assert.assertFalse(billingRow.stale(), "billing not stale");

        StatusShell.ServiceStatusRow paymentsRow = offline.services().get(2);
        Assert.assertEquals("payments", paymentsRow.service(), "payments third");
        Assert.assertEquals(Route.LOCAL, paymentsRow.route(), "payments LOCAL");
        Assert.assertEquals(Ownership.CONFLICT, paymentsRow.ownership(), "payments CONFLICT");
        Assert.assertEquals(HealthState.STARTING, paymentsRow.health(), "payments conflict health STARTING");
        Assert.assertEquals(Optional.of(6300L), paymentsRow.pid(), "payments conflict pid");

        StatusShell.ServiceStatusRow inventoryRow = offline.services().get(3);
        Assert.assertEquals("inventory", inventoryRow.service(), "inventory fourth");
        Assert.assertEquals(Route.REMOTE, inventoryRow.route(), "inventory REMOTE");
        Assert.assertEquals(Ownership.MANAGED, inventoryRow.ownership(), "inventory MANAGED");
        Assert.assertEquals(HealthState.CRASHED, inventoryRow.health(), "inventory CRASHED");
        Assert.assertEquals(Optional.of(7400L), inventoryRow.pid(), "inventory crashed pid retained");
        Assert.assertEquals(Optional.empty(), inventoryRow.uptime(), "crashed has no uptime");
        Assert.assertFalse(inventoryRow.stale(), "crashed not stale");

        StatusShell.ServiceStatusRow shippingRow = offline.services().get(4);
        Assert.assertEquals("shipping", shippingRow.service(), "shipping fifth");
        Assert.assertEquals(Route.REMOTE, shippingRow.route(), "shipping REMOTE");
        Assert.assertEquals(Ownership.NONE, shippingRow.ownership(), "shipping NONE");
        Assert.assertEquals(HealthState.STOPPED, shippingRow.health(), "shipping STOPPED");

        // Phase B: Gateway MANAGED + HEALTHY + DRIFTED -> reconcile -> IN_SYNC
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.gateway(), new ManagedProcessRecord.Running(
            8100L, Instant.parse("2026-11-20T11:50:00Z"), List.of("java", "-jar", "wiremock.jar"),
            Optional.empty(), stateStore.logPath("acme", ProcessKey.gateway(), false),
            stateStore.logPath("acme", ProcessKey.gateway(), true)
        )), "seed gateway managed");
        processRunner.registerProcess(new ObservedProcess(
            8100L, true, Optional.of(Instant.parse("2026-11-20T11:50:00Z")),
            Optional.of("java -jar wiremock.jar"), List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "{\"status\":\"healthy\"}");

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

        StatusShell.EstateStatus drifted = Assert.assertOk(statusShell.status(project), "status with drifted gateway");
        GatewayShell.GatewayStatusReport driftedGw = drifted.gateway().report().orElseThrow();
        Assert.assertEquals(Ownership.MANAGED, driftedGw.discovery().ownership(), "gateway MANAGED");
        Assert.assertEquals(HealthState.HEALTHY, driftedGw.discovery().health(), "gateway HEALTHY");
        Assert.assertEquals(Optional.of(8100L), driftedGw.discovery().pid(), "gateway pid");
        Assert.assertEquals(Optional.of(Duration.ofMinutes(10)), driftedGw.uptime(), "gateway uptime");
        Assert.assertEquals(GatewaySyncStatus.DRIFTED, driftedGw.drift().syncStatus(), "gateway DRIFTED");
        Assert.assertEquals(5, driftedGw.drift().drifted().size(), "all 5 services UNSET");

        GatewayShell gatewayShell = new GatewayShell(stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer);
        Assert.assertOk(gatewayShell.reconcile(project), "reconcile converges");

        StatusShell.EstateStatus synced = Assert.assertOk(statusShell.status(project), "status after reconcile");
        GatewayShell.GatewayStatusReport syncedGw = synced.gateway().report().orElseThrow();
        Assert.assertEquals(GatewaySyncStatus.IN_SYNC, syncedGw.drift().syncStatus(), "gateway IN_SYNC");
        Assert.assertTrue(syncedGw.drift().drifted().isEmpty(), "no drifted services");
        Assert.assertEquals(0, processRunner.spawnedRequests().size(), "status never spawns processes");
        Assert.assertEquals(0, processRunner.stoppedPids().size(), "status never stops processes");
        Assert.assertNotContains(synced.toString(), "top-secret-estate", "EstateStatus never holds secrets");
    }

    public static void testStatusPropagatesGatewayAdminError() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Project project = loadRichProject(root, "sec");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner().bind(8080, 9900L);
        processRunner.registerProcess(new ObservedProcess(
            9900L, true, Optional.of(clock.now()), Optional.of("java -jar wiremock.jar"),
            List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));
        var httpClient = new FakeHttpClient()
            .stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "OK")
            .stubGet(URI.create("http://127.0.0.1:8080/__admin/mappings"), 500, "boom");

        StatusShell statusShell = new StatusShell(stateStore, processRunner, portScanner, httpClient, new FakeProjectFiles(), clock);
        DomainError err = Assert.assertErr(statusShell.status(project), "status propagates gateway admin failure");
        Assert.assertEquals("GATEWAY_ADMIN_ERROR", err.code(), "GATEWAY_ADMIN_ERROR code");
    }

    public static void testStatusReadsManagedRecordsBeforePortScanAndReportsStopping() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "runtimes": {
                    "java": {
                      "command": ["java", "-jar", "svc.jar"],
                      "healthPath": "/actuator/health"
                    }
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "orders" }
                  }
                }
                """);
        Project project = Assert.assertOk(new ProjectLoader(configSource).load(Optional.empty()), "project loads");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        // Track whether portScanner is invoked for a managed service (it must not be!)
        final int[] portScanCalls = {0};
        procomp.shell.ports.PortScanner trackingScanner = new procomp.shell.ports.PortScanner() {
            @Override
            public boolean isTcpListening(int port, Duration timeout) {
                return false;
            }
            @Override
            public procomp.core.common.Result<Optional<procomp.core.process.PortBinding>, DomainError> findListener(int port) {
                portScanCalls[0]++;
                return procomp.core.common.Result.ok(Optional.of(new procomp.core.process.PortBinding(port, Optional.of(4100L), "127.0.0.1:" + port)));
            }
            @Override
            public procomp.core.common.Result<java.util.Map<Integer, procomp.core.process.PortBinding>, DomainError> findListeners(java.util.Set<Integer> ports) {
                portScanCalls[0]++;
                return procomp.core.common.Result.ok(java.util.Map.of(9001, new procomp.core.process.PortBinding(9001, Optional.of(4100L), "127.0.0.1:9001")));
            }
        };
        var httpClient = new FakeHttpClient(); // health check fails (no stub)

        ManagedProcessRecord.Stopping stopping = new ManagedProcessRecord.Running(
            4100L, clock.now().minusSeconds(60), List.of("java", "-jar", "svc.jar"), Optional.empty(),
            stateStore.logPath("acme", ProcessKey.service("orders"), false),
            stateStore.logPath("acme", ProcessKey.service("orders"), true)
        ).requestStop(clock.now().minusSeconds(2));
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("orders"), stopping), "seed stopping");
        processRunner.registerProcess(new ObservedProcess(
            4100L, true, Optional.of(clock.now().minusSeconds(60)), Optional.of("java -jar svc.jar"),
            List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));

        StatusShell statusShell = new StatusShell(stateStore, processRunner, trackingScanner, httpClient, new FakeProjectFiles(), clock);
        StatusShell.EstateStatus estate = Assert.assertOk(statusShell.status(project), "status succeeds");
        StatusShell.ServiceStatusRow row = estate.services().get(0);
        Assert.assertEquals(Ownership.MANAGED, row.ownership(), "stopping service is MANAGED, never CONFLICT");
        Assert.assertEquals(HealthState.STOPPING, row.health(), "stopping service health is STOPPING, not STARTING");
        Assert.assertEquals(0, portScanCalls[0], "port scan skipped when all services have managed records");
    }

    private static Project loadRichProject(Path root, String secret) {
        var configSource = new InMemoryConfigSource(root)
            .withEnv("ESTATE_SECRET", secret)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "defaults": {
                    "proxyPathTemplate": "/{{service}}",
                    "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}",
                    "env": {
                      "API_TOKEN": "${env:ESTATE_SECRET}"
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
                    "inventory": { "runtime": "java", "port": 9004, "workingDir": "inventory" },
                    "shipping": { "runtime": "java", "port": 9005, "workingDir": "shipping" }
                  }
                }
                """);
        return Assert.assertOk(new ProjectLoader(configSource).load(Optional.empty()), "rich project loads");
    }
}
