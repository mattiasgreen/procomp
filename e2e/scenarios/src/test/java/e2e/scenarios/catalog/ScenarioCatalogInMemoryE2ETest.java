package e2e.scenarios.catalog;

import e2e.scenarios.engine.CliResult;
import e2e.scenarios.engine.LagProfile;
import e2e.scenarios.engine.Scenario;
import e2e.scenarios.engine.ScenarioRunner;
import e2e.scenarios.engine.ScenarioStep;
import e2e.scenarios.engine.WorldKind;
import e2e.scenarios.inmemory.InMemoryConfigSource;
import e2e.scenarios.inmemory.InMemoryWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScenarioCatalogInMemoryE2ETest {

    private final ScenarioRunner runner = new ScenarioRunner();

    static Stream<String> allScenarioIds() {
        return ScenarioCatalog.all().stream().map(Scenario::id);
    }

    @Test
    void catalogExposesAllFourCanonicalScenariosById() {
        List<Scenario> all = ScenarioCatalog.all();
        assertEquals(
            List.of(
                "hybrid-routing",
                "ownership-and-conflicts",
                "failures-logs-and-stale",
                "large-estate"
            ),
            all.stream().map(Scenario::id).toList()
        );

        for (Scenario scenario : all) {
            assertEquals(scenario, ScenarioCatalog.byId(scenario.id()).orElseThrow());
            assertTrue(
                scenario.steps().stream().anyMatch(step -> step.showcase().isPresent()),
                "Scenario '" + scenario.id() + "' should mark key steps with ShowcaseSpec for Demo Capture"
            );
        }
        assertTrue(ScenarioCatalog.byId("does-not-exist").isEmpty());

        assertTrue(ScenarioCatalog.hybridRouting().dualWorld());
        assertTrue(ScenarioCatalog.ownershipAndConflicts().dualWorld());
        assertTrue(ScenarioCatalog.failuresLogsAndStale().dualWorld());
        assertFalse(ScenarioCatalog.largeEstate().dualWorld());
    }

    @Test
    void canonicalStepIdsMatchSpecification() {
        assertEquals(
            List.of("bootstrap", "gateway-ready", "hybrid-running", "drifted-route", "local-reconciled", "local-fail-fast"),
            ScenarioCatalog.hybridRouting().steps().stream().map(ScenarioStep::id).toList()
        );
        assertEquals(
            List.of("bootstrap", "unmanaged-service", "stop-spares-unmanaged", "port-conflict", "unmanaged-gateway"),
            ScenarioCatalog.ownershipAndConflicts().steps().stream().map(ScenarioStep::id).toList()
        );
        assertEquals(
            List.of("hybrid-running", "service-crashed", "stale-artifact", "logs-streaming"),
            ScenarioCatalog.failuresLogsAndStale().steps().stream().map(ScenarioStep::id).toList()
        );
        assertEquals(
            List.of("bootstrap", "profile-and-gateway", "local-slice-running", "single-service-drift"),
            ScenarioCatalog.largeEstate().steps().stream().map(ScenarioStep::id).toList()
        );
    }

    @ParameterizedTest(name = "runAll: {0}")
    @MethodSource("allScenarioIds")
    void scenarioPassesEndToEndInInMemoryWorld(String scenarioId) {
        Scenario scenario = ScenarioCatalog.byId(scenarioId).orElseThrow();
        try (InMemoryWorld world = new InMemoryWorld()) {
            world.setLag(LagProfile.NONE);
            ScenarioRunner.ScenarioRunResult result = runner.runAll(scenario, world);
            assertEquals(WorldKind.IN_MEMORY, result.worldKind());
            assertEquals(scenario.steps().size() - 1, result.lastStepIndex());
            assertEquals(scenario.steps().size(), result.stepResults().size());
        }
    }

    @ParameterizedTest(name = "jumpToStep equivalence: {0}")
    @MethodSource("allScenarioIds")
    void fastForwardJumpToEveryStepMatchesSequentialExecution(String scenarioId) {
        Scenario scenario = ScenarioCatalog.byId(scenarioId).orElseThrow();
        try (InMemoryWorld sequentialWorld = new InMemoryWorld();
             InMemoryWorld jumpedWorld = new InMemoryWorld()) {
            sequentialWorld.setLag(LagProfile.NONE);
            jumpedWorld.setLag(LagProfile.NONE);
            sequentialWorld.reset(scenario.estateConfig());

            for (int i = 0; i < scenario.steps().size(); i++) {
                ScenarioStep step = scenario.steps().get(i);
                runner.advanceStep(scenario, sequentialWorld, i);
                CliResult seqStatus = sequentialWorld.runProcomp("status", "--json");
                assertEquals(0, seqStatus.exitCode(), "Sequential status failed at step " + step.id());

                // Pollute jumpedWorld before jumping to ensure clean reset + replay
                jumpedWorld.runProcomp("status");
                ScenarioRunner.ScenarioRunResult jumpRes = runner.jumpToStep(scenario, jumpedWorld, step.id());
                assertEquals(i, jumpRes.lastStepIndex(), "lastStepIndex mismatch at step " + step.id());

                CliResult jumpStatus = jumpedWorld.runProcomp("status", "--json");
                assertEquals(0, jumpStatus.exitCode(), "Jumped status failed at step " + step.id());
                assertEquals(
                    seqStatus.stdout(),
                    jumpStatus.stdout(),
                    "status --json mismatch between sequential and jumpToStep for " + scenario.id() + "/" + step.id()
                );
            }
        }
    }

    @ParameterizedTest(name = "secret hygiene: {0}")
    @MethodSource("allScenarioIds")
    void secretHygieneAcrossStatusInspectAndLogs(String scenarioId) {
        Scenario scenario = ScenarioCatalog.byId(scenarioId).orElseThrow();
        String secret = InMemoryConfigSource.DEMO_SECRET_VALUE;
        String targetService = scenario.id().equals(ScenarioCatalog.LARGE_ESTATE) ? "svc-01" : "orders";

        try (InMemoryWorld world = new InMemoryWorld()) {
            world.setLag(LagProfile.NONE);
            runner.runAll(scenario, world);

            CliResult status = world.runProcomp("status", "--json");
            assertEquals(0, status.exitCode(), status.stderr());
            assertFalse(status.stdout().contains(secret), "status --json leaked secret");
            assertFalse(status.stderr().contains(secret), "status --json stderr leaked secret");

            CliResult inspect = world.runProcomp("inspect", targetService, "--json");
            assertEquals(0, inspect.exitCode(), inspect.stderr());
            assertFalse(inspect.stdout().contains(secret), "inspect --json leaked secret");
            assertFalse(inspect.stderr().contains(secret), "inspect --json stderr leaked secret");

            CliResult outLogs = world.runProcomp("logs", targetService);
            assertFalse(outLogs.stdout().contains(secret), "logs stdout leaked secret");
            assertFalse(outLogs.stderr().contains(secret), "logs stderr leaked secret");

            CliResult errLogs = world.runProcomp("logs", targetService, "--err");
            assertFalse(errLogs.stdout().contains(secret), "logs --err stdout leaked secret");
            assertFalse(errLogs.stderr().contains(secret), "logs --err stderr leaked secret");
        }
    }
}
