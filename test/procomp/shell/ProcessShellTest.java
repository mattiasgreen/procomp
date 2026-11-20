package procomp.shell;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.process.HealthState;
import procomp.core.process.InspectionReport;
import procomp.core.process.Ownership;
import procomp.core.project.Project;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeProjectFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.fakes.RecordingObserver;
import procomp.shell.ports.ManagedProcessRecord;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner.ParentProcessInfo;
import procomp.shell.ports.ProcessRunner.ProcessSnapshot;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class ProcessShellTest {

    public static void testInspectReportsManagedStaleIdeAndSecretsAreNeverExposed() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Project project = loadSampleProject(root, "super-secret-db-pass");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var projectFiles = new FakeProjectFiles()
            .withDirectory(root.resolve("orders"))
            .withDirectory(root.resolve("billing"))
            .withFile(root.resolve("orders/build/libs/orders-1.0.jar"), Instant.parse("2026-11-20T11:50:00Z"))
            .withFile(root.resolve("orders/build/libs/orders-1.0-plain.jar"), Instant.parse("2026-11-20T12:05:00Z"));

        // Set orders LOCAL in routing table
        Assert.assertOk(new RoutingShell(stateStore).updateRoutes(project, List.of("orders"), Route.LOCAL), "set orders local");

        // Managed process started at 11:45 with artifact from 11:40, newer non-plain jar built at 11:50 -> stale=true
        Path outLog = stateStore.logPath("acme", ProcessKey.service("orders"), false);
        Path errLog = stateStore.logPath("acme", ProcessKey.service("orders"), true);
        ManagedProcessRecord rec = new ManagedProcessRecord(
            4100L,
            Instant.parse("2026-11-20T11:45:00Z"),
            List.of("java", "-jar", "orders-1.0.jar"),
            Optional.of(Instant.parse("2026-11-20T11:40:00Z")),
            outLog,
            errLog
        );
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("orders"), rec), "persist managed record");
        processRunner.registerProcess(new ProcessSnapshot(
            4100L,
            true,
            Optional.of(Instant.parse("2026-11-20T11:45:00Z")),
            Optional.of("java -jar orders-1.0.jar"),
            List.of("-jar", "orders-1.0.jar"),
            List.of(new ParentProcessInfo(10L, Optional.of("procomp start orders"), Optional.of("java.exe"))),
            Optional.of(128L * 1024 * 1024),
            Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:9001/actuator/health"), 200, "{\"status\":\"UP\"}");

        ProcessShell shell = new ProcessShell(
            stateStore, processRunner, portScanner, httpClient, projectFiles, clock, new RecordingObserver()
        );

        InspectionReport ordersReport = Assert.assertOk(shell.inspect(project, "orders"), "inspect orders succeeds");
        Assert.assertEquals(Ownership.MANAGED, ordersReport.ownership(), "orders owner");
        Assert.assertEquals(Optional.of("procomp"), ordersReport.provenance(), "orders provenance");
        Assert.assertEquals(HealthState.HEALTHY, ordersReport.health(), "orders health");
        Assert.assertEquals(Optional.of("LOCAL"), ordersReport.desiredRoute(), "orders desiredRoute");
        Assert.assertEquals(Optional.of("OFFLINE"), ordersReport.liveGatewayRoute(), "orders liveGatewayRoute OFFLINE when gateway stopped");
        Assert.assertEquals(Optional.of(Duration.ofMinutes(15)), ordersReport.uptime(), "orders uptime");
        Assert.assertTrue(ordersReport.stale(), "orders is stale due to newer non-plain jar");
        Assert.assertNotContains(ordersReport.toString(), "super-secret-db-pass", "report never contains secret env");

        // Now verify *-plain.jar newer timestamp alone doesn't falsely mark not-stale as stale when excluded
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("orders"), new ManagedProcessRecord(
            4100L,
            Instant.parse("2026-11-20T11:45:00Z"),
            List.of("java", "-jar", "orders-1.0.jar"),
            Optional.of(Instant.parse("2026-11-20T11:50:00Z")),
            outLog,
            errLog
        )), "update artifact timestamp to match fat jar");
        InspectionReport notStale = Assert.assertOk(shell.inspect(project, "orders"), "re-inspect orders");
        Assert.assertFalse(notStale.stale(), "excluded *-plain.jar must not trigger stale");

        // Unmanaged booting IDE process on billing
        portScanner.bind(9002, 5500L);
        processRunner.registerProcess(new ProcessSnapshot(
            5500L,
            true,
            Optional.of(Instant.parse("2026-11-20T11:59:00Z")),
            Optional.of("java -agentlib:jdwp -jar billing.jar"),
            List.of("-agentlib:jdwp", "-jar", "billing.jar"),
            List.of(new ParentProcessInfo(99L, Optional.of("\"C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe\""), Optional.of("C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe"))),
            Optional.empty(),
            Optional.of("java")
        ));
        InspectionReport billingReport = Assert.assertOk(shell.inspect(project, "billing"), "inspect billing succeeds");
        Assert.assertEquals(Ownership.UNMANAGED, billingReport.ownership(), "billing owner UNMANAGED");
        Assert.assertEquals(Optional.of("IDE"), billingReport.provenance(), "billing provenance IDE");
        Assert.assertEquals(HealthState.STARTING, billingReport.health(), "booting IDE billing is STARTING");
        Assert.assertEquals(Optional.of("REMOTE"), billingReport.desiredRoute(), "billing route REMOTE");

        DomainError unknownErr = Assert.assertErr(shell.inspect(project, "ghost"), "inspect unknown service fails");
        Assert.assertEquals("UNKNOWN_SERVICE", unknownErr.code(), "unknown service error code");
    }

    public static void testInspectPopulatesLiveGatewayRouteAcrossOfflineInSyncDriftedAndNoGateway() {
        Path root = Path.of("C:/work/acme-gw").toAbsolutePath().normalize();
        var noGwConfig = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "no-gw",
                  "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "orders" }
                  }
                }
                """);
        Project noGwProject = Assert.assertOk(new ProjectLoader(noGwConfig).load(Optional.empty()), "no-gw project loads");

        var gwConfig = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "defaults": {
                    "proxyPathTemplate": "/{{service}}",
                    "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}"
                  },
                  "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
                  "gateway": {
                    "type": "wiremock",
                    "port": 8080,
                    "healthPath": "/__admin/health"
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "orders" },
                    "billing": { "runtime": "java", "port": 9002, "workingDir": "billing" }
                  }
                }
                """);
        Project gwProject = Assert.assertOk(new ProjectLoader(gwConfig).load(Optional.empty()), "gw project loads");

        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var projectFiles = new FakeProjectFiles();
        var observer = new RecordingObserver();

        ProcessShell shell = new ProcessShell(stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer);
        GatewayShell gatewayShell = new GatewayShell(stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer);

        // 1. No gateway configured -> liveGatewayRoute is empty
        InspectionReport noGwReport = Assert.assertOk(shell.inspect(noGwProject, "orders"), "inspect without gateway");
        Assert.assertEquals(Optional.empty(), noGwReport.liveGatewayRoute(), "no gateway -> empty liveGatewayRoute");

        // 2. Gateway configured but STOPPED -> OFFLINE (never queries /__admin/mappings)
        InspectionReport stoppedGwReport = Assert.assertOk(shell.inspect(gwProject, "orders"), "inspect with stopped gateway");
        Assert.assertEquals(Optional.of("OFFLINE"), stoppedGwReport.liveGatewayRoute(), "stopped gateway -> OFFLINE");

        // 3. Gateway STARTING (health 503) -> OFFLINE even if /__admin/mappings would error
        portScanner.bind(8080, 8100L);
        processRunner.registerProcess(new ProcessSnapshot(
            8100L, true, Optional.of(clock.now()), Optional.of("java -jar wiremock.jar"),
            List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 503, "starting");
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/mappings"), 500, "not ready");
        InspectionReport startingGwReport = Assert.assertOk(shell.inspect(gwProject, "orders"), "inspect with starting gateway stays resilient");
        Assert.assertEquals(Optional.of("OFFLINE"), startingGwReport.liveGatewayRoute(), "starting gateway -> OFFLINE");

        // 4. Gateway HEALTHY and UNSET -> UNSET
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "OK");
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/mappings"), 200, "{\"mappings\":[]}");
        InspectionReport unsetReport = Assert.assertOk(shell.inspect(gwProject, "orders"), "inspect when UNSET");
        Assert.assertEquals(Optional.of("UNSET"), unsetReport.liveGatewayRoute(), "healthy empty WireMock -> UNSET");

        // 5. Reconcile orders LOCAL & billing REMOTE -> IN_SYNC -> liveGatewayRoute matches desiredRoute
        Assert.assertOk(new RoutingShell(stateStore).updateRoutes(gwProject, List.of("orders"), Route.LOCAL), "route orders local");
        java.util.Map<String, JsonValue.JsonObject> liveMappings = new java.util.LinkedHashMap<>();
        httpClient.stub("GET", URI.create("http://127.0.0.1:8080/__admin/mappings"), _ -> {
            java.util.List<JsonValue> arr = new java.util.ArrayList<>(liveMappings.values());
            return procomp.core.common.Result.ok(new procomp.shell.ports.HttpClient.HttpResponseData(
                200, java.util.Map.of(), Json.stringify(Json.obj(java.util.Map.of("mappings", Json.arr(arr))))
            ));
        });
        httpClient.stub("POST", URI.create("http://127.0.0.1:8080/__admin/mappings/import"), req -> {
            JsonValue.JsonObject payload = (JsonValue.JsonObject) Assert.assertOk(Json.parse(req.body()), "import json");
            JsonValue.JsonArray arr = (JsonValue.JsonArray) payload.get("mappings").orElseThrow();
            for (JsonValue el : arr.elements()) {
                JsonValue.JsonObject m = (JsonValue.JsonObject) el;
                String id = ((JsonValue.JsonString) m.get("id").orElseThrow()).value();
                liveMappings.put(id, m);
            }
            return procomp.core.common.Result.ok(new procomp.shell.ports.HttpClient.HttpResponseData(200, java.util.Map.of(), "{}"));
        });
        Assert.assertOk(gatewayShell.reconcile(gwProject), "reconcile succeeds");

        InspectionReport inSyncOrders = Assert.assertOk(shell.inspect(gwProject, "orders"), "inspect orders IN_SYNC");
        Assert.assertEquals(Optional.of("LOCAL"), inSyncOrders.desiredRoute(), "orders desired LOCAL");
        Assert.assertEquals(Optional.of("LOCAL"), inSyncOrders.liveGatewayRoute(), "orders live LOCAL");

        InspectionReport inSyncBilling = Assert.assertOk(shell.inspect(gwProject, "billing"), "inspect billing IN_SYNC");
        Assert.assertEquals(Optional.of("REMOTE"), inSyncBilling.desiredRoute(), "billing desired REMOTE");
        Assert.assertEquals(Optional.of("REMOTE"), inSyncBilling.liveGatewayRoute(), "billing live REMOTE");

        // 6. Flip orders to REMOTE in Routing Table -> DRIFTED -> desired REMOTE, live LOCAL
        Assert.assertOk(new RoutingShell(stateStore).updateRoutes(gwProject, List.of("orders"), Route.REMOTE), "flip orders remote");
        InspectionReport driftedOrders = Assert.assertOk(shell.inspect(gwProject, "orders"), "inspect orders DRIFTED");
        Assert.assertEquals(Optional.of("REMOTE"), driftedOrders.desiredRoute(), "orders desired REMOTE");
        Assert.assertEquals(Optional.of("LOCAL"), driftedOrders.liveGatewayRoute(), "orders live still LOCAL");

        // 7. MISMATCH mapping + genuine HTTP error when healthy
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/mappings"), 200, """
            {"mappings":[{"id":"bad-id","request":{"method":"ANY","urlPathPattern":"/orders(/.*)?"},"response":{"proxyBaseUrl":"http://127.0.0.1:9999","proxyUrlPrefixToRemove":"/orders"},"metadata":{"procomp":true,"procompProject":"acme","service":"orders","route":"LOCAL"}}]}
            """);
        InspectionReport mismatchOrders = Assert.assertOk(shell.inspect(gwProject, "orders"), "inspect orders MISMATCH");
        Assert.assertEquals(Optional.of("MISMATCH"), mismatchOrders.liveGatewayRoute(), "mismatched mapping -> MISMATCH");

        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/mappings"), 500, "boom");
        DomainError adminErr = Assert.assertErr(shell.inspect(gwProject, "orders"), "unexpected admin HTTP 500 propagates when healthy");
        Assert.assertEquals("GATEWAY_ADMIN_ERROR", adminErr.code(), "GATEWAY_ADMIN_ERROR propagated");
    }

    public static void testStartBareTargetsLocalOnlyAndExplicitStartWaitsOrDetaches() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Project project = loadSampleProject(root, "secret-start-val");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var observer = new RecordingObserver();
        // Note: billing and worker dirs intentionally absent initially to verify bare REMOTE skip
        var projectFiles = new FakeProjectFiles()
            .withDirectory(root.resolve("orders"))
            .withFile(root.resolve("orders/build/libs/orders-1.0.jar"), Instant.parse("2026-11-20T11:50:00Z"))
            .withFile(root.resolve("orders/build/libs/orders-2.0.jar"), Instant.parse("2026-11-20T11:55:00Z"))
            .withFile(root.resolve("orders/build/libs/orders-2.0-plain.jar"), Instant.parse("2026-11-20T11:59:00Z"));

        ProcessShell shell = new ProcessShell(
            stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer
        );

        // 1. Fresh project: bare start has 0 LOCAL services -> spawns nothing, touches nothing
        ProcessShell.StartResult emptyStart = Assert.assertOk(
            shell.start(project, new ProcessShell.StartRequest(List.of(), false, Duration.ofSeconds(5))),
            "bare start with all REMOTE succeeds as no-op"
        );
        Assert.assertTrue(emptyStart.outcomes().isEmpty(), "no outcomes when all REMOTE");
        Assert.assertEquals(0, processRunner.spawnedRequests().size(), "nothing spawned");

        // 2. Route orders LOCAL, leave billing/worker REMOTE, run bare start -> waits until 200 OK on poll 2
        Assert.assertOk(new RoutingShell(stateStore).updateRoutes(project, List.of("orders"), Route.LOCAL), "route orders local");
        clock.onSleep(step -> {
            if (step == 2) {
                httpClient.stubGet(URI.create("http://127.0.0.1:9001/actuator/health"), 200, "UP");
            }
            return true;
        });

        ProcessShell.StartResult localStart = Assert.assertOk(
            shell.start(project, new ProcessShell.StartRequest(List.of(), false, Duration.ofSeconds(5))),
            "bare start launches LOCAL orders"
        );
        Assert.assertEquals(1, localStart.outcomes().size(), "only LOCAL orders started");
        ProcessShell.StartServiceOutcome ordersOutcome = localStart.outcomes().get(0);
        Assert.assertEquals("orders", ordersOutcome.service(), "orders started");
        Assert.assertEquals(ProcessShell.StartDisposition.SPAWNED, ordersOutcome.disposition(), "orders spawned");
        Assert.assertEquals(HealthState.HEALTHY, ordersOutcome.discovery().health(), "orders waited until HEALTHY");
        Assert.assertEquals(2, clock.sleepCount(), "polled via clock.sleep until healthy");

        // Verify newest non-plain jar resolved and env passed to SpawnRequest
        Assert.assertEquals(1, processRunner.spawnedRequests().size(), "one spawn");
        var spawnReq = processRunner.spawnedRequests().get(0);
        Path expectedJar = root.resolve("orders/build/libs/orders-2.0.jar").toAbsolutePath().normalize();
        Assert.assertEquals(
            List.of("java", "-Dserver.port=9001", "-jar", expectedJar.toString()),
            spawnReq.command(),
            "newest non-plain artifact expanded into command"
        );
        Assert.assertEquals("secret-start-val", spawnReq.envOverrides().get("DB_PASSWORD"), "resolved env passed to child");
        ManagedProcessRecord persisted = Assert.assertOk(
            stateStore.readManagedProcess("acme", ProcessKey.service("orders")),
            "managed record persisted"
        ).orElseThrow();
        Assert.assertEquals(Optional.of(Instant.parse("2026-11-20T11:55:00Z")), persisted.artifactTimestamp(), "artifact timestamp recorded");

        // 3. Re-running start reuses MANAGED orders without duplicating
        ProcessShell.StartResult reuseRes = Assert.assertOk(
            shell.start(project, new ProcessShell.StartRequest(List.of("orders"), false, Duration.ofSeconds(5))),
            "re-start reuses managed orders"
        );
        Assert.assertEquals(1, processRunner.spawnedRequests().size(), "no duplicate spawn");
        Assert.assertEquals(ProcessShell.StartDisposition.REUSED_MANAGED, reuseRes.outcomes().get(0).disposition(), "reused managed");

        // 4. Explicit start with -d on REMOTE worker spawns detached without mutating Routing Table
        projectFiles.withDirectory(root.resolve("worker"));
        ProcessShell.StartResult detachedRes = Assert.assertOk(
            shell.start(project, new ProcessShell.StartRequest(List.of("worker"), true, Duration.ofSeconds(5))),
            "detached start worker succeeds"
        );
        Assert.assertEquals(ProcessShell.StartDisposition.SPAWNED, detachedRes.outcomes().get(0).disposition(), "worker spawned");
        Assert.assertEquals(Route.REMOTE, detachedRes.outcomes().get(0).desiredRoute(), "worker desired route remains REMOTE");
        RoutingTable routesAfter = Assert.assertOk(new RoutingShell(stateStore).showRoutes(project), "read routes after start");
        Assert.assertEquals(Route.REMOTE, routesAfter.routeOf("worker").orElseThrow(), "start never mutates Routing Table");
    }

    public static void testStartReusesUnmanagedFailsFastOnConflictMissingWorkingDirCrashAndTimeout() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Project project = loadSampleProject(root, "sec");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var projectFiles = new FakeProjectFiles()
            .withDirectory(root.resolve("orders"))
            .withDirectory(root.resolve("billing"))
            .withDirectory(root.resolve("worker"))
            .withFile(root.resolve("orders/build/libs/orders-1.0.jar"), Instant.parse("2026-11-20T11:00:00Z"))
            .withFile(root.resolve("billing/build/libs/billing-1.0.jar"), Instant.parse("2026-11-20T11:00:00Z"));

        ProcessShell shell = new ProcessShell(
            stateStore, processRunner, portScanner, httpClient, projectFiles, clock, new RecordingObserver()
        );

        // 1. Conflict on billing + NONE on orders -> fail fast BEFORE spawning orders!
        portScanner.bind(9002, 7777L);
        processRunner.registerProcess(new ProcessSnapshot(
            7777L, true, Optional.of(Instant.parse("2026-11-20T11:30:00Z")),
            Optional.of("python -m http.server 9002"), List.of("-m", "http.server", "9002"),
            List.of(new ParentProcessInfo(10L, Optional.of("bash"), Optional.of("bash"))),
            Optional.empty(), Optional.of("python")
        ));
        DomainError conflictErr = Assert.assertErr(
            shell.start(project, new ProcessShell.StartRequest(List.of("orders", "billing"), false, Duration.ofSeconds(5))),
            "conflict fails fast"
        );
        Assert.assertEquals("PORT_CONFLICT", conflictErr.code(), "PORT_CONFLICT error code");
        Assert.assertContains(conflictErr.message(), "7777", "conflict message includes PID");
        Assert.assertContains(conflictErr.message(), "python -m http.server 9002", "conflict message includes cmdline");
        Assert.assertEquals(0, processRunner.spawnedRequests().size(), "conflict prevents spawning any process");

        // 2. Booting IDE process on billing -> UNMANAGED (IDE) STARTING, start waits until healthy without spawning
        processRunner.registerProcess(new ProcessSnapshot(
            7777L, true, Optional.of(Instant.parse("2026-11-20T11:30:00Z")),
            Optional.of("java -jar billing.jar"), List.of("-jar", "billing.jar"),
            List.of(new ParentProcessInfo(20L, Optional.of("idea64.exe"), Optional.of("idea64.exe"))),
            Optional.empty(), Optional.of("java")
        ));
        clock.onSleep(step -> {
            if (step == 1) {
                httpClient.stubGet(URI.create("http://127.0.0.1:9002/actuator/health"), 200, "UP");
            }
            return true;
        });
        ProcessShell.StartResult ideReuse = Assert.assertOk(
            shell.start(project, new ProcessShell.StartRequest(List.of("billing"), false, Duration.ofSeconds(5))),
            "booting IDE reused and waited until healthy"
        );
        Assert.assertEquals(ProcessShell.StartDisposition.REUSED_UNMANAGED, ideReuse.outcomes().get(0).disposition(), "reused unmanaged IDE");
        Assert.assertEquals(HealthState.HEALTHY, ideReuse.outcomes().get(0).discovery().health(), "waited for IDE health");
        Assert.assertEquals(0, processRunner.spawnedRequests().size(), "IDE process never duplicated");

        // 3. Crashed managed record gets replaced by a fresh spawn
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("worker"), new ManagedProcessRecord(
            9999L, Instant.parse("2026-11-20T11:00:00Z"), List.of("node", "server.js"),
            Optional.empty(), stateStore.logPath("acme", ProcessKey.service("worker"), false),
            stateStore.logPath("acme", ProcessKey.service("worker"), true)
        )), "seed dead worker record");
        ProcessShell.StartResult respawned = Assert.assertOk(
            shell.start(project, new ProcessShell.StartRequest(List.of("worker"), false, Duration.ofSeconds(5))),
            "crashed managed process replaced with fresh spawn"
        );
        Assert.assertEquals(ProcessShell.StartDisposition.SPAWNED, respawned.outcomes().get(0).disposition(), "respawned");
        Assert.assertEquals(1, processRunner.spawnedRequests().size(), "spawned replacement");

        // 4. Lazy artifact / workingDir check fails fast without spawning
        projectFiles.removeDirectory(root.resolve("orders"));
        DomainError missingWd = Assert.assertErr(
            shell.start(project, new ProcessShell.StartRequest(List.of("orders"), false, Duration.ofSeconds(5))),
            "missing workingDir fails fast"
        );
        Assert.assertEquals("WORKING_DIR_NOT_FOUND", missingWd.code(), "missing workingDir code");
        Assert.assertEquals(1, processRunner.spawnedRequests().size(), "no spawn on missing workingDir");

        // 5. Crash while waiting fails fast
        projectFiles.withDirectory(root.resolve("orders"))
            .withFile(root.resolve("orders/build/libs/orders-1.0.jar"), Instant.parse("2026-11-20T11:00:00Z"));
        httpClient.remove("GET", URI.create("http://127.0.0.1:9001/actuator/health"));
        clock.onSleep(step -> {
            long lastPid = 10001L; // worker took 10000, orders gets 10001
            processRunner.markExited(lastPid);
            return true;
        });
        DomainError crashWhileWaiting = Assert.assertErr(
            shell.start(project, new ProcessShell.StartRequest(List.of("orders"), false, Duration.ofSeconds(5))),
            "process crashing during startup fails fast"
        );
        Assert.assertEquals("PROCESS_CRASHED", crashWhileWaiting.code(), "PROCESS_CRASHED error");

        // 6. Timeout while waiting fails fast
        clock.onSleep(_ -> true);
        DomainError timeoutErr = Assert.assertErr(
            shell.start(project, new ProcessShell.StartRequest(List.of("orders"), false, Duration.ofMillis(400))),
            "unhealthy process times out cleanly"
        );
        Assert.assertEquals("START_TIMEOUT", timeoutErr.code(), "START_TIMEOUT error");
    }

    public static void testStopRespectsOwnershipGatewayAllAndKillConflictConsent() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Project project = loadSampleProject(root, "sec");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var projectFiles = new FakeProjectFiles()
            .withDirectory(root.resolve("orders"))
            .withDirectory(root.resolve("billing"))
            .withDirectory(root.resolve("worker"));

        // Seed routing table so we can prove stop never mutates it
        Assert.assertOk(new RoutingShell(stateStore).updateRoutes(project, List.of("orders"), Route.LOCAL), "route orders local");

        // 1. orders: MANAGED alive (PID 4100)
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("orders"), new ManagedProcessRecord(
            4100L, clock.now(), List.of("java", "-jar", "orders.jar"), Optional.empty(),
            stateStore.logPath("acme", ProcessKey.service("orders"), false),
            stateStore.logPath("acme", ProcessKey.service("orders"), true)
        )), "seed orders managed");
        processRunner.registerProcess(new ProcessSnapshot(
            4100L, true, Optional.of(clock.now()), Optional.of("java -jar orders.jar"),
            List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));

        // 2. billing: UNMANAGED IDE alive (PID 5200)
        portScanner.bind(9002, 5200L);
        processRunner.registerProcess(new ProcessSnapshot(
            5200L, true, Optional.of(clock.now()), Optional.of("java -jar billing.jar"),
            List.of(), List.of(new ParentProcessInfo(10L, Optional.empty(), Optional.of("idea64.exe"))),
            Optional.empty(), Optional.of("java")
        ));

        // 3. worker: CRASHED managed record (PID 6300 dead)
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("worker"), new ManagedProcessRecord(
            6300L, clock.now(), List.of("node", "server.js"), Optional.empty(),
            stateStore.logPath("acme", ProcessKey.service("worker"), false),
            stateStore.logPath("acme", ProcessKey.service("worker"), true)
        )), "seed crashed worker");

        // 4. gateway: MANAGED alive (PID 7400)
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.gateway(), new ManagedProcessRecord(
            7400L, clock.now(), List.of("java", "-jar", "wiremock.jar"), Optional.empty(),
            stateStore.logPath("acme", ProcessKey.gateway(), false),
            stateStore.logPath("acme", ProcessKey.gateway(), true)
        )), "seed managed gateway");
        processRunner.registerProcess(new ProcessSnapshot(
            7400L, true, Optional.of(clock.now()), Optional.of("java -jar wiremock.jar"),
            List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));

        ProcessShell shell = new ProcessShell(
            stateStore, processRunner, portScanner, httpClient, projectFiles, clock, new RecordingObserver()
        );

        // Bare stop -> stops orders (4100), cleans worker record (6300), spares billing (5200), leaves gateway (7400) running!
        ProcessShell.StopResult bareStop = Assert.assertOk(
            shell.stop(project, new ProcessShell.StopRequest(List.of(), false, false, Duration.ofSeconds(5))),
            "bare stop succeeds"
        );
        Assert.assertEquals(List.of(4100L), processRunner.stoppedPids(), "only managed orders stopped; gateway spared");
        Assert.assertTrue(processRunner.isAlive(5200L), "unmanaged IDE process never killed");
        Assert.assertTrue(processRunner.isAlive(7400L), "managed gateway spared on bare stop");
        Assert.assertEquals(
            Optional.empty(),
            Assert.assertOk(stateStore.readManagedProcess("acme", ProcessKey.service("orders")), "read orders"),
            "orders record deleted"
        );
        Assert.assertEquals(
            Optional.empty(),
            Assert.assertOk(stateStore.readManagedProcess("acme", ProcessKey.service("worker")), "read worker"),
            "crashed worker record cleaned up"
        );
        Assert.assertEquals(ProcessShell.GatewayStopOutcome.LEFT_RUNNING, bareStop.gatewayOutcome(), "gateway left running");

        // stop --all -> now stops Managed gateway (7400) too, still spares Unmanaged billing (5200)
        ProcessShell.StopResult stopAll = Assert.assertOk(
            shell.stop(project, new ProcessShell.StopRequest(List.of(), true, false, Duration.ofSeconds(5))),
            "stop --all succeeds"
        );
        Assert.assertEquals(ProcessShell.GatewayStopOutcome.STOPPED_MANAGED, stopAll.gatewayOutcome(), "managed gateway stopped");
        Assert.assertFalse(processRunner.isAlive(7400L), "gateway process killed");
        Assert.assertTrue(processRunner.isAlive(5200L), "unmanaged billing still alive");
        Assert.assertEquals(
            Optional.empty(),
            Assert.assertOk(stateStore.readManagedProcess("acme", ProcessKey.gateway()), "read gateway"),
            "gateway record deleted"
        );

        // Unmanaged Gateway must never be killed by stop --all
        portScanner.bind(8080, 7500L);
        processRunner.registerProcess(new ProcessSnapshot(
            7500L, true, Optional.of(clock.now()), Optional.of("java -jar ext-wiremock.jar"),
            List.of(), List.of(new ParentProcessInfo(11L, Optional.empty(), Optional.of("bash"))),
            Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "OK");
        ProcessShell.StopResult stopAllUnmanagedGw = Assert.assertOk(
            shell.stop(project, new ProcessShell.StopRequest(List.of(), true, false, Duration.ofSeconds(5))),
            "stop --all with unmanaged gateway succeeds"
        );
        Assert.assertEquals(ProcessShell.GatewayStopOutcome.SPARED_NON_MANAGED, stopAllUnmanagedGw.gatewayOutcome(), "unmanaged gateway spared");
        Assert.assertTrue(processRunner.isAlive(7500L), "unmanaged gateway alive");

        // Conflict on orders (PID 8888): spared without --kill-conflict, rejected if bare --kill-conflict, killed with explicit target + --kill-conflict
        portScanner.bind(9001, 8888L);
        processRunner.registerProcess(new ProcessSnapshot(
            8888L, true, Optional.of(clock.now()), Optional.of("python -m http.server 9001"),
            List.of(), List.of(), Optional.empty(), Optional.of("python")
        ));

        ProcessShell.StopResult sparedConflict = Assert.assertOk(
            shell.stop(project, new ProcessShell.StopRequest(List.of("orders"), false, false, Duration.ofSeconds(5))),
            "stop without --kill-conflict spares conflict"
        );
        Assert.assertEquals(ProcessShell.StopDisposition.SPARED_CONFLICT, sparedConflict.outcomes().get(0).disposition(), "conflict spared");
        Assert.assertTrue(processRunner.isAlive(8888L), "conflict still alive without consent");

        DomainError bareKillConflict = Assert.assertErr(
            shell.stop(project, new ProcessShell.StopRequest(List.of(), false, true, Duration.ofSeconds(5))),
            "bare --kill-conflict without target rejected"
        );
        Assert.assertEquals("INVALID_STOP_OPTIONS", bareKillConflict.code(), "bare kill-conflict error code");

        // Even with --kill-conflict on billing (UNMANAGED), unmanaged is STILL spared!
        ProcessShell.StopResult unmanagedConsent = Assert.assertOk(
            shell.stop(project, new ProcessShell.StopRequest(List.of("billing"), false, true, Duration.ofSeconds(5))),
            "stop unmanaged with --kill-conflict still spares unmanaged"
        );
        Assert.assertEquals(ProcessShell.StopDisposition.SPARED_UNMANAGED, unmanagedConsent.outcomes().get(0).disposition(), "spared unmanaged");
        Assert.assertTrue(processRunner.isAlive(5200L), "unmanaged billing still alive");

        // Explicit target + --kill-conflict kills CONFLICT (8888)
        ProcessShell.StopResult killedConflict = Assert.assertOk(
            shell.stop(project, new ProcessShell.StopRequest(List.of("orders"), false, true, Duration.ofSeconds(5))),
            "stop orders --kill-conflict kills conflict"
        );
        Assert.assertEquals(ProcessShell.StopDisposition.KILLED_CONFLICT, killedConflict.outcomes().get(0).disposition(), "conflict killed");
        Assert.assertFalse(processRunner.isAlive(8888L), "conflict process terminated");

        // Routing table remained untouched throughout all stop operations
        RoutingTable routesAfter = Assert.assertOk(new RoutingShell(stateStore).showRoutes(project), "read routes after stop");
        Assert.assertEquals(Route.LOCAL, routesAfter.routeOf("orders").orElseThrow(), "stop never mutates Routing Table");
    }

    static Project loadSampleProject(Path root, String secretValue) {
        var configSource = new InMemoryConfigSource(root)
            .withEnv("TEST_DB_SECRET", secretValue)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "defaults": {
                    "env": {
                      "DB_PASSWORD": "${env:TEST_DB_SECRET}"
                    }
                  },
                  "runtimes": {
                    "java": {
                      "command": ["java", "-Dserver.port={{port}}", "-jar", "{{artifact}}"],
                      "healthPath": "/actuator/health",
                      "artifactGlob": "build/libs/*.jar",
                      "artifactExclude": "*-plain.jar"
                    },
                    "plain": {
                      "command": ["node", "server.js"]
                    }
                  },
                  "gateway": {
                    "type": "wiremock",
                    "port": 8080,
                    "healthPath": "/__admin/health",
                    "command": ["java", "-jar", "wiremock.jar", "--port", "8080"],
                    "workingDir": "."
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "orders" },
                    "billing": { "runtime": "java", "port": 9002, "workingDir": "billing" },
                    "worker": { "runtime": "plain", "workingDir": "worker" }
                  }
                }
                """);
        return Assert.assertOk(new ProjectLoader(configSource).load(Optional.empty()), "sample project loads");
    }
}
