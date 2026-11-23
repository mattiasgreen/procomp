package e2e.runner;

import e2e.scenarios.catalog.ScenarioCatalog;
import e2e.scenarios.engine.CliResult;
import e2e.scenarios.engine.HttpProbeResult;
import e2e.scenarios.engine.ScenarioRunner;
import e2e.scenarios.engine.WorldKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * True end-to-end integration test exercising all dual-world v1 {@link ScenarioCatalog} Scenarios
 * via {@link ScenarioRunner} + {@link RealTopologyWorld} against real Spring Boot 4 JARs,
 * embedded ActiveMQ Artemis, real WireMock standalone, and JEP 458 {@code Procomp.java}.
 */
@Tag("e2e")
final class ProcompTopologyE2ETest {

    private final ScenarioRunner runner = new ScenarioRunner();

    @Test
    void hybridRoutingScenarioWithRealArtemisJmsDeliveryAndWireMock() {
        try (RealTopologyWorld world = RealTopologyWorld.fromSystemProperties()) {
            // Fast-forward through hybrid-running and verify real async Artemis JMS delivery + Remote WireMock journal
            ScenarioRunner.ScenarioRunResult hybridRunning = runner.jumpToStep(
                ScenarioCatalog.hybridRouting(),
                world,
                "hybrid-running"
            );
            assertEquals(WorldKind.REAL_TOPOLOGY, hybridRunning.worldKind());

            HttpProbeResult orderResp1 = world.httpPost("/orders/create");
            assertEquals(200, orderResp1.statusCode(), "POST /orders/create failed: " + orderResp1.body());
            String orderId1 = extractJsonString(orderResp1.body(), "orderId");
            assertNotNull(orderId1, "orderId must be present: " + orderResp1.body());

            HttpProbeResult notif1 = world.pollHttpGet200("/notifications/" + orderId1, Duration.ofSeconds(15));
            assertTrue(notif1.body().contains("\"pricedBy\":\"remote-wiremock-pricing\""),
                "JMS notification should carry remote pricing: " + notif1.body());
            assertTrue(notif1.body().contains("\"servedBy\":\"local-notifications\""),
                "JMS notification should be served by local-notifications: " + notif1.body());

            HttpProbeResult remoteReqs = world.remoteHttpGet("/__admin/requests");
            assertEquals(200, remoteReqs.statusCode());
            assertTrue(remoteReqs.body().contains("/pricing/quote"),
                "Remote WireMock journal should contain /pricing/quote");

            // Advance remaining steps: drifted-route -> local-reconciled (verify JMS with local pricing) -> local-fail-fast
            runner.advanceStep(ScenarioCatalog.hybridRouting(), world, hybridRunning.lastStepIndex() + 1);
            runner.advanceStep(ScenarioCatalog.hybridRouting(), world, hybridRunning.lastStepIndex() + 2);

            HttpProbeResult orderResp2 = world.httpPost("/orders/create");
            assertEquals(200, orderResp2.statusCode(), "POST /orders/create after reconcile failed: " + orderResp2.body());
            String orderId2 = extractJsonString(orderResp2.body(), "orderId");
            assertNotNull(orderId2, "orderId2 must be present: " + orderResp2.body());

            HttpProbeResult notif2 = world.pollHttpGet200("/notifications/" + orderId2, Duration.ofSeconds(15));
            assertTrue(notif2.body().contains("\"pricedBy\":\"local-pricing\""),
                "JMS notification after reconcile should carry local pricing: " + notif2.body());

            runner.advanceStep(ScenarioCatalog.hybridRouting(), world, hybridRunning.lastStepIndex() + 3);

            // Verify clean stop --all teardown
            CliResult stopAll = world.runProcomp("stop", "--all");
            assertEquals(0, stopAll.exitCode(), "stop --all failed: " + stopAll.stderr());
            CliResult finalStatus = world.runProcomp("status", "--json");
            assertEquals(0, finalStatus.exitCode());
            assertTrue(finalStatus.stdout().contains("\"syncStatus\":\"OFFLINE\""),
                "Gateway must be OFFLINE after stop --all: " + finalStatus.stdout());
        }
    }

    @Test
    void ownershipAndConflictsScenarioOnRealOsProcesses() {
        try (RealTopologyWorld world = RealTopologyWorld.fromSystemProperties()) {
            ScenarioRunner.ScenarioRunResult result = runner.runAll(ScenarioCatalog.ownershipAndConflicts(), world);
            assertEquals(WorldKind.REAL_TOPOLOGY, result.worldKind());
            assertEquals(ScenarioCatalog.ownershipAndConflicts().steps().size(), result.stepResults().size());
        }
    }

    @Test
    void failuresLogsAndStaleScenarioOnRealOsProcesses() {
        try (RealTopologyWorld world = RealTopologyWorld.fromSystemProperties()) {
            ScenarioRunner.ScenarioRunResult result = runner.runAll(ScenarioCatalog.failuresLogsAndStale(), world);
            assertEquals(WorldKind.REAL_TOPOLOGY, result.worldKind());
            assertEquals(ScenarioCatalog.failuresLogsAndStale().steps().size(), result.stepResults().size());
        }
    }

    @Test
    void rejectsInMemoryOnlyLargeEstateScenario() {
        try (RealTopologyWorld world = RealTopologyWorld.fromSystemProperties()) {
            assertThrows(
                UnsupportedOperationException.class,
                () -> runner.runAll(ScenarioCatalog.largeEstate(), world)
            );
        }
    }

    private static String extractJsonString(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
