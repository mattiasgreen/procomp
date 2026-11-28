package procomp.shell;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.gateway.GatewaySyncStatus;
import procomp.core.gateway.RouteDrift;
import procomp.core.process.HealthState;
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

public final class GatewayShellTest {

    public static void testStartSpawnsManagedReusesExistingFailsOnConflictOrMissingCommandAndNeverTouchesRoutes() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Project project = loadSampleProject(root, true);
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var projectFiles = new FakeProjectFiles().withDirectory(root.resolve("wiremock"));
        var observer = new RecordingObserver();

        Assert.assertOk(new RoutingShell(stateStore).updateRoutes(project, List.of("orders"), Route.LOCAL), "seed orders local");
        GatewayShell shell = new GatewayShell(stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer);

        // 1. NONE -> spawns Managed Gateway and waits for /__admin/health 200 OK
        clock.onSleep(step -> {
            if (step == 2) {
                httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "{\"status\":\"healthy\"}");
            }
            return true;
        });
        GatewayShell.GatewayStartResult started = Assert.assertOk(
            shell.start(project, new GatewayShell.GatewayStartRequest(false, Duration.ofSeconds(5))),
            "gateway start spawns Managed Gateway"
        );
        Assert.assertEquals(GatewayShell.GatewayStartDisposition.SPAWNED, started.disposition(), "disposition SPAWNED");
        Assert.assertEquals(Ownership.MANAGED, started.discovery().ownership(), "ownership MANAGED");
        Assert.assertEquals(HealthState.HEALTHY, started.discovery().health(), "waited until HEALTHY");
        Assert.assertEquals(2, clock.sleepCount(), "polled via clock.sleep");
        Assert.assertEquals(1, processRunner.spawnedRequests().size(), "one gateway spawned");
        Assert.assertEquals("gw-secret-val", processRunner.spawnedRequests().get(0).envOverrides().get("GW_TOKEN"), "gateway env passed");

        // 2. Re-running start reuses Managed Gateway without duplicating
        GatewayShell.GatewayStartResult reusedManaged = Assert.assertOk(
            shell.start(project, new GatewayShell.GatewayStartRequest(false, Duration.ofSeconds(5))),
            "second gateway start reuses Managed"
        );
        Assert.assertEquals(GatewayShell.GatewayStartDisposition.REUSED_MANAGED, reusedManaged.disposition(), "REUSED_MANAGED");
        Assert.assertEquals(1, processRunner.spawnedRequests().size(), "no duplicate gateway spawn");

        // 3. Stop Managed Gateway cleanly and verify record removed
        GatewayShell.GatewayStopResult stopped = Assert.assertOk(
            shell.stop(project, new GatewayShell.GatewayStopRequest(Duration.ofSeconds(5))),
            "gateway stop terminates Managed Gateway"
        );
        Assert.assertEquals(GatewayShell.GatewayStopDisposition.STOPPED_MANAGED, stopped.disposition(), "STOPPED_MANAGED");
        Assert.assertEquals(
            Optional.empty(),
            Assert.assertOk(stateStore.readManagedProcess("acme", ProcessKey.gateway()), "gateway record read"),
            "gateway record deleted"
        );
        Assert.assertEquals(List.of(started.discovery().pid().orElseThrow()), processRunner.stoppedPids(), "stopped managed pid");

        // 4. Stopping when NONE returns ALREADY_STOPPED
        GatewayShell.GatewayStopResult alreadyStopped = Assert.assertOk(
            shell.stop(project, new GatewayShell.GatewayStopRequest(Duration.ofSeconds(5))),
            "gateway stop when already stopped succeeds"
        );
        Assert.assertEquals(GatewayShell.GatewayStopDisposition.ALREADY_STOPPED, alreadyStopped.disposition(), "ALREADY_STOPPED");

        // 5. CRASHED managed record is cleaned by stop, or respawned by start -d
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.gateway(), new ManagedProcessRecord.Running(
            9991L, clock.now(), List.of("java", "-jar", "wiremock.jar"), Optional.empty(),
            stateStore.logPath("acme", ProcessKey.gateway(), false),
            stateStore.logPath("acme", ProcessKey.gateway(), true)
        )), "seed crashed gateway");
        httpClient.remove("GET", URI.create("http://127.0.0.1:8080/__admin/health"));
        GatewayShell.GatewayStartResult respawned = Assert.assertOk(
            shell.start(project, new GatewayShell.GatewayStartRequest(true, Duration.ofSeconds(5))),
            "gateway start -d replaces crashed managed gateway"
        );
        Assert.assertEquals(GatewayShell.GatewayStartDisposition.SPAWNED, respawned.disposition(), "respawned after crash");
        Assert.assertTrue(respawned.detached(), "detached flag preserved");
        Assert.assertOk(shell.stop(project, new GatewayShell.GatewayStopRequest(Duration.ofSeconds(5))), "clean up respawned");

        // 6. Unmanaged WireMock on port 8080 -> start reuses without duplicating; stop REFUSES without killing!
        portScanner.bind(8080, 6100L);
        processRunner.registerProcess(new ObservedProcess(
            6100L, true, Optional.of(clock.now()), Optional.of("java -jar ext-wiremock.jar"),
            List.of("-jar", "ext-wiremock.jar"), List.of(new ParentProcess(10L, Optional.of("bash"), Optional.of("bash"))),
            Optional.empty(), Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "OK");

        int spawnsBeforeUnmanaged = processRunner.spawnedRequests().size();
        GatewayShell.GatewayStartResult reusedUnmanaged = Assert.assertOk(
            shell.start(project, new GatewayShell.GatewayStartRequest(false, Duration.ofSeconds(5))),
            "gateway start reuses Unmanaged WireMock"
        );
        Assert.assertEquals(GatewayShell.GatewayStartDisposition.REUSED_UNMANAGED, reusedUnmanaged.disposition(), "REUSED_UNMANAGED");
        Assert.assertEquals(spawnsBeforeUnmanaged, processRunner.spawnedRequests().size(), "unmanaged gateway never duplicated");

        DomainError stopUnmanagedErr = Assert.assertErr(
            shell.stop(project, new GatewayShell.GatewayStopRequest(Duration.ofSeconds(5))),
            "gateway stop refuses on Unmanaged Gateway"
        );
        Assert.assertEquals("GATEWAY_UNMANAGED", stopUnmanagedErr.code(), "GATEWAY_UNMANAGED error code");
        Assert.assertTrue(processRunner.isAlive(6100L), "unmanaged gateway must never be killed");

        // 7. Conflict on port 8080 -> start fails fast with PORT_CONFLICT, stop refuses with GATEWAY_CONFLICT without killing
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 404, "Not Found");
        DomainError startConflictErr = Assert.assertErr(
            shell.start(project, new GatewayShell.GatewayStartRequest(false, Duration.ofSeconds(5))),
            "gateway start fails fast on port conflict"
        );
        Assert.assertEquals("PORT_CONFLICT", startConflictErr.code(), "PORT_CONFLICT on gateway port");
        Assert.assertContains(startConflictErr.message(), "6100", "conflict includes PID");

        DomainError stopConflictErr = Assert.assertErr(
            shell.stop(project, new GatewayShell.GatewayStopRequest(Duration.ofSeconds(5))),
            "gateway stop refuses on Conflict"
        );
        Assert.assertEquals("GATEWAY_CONFLICT", stopConflictErr.code(), "GATEWAY_CONFLICT error code");
        Assert.assertTrue(processRunner.isAlive(6100L), "conflict process never killed by gateway stop");

        // 8. Project without gateway.command fails fast with GATEWAY_COMMAND_MISSING when NONE
        portScanner.unbind(8080);
        Project noCmdProject = loadSampleProject(root, false);
        DomainError missingCmdErr = Assert.assertErr(
            shell.start(noCmdProject, new GatewayShell.GatewayStartRequest(false, Duration.ofSeconds(5))),
            "missing gateway.command fails fast"
        );
        Assert.assertEquals("GATEWAY_COMMAND_MISSING", missingCmdErr.code(), "GATEWAY_COMMAND_MISSING code");

        // Routing table untouched by any gateway start/stop calls
        RoutingTable routesAfter = Assert.assertOk(new RoutingShell(stateStore).showRoutes(project), "read routes");
        Assert.assertEquals(Route.LOCAL, routesAfter.routeOf("orders").orElseThrow(), "orders route preserved");
    }

    public static void testStatusAndReconcileConvergeDriftClearOnlyManagedMappingsAndSupportImportFallback() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Project project = loadSampleProject(root, true);
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var projectFiles = new FakeProjectFiles().withDirectory(root.resolve("wiremock"));
        GatewayShell shell = new GatewayShell(
            stateStore, processRunner, portScanner, httpClient, projectFiles, clock, new RecordingObserver()
        );

        // 1. Offline status -> OFFLINE, empty drift; offline reconcile fails fast
        GatewayShell.GatewayStatusReport offlineStatus = Assert.assertOk(shell.status(project), "offline status succeeds");
        Assert.assertEquals(Ownership.NONE, offlineStatus.discovery().ownership(), "offline owner NONE");
        Assert.assertEquals(HealthState.STOPPED, offlineStatus.discovery().health(), "offline health STOPPED");
        Assert.assertEquals(GatewaySyncStatus.OFFLINE, offlineStatus.drift().syncStatus(), "offline syncStatus OFFLINE");

        DomainError offlineReconcileErr = Assert.assertErr(shell.reconcile(project), "reconcile fails fast when Gateway offline");
        Assert.assertEquals("GATEWAY_NOT_HEALTHY", offlineReconcileErr.code(), "GATEWAY_NOT_HEALTHY error code");

        // 2. Start Managed Gateway detached, while STARTING -> status still OFFLINE and reconcile still fails fast
        Assert.assertOk(shell.start(project, new GatewayShell.GatewayStartRequest(true, Duration.ofSeconds(5))), "start -d");
        GatewayShell.GatewayStatusReport startingStatus = Assert.assertOk(shell.status(project), "starting status succeeds");
        Assert.assertEquals(HealthState.STARTING, startingStatus.discovery().health(), "gateway STARTING");
        Assert.assertEquals(GatewaySyncStatus.OFFLINE, startingStatus.drift().syncStatus(), "STARTING gateway is OFFLINE for sync");
        Assert.assertEquals("GATEWAY_NOT_HEALTHY", Assert.assertErr(shell.reconcile(project), "reconcile fails when STARTING").code(), "STARTING fails reconcile");

        // 3. Bring Gateway HEALTHY with 1 stale managed mapping, 1 custom user mapping, and 1 other-project mapping
        clock.advance(Duration.ofSeconds(45));
        httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "{\"status\":\"healthy\"}");

        Map<String, JsonValue.JsonObject> liveWireMockMappings = new LinkedHashMap<>();
        liveWireMockMappings.put("user-stub-1", asObj("""
            {"id":"user-stub-1","request":{"method":"GET","urlPath":"/custom"},"response":{"status":200}}"""));
        liveWireMockMappings.put("other-proj-1", asObj("""
            {"id":"other-proj-1","request":{"method":"ANY","urlPathPattern":"/orders(/.*)?"},"response":{"proxyBaseUrl":"http://127.0.0.1:9999","proxyUrlPrefixToRemove":"/orders"},"metadata":{"procomp":true,"procompProject":"other","service":"orders","route":"LOCAL"}}"""));
        liveWireMockMappings.put("stale-acme-1", asObj("""
            {"id":"stale-acme-1","request":{"method":"ANY","urlPathPattern":"/legacy(/.*)?"},"response":{"proxyBaseUrl":"https://shared-dev.mycorp/legacy","proxyUrlPrefixToRemove":"/legacy"},"metadata":{"procomp":true,"procompProject":"acme","service":"legacy","route":"REMOTE"}}"""));

        httpClient.stub("GET", URI.create("http://127.0.0.1:8080/__admin/mappings"), _ -> {
            List<JsonValue> arr = new ArrayList<>(liveWireMockMappings.values());
            return Result.ok(new HttpResponseData(200, Map.of(), Json.stringify(Json.obj(Map.of("mappings", Json.arr(arr))))));
        });
        httpClient.stub("DELETE", URI.create("http://127.0.0.1:8080/__admin/mappings/stale-acme-1"), _ -> {
            liveWireMockMappings.remove("stale-acme-1");
            return Result.ok(new HttpResponseData(200, Map.of(), "{}"));
        });
        httpClient.stub("POST", URI.create("http://127.0.0.1:8080/__admin/mappings/import"), req -> {
            JsonValue.JsonObject payload = (JsonValue.JsonObject) Assert.assertOk(Json.parse(req.body()), "import json");
            JsonValue.JsonArray arr = (JsonValue.JsonArray) payload.get("mappings").orElseThrow();
            for (JsonValue el : arr.elements()) {
                JsonValue.JsonObject m = (JsonValue.JsonObject) el;
                String id = ((JsonValue.JsonString) m.get("id").orElseThrow()).value();
                liveWireMockMappings.put(id, m);
            }
            return Result.ok(new HttpResponseData(200, Map.of(), "{}"));
        });

        Assert.assertOk(new RoutingShell(stateStore).updateRoutes(project, List.of("orders"), Route.LOCAL), "route orders local");
        GatewayShell.GatewayStatusReport driftedBefore = Assert.assertOk(shell.status(project), "status before reconcile");
        Assert.assertEquals(HealthState.HEALTHY, driftedBefore.discovery().health(), "gateway HEALTHY");
        Assert.assertEquals(Optional.of(Duration.ofSeconds(45)), driftedBefore.uptime(), "uptime reported");
        Assert.assertEquals(GatewaySyncStatus.DRIFTED, driftedBefore.drift().syncStatus(), "DRIFTED before reconcile");
        Assert.assertEquals(
            List.of(new RouteDrift("orders", Route.LOCAL, "UNSET"), new RouteDrift("billing", Route.REMOTE, "UNSET")),
            driftedBefore.drift().drifted(),
            "orders & billing UNSET"
        );
        Assert.assertEquals(1, driftedBefore.drift().staleMappings().size(), "1 stale managed mapping detected");

        // 4. Reconcile via /__admin/mappings/import -> deletes stale-acme-1, keeps user-stub-1 & other-proj-1, installs orders + billing -> IN_SYNC
        GatewayShell.GatewayReconcileResult reconciled = Assert.assertOk(shell.reconcile(project), "reconcile succeeds");
        Assert.assertEquals(2, reconciled.appliedMappings(), "applied 2 service mappings");
        Assert.assertEquals(1, reconciled.deletedPriorMappings(), "deleted 1 stale managed mapping");
        Assert.assertFalse(reconciled.usedSingleMappingFallback(), "used /import endpoint");
        Assert.assertEquals(GatewaySyncStatus.IN_SYNC, reconciled.drift().syncStatus(), "IN_SYNC after reconcile");
        Assert.assertTrue(liveWireMockMappings.containsKey("user-stub-1"), "unrelated user stub preserved");
        Assert.assertTrue(liveWireMockMappings.containsKey("other-proj-1"), "other project mapping preserved");
        Assert.assertFalse(liveWireMockMappings.containsKey("stale-acme-1"), "stale acme mapping removed");

        // Stub DELETE for the deterministic IDs now present so subsequent reconcile can delete them cleanly
        for (String existingId : new ArrayList<>(liveWireMockMappings.keySet())) {
            if (!existingId.equals("user-stub-1") && !existingId.equals("other-proj-1")) {
                httpClient.stub("DELETE", URI.create("http://127.0.0.1:8080/__admin/mappings/" + existingId), _ -> {
                    liveWireMockMappings.remove(existingId);
                    return Result.ok(new HttpResponseData(200, Map.of(), "{}"));
                });
            }
        }

        // 5. Change route in Routing Table -> status shows exact drift -> reconcile with /import returning 404 falls back to POST /__admin/mappings!
        Assert.assertOk(new RoutingShell(stateStore).updateRoutes(project, List.of("orders"), Route.REMOTE), "flip orders REMOTE");
        GatewayShell.GatewayStatusReport flippedStatus = Assert.assertOk(shell.status(project), "status after flip");
        Assert.assertEquals(GatewaySyncStatus.DRIFTED, flippedStatus.drift().syncStatus(), "DRIFTED after route flip");
        Assert.assertEquals(List.of(new RouteDrift("orders", Route.REMOTE, "LOCAL")), flippedStatus.drift().drifted(), "orders desired REMOTE live LOCAL");

        httpClient.stub("POST", URI.create("http://127.0.0.1:8080/__admin/mappings/import"),
            _ -> Result.ok(new HttpResponseData(404, Map.of(), "Not Found")));
        httpClient.stub("POST", URI.create("http://127.0.0.1:8080/__admin/mappings"), req -> {
            JsonValue.JsonObject m = (JsonValue.JsonObject) Assert.assertOk(Json.parse(req.body()), "single mapping json");
            String id = ((JsonValue.JsonString) m.get("id").orElseThrow()).value();
            liveWireMockMappings.put(id, m);
            return Result.ok(new HttpResponseData(201, Map.of(), "{}"));
        });

        GatewayShell.GatewayReconcileResult fallbackReconcile = Assert.assertOk(shell.reconcile(project), "reconcile via 404 fallback succeeds");
        Assert.assertTrue(fallbackReconcile.usedSingleMappingFallback(), "used single mapping fallback on /import 404");
        Assert.assertEquals(GatewaySyncStatus.IN_SYNC, fallbackReconcile.drift().syncStatus(), "IN_SYNC after fallback reconcile");
        Assert.assertEquals(GatewaySyncStatus.IN_SYNC, Assert.assertOk(shell.status(project), "status after fallback").drift().syncStatus(), "status confirms IN_SYNC");
    }

    public static void testGatewayRestartRestartsManagedSparesUnmanagedAndFailsOnStopping() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Project project = ProcessShellTest.loadSampleProject(root, "top-secret");

        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient()
            .stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "{\"status\":\"healthy\"}");
        var projectFiles = new FakeProjectFiles().withDirectory(root);

        GatewayShell shell = new GatewayShell(
            stateStore, processRunner, portScanner, httpClient, projectFiles, clock, new RecordingObserver()
        );

        // 1. Restart when STOPPED -> SPAWNED
        GatewayShell.GatewayRestartResult spawned = Assert.assertOk(
            shell.restart(project, new GatewayShell.GatewayRestartRequest(false, Duration.ofSeconds(5))),
            "restart stopped gateway spawns it"
        );
        Assert.assertEquals(GatewayShell.GatewayRestartDisposition.SPAWNED, spawned.disposition(), "SPAWNED");
        long firstPid = spawned.discovery().pid().orElseThrow();

        // 2. Restart when MANAGED HEALTHY -> stops old PID and spawns new PID (RESTARTED)
        GatewayShell.GatewayRestartResult restarted = Assert.assertOk(
            shell.restart(project, new GatewayShell.GatewayRestartRequest(false, Duration.ofSeconds(5))),
            "restart managed gateway restarts it"
        );
        Assert.assertEquals(GatewayShell.GatewayRestartDisposition.RESTARTED, restarted.disposition(), "RESTARTED");
        Assert.assertFalse(processRunner.isAlive(firstPid), "old gateway PID stopped");
        Assert.assertTrue(restarted.discovery().pid().orElseThrow() != firstPid, "new gateway PID spawned");

        // 3. Restart while STOPPING -> fails fast with PROCESS_STOPPING
        ManagedProcessRecord gwRec = Assert.assertOk(
            stateStore.readManagedProcess("acme", ProcessKey.gateway()), "read gw"
        ).orElseThrow();
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.gateway(), gwRec.requestStop(clock.now())), "mark gw stopping");
        DomainError stoppingErr = Assert.assertErr(
            shell.restart(project, new GatewayShell.GatewayRestartRequest(true, Duration.ofSeconds(5))),
            "restart while gateway STOPPING fails fast"
        );
        Assert.assertEquals("PROCESS_STOPPING", stoppingErr.code(), "PROCESS_STOPPING");
    }

    public static void testReconcileWithFallbackRemoteDeletesOldLocalMappingOnFlipToRemote() {
        Path root = Path.of("C:/work/fb").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(root)
            .withUser("mattias")
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "fb",
                  "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
                  "gateway": {
                    "type": "wiremock",
                    "port": 8080,
                    "healthPath": "/__admin/health",
                    "proxyHeaders": { "USER": "{{user}}" },
                    "fallbackRemote": { "targetUrl": "http://shared-dev-env", "stripPrefix": "/api" }
                  },
                  "services": {
                    "orders": { "port": 9001, "runtime": "java", "workingDir": ".", "proxyPath": "/orders" },
                    "billing": { "port": 9002, "runtime": "java", "workingDir": ".", "proxyPath": "/billing" }
                  }
                }
                """);
        Project project = Assert.assertOk(new ProjectLoader(configSource).load(Optional.empty()), "project loads");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-23T10:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner().bind(8080, 7200L);
        processRunner.registerProcess(new ObservedProcess(
            7200L, true, Optional.of(clock.now()), Optional.of("java -jar wiremock.jar"),
            List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));
        var httpClient = new FakeHttpClient();
        httpClient.stub("GET", URI.create("http://127.0.0.1:8080/__admin/health"),
            _ -> Result.ok(new HttpResponseData(200, Map.of(), "{\"status\":\"healthy\"}")));

        Map<String, JsonValue.JsonObject> live = new LinkedHashMap<>();
        httpClient.stub("GET", URI.create("http://127.0.0.1:8080/__admin/mappings"),
            _ -> Result.ok(new HttpResponseData(200, Map.of(),
                Json.stringify(Json.obj(Map.of("mappings", Json.arr(new ArrayList<>(live.values()))))))));
        httpClient.stub("POST", URI.create("http://127.0.0.1:8080/__admin/mappings/import"), req -> {
            JsonValue.JsonObject payload = (JsonValue.JsonObject) Assert.assertOk(Json.parse(req.body()), "import json");
            JsonValue.JsonArray arr = (JsonValue.JsonArray) payload.get("mappings").orElseThrow();
            for (JsonValue el : arr.elements()) {
                JsonValue.JsonObject m = (JsonValue.JsonObject) el;
                String id = ((JsonValue.JsonString) m.get("id").orElseThrow()).value();
                live.put(id, m);
                httpClient.stub("DELETE", URI.create("http://127.0.0.1:8080/__admin/mappings/" + id), _ -> {
                    live.remove(id);
                    return Result.ok(new HttpResponseData(200, Map.of(), "{}"));
                });
            }
            return Result.ok(new HttpResponseData(200, Map.of(), "{}"));
        });

        var routingShell = new RoutingShell(stateStore);
        var shell = new GatewayShell(
            stateStore, processRunner, portScanner, httpClient, new FakeProjectFiles(), clock,
            procomp.shell.ports.ProgressObserver.noop()
        );

        // Start with orders=LOCAL, billing=REMOTE -> reconcile produces 2 mappings (orders + fallback-remote)
        Assert.assertOk(routingShell.updateRoutes(project, List.of("orders"), Route.LOCAL), "orders LOCAL");
        GatewayShell.GatewayReconcileResult first = Assert.assertOk(shell.reconcile(project), "first reconcile");
        Assert.assertEquals(GatewaySyncStatus.IN_SYNC, first.drift().syncStatus(), "IN_SYNC after first reconcile");
        Assert.assertEquals(2, live.size(), "only orders LOCAL and fallback-remote mappings exist");

        // Flip orders back to REMOTE -> reconcile deletes orders priority-5 mapping, keeps only fallback-remote (1 mapping)
        Assert.assertOk(routingShell.updateRoutes(project, List.of("orders"), Route.REMOTE), "orders REMOTE");
        Assert.assertEquals(GatewaySyncStatus.DRIFTED, Assert.assertOk(shell.status(project), "status drifted").drift().syncStatus(), "DRIFTED after flip");
        GatewayShell.GatewayReconcileResult second = Assert.assertOk(shell.reconcile(project), "second reconcile");
        Assert.assertEquals(GatewaySyncStatus.IN_SYNC, second.drift().syncStatus(), "IN_SYNC after second reconcile");
        Assert.assertEquals(1, live.size(), "only fallback-remote catch-all mapping remains");
    }

    public static void testReconcileFailsFastOnMissingServiceTargetBeforeMutatingGateway() {
        Path root = Path.of("C:/work/broken").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "broken",
                  "runtimes": {
                    "plain": { "command": ["node", "app.js"] }
                  },
                  "gateway": {
                    "type": "wiremock",
                    "port": 8080,
                    "healthPath": "/__admin/health"
                  },
                  "services": {
                    "worker": { "runtime": "plain", "workingDir": "." }
                  }
                }
                """);
        Project project = Assert.assertOk(new ProjectLoader(configSource).load(Optional.empty()), "project loads");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner().bind(8080, 6200L);
        processRunner.registerProcess(new ObservedProcess(
            6200L, true, Optional.of(clock.now()), Optional.of("java -jar wiremock.jar"),
            List.of(), List.of(), Optional.empty(), Optional.of("java")
        ));
        var httpClient = new FakeHttpClient()
            .stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "OK");

        GatewayShell shell = new GatewayShell(
            stateStore, processRunner, portScanner, httpClient, new FakeProjectFiles(), clock, new RecordingObserver()
        );
        DomainError missingRemote = Assert.assertErr(shell.reconcile(project), "REMOTE without remoteUrl fails reconcile");
        Assert.assertEquals("GATEWAY_MISSING_REMOTE_URL", missingRemote.code(), "GATEWAY_MISSING_REMOTE_URL code");
        boolean anyMutatingCall = httpClient.recordedRequests().stream()
            .anyMatch(req -> "DELETE".equals(req.method()) || "POST".equals(req.method()));
        Assert.assertFalse(anyMutatingCall, "no DELETE or POST sent to WireMock when buildPlan fails");
    }

    private static JsonValue.JsonObject asObj(String json) {
        return (JsonValue.JsonObject) Assert.assertOk(Json.parse(json), "valid json fixture");
    }

    static Project loadSampleProject(Path root, boolean withGatewayCommand) {
        String gwBlock = withGatewayCommand
            ? """
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "healthPath": "/__admin/health",
                "command": ["java", "-jar", "wiremock.jar", "--port", "8080"],
                "workingDir": "wiremock",
                "env": {
                  "GW_TOKEN": "${env:TEST_GW_SECRET}"
                }
              },"""
            : """
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "healthPath": "/__admin/health"
              },""";
        var configSource = new InMemoryConfigSource(root)
            .withEnv("TEST_GW_SECRET", "gw-secret-val")
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "defaults": {
                    "proxyPathTemplate": "/{{service}}",
                    "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}"
                  },
                  "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                  },
                  %s
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "orders" },
                    "billing": { "runtime": "java", "port": 9002, "workingDir": "billing" }
                  }
                }
                """.formatted(gwBlock));
        return Assert.assertOk(new ProjectLoader(configSource).load(Optional.empty()), "sample project loads");
    }
}
