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
import procomp.shell.ports.ProcessRunner.ParentProcessInfo;
import procomp.shell.ports.ProcessRunner.ProcessSnapshot;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GatewayCliTest {

    public static void testGatewayLifecycleStatusJsonReconcileAndExitCodes() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = sampleConfigSource(root, "top-secret-gw-token");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var workspaceFiles = new FakeWorkspaceFiles().withDirectory(root.resolve("wiremock"));

        Map<String, JsonValue.JsonObject> liveMappings = new LinkedHashMap<>();
        httpClient.stub("GET", URI.create("http://127.0.0.1:8080/__admin/mappings"), _ -> {
            List<JsonValue> arr = new ArrayList<>(liveMappings.values());
            return Result.ok(new HttpResponseData(200, Map.of(), Json.stringify(Json.obj(Map.of("mappings", Json.arr(arr))))));
        });
        httpClient.stub("POST", URI.create("http://127.0.0.1:8080/__admin/mappings/import"), req -> {
            JsonValue.JsonObject payload = (JsonValue.JsonObject) Assert.assertOk(Json.parse(req.body()), "valid import payload");
            JsonValue.JsonArray arr = (JsonValue.JsonArray) payload.get("mappings").orElseThrow();
            for (JsonValue el : arr.elements()) {
                JsonValue.JsonObject m = (JsonValue.JsonObject) el;
                String id = ((JsonValue.JsonString) m.get("id").orElseThrow()).value();
                liveMappings.put(id, m);
            }
            return Result.ok(new HttpResponseData(200, Map.of(), "{}"));
        });

        // 1. Offline status --json -> OFFLINE, reconcile -> exit 1
        CliResult offlineStatus = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "gateway", "status", "--json");
        Assert.assertEquals(0, offlineStatus.exitCode(), "gateway status --json offline exits 0; stderr=" + offlineStatus.stderr());
        JsonValue.JsonObject offlineObj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(offlineStatus.stdout().trim()), "valid json");
        Assert.assertEquals("NONE", ((JsonValue.JsonString) offlineObj.get("owner").orElseThrow()).value(), "offline owner NONE");
        Assert.assertEquals("STOPPED", ((JsonValue.JsonString) offlineObj.get("health").orElseThrow()).value(), "offline health STOPPED");
        Assert.assertEquals("OFFLINE", ((JsonValue.JsonString) offlineObj.get("syncStatus").orElseThrow()).value(), "offline syncStatus OFFLINE");
        Assert.assertNotContains(offlineStatus.stdout(), "top-secret-gw-token", "status --json never leaks secrets");

        CliResult offlineReconcile = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "gateway", "reconcile");
        Assert.assertEquals(1, offlineReconcile.exitCode(), "offline reconcile exits 1");
        Assert.assertContains(offlineReconcile.stderr(), "GATEWAY_NOT_HEALTHY", "stderr reports GATEWAY_NOT_HEALTHY");

        // 2. gateway start -> spawns Managed Gateway and waits until healthy
        clock.onSleep(step -> {
            if (step == 1) {
                httpClient.stubGet(URI.create("http://127.0.0.1:8080/__admin/health"), 200, "{\"status\":\"healthy\"}");
            }
            return true;
        });
        CliResult startRes = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "gateway", "start", "--timeout", "10");
        Assert.assertEquals(0, startRes.exitCode(), "gateway start exits 0; stderr=" + startRes.stderr());
        Assert.assertContains(startRes.stdout(), "MANAGED", "start stdout mentions MANAGED");
        Assert.assertContains(startRes.stdout(), "HEALTHY", "start stdout mentions HEALTHY");

        // 3. Set orders LOCAL via route CLI -> gateway status reports DRIFTED -> reconcile -> IN_SYNC
        Assert.assertEquals(0, runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "route", "orders", "local").exitCode(), "route orders local");
        clock.advance(Duration.ofSeconds(30));

        CliResult driftedText = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "gateway", "status");
        Assert.assertEquals(0, driftedText.exitCode(), "gateway status text exits 0");
        Assert.assertContains(driftedText.stdout(), "DRIFTED", "status text prints DRIFTED");
        Assert.assertContains(driftedText.stdout(), "orders", "status text lists drifted orders");
        Assert.assertContains(driftedText.stdout(), "UNSET", "status text lists UNSET");
        Assert.assertNotContains(driftedText.stdout(), "top-secret-gw-token", "status text never leaks secrets");

        CliResult reconcileRes = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "gateway", "reconcile");
        Assert.assertEquals(0, reconcileRes.exitCode(), "gateway reconcile exits 0; stderr=" + reconcileRes.stderr());
        Assert.assertContains(reconcileRes.stdout(), "IN_SYNC", "reconcile confirms IN_SYNC");

        CliResult syncedJson = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "gateway", "status", "--json");
        Assert.assertEquals(0, syncedJson.exitCode(), "gateway status --json after reconcile exits 0");
        JsonValue.JsonObject syncedObj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(syncedJson.stdout().trim()), "valid json");
        Assert.assertEquals("IN_SYNC", ((JsonValue.JsonString) syncedObj.get("syncStatus").orElseThrow()).value(), "syncStatus IN_SYNC");
        Assert.assertEquals(0, ((JsonValue.JsonArray) syncedObj.get("drifted").orElseThrow()).elements().size(), "no drifted routes");

        // 4. gateway stop -> stops Managed Gateway cleanly
        CliResult stopManaged = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "gateway", "stop");
        Assert.assertEquals(0, stopManaged.exitCode(), "gateway stop Managed exits 0; stderr=" + stopManaged.stderr());
        Assert.assertContains(stopManaged.stdout(), "stopped", "stop mentions stopped");

        // 5. Unmanaged Gateway -> gateway start reuses, gateway stop exits 1 without killing
        portScanner.bind(8080, 7700L);
        processRunner.registerProcess(new ProcessSnapshot(
            7700L, true, Optional.of(clock.now()), Optional.of("java -jar ext-wiremock.jar"),
            List.of(), List.of(new ParentProcessInfo(10L, Optional.of("bash"), Optional.of("bash"))),
            Optional.empty(), Optional.of("java")
        ));
        CliResult reuseUnmanaged = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "gateway", "start");
        Assert.assertEquals(0, reuseUnmanaged.exitCode(), "gateway start reuses Unmanaged");
        Assert.assertContains(reuseUnmanaged.stdout(), "UNMANAGED", "mentions UNMANAGED");

        CliResult refuseStop = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "gateway", "stop");
        Assert.assertEquals(1, refuseStop.exitCode(), "gateway stop on Unmanaged exits 1");
        Assert.assertContains(refuseStop.stderr(), "GATEWAY_UNMANAGED", "stderr reports GATEWAY_UNMANAGED");
        Assert.assertTrue(processRunner.isAlive(7700L), "Unmanaged Gateway process still alive");

        // 6. Syntax & flag validation -> exit 2
        for (String[] badArgs : List.of(
            new String[]{"gateway"},
            new String[]{"gateway", "bogus"},
            new String[]{"gateway", "start", "--bogus"},
            new String[]{"gateway", "start", "--timeout", "0"},
            new String[]{"gateway", "start", "extra"},
            new String[]{"gateway", "stop", "--force"},
            new String[]{"gateway", "status", "extra"},
            new String[]{"gateway", "status", "--bogus"},
            new String[]{"gateway", "reconcile", "--bogus"}
        )) {
            CliResult bad = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, badArgs);
            Assert.assertEquals(2, bad.exitCode(), "bad gateway syntax " + String.join(" ", badArgs) + " exits 2");
        }
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

    private static InMemoryConfigSource sampleConfigSource(Path root, String secret) {
        return new InMemoryConfigSource(root)
            .withEnv("TEST_GW_SECRET", secret)
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
                  "gateway": {
                    "type": "wiremock",
                    "port": 8080,
                    "healthPath": "/__admin/health",
                    "command": ["java", "-jar", "wiremock.jar", "--port", "8080"],
                    "workingDir": "wiremock",
                    "env": {
                      "GW_TOKEN": "${env:TEST_GW_SECRET}"
                    }
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "orders" },
                    "billing": { "runtime": "java", "port": 9002, "workingDir": "billing" }
                  }
                }
                """);
    }

    private record CliResult(int exitCode, String stdout, String stderr) {}
}
