package procomp.cli;

import procomp.Assert;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeWorkspaceFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.fakes.RecordingObserver;
import procomp.shell.ports.ManagedProcessRecord;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner.ParentProcessInfo;
import procomp.shell.ports.ProcessRunner.ProcessSnapshot;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class ProcessCliTest {

    public static void testInspectCliRendersTextAndJsonWithoutExposingSecrets() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = sampleConfigSource(root, "secret-token-xyz");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var workspaceFiles = new FakeWorkspaceFiles()
            .withDirectory(root.resolve("orders"))
            .withFile(root.resolve("orders/app.jar"), Instant.parse("2026-11-20T11:00:00Z"));

        Path outLog = stateStore.logPath("acme", ProcessKey.service("orders"), false);
        Path errLog = stateStore.logPath("acme", ProcessKey.service("orders"), true);
        Assert.assertOk(stateStore.writeManagedProcess("acme", ProcessKey.service("orders"), new ManagedProcessRecord(
            3001L,
            Instant.parse("2026-11-20T11:50:00Z"),
            List.of("java", "-jar", "orders/app.jar"),
            Optional.of(Instant.parse("2026-11-20T11:00:00Z")),
            outLog,
            errLog
        )), "seed record");
        processRunner.registerProcess(new ProcessSnapshot(
            3001L,
            true,
            Optional.of(Instant.parse("2026-11-20T11:50:00Z")),
            Optional.of("java -jar orders/app.jar"),
            List.of("-jar", "orders/app.jar"),
            List.of(new ParentProcessInfo(100L, Optional.of("procomp start"), Optional.of("java"))),
            Optional.of(128L * 1024 * 1024),
            Optional.of("java")
        ));
        httpClient.stubGet(URI.create("http://127.0.0.1:9001/actuator/health"), 200, "UP");

        CliResult textRes = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "inspect", "orders");
        Assert.assertEquals(0, textRes.exitCode(), "inspect text exits 0; stderr=" + textRes.stderr());
        Assert.assertContains(textRes.stdout(), "MANAGED", "inspect text prints owner");
        Assert.assertContains(textRes.stdout(), "HEALTHY", "inspect text prints health");
        Assert.assertContains(textRes.stdout(), "3001", "inspect text prints pid");
        Assert.assertNotContains(textRes.stdout(), "secret-token-xyz", "inspect text never leaks secrets");

        CliResult jsonRes = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "inspect", "orders", "--json");
        Assert.assertEquals(0, jsonRes.exitCode(), "inspect --json exits 0; stderr=" + jsonRes.stderr());
        JsonValue.JsonObject obj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(jsonRes.stdout().trim()), "valid json");
        Assert.assertEquals("orders", ((JsonValue.JsonString) obj.get("service").orElseThrow()).value(), "json service");
        Assert.assertEquals("MANAGED", ((JsonValue.JsonString) obj.get("owner").orElseThrow()).value(), "json owner");
        Assert.assertEquals("HEALTHY", ((JsonValue.JsonString) obj.get("health").orElseThrow()).value(), "json health");
        Assert.assertEquals("REMOTE", ((JsonValue.JsonString) obj.get("desiredRoute").orElseThrow()).value(), "json desiredRoute");
        Assert.assertNotContains(jsonRes.stdout(), "secret-token-xyz", "inspect --json never leaks secrets");

        CliResult missingArg = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "inspect");
        Assert.assertEquals(2, missingArg.exitCode(), "missing <service> exits 2");

        CliResult unknownSvc = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "inspect", "missing");
        Assert.assertEquals(1, unknownSvc.exitCode(), "unknown service exits 1");
        Assert.assertContains(unknownSvc.stderr(), "UNKNOWN_SERVICE", "unknown service code");
    }

    public static void testStartCliSupportsDetachConflictFailFastAndFlagValidation() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = sampleConfigSource(root, "secret-token-xyz");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var workspaceFiles = new FakeWorkspaceFiles()
            .withDirectory(root.resolve("orders"))
            .withDirectory(root.resolve("billing"))
            .withFile(root.resolve("orders/app.jar"), Instant.parse("2026-11-20T11:00:00Z"));

        // 1. Bare start with no LOCAL routes exits 0 cleanly
        CliResult bareEmpty = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "start");
        Assert.assertEquals(0, bareEmpty.exitCode(), "bare start with no LOCAL exits 0");
        Assert.assertContains(bareEmpty.stdout(), "No Services routed LOCAL", "explains nothing to start");

        // 2. Start orders -d succeeds immediately without waiting
        CliResult detachRes = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "start", "orders", "-d");
        Assert.assertEquals(0, detachRes.exitCode(), "start orders -d exits 0; stderr=" + detachRes.stderr());
        Assert.assertContains(detachRes.stdout(), "orders", "mentions orders");
        Assert.assertEquals(0, clock.sleepCount(), "-d does not poll");

        // 3. Port conflict on billing exits 1 with inspection details and never leaks secrets
        portScanner.bind(9002, 8888L);
        processRunner.registerProcess(new ProcessSnapshot(
            8888L, true, Optional.of(Instant.parse("2026-11-20T11:55:00Z")),
            Optional.of("nc -l 9002"), List.of("-l", "9002"),
            List.of(new ParentProcessInfo(5L, Optional.of("sh"), Optional.of("sh"))),
            Optional.empty(), Optional.of("nc")
        ));
        CliResult conflictRes = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "start", "billing");
        Assert.assertEquals(1, conflictRes.exitCode(), "start on conflict exits 1");
        Assert.assertContains(conflictRes.stderr(), "PORT_CONFLICT", "stderr prints PORT_CONFLICT");
        Assert.assertContains(conflictRes.stderr(), "8888", "stderr prints conflicting PID");
        Assert.assertContains(conflictRes.stderr(), "nc -l 9002", "stderr prints conflicting cmdline");
        Assert.assertNotContains(conflictRes.stderr(), "secret-token-xyz", "stderr never leaks secrets");

        // 4. Invalid CLI flags exit 2
        CliResult badFlag = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "start", "--bogus");
        Assert.assertEquals(2, badFlag.exitCode(), "unknown start flag exits 2");
        CliResult badTimeout = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "start", "--timeout", "0");
        Assert.assertEquals(2, badTimeout.exitCode(), "non-positive --timeout exits 2");
    }

    public static void testStopCliHandlesManagedAllKillConflictAndSyntaxValidation() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = sampleConfigSource(root, "secret-token-xyz");
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var workspaceFiles = new FakeWorkspaceFiles()
            .withDirectory(root.resolve("orders"))
            .withDirectory(root.resolve("billing"))
            .withFile(root.resolve("orders/app.jar"), Instant.parse("2026-11-20T11:00:00Z"));

        // Start orders -d, then stop orders via CLI
        Assert.assertEquals(0, runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "start", "orders", "-d").exitCode(), "start orders -d");
        CliResult stopOrders = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "stop", "orders");
        Assert.assertEquals(0, stopOrders.exitCode(), "stop orders exits 0; stderr=" + stopOrders.stderr());
        Assert.assertContains(stopOrders.stdout(), "orders", "stop output mentions orders");
        Assert.assertEquals(Optional.empty(), Assert.assertOk(stateStore.readManagedProcess("acme", ProcessKey.service("orders")), "read record"), "record removed");

        // Bare --kill-conflict fails fast with exit 2
        CliResult bareKillConflict = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "stop", "--kill-conflict");
        Assert.assertEquals(2, bareKillConflict.exitCode(), "bare --kill-conflict exits 2");

        // Explicit service --kill-conflict kills conflict
        portScanner.bind(9002, 7171L);
        processRunner.registerProcess(new ProcessSnapshot(
            7171L, true, Optional.of(clock.now()), Optional.of("socat"), List.of(), List.of(), Optional.empty(), Optional.of("socat")
        ));
        CliResult killConflict = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "stop", "billing", "--kill-conflict");
        Assert.assertEquals(0, killConflict.exitCode(), "stop billing --kill-conflict exits 0; stderr=" + killConflict.stderr());
        Assert.assertFalse(processRunner.isAlive(7171L), "conflict killed");

        // Unknown stop flag exits 2
        CliResult badStopFlag = runCli(configSource, stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, "stop", "--bogus");
        Assert.assertEquals(2, badStopFlag.exitCode(), "unknown stop flag exits 2");
    }

    static CliResult runCli(
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

    static InMemoryConfigSource sampleConfigSource(Path root, String secret) {
        return new InMemoryConfigSource(root)
            .withEnv("TEST_DB_SECRET", secret)
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
                      "artifactGlob": "*.jar",
                      "artifactExclude": "*-plain.jar"
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
                    "billing": { "runtime": "java", "port": 9002, "workingDir": "billing" }
                  }
                }
                """);
    }

    record CliResult(int exitCode, String stdout, String stderr) {}
}
