package procomp.cli;

import procomp.Assert;
import procomp.core.common.Json;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeProjectFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;

public final class ProcompCliTest {

    public static void testHelpPrintsUsageAndExitsZero() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd);
        var stateStore = new InMemoryStateStore();

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int code = Procomp.run(
            new String[]{"--help"},
            CliRuntime.builder(new PrintWriter(outBuf, true), new PrintWriter(errBuf, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );

        Assert.assertEquals(0, code, "--help should exit 0");
        Assert.assertContains(outBuf.toString(), "Usage: procomp", "help output shows usage");
        Assert.assertContains(outBuf.toString(), "--config", "help output mentions --config");
        Assert.assertContains(outBuf.toString(), "--verbose", "help output mentions --verbose");
        Assert.assertEquals("", errBuf.toString(), "--help should write nothing to stderr");
    }

    public static void testInvalidFlagsAndUnknownCommandsFailCleanlyWithExitCode2() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd);
        var stateStore = new InMemoryStateStore();

        StringWriter out1 = new StringWriter();
        StringWriter err1 = new StringWriter();
        int badFlagCode = Procomp.run(
            new String[]{"--bogus"},
            CliRuntime.builder(new PrintWriter(out1, true), new PrintWriter(err1, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );
        Assert.assertEquals(2, badFlagCode, "unknown flag exits 2");
        Assert.assertContains(err1.toString(), "--bogus", "stderr mentions unknown flag");

        StringWriter out2 = new StringWriter();
        StringWriter err2 = new StringWriter();
        int missingCfgArgCode = Procomp.run(
            new String[]{"--config"},
            CliRuntime.builder(new PrintWriter(out2, true), new PrintWriter(err2, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );
        Assert.assertEquals(2, missingCfgArgCode, "missing --config value exits 2");

        StringWriter out3 = new StringWriter();
        StringWriter err3 = new StringWriter();
        int unknownCmdCode = Procomp.run(
            new String[]{"nonexistent-cmd"},
            CliRuntime.builder(new PrintWriter(out3, true), new PrintWriter(err3, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );
        Assert.assertEquals(2, unknownCmdCode, "unknown command exits 2");
        Assert.assertContains(err3.toString(), "nonexistent-cmd", "stderr mentions unknown command");
    }

    public static void testMalformedConfigFailsWithExitCode1AndContextualError() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd)
            .withFile(cwd.resolve("bad.json"), "{\"project\": \"acme\", \"runtimes\": {}, \"badKey\": true}");
        var stateStore = new InMemoryStateStore();

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int code = Procomp.run(
            new String[]{"--config", "bad.json", "status"},
            CliRuntime.builder(new PrintWriter(outBuf, true), new PrintWriter(errBuf, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );

        Assert.assertEquals(1, code, "malformed config should exit 1");
        Assert.assertContains(errBuf.toString(), "CONFIG_UNRECOGNIZED_FIELD", "stderr reports strict config error");
        Assert.assertContains(errBuf.toString(), "badKey", "stderr reports offending field");
    }

    public static void testBareConfigOptionWithoutCommandPrintsUsageAndExitsZero() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd)
            .withFile(cwd.resolve("valid.json"), "{\"project\": \"acme\", \"runtimes\": {}}");
        var stateStore = new InMemoryStateStore();

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int code = Procomp.run(
            new String[]{"--config", "valid.json"},
            CliRuntime.builder(new PrintWriter(outBuf, true), new PrintWriter(errBuf, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );

        Assert.assertEquals(0, code, "bare --config without command should print usage and exit 0");
        Assert.assertContains(outBuf.toString(), "Usage: procomp", "bare --config prints usage");
        Assert.assertNotContains(outBuf.toString(), "Config OK", "bare --config must not emit Config OK");
        Assert.assertEquals("", errBuf.toString(), "bare --config writes nothing to stderr");
    }

    public static void testOmittedPortsFailCleanlyWithoutFallingBackToRealOsAdapters() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd)
            .withFile(cwd.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
                  "services": { "orders": { "runtime": "java", "port": 9001, "workingDir": "." } }
                }
                """);
        var stateStore = new InMemoryStateStore();

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int statusCode = Procomp.run(
            new String[]{"status"},
            CliRuntime.builder(new PrintWriter(outBuf, true), new PrintWriter(errBuf, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );
        Assert.assertEquals(1, statusCode, "status without process ports exits 1");
        Assert.assertContains(errBuf.toString(), "PORT_NOT_CONFIGURED", "stderr reports PORT_NOT_CONFIGURED");
        Assert.assertContains(errBuf.toString(), "ProcessRunner", "stderr lists missing ProcessRunner");

        StringWriter outNoState = new StringWriter();
        StringWriter errNoState = new StringWriter();
        int routeCode = Procomp.run(
            new String[]{"route"},
            CliRuntime.builder(new PrintWriter(outNoState, true), new PrintWriter(errNoState, true))
                .configSource(configSource)
                .build()
        );
        Assert.assertEquals(1, routeCode, "route without stateStore exits 1");
        Assert.assertContains(errNoState.toString(), "PORT_NOT_CONFIGURED", "stderr reports PORT_NOT_CONFIGURED");
        Assert.assertContains(errNoState.toString(), "StateStore", "stderr lists missing StateStore");
    }

    public static void testVerboseFlagWritesDiagnosticsToStderrWithoutPollutingStdoutOrLeakingSecrets() {
        String secret = "super-secret-verbose-token-999";
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd)
            .withFile(cwd.resolve(".env"), "TOP_SECRET=" + secret + "\n")
            .withFile(cwd.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "envFiles": [".env"],
                  "defaults": {
                    "remoteUrlTemplate": "https://remote.example/{{service}}",
                    "env": { "INLINE_SECRET": "%s" }
                  },
                  "runtimes": {
                    "java": {
                      "command": ["java", "-jar", "orders.jar"],
                      "healthPath": "/actuator/health"
                    }
                  },
                  "gateway": {
                    "type": "wiremock",
                    "port": 18080,
                    "healthPath": "/__admin/health",
                    "command": ["java", "-jar", "wiremock.jar", "--port", "18080"],
                    "workingDir": "."
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 18101, "workingDir": "." }
                  }
                }
                """.formatted(secret));
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-23T05:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var projectFiles = new FakeProjectFiles().withDirectory(cwd);

        httpClient.stubGet(URI.create("http://127.0.0.1:18101/actuator/health"), 200, "{\"status\":\"UP\"}");
        httpClient.stubGet(URI.create("http://127.0.0.1:18080/__admin/health"), 200, "{\"status\":\"healthy\"}");
        httpClient.stubGet(URI.create("http://127.0.0.1:18080/__admin/mappings"), 200, "{\"mappings\":[]}");

        // 1. Verbose flag BEFORE subcommand on `start orders`
        StringWriter startOut = new StringWriter();
        StringWriter startErr = new StringWriter();
        int startCode = Procomp.run(
            new String[]{"-v", "start", "orders"},
            CliRuntime.builder(new PrintWriter(startOut, true), new PrintWriter(startErr, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .processRunner(processRunner)
                .portScanner(portScanner)
                .httpClient(httpClient)
                .projectFiles(projectFiles)
                .clock(clock)
                .build()
        );
        Assert.assertEquals(0, startCode, "procomp -v start orders exits 0; err=" + startErr);
        String startDiag = startErr.toString();
        Assert.assertContains(startDiag, "[+0.000s] [config]", "verbose stderr emits elapsed-time config diagnostic");
        Assert.assertContains(startDiag, "[discovery]", "verbose stderr emits discovery diagnostics");
        Assert.assertContains(startDiag, "[process]", "verbose stderr emits process spawn diagnostics");
        Assert.assertContains(startDiag, "orders.jar", "verbose stderr shows resolved argv");
        Assert.assertNotContains(startDiag, secret, "verbose stderr must NEVER leak secret values");
        Assert.assertNotContains(startOut.toString(), "[config]", "stdout must not contain verbose diagnostics");

        // 2. Verbose flag AFTER subcommand on `status --json` must keep stdout valid JSON
        StringWriter statusOut = new StringWriter();
        StringWriter statusErr = new StringWriter();
        int statusCode = Procomp.run(
            new String[]{"status", "--json", "--verbose"},
            CliRuntime.builder(new PrintWriter(statusOut, true), new PrintWriter(statusErr, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .processRunner(processRunner)
                .portScanner(portScanner)
                .httpClient(httpClient)
                .projectFiles(projectFiles)
                .clock(clock)
                .build()
        );
        Assert.assertEquals(0, statusCode, "procomp status --json --verbose exits 0; err=" + statusErr);
        Assert.assertOk(Json.parse(statusOut.toString()), "stdout remains strictly valid JSON under --verbose");
        String statusDiag = statusErr.toString();
        Assert.assertContains(statusDiag, "[config]", "status --verbose logs config resolution");
        Assert.assertContains(statusDiag, "[discovery]", "status --verbose logs discovery checks");
        Assert.assertContains(statusDiag, "http://127.0.0.1:18101/actuator/health -> HTTP 200", "status --verbose logs health check URL & status");
        Assert.assertNotContains(statusDiag, secret, "status --verbose stderr must NEVER leak secret values");
        Assert.assertNotContains(statusOut.toString(), secret, "status --json stdout must NEVER leak secret values");

        // 3. Verbose `gateway start` + `gateway reconcile` emits [gateway] HTTP diagnostics
        var mappingsBody = new java.util.concurrent.atomic.AtomicReference<>("{\"mappings\":[]}");
        httpClient.stub("GET", URI.create("http://127.0.0.1:18080/__admin/mappings"),
            _ -> procomp.core.common.Result.ok(new procomp.shell.ports.HttpClient.HttpResponseData(200, java.util.Map.of(), mappingsBody.get())));
        httpClient.stub("POST", URI.create("http://127.0.0.1:18080/__admin/mappings/import"), req -> {
            mappingsBody.set(req.body());
            return procomp.core.common.Result.ok(new procomp.shell.ports.HttpClient.HttpResponseData(200, java.util.Map.of(), "{}"));
        });
        StringWriter gwOut = new StringWriter();
        StringWriter gwErr = new StringWriter();
        int gwStartCode = Procomp.run(
            new String[]{"gateway", "start", "-v"},
            CliRuntime.builder(new PrintWriter(gwOut, true), new PrintWriter(gwErr, true))
                .configSource(configSource).stateStore(stateStore).processRunner(processRunner)
                .portScanner(portScanner).httpClient(httpClient).projectFiles(projectFiles).clock(clock).build()
        );
        Assert.assertEquals(0, gwStartCode, "gateway start -v exits 0; err=" + gwErr);
        int gwReconcileCode = Procomp.run(
            new String[]{"-v", "gateway", "reconcile"},
            CliRuntime.builder(new PrintWriter(gwOut, true), new PrintWriter(gwErr, true))
                .configSource(configSource).stateStore(stateStore).processRunner(processRunner)
                .portScanner(portScanner).httpClient(httpClient).projectFiles(projectFiles).clock(clock).build()
        );
        Assert.assertEquals(0, gwReconcileCode, "gateway reconcile -v exits 0; err=" + gwErr);
        Assert.assertContains(gwErr.toString(), "[gateway] GET http://127.0.0.1:18080/__admin/mappings -> HTTP 200", "gateway reconcile logs GET mappings");
        Assert.assertContains(gwErr.toString(), "[gateway] POST http://127.0.0.1:18080/__admin/mappings/import", "gateway reconcile logs POST import");

        // 4. Verbose `stop orders` emits [process] stopping/stopped diagnostics
        StringWriter stopOut = new StringWriter();
        StringWriter stopErr = new StringWriter();
        int stopCode = Procomp.run(
            new String[]{"stop", "orders", "--verbose"},
            CliRuntime.builder(new PrintWriter(stopOut, true), new PrintWriter(stopErr, true))
                .configSource(configSource).stateStore(stateStore).processRunner(processRunner)
                .portScanner(portScanner).httpClient(httpClient).projectFiles(projectFiles).clock(clock).build()
        );
        Assert.assertEquals(0, stopCode, "stop orders --verbose exits 0; err=" + stopErr);
        Assert.assertContains(stopErr.toString(), "[process] orders: stopping PID", "stop --verbose logs stopping PID");
        Assert.assertContains(stopErr.toString(), "[process] orders: stopped PID", "stop --verbose logs stopped PID");

        // 5. Verbose `logs orders` keeps stdout raw while diagnostics go strictly to stderr
        stateStore.appendLog(stateStore.logPath("acme", procomp.shell.ports.ProcessKey.service("orders"), false), "raw-log-line\n");
        StringWriter logsOut = new StringWriter();
        StringWriter logsErr = new StringWriter();
        int logsCode = Procomp.run(
            new String[]{"-v", "logs", "orders", "-n", "5"},
            CliRuntime.builder(new PrintWriter(logsOut, true), new PrintWriter(logsErr, true))
                .configSource(configSource).stateStore(stateStore).clock(clock).build()
        );
        Assert.assertEquals(0, logsCode, "-v logs orders exits 0; err=" + logsErr);
        Assert.assertEquals("raw-log-line\n", logsOut.toString(), "logs stdout stays raw under -v");
        Assert.assertContains(logsErr.toString(), "[config]", "logs -v writes diagnostics to stderr");
    }
}
