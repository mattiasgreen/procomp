package e2e.scenarios.catalog;

import e2e.scenarios.engine.CliResult;
import e2e.scenarios.engine.EstateSpec;
import e2e.scenarios.engine.ExternalActorEvent;
import e2e.scenarios.engine.HttpProbeResult;
import e2e.scenarios.engine.Scenario;
import e2e.scenarios.engine.ScenarioStep;
import e2e.scenarios.engine.ShowcaseSpec;
import e2e.scenarios.engine.StepAction;
import procomp.core.common.JsonValue;

import java.util.List;

import static e2e.scenarios.catalog.ScenarioAssertions.assertCliOk;
import static e2e.scenarios.catalog.ScenarioAssertions.assertEquals;
import static e2e.scenarios.catalog.ScenarioAssertions.assertNoSecretLeak;
import static e2e.scenarios.catalog.ScenarioAssertions.findServiceRow;
import static e2e.scenarios.catalog.ScenarioAssertions.gatewayObject;
import static e2e.scenarios.catalog.ScenarioAssertions.parseJsonObject;
import static e2e.scenarios.catalog.ScenarioAssertions.requireString;
import static e2e.scenarios.catalog.ScenarioAssertions.serviceRows;

/**
 * {@code InMemoryWorld}-only Scenario ({@code dualWorld = false}) simulating a 25-service estate
 * with an 8-service {@code LOCAL} Profile slice, Gateway Reconciliation, bare {@code start},
 * and single-service drift.
 */
final class LargeEstateScenario {

    static final int SERVICE_COUNT = 25;
    static final int LOCAL_SLICE_COUNT = 8;

    private LargeEstateScenario() {}

    static Scenario build() {
        return new Scenario(
            ScenarioCatalog.LARGE_ESTATE,
            "Large Estate (25 Services, 8-Service LOCAL Slice)",
            "InMemoryWorld-only scaling scenario: 25 services, 8-service LOCAL checkout-slice Profile, "
                + "Gateway Reconciliation, bare start, and single-service drift.",
            false,
            new EstateSpec.LargeEstate(SERVICE_COUNT),
            List.of(
                ScenarioStep.of(
                    "bootstrap",
                    "Clean 25-service estate bootstrap",
                    new StepAction.ExternalActor(new ExternalActorEvent.BootstrapClean())
                ),
                ScenarioStep.showcased(
                    "profile-and-gateway",
                    "Apply 8-service checkout-slice Profile, start Gateway, and reconcile",
                    StepAction.Mixed.of(
                        new StepAction.ProcompBatch(List.of(
                            List.of("profile", "use", "checkout-slice"),
                            List.of("gateway", "start"),
                            List.of("gateway", "reconcile")
                        )),
                        new StepAction.Assert("Gateway is IN_SYNC across all 25 services", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            assertNoSecretLeak(status.stdout(), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            assertEquals(SERVICE_COUNT, serviceRows(root).size(), "service count");
                            assertEquals("IN_SYNC", requireString(gatewayObject(root), "syncStatus", "gateway"),
                                "Gateway syncStatus");
                        })
                    ),
                    ShowcaseSpec.of(
                        "Apply the 8-service LOCAL checkout-slice Profile and reconcile the Gateway across 25 services",
                        "gateway", "status"
                    )
                ),
                ScenarioStep.showcased(
                    "local-slice-running",
                    "Bare start launches the 8 LOCAL services while 17 remain REMOTE",
                    StepAction.Mixed.of(
                        StepAction.ProcompCommand.of("start"),
                        new StepAction.Assert("8 LOCAL services HEALTHY, 17 REMOTE STOPPED, and Gateway routes both", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            assertNoSecretLeak(status.stdout(), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            assertEquals("IN_SYNC", requireString(gatewayObject(root), "syncStatus", "gateway"),
                                "Gateway syncStatus");

                            for (int i = 1; i <= SERVICE_COUNT; i++) {
                                String name = String.format("svc-%02d", i);
                                JsonValue.JsonObject row = findServiceRow(root, name);
                                if (i <= LOCAL_SLICE_COUNT) {
                                    assertEquals("LOCAL", requireString(row, "route", name), name + " route");
                                    assertEquals("MANAGED", requireString(row, "owner", name), name + " owner");
                                    assertEquals("HEALTHY", requireString(row, "health", name), name + " health");
                                } else {
                                    assertEquals("REMOTE", requireString(row, "route", name), name + " route");
                                    assertEquals("NONE", requireString(row, "owner", name), name + " owner");
                                    assertEquals("STOPPED", requireString(row, "health", name), name + " health");
                                }
                            }

                            HttpProbeResult localResp = world.httpGet("/svc-01/ping");
                            assertEquals(200, localResp.statusCode(), "local svc-01 ping: " + localResp.body());
                            assertEquals("local-svc-01",
                                requireString(parseJsonObject(localResp.body(), "svc-01"), "servedBy", "svc-01"),
                                "svc-01 should route LOCAL");

                            HttpProbeResult remoteResp = world.httpGet("/svc-25/ping");
                            assertEquals(200, remoteResp.statusCode(), "remote svc-25 ping: " + remoteResp.body());
                            assertEquals("remote-svc-25",
                                requireString(parseJsonObject(remoteResp.body(), "svc-25"), "servedBy", "svc-25"),
                                "svc-25 should route REMOTE");
                        })
                    ),
                    ShowcaseSpec.of(
                        "25-service status table with 8 LOCAL services running and 17 routed REMOTE",
                        "status"
                    )
                ),
                ScenarioStep.showcased(
                    "single-service-drift",
                    "Flip svc-09 Route to LOCAL and observe single-service Gateway drift",
                    StepAction.Mixed.of(
                        StepAction.ProcompCommand.of("route", "svc-09", "local"),
                        new StepAction.Assert("Gateway reports DRIFTED with svc-09 routed LOCAL", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            assertEquals("DRIFTED", requireString(gatewayObject(root), "syncStatus", "gateway"),
                                "Gateway should report DRIFTED after flipping svc-09");
                            assertEquals("LOCAL", requireString(findServiceRow(root, "svc-09"), "route", "svc-09"),
                                "svc-09 route should be LOCAL");
                        })
                    ),
                    ShowcaseSpec.of(
                        "Changing a single Route in a 25-service estate surfaces Gateway DRIFTED immediately",
                        "status"
                    )
                )
            )
        );
    }
}
