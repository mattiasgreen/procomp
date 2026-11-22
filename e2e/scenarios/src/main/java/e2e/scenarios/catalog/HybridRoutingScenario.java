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
import static e2e.scenarios.catalog.ScenarioAssertions.assertFalse;
import static e2e.scenarios.catalog.ScenarioAssertions.assertNoSecretLeak;
import static e2e.scenarios.catalog.ScenarioAssertions.assertTrue;
import static e2e.scenarios.catalog.ScenarioAssertions.findServiceRow;
import static e2e.scenarios.catalog.ScenarioAssertions.gatewayObject;
import static e2e.scenarios.catalog.ScenarioAssertions.parseJsonObject;
import static e2e.scenarios.catalog.ScenarioAssertions.requireInt;
import static e2e.scenarios.catalog.ScenarioAssertions.requireString;

/**
 * Dual-world Scenario exercising Profile application, Gateway Reconciliation, UNIX separation
 * ({@code DRIFTED} vs {@code IN_SYNC}), live Route flip without restarting callers, fail-fast 5xx
 * on a stopped {@code LOCAL} target, and secret hygiene.
 */
final class HybridRoutingScenario {

    private HybridRoutingScenario() {}

    static Scenario build() {
        return new Scenario(
            ScenarioCatalog.HYBRID_ROUTING,
            "Hybrid Local/Remote Routing",
            "End-to-end hybrid workflow: Profile application, Gateway Reconciliation, bare start of the LOCAL slice, "
                + "UNIX separation (DRIFTED before reconcile), live Route switch without restarting callers, "
                + "fail-fast 5xx when a LOCAL target stops, and secret redaction.",
            true,
            EstateSpec.StandardTopology.INSTANCE,
            List.of(
                ScenarioStep.showcased(
                    "bootstrap",
                    "Clean estate bootstrap",
                    new StepAction.ExternalActor(new ExternalActorEvent.BootstrapClean()),
                    ShowcaseSpec.of(
                        "Clean estate before applying a Profile or starting the Gateway",
                        "status"
                    )
                ),
                ScenarioStep.showcased(
                    "gateway-ready",
                    "Apply orders-local Profile, start Gateway, and reconcile",
                    StepAction.Mixed.of(
                        new StepAction.ProcompBatch(List.of(
                            List.of("profile", "use", "orders-local"),
                            List.of("gateway", "start"),
                            List.of("gateway", "reconcile")
                        )),
                        new StepAction.Assert("Gateway is IN_SYNC and orders-local routes are active", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            assertNoSecretLeak(status.stdout(), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            assertEquals("IN_SYNC", requireString(gatewayObject(root), "syncStatus", "gateway"),
                                "Gateway should be IN_SYNC after reconcile");
                            assertEquals("LOCAL", requireString(findServiceRow(root, "orders"), "route", "orders"),
                                "orders should be routed LOCAL");
                            assertEquals("REMOTE", requireString(findServiceRow(root, "pricing"), "route", "pricing"),
                                "pricing should be routed REMOTE");
                        })
                    ),
                    ShowcaseSpec.of(
                        "Apply the orders-local Profile, start the WireMock Gateway, and reconcile mappings",
                        "gateway", "status"
                    )
                ),
                ScenarioStep.showcased(
                    "hybrid-running",
                    "Start LOCAL slice and verify hybrid remote pricing",
                    StepAction.Mixed.of(
                        StepAction.ProcompCommand.of("start"),
                        new StepAction.Assert("LOCAL slice is HEALTHY and orders calls REMOTE pricing", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            assertNoSecretLeak(status.stdout(), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            assertEquals("IN_SYNC", requireString(gatewayObject(root), "syncStatus", "gateway"),
                                "Gateway must remain IN_SYNC");
                            for (String localSvc : List.of("broker", "orders", "notifications")) {
                                JsonValue.JsonObject row = findServiceRow(root, localSvc);
                                assertEquals("LOCAL", requireString(row, "route", localSvc), localSvc + " route");
                                assertEquals("MANAGED", requireString(row, "owner", localSvc), localSvc + " owner");
                                assertEquals("HEALTHY", requireString(row, "health", localSvc), localSvc + " health");
                            }
                            JsonValue.JsonObject pricing = findServiceRow(root, "pricing");
                            assertEquals("REMOTE", requireString(pricing, "route", "pricing"), "pricing route");
                            assertEquals("STOPPED", requireString(pricing, "health", "pricing"), "pricing health");

                            HttpProbeResult order = world.httpPost("/orders/create");
                            assertEquals(200, order.statusCode(), "POST /orders/create: " + order.body());
                            JsonValue.JsonObject orderJson = parseJsonObject(order.body(), "POST /orders/create");
                            assertEquals("local-orders", requireString(orderJson, "servedBy", "order"), "servedBy");
                            assertEquals("remote-wiremock-pricing", requireString(orderJson, "pricedBy", "order"),
                                "pricedBy should hit remote pricing");
                            assertEquals(99, requireInt(orderJson, "price", "order"), "remote price");
                        })
                    ),
                    ShowcaseSpec.of(
                        "Bare start launches only LOCAL services while pricing stays routed REMOTE",
                        "status"
                    )
                ),
                ScenarioStep.showcased(
                    "drifted-route",
                    "Start pricing and flip Route LOCAL without reconciling (UNIX separation)",
                    StepAction.Mixed.of(
                        new StepAction.ProcompBatch(List.of(
                            List.of("start", "pricing"),
                            List.of("route", "pricing", "local")
                        )),
                        new StepAction.Assert("Gateway is DRIFTED and still forwards pricing REMOTE before reconcile", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            assertEquals("DRIFTED", requireString(gatewayObject(root), "syncStatus", "gateway"),
                                "Gateway must be DRIFTED after route edit before reconcile");
                            JsonValue.JsonObject pricing = findServiceRow(root, "pricing");
                            assertEquals("LOCAL", requireString(pricing, "route", "pricing"), "pricing desired route");
                            assertEquals("HEALTHY", requireString(pricing, "health", "pricing"), "pricing health");

                            HttpProbeResult preReconcile = world.httpPost("/orders/create");
                            assertEquals(200, preReconcile.statusCode(), "pre-reconcile order: " + preReconcile.body());
                            JsonValue.JsonObject orderJson = parseJsonObject(preReconcile.body(), "pre-reconcile order");
                            assertEquals("remote-wiremock-pricing", requireString(orderJson, "pricedBy", "order"),
                                "UNIX separation: Gateway must still route pricing REMOTE before reconcile");
                        })
                    ),
                    ShowcaseSpec.of(
                        "Starting pricing and editing its Route marks the Gateway DRIFTED without touching live traffic",
                        "status"
                    )
                ),
                ScenarioStep.showcased(
                    "local-reconciled",
                    "Reconcile Gateway for live switch to local pricing without restarting orders",
                    StepAction.Mixed.of(
                        StepAction.ProcompCommand.of("gateway", "reconcile"),
                        new StepAction.Assert("Gateway IN_SYNC, orders hits local pricing, and inspect redacts secrets", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            assertEquals("IN_SYNC", requireString(gatewayObject(root), "syncStatus", "gateway"),
                                "Gateway must be IN_SYNC after reconcile");

                            HttpProbeResult order = world.httpPost("/orders/create");
                            assertEquals(200, order.statusCode(), "post-reconcile order: " + order.body());
                            JsonValue.JsonObject orderJson = parseJsonObject(order.body(), "post-reconcile order");
                            assertEquals("local-orders", requireString(orderJson, "servedBy", "order"), "servedBy");
                            assertEquals("local-pricing", requireString(orderJson, "pricedBy", "order"),
                                "orders should hit local pricing without restarting orders");
                            assertEquals(42, requireInt(orderJson, "price", "order"), "local price");

                            CliResult inspect = assertCliOk(world.runProcomp("inspect", "orders", "--json"), "inspect orders");
                            assertNoSecretLeak(inspect.stdout(), "inspect orders --json");
                        })
                    ),
                    ShowcaseSpec.of(
                        "Reconciling the Gateway converges live traffic to local pricing without restarting callers",
                        "status"
                    )
                ),
                ScenarioStep.showcased(
                    "local-fail-fast",
                    "Stop LOCAL pricing and verify fail-fast 5xx with zero fallback to REMOTE",
                    StepAction.Mixed.of(
                        StepAction.ProcompCommand.of("stop", "pricing"),
                        new StepAction.Assert("Stopped LOCAL pricing fails fast with 5xx and never falls back to REMOTE", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            JsonValue.JsonObject pricing = findServiceRow(root, "pricing");
                            assertEquals("LOCAL", requireString(pricing, "route", "pricing"), "pricing route remains LOCAL");
                            assertEquals("STOPPED", requireString(pricing, "health", "pricing"), "pricing health");

                            HttpProbeResult failedPricing = world.httpGet("/pricing/quote");
                            assertTrue(failedPricing.is5xx(),
                                "Stopped LOCAL target must fail fast with 5xx, got " + failedPricing);
                            assertFalse(failedPricing.body().contains("remote-wiremock-pricing"),
                                "Stopped LOCAL target must never silently fall back to REMOTE: " + failedPricing.body());
                        })
                    ),
                    ShowcaseSpec.of(
                        "Stopping a LOCAL service fails fast with 5xx rather than silently falling back to REMOTE",
                        "status"
                    )
                )
            )
        );
    }
}
