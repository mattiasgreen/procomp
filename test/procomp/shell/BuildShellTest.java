package procomp.shell;

import procomp.Assert;
import procomp.core.build.BuildDisplayState;
import procomp.core.build.BuildRunRecord;
import procomp.core.build.BuildStatus;
import procomp.core.build.ObservedBuildRun;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.core.routing.Route;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeProjectFiles;
import procomp.fakes.InMemoryStateStore;
import procomp.shell.BuildShell.BuildRequest;
import procomp.shell.BuildShell.BuildResult;
import procomp.shell.BuildShell.CancelDisposition;
import procomp.shell.BuildShell.CancelResult;
import procomp.shell.LogsShell.LogsRequest;
import procomp.shell.LogsShell.LogsResult;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner.ExitOutcome;
import procomp.shell.ports.ProgressObserver;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BuildShellTest {

    private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");

    private static Project sampleProject() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String json = """
            {
              "project": "acme",
              "builders": {
                "gradle": { "command": ["./gradlew", ":{{service}}:assemble"] }
              },
              "runtimes": {
                "java": {
                  "command": ["java", "-jar", "{{artifact}}"],
                  "artifactGlob": "build/libs/*.jar"
                }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "builder": "gradle", "workingDir": "orders" },
                "billing": { "port": 9002, "runtime": "java", "builder": "gradle", "workingDir": "billing" },
                "static-app": { "port": 9003, "runtime": "java", "workingDir": "static" }
              }
            }
            """;
        return Assert.assertOk(
            ProjectResolver.resolve(configPath, json, _ -> Result.ok(Optional.empty()), Map.of()),
            "resolve sample project"
        );
    }

    public static void testBuildSucceedsPersistsRecordAndExposesBuildLogs() {
        Project project = sampleProject();
        InMemoryStateStore store = new InMemoryStateStore();
        FakeClock clock = new FakeClock(T0);
        FakeProcessRunner runner = new FakeProcessRunner(clock).onAttachedExit(req -> {
            store.replaceLog(req.stdoutLog(), "BUILD SUCCESSFUL\n");
            store.replaceLog(req.stderrLog(), "");
            clock.advance(Duration.ofSeconds(3));
            return new ExitOutcome.Exited(0);
        });
        FakeProjectFiles files = new FakeProjectFiles();

        BuildShell shell = new BuildShell(store, runner, files, clock, ProgressObserver.noop());
        BuildResult res = Assert.assertOk(
            shell.build(project, new BuildRequest(List.of("orders"), false, Optional.empty())),
            "build orders"
        );

        Assert.assertEquals(1, res.outcomes().size(), "one build outcome");
        Assert.assertEquals("orders", res.outcomes().get(0).service(), "service name");
        Assert.assertEquals("gradle", res.outcomes().get(0).builder(), "builder name");
        Assert.assertEquals(Duration.ofSeconds(3), res.outcomes().get(0).duration(), "elapsed duration");

        ObservedBuildRun observed = Assert.assertOk(shell.inspectBuild(project, "orders"), "inspect build");
        Assert.assertEquals(BuildDisplayState.SUCCEEDED, observed.displayState(), "display state SUCCEEDED");
        Assert.assertEquals(Optional.of(BuildStatus.SUCCEEDED), observed.status(), "status SUCCEEDED");

        // Build logs are readable via LogsShell with build=true and do not touch runtime logs
        LogsShell logsShell = new LogsShell(store, clock, ProgressObserver.noop());
        LogsResult buildLog = Assert.assertOk(
            logsShell.run(project, new LogsRequest("orders", false, false, 10, false, true)),
            "read build stdout log"
        );
        Assert.assertTrue(buildLog instanceof LogsResult.RawTail, "expected RawTail");
        Assert.assertContains(((LogsResult.RawTail) buildLog).text(), "BUILD SUCCESSFUL", "build stdout content");
        Assert.assertErr(
            logsShell.run(project, new LogsRequest("orders", false, false, 10, false, false)),
            "runtime stdout log must remain untouched"
        );
    }

    public static void testBareBuildTargetsLocalServicesWithBuilderAndFailsFast() {
        Project project = sampleProject();
        InMemoryStateStore store = new InMemoryStateStore();
        FakeClock clock = new FakeClock(T0);
        FakeProcessRunner runner = new FakeProcessRunner(clock).onAttachedExit(req -> {
            if (req.command().contains(":orders:assemble")) {
                return new ExitOutcome.Exited(1);
            }
            return new ExitOutcome.Exited(0);
        });
        FakeProjectFiles files = new FakeProjectFiles();

        RoutingShell routingShell = new RoutingShell(store);
        Assert.assertOk(routingShell.updateRoutes(project, List.of("orders", "billing", "static-app"), Route.LOCAL), "route local");

        BuildShell shell = new BuildShell(store, runner, files, clock, ProgressObserver.noop());
        DomainError err = Assert.assertErr(
            shell.build(project, new BuildRequest(List.of(), false, Optional.empty())),
            "bare build must fail fast when orders fails"
        );
        Assert.assertEquals("BUILD_FAILED", err.code(), "BUILD_FAILED code");
        Assert.assertEquals(1, runner.spawnedRequests().size(), "must not start billing after orders failed");

        ObservedBuildRun ordersObs = Assert.assertOk(shell.inspectBuild(project, "orders"), "inspect orders");
        Assert.assertEquals(BuildDisplayState.FAILED, ordersObs.displayState(), "orders FAILED");
        ObservedBuildRun staticObs = Assert.assertOk(shell.inspectBuild(project, "static-app"), "inspect static-app");
        Assert.assertEquals(BuildDisplayState.NO_BUILDER, staticObs.displayState(), "static-app NO_BUILDER");
    }

    public static void testUnknownStateRequiresForceOrCancelAndTimeoutStopsTree() {
        Project project = sampleProject();
        InMemoryStateStore store = new InMemoryStateStore();
        FakeClock clock = new FakeClock(T0);
        FakeProcessRunner runner = new FakeProcessRunner(clock);
        FakeProjectFiles files = new FakeProjectFiles();

        Path out = store.logPath(project.name(), ProcessKey.build("orders"), false);
        Path errLog = store.logPath(project.name(), ProcessKey.build("orders"), true);
        // Simulate a prior procomp build hard-killed while RUNNING (PID 777 is dead)
        Assert.assertOk(
            store.writeBuildRun(project.name(), "orders", new BuildRunRecord.Running(777L, T0, List.of("./gradlew"), out, errLog)),
            "seed dead RUNNING record"
        );

        BuildShell shell = new BuildShell(store, runner, files, clock, ProgressObserver.noop());
        ObservedBuildRun unknownObs = Assert.assertOk(shell.inspectBuild(project, "orders"), "inspect unknown");
        Assert.assertEquals(BuildDisplayState.UNKNOWN, unknownObs.displayState(), "display state UNKNOWN");

        // Without --force -> refused
        DomainError unknownErr = Assert.assertErr(
            shell.build(project, new BuildRequest(List.of("orders"), false, Optional.empty())),
            "must refuse without --force"
        );
        Assert.assertEquals("BUILD_RUN_UNKNOWN", unknownErr.code(), "BUILD_RUN_UNKNOWN code");

        // Cancel clears UNKNOWN to FAILED
        CancelResult cancelled = Assert.assertOk(shell.cancel(project, "orders"), "cancel unknown");
        Assert.assertEquals(CancelDisposition.CLEARED_UNKNOWN, cancelled.disposition(), "CLEARED_UNKNOWN");

        // Timeout stops tree and persists FAILED
        runner.onAttachedExit(_ -> ExitOutcome.TimedOut.INSTANCE);
        DomainError timeoutErr = Assert.assertErr(
            shell.build(project, new BuildRequest(List.of("orders"), false, Optional.of(Duration.ofSeconds(5)))),
            "must time out"
        );
        Assert.assertEquals("BUILD_TIMED_OUT", timeoutErr.code(), "BUILD_TIMED_OUT code");
        Assert.assertEquals(1, runner.stoppedPids().size(), "stopTree called on timeout");
    }

    public static void testStatusShellIncludesArtifactPresentAndBuildStates() {
        Project project = sampleProject();
        InMemoryStateStore store = new InMemoryStateStore();
        FakeClock clock = new FakeClock(T0);
        FakeProcessRunner runner = new FakeProcessRunner(clock);
        FakeProjectFiles files = new FakeProjectFiles();
        Path ordersWd = project.service("orders").orElseThrow().workingDir();
        files.withFile(ordersWd.resolve("build/libs/orders.jar"), T0);

        StatusShell statusShell = new StatusShell(
            store, runner, new FakePortScanner(), new FakeHttpClient(), files, clock, ProgressObserver.noop()
        );
        StatusShell.EstateStatus estate = Assert.assertOk(statusShell.status(project), "status");
        Assert.assertEquals(BuildDisplayState.ARTIFACT_PRESENT, estate.services().get(0).build().displayState(), "orders ARTIFACT_PRESENT");
        Assert.assertEquals(BuildDisplayState.NOT_BUILT, estate.services().get(1).build().displayState(), "billing NOT_BUILT");
        Assert.assertEquals(BuildDisplayState.NO_BUILDER, estate.services().get(2).build().displayState(), "static-app NO_BUILDER");
    }
}
