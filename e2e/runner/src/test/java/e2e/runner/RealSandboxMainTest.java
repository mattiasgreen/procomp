package e2e.runner;

import e2e.scenarios.catalog.ScenarioCatalog;
import e2e.scenarios.engine.EstateSpec;
import e2e.scenarios.engine.ScenarioRunner;
import e2e.scenarios.engine.WorldKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RealSandboxMainTest {

    @Test
    void parsesDefaultFlagsAndPositionalScenarioStepSyntax() {
        RealSandboxMain.SandboxArgs defaults = RealSandboxMain.SandboxArgs.parse(new String[0]);
        assertEquals(ScenarioCatalog.HYBRID_ROUTING, defaults.scenarioId());
        assertEquals(Optional.empty(), defaults.stepId());

        RealSandboxMain.SandboxArgs flags = RealSandboxMain.SandboxArgs.parse(
            new String[] { "--scenario", "ownership-and-conflicts", "--step", "port-conflict" }
        );
        assertEquals("ownership-and-conflicts", flags.scenarioId());
        assertEquals(Optional.of("port-conflict"), flags.stepId());

        RealSandboxMain.SandboxArgs shorthand = RealSandboxMain.SandboxArgs.parse(
            new String[] { "failures-logs-and-stale/stale-artifact" }
        );
        assertEquals("failures-logs-and-stale", shorthand.scenarioId());
        assertEquals(Optional.of("stale-artifact"), shorthand.stepId());
    }

    @Test
    void realTopologyWorldRejectsLargeEstateAndInMemoryOnlyScenario(@TempDir Path tmpDir) {
        try (RealTopologyWorld world = new RealTopologyWorld(tmpDir, tmpDir.resolve("wiremock.jar"), System.getProperty("java.home"))) {
            assertEquals(WorldKind.REAL_TOPOLOGY, world.worldKind());

            assertThrows(UnsupportedOperationException.class, () -> world.reset(new EstateSpec.LargeEstate(25)));

            ScenarioRunner runner = new ScenarioRunner();
            UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> runner.runAll(ScenarioCatalog.largeEstate(), world)
            );
            assertTrue(ex.getMessage().contains("large-estate"), "Error should mention scenario id: " + ex.getMessage());
        }
    }

    @Test
    void injectsDefaultTimeoutForBareStartAndGatewayStartOnly() {
        assertEquals(
            List.of("start", "--timeout", "60"),
            RealTopologyWorld.withDefaultTimeoutIfNeeded(List.of("start"))
        );
        assertEquals(
            List.of("start", "pricing", "--timeout", "60"),
            RealTopologyWorld.withDefaultTimeoutIfNeeded(List.of("start", "pricing"))
        );
        assertEquals(
            List.of("gateway", "start", "--timeout", "60"),
            RealTopologyWorld.withDefaultTimeoutIfNeeded(List.of("gateway", "start"))
        );
        assertEquals(
            List.of("start", "--timeout", "15"),
            RealTopologyWorld.withDefaultTimeoutIfNeeded(List.of("start", "--timeout", "15"))
        );
        assertEquals(
            List.of("status", "--json"),
            RealTopologyWorld.withDefaultTimeoutIfNeeded(List.of("status", "--json"))
        );
    }
}
