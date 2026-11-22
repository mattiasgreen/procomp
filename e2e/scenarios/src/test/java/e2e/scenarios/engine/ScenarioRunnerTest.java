package e2e.scenarios.engine;

import e2e.scenarios.inmemory.InMemoryWorld;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScenarioRunnerTest {

    private final ScenarioRunner runner = new ScenarioRunner();

    private static Scenario sampleHybridScenario() {
        return new Scenario(
            "sample-hybrid",
            "Sample Hybrid Workflow",
            "Exercises batch, command, external actor, and assertion steps.",
            true,
            EstateSpec.StandardTopology.INSTANCE,
            List.of(
                ScenarioStep.of(
                    "bootstrap",
                    "Clean estate bootstrap",
                    new StepAction.ExternalActor(new ExternalActorEvent.BootstrapClean())
                ),
                ScenarioStep.of(
                    "apply-and-gateway",
                    "Apply profile and start/reconcile Gateway",
                    new StepAction.ProcompBatch(List.of(
                        List.of("profile", "use", "orders-local"),
                        List.of("gateway", "start"),
                        List.of("gateway", "reconcile")
                    ))
                ),
                ScenarioStep.of(
                    "start-local",
                    "Start LOCAL slice",
                    StepAction.ProcompCommand.of("start")
                ),
                ScenarioStep.of(
                    "verify-order",
                    "Verify hybrid order routing",
                    new StepAction.Assert("orders calls remote pricing", world -> {
                        HttpProbeResult resp = world.httpPost("/orders/create");
                        assertEquals(200, resp.statusCode(), resp.body());
                        assertTrue(resp.body().contains("\"pricedBy\":\"remote-wiremock-pricing\""), resp.body());
                    })
                )
            )
        );
    }

    @Test
    void jumpToStepYieldsIdenticalStateToSequentialStepping() {
        Scenario scenario = sampleHybridScenario();

        String sequentialStatus;
        try (InMemoryWorld worldA = new InMemoryWorld()) {
            worldA.reset(scenario.estateConfig());
            for (int i = 0; i <= 2; i++) {
                runner.advanceStep(scenario, worldA, i);
            }
            sequentialStatus = worldA.runProcomp("status", "--json").stdout();
        }

        String jumpedStatus;
        try (InMemoryWorld worldB = new InMemoryWorld()) {
            // Pollute worldB first with extra commands before jumping
            worldB.runProcomp("route", "pricing", "local");
            worldB.runProcomp("start", "pricing");

            ScenarioRunner.ScenarioRunResult jumpRes = runner.jumpToStep(scenario, worldB, "start-local");
            assertEquals(2, jumpRes.lastStepIndex());
            assertEquals(3, jumpRes.stepResults().size());
            jumpedStatus = worldB.runProcomp("status", "--json").stdout();
        }

        assertEquals(sequentialStatus, jumpedStatus, "Fast-forward jump must match sequential execution identically");
    }

    @Test
    void jumpToStepDisablesLagDuringReplayAndRestoresActiveLagAfterward() {
        Scenario scenario = sampleHybridScenario();
        try (InMemoryWorld world = new InMemoryWorld()) {
            world.setLag(LagProfile.REALISTIC);

            long startNanos = System.nanoTime();
            runner.jumpToStep(scenario, world, "start-local");
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            assertEquals(LagProfile.REALISTIC, world.lag(), "Active lag must be restored after fast-forward replay");
            assertTrue(elapsedMs < 1000L,
                "Fast-forward replay must run with LagProfile.NONE (took " + elapsedMs + "ms)");
        }
    }

    @Test
    void runAllExecutesAllStepsIncludingAssertions() {
        Scenario scenario = sampleHybridScenario();
        try (InMemoryWorld world = new InMemoryWorld()) {
            ScenarioRunner.ScenarioRunResult res = runner.runAll(scenario, world);
            assertEquals(WorldKind.IN_MEMORY, res.worldKind());
            assertEquals(3, res.lastStepIndex());
            assertEquals(4, res.stepResults().size());
        }
    }

    @Test
    void rejectsInMemoryOnlyScenarioOnRealTopologyWorld() {
        Scenario largeEstateOnly = new Scenario(
            "large-estate",
            "Large Estate",
            "InMemoryWorld-only scaling scenario",
            false,
            new EstateSpec.LargeEstate(25),
            List.of(ScenarioStep.of("status", "Check status", StepAction.ProcompCommand.of("status")))
        );

        ScenarioWorld realWorldStub = new RealTopologyWorldStub();

        UnsupportedOperationException runAllEx = assertThrows(
            UnsupportedOperationException.class,
            () -> runner.runAll(largeEstateOnly, realWorldStub)
        );
        assertTrue(runAllEx.getMessage().contains("large-estate"), runAllEx.getMessage());

        assertThrows(
            UnsupportedOperationException.class,
            () -> runner.jumpToStep(largeEstateOnly, realWorldStub, "status")
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> runner.advanceStep(largeEstateOnly, realWorldStub, 0)
        );
    }

    private static final class RealTopologyWorldStub implements ScenarioWorld {
        @Override public WorldKind worldKind() { return WorldKind.REAL_TOPOLOGY; }
        @Override public void reset(EstateSpec estateSpec) {}
        @Override public void setLag(LagProfile lag) {}
        @Override public LagProfile lag() { return LagProfile.NONE; }
        @Override public CliResult runProcomp(String... args) { throw new AssertionError("Should not execute"); }
        @Override public void triggerActor(ExternalActorEvent event) { throw new AssertionError("Should not execute"); }
        @Override public HttpProbeResult httpGet(String path) { throw new AssertionError("Should not execute"); }
        @Override public HttpProbeResult httpPost(String path) { throw new AssertionError("Should not execute"); }
        @Override public String gatewayBaseUrl() { return "http://127.0.0.1:18080"; }
        @Override public void advanceClockSeconds(long seconds) {}
        @Override public void close() {}
    }
}
