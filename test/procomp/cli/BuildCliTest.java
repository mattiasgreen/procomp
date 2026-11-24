package procomp.cli;

import procomp.Assert;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeProjectFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.shell.ports.ProcessRunner.ExitOutcome;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class BuildCliTest {

    public static void testParseRejectsMutuallyExclusiveAndInvalidFlags() {
        Assert.assertErr(BuildCli.parse(List.of("--force", "--cancel", "orders")), "force+cancel invalid");
        Assert.assertErr(BuildCli.parse(List.of("--cancel")), "cancel requires explicit service");
        Assert.assertErr(BuildCli.parse(List.of("--cancel", "--timeout", "10", "orders")), "cancel+timeout invalid");
        Assert.assertErr(BuildCli.parse(List.of("--bogus")), "unknown flag");

        BuildCli.Action buildAction = Assert.assertOk(
            BuildCli.parse(List.of("orders", "--force", "--timeout", "30", "--json")),
            "parse build action"
        );
        Assert.assertTrue(buildAction instanceof BuildCli.Action.Build, "expected Action.Build");
        var b = (BuildCli.Action.Build) buildAction;
        Assert.assertEquals(List.of("orders"), b.services(), "services");
        Assert.assertTrue(b.force(), "force flag");
        Assert.assertEquals(Optional.of(Duration.ofSeconds(30)), b.timeout(), "timeout");
        Assert.assertTrue(b.json(), "json flag");
    }

    public static void testEndToEndCliBuildStatusInspectAndBuildLogs() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String configJson = """
            {
              "project": "acme",
              "builders": {
                "gradle": { "command": ["./gradlew", ":{{service}}:assemble"] }
              },
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "builder": "gradle", "workingDir": "orders" }
              }
            }
            """;

        InMemoryConfigSource configSource = new InMemoryConfigSource(configPath.getParent())
            .withFile(configPath, configJson);
        InMemoryStateStore stateStore = new InMemoryStateStore();
        FakeClock clock = new FakeClock(Instant.parse("2026-11-23T10:00:00Z"));
        FakeProcessRunner runner = new FakeProcessRunner(clock).onAttachedExit(req -> {
            stateStore.replaceLog(req.stdoutLog(), "Compiled orders cleanly\n");
            stateStore.replaceLog(req.stderrLog(), "");
            clock.advance(Duration.ofSeconds(2));
            return new ExitOutcome.Exited(0);
        });
        FakeProjectFiles projectFiles = new FakeProjectFiles();

        // 1. procomp build orders
        StringWriter out1 = new StringWriter();
        StringWriter err1 = new StringWriter();
        CliRuntime rt1 = CliRuntime.builder(new PrintWriter(out1), new PrintWriter(err1))
            .configSource(configSource)
            .stateStore(stateStore)
            .processRunner(runner)
            .portScanner(new FakePortScanner())
            .httpClient(new FakeHttpClient())
            .projectFiles(projectFiles)
            .clock(clock)
            .build();
        int buildExit = Procomp.run(new String[] {"build", "orders"}, rt1);
        Assert.assertEquals(0, buildExit, "build exit 0; stderr=" + err1);
        Assert.assertContains(out1.toString(), "orders: built with 'gradle'", "build summary output");

        // 2. procomp status shows BUILD column with SUCCEEDED
        StringWriter out2 = new StringWriter();
        StringWriter err2 = new StringWriter();
        CliRuntime rt2 = CliRuntime.builder(new PrintWriter(out2), new PrintWriter(err2))
            .configSource(configSource)
            .stateStore(stateStore)
            .processRunner(runner)
            .portScanner(new FakePortScanner())
            .httpClient(new FakeHttpClient())
            .projectFiles(projectFiles)
            .clock(clock)
            .build();
        Assert.assertEquals(0, Procomp.run(new String[] {"status"}, rt2), "status exit 0");
        Assert.assertContains(out2.toString(), "BUILD", "BUILD column header");
        Assert.assertContains(out2.toString(), "SUCCEEDED", "SUCCEEDED build state");

        // 3. procomp inspect orders shows Build State & Build Exit Code
        StringWriter out3 = new StringWriter();
        StringWriter err3 = new StringWriter();
        CliRuntime rt3 = CliRuntime.builder(new PrintWriter(out3), new PrintWriter(err3))
            .configSource(configSource)
            .stateStore(stateStore)
            .processRunner(runner)
            .portScanner(new FakePortScanner())
            .httpClient(new FakeHttpClient())
            .projectFiles(projectFiles)
            .clock(clock)
            .build();
        Assert.assertEquals(0, Procomp.run(new String[] {"inspect", "orders"}, rt3), "inspect exit 0");
        Assert.assertContains(out3.toString(), "Build Run:       SUCCEEDED", "inspect build run");
        Assert.assertContains(out3.toString(), "Build Exit Code: 0", "inspect build exit code");
        Assert.assertFalse(out3.toString().contains("Build PID:"), "finished build should not print Build PID");

        // 4. procomp logs orders --build shows build stdout
        StringWriter out4 = new StringWriter();
        StringWriter err4 = new StringWriter();
        CliRuntime rt4 = CliRuntime.builder(new PrintWriter(out4), new PrintWriter(err4))
            .configSource(configSource)
            .stateStore(stateStore)
            .processRunner(runner)
            .portScanner(new FakePortScanner())
            .httpClient(new FakeHttpClient())
            .projectFiles(projectFiles)
            .clock(clock)
            .build();
        Assert.assertEquals(0, Procomp.run(new String[] {"logs", "orders", "--build"}, rt4), "logs --build exit 0");
        Assert.assertContains(out4.toString(), "Compiled orders cleanly", "build log text");
    }
}
