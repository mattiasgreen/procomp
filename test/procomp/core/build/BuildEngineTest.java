package procomp.core.build;

import procomp.Assert;
import procomp.core.project.SecretEnv;
import procomp.core.project.ServiceBuildConfig;
import procomp.core.project.ServiceDefinition;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class BuildEngineTest {

    private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-23T10:00:12Z");
    private static final Path OUT = Path.of("logs/orders.build.out.log");
    private static final Path ERR = Path.of("logs/orders.build.err.log");

    private static ServiceDefinition service(boolean withBuilder) {
        Path wd = Path.of("C:/work/orders").toAbsolutePath().normalize();
        Optional<ServiceBuildConfig> buildCfg = withBuilder
            ? Optional.of(new ServiceBuildConfig("gradle", List.of("./gradlew", "assemble"), wd, SecretEnv.EMPTY))
            : Optional.empty();
        return new ServiceDefinition(
            "orders",
            Optional.of(9001),
            "java",
            buildCfg,
            Optional.empty(),
            wd,
            Optional.of("/actuator/health"),
            "/orders",
            Optional.empty(),
            Optional.of("build/libs/*.jar"),
            Optional.empty(),
            List.of("java", "-jar", "{{artifact}}"),
            SecretEnv.EMPTY,
            List.of()
        );
    }

    public static void testEvaluateCoversAllSevenExplicitDisplayStates() {
        // 1. No record, no builder -> NO_BUILDER
        ObservedBuildRun noBuilder = BuildEngine.evaluate(Optional.empty(), false, false, true);
        Assert.assertEquals(BuildDisplayState.NO_BUILDER, noBuilder.displayState(), "no builder");
        Assert.assertEquals(Optional.empty(), noBuilder.status(), "no status when no record");

        // 2. No record, has builder, artifact on disk -> ARTIFACT_PRESENT
        ObservedBuildRun artifactPresent = BuildEngine.evaluate(Optional.empty(), true, false, true);
        Assert.assertEquals(BuildDisplayState.ARTIFACT_PRESENT, artifactPresent.displayState(), "artifact present");
        Assert.assertEquals(Optional.empty(), artifactPresent.status(), "no build run status yet");

        // 3. No record, has builder, no artifact -> NOT_BUILT
        ObservedBuildRun notBuilt = BuildEngine.evaluate(Optional.empty(), true, false, false);
        Assert.assertEquals(BuildDisplayState.NOT_BUILT, notBuilt.displayState(), "not built");

        // 4. Running + PID alive -> RUNNING
        BuildRunRecord.Running running = new BuildRunRecord.Running(1234L, T0, List.of("./gradlew"), OUT, ERR);
        ObservedBuildRun live = BuildEngine.evaluate(Optional.of(running), true, true, false);
        Assert.assertEquals(BuildDisplayState.RUNNING, live.displayState(), "running when pid alive");
        Assert.assertEquals(Optional.of(BuildStatus.RUNNING), live.status(), "status RUNNING");

        // 5. Running + PID dead -> UNKNOWN
        ObservedBuildRun unknown = BuildEngine.evaluate(Optional.of(running), true, false, true);
        Assert.assertEquals(BuildDisplayState.UNKNOWN, unknown.displayState(), "unknown when running pid dead");
        Assert.assertEquals(Optional.of(BuildStatus.UNKNOWN), unknown.status(), "status UNKNOWN");

        // 6. Succeeded -> SUCCEEDED
        BuildRunRecord.Succeeded succeeded = new BuildRunRecord.Succeeded(1234L, T0, T1, List.of("./gradlew"), OUT, ERR);
        ObservedBuildRun ok = BuildEngine.evaluate(Optional.of(succeeded), true, false, true);
        Assert.assertEquals(BuildDisplayState.SUCCEEDED, ok.displayState(), "succeeded");
        Assert.assertEquals(Optional.of(BuildStatus.SUCCEEDED), ok.status(), "status SUCCEEDED");

        // 7. Failed -> FAILED (even if an older artifact exists on disk!)
        BuildRunRecord.Failed failed = new BuildRunRecord.Failed(
            1234L, T0, T1, Optional.of(1), "Exited with code 1", List.of("./gradlew"), OUT, ERR
        );
        ObservedBuildRun fail = BuildEngine.evaluate(Optional.of(failed), true, false, true);
        Assert.assertEquals(BuildDisplayState.FAILED, fail.displayState(), "failed is not masked by stale artifact");
        Assert.assertEquals(Optional.of(BuildStatus.FAILED), fail.status(), "status FAILED");
    }

    public static void testPlanBuildHandlesConcurrencyUnknownAndForce() {
        ServiceDefinition noBuilderSvc = service(false);
        Assert.assertTrue(
            BuildEngine.planBuild(noBuilderSvc, Optional.empty(), false, false) instanceof BuildPlan.RejectNoBuilder,
            "reject when no builder"
        );

        ServiceDefinition svc = service(true);
        Assert.assertTrue(
            BuildEngine.planBuild(svc, Optional.empty(), false, false) instanceof BuildPlan.Start,
            "start when no prior record"
        );

        BuildRunRecord.Running running = new BuildRunRecord.Running(42L, T0, List.of("./gradlew"), OUT, ERR);

        // Live running build without force -> RejectAlreadyRunning
        BuildPlan liveNoForce = BuildEngine.planBuild(svc, Optional.of(running), true, false);
        Assert.assertTrue(liveNoForce instanceof BuildPlan.RejectAlreadyRunning, "reject already running");
        Assert.assertEquals(42L, ((BuildPlan.RejectAlreadyRunning) liveNoForce).pid(), "running pid");

        // Live running build with force -> CancelThenStart
        BuildPlan liveForce = BuildEngine.planBuild(svc, Optional.of(running), true, true);
        Assert.assertTrue(liveForce instanceof BuildPlan.CancelThenStart, "cancel then start on force");

        // Dead-PID running build (UNKNOWN) without force -> RejectUnknownState
        BuildPlan unknownNoForce = BuildEngine.planBuild(svc, Optional.of(running), false, false);
        Assert.assertTrue(unknownNoForce instanceof BuildPlan.RejectUnknownState, "reject UNKNOWN without force");

        // Dead-PID running build (UNKNOWN) with force -> ClearUnknownThenStart
        BuildPlan unknownForce = BuildEngine.planBuild(svc, Optional.of(running), false, true);
        Assert.assertTrue(unknownForce instanceof BuildPlan.ClearUnknownThenStart, "clear UNKNOWN on force");

        // Prior Succeeded or Failed allows fresh start without force
        BuildRunRecord.Failed priorFailed = new BuildRunRecord.Failed(
            42L, T0, T1, Optional.of(1), "failed", List.of("./gradlew"), OUT, ERR
        );
        Assert.assertTrue(
            BuildEngine.planBuild(svc, Optional.of(priorFailed), false, false) instanceof BuildPlan.Start,
            "start after failed"
        );
    }

    public static void testPlanCancelHandlesRunningUnknownAndIdle() {
        BuildRunRecord.Running running = new BuildRunRecord.Running(99L, T0, List.of("./gradlew"), OUT, ERR);

        Assert.assertTrue(
            BuildEngine.planCancel("orders", Optional.of(running), true) instanceof CancelPlan.StopAndMarkFailed,
            "stop and mark failed when alive"
        );
        Assert.assertTrue(
            BuildEngine.planCancel("orders", Optional.of(running), false) instanceof CancelPlan.ClearUnknownAsFailed,
            "clear unknown as failed when dead"
        );
        Assert.assertTrue(
            BuildEngine.planCancel("orders", Optional.empty(), false) instanceof CancelPlan.NotRunning,
            "not running when absent"
        );
    }
}
