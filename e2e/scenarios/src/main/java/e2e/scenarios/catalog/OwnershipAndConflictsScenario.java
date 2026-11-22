package e2e.scenarios.catalog;

import e2e.scenarios.engine.CliResult;
import e2e.scenarios.engine.EstateSpec;
import e2e.scenarios.engine.ExternalActorEvent;
import e2e.scenarios.engine.Scenario;
import e2e.scenarios.engine.ScenarioStep;
import e2e.scenarios.engine.ShowcaseSpec;
import e2e.scenarios.engine.StepAction;
import e2e.scenarios.engine.WorldKind;
import procomp.core.common.JsonValue;

import java.util.List;

import static e2e.scenarios.catalog.ScenarioAssertions.assertCliOk;
import static e2e.scenarios.catalog.ScenarioAssertions.assertEquals;
import static e2e.scenarios.catalog.ScenarioAssertions.assertNoSecretLeak;
import static e2e.scenarios.catalog.ScenarioAssertions.assertNotEquals;
import static e2e.scenarios.catalog.ScenarioAssertions.assertTrue;
import static e2e.scenarios.catalog.ScenarioAssertions.findServiceRow;
import static e2e.scenarios.catalog.ScenarioAssertions.optionalString;
import static e2e.scenarios.catalog.ScenarioAssertions.parseJsonObject;
import static e2e.scenarios.catalog.ScenarioAssertions.requireString;

/**
 * Dual-world Scenario exercising ownership boundaries: {@code Unmanaged} Service reuse,
 * {@code stop} sparing {@code Unmanaged} processes, port {@code Conflict} fail-fast with
 * {@code Inspection} details, and {@code Unmanaged} Gateway reuse and protection.
 */
final class OwnershipAndConflictsScenario {

    private OwnershipAndConflictsScenario() {}

    static Scenario build() {
        return new Scenario(
            ScenarioCatalog.OWNERSHIP_AND_CONFLICTS,
            "Ownership Boundaries and Port Conflicts",
            "Verifies 'never kill what you do not own': Unmanaged IDE Service reuse on start, stop sparing "
                + "Unmanaged processes, port Conflict fail-fast with inspect diagnostics, and Unmanaged Gateway reuse.",
            true,
            EstateSpec.StandardTopology.INSTANCE,
            List.of(
                ScenarioStep.of(
                    "bootstrap",
                    "Clean estate bootstrap",
                    new StepAction.ExternalActor(new ExternalActorEvent.BootstrapClean())
                ),
                ScenarioStep.showcased(
                    "unmanaged-service",
                    "Spawn Unmanaged IDE pricing Process and reuse it on start",
                    StepAction.Mixed.of(
                        new StepAction.ExternalActor(new ExternalActorEvent.SpawnUnmanagedService("pricing", true)),
                        StepAction.ProcompCommand.of("start", "pricing"),
                        new StepAction.Assert("pricing is discovered as UNMANAGED and reused", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            JsonValue.JsonObject pricing = findServiceRow(root, "pricing");
                            assertEquals("UNMANAGED", requireString(pricing, "owner", "pricing"), "pricing owner");
                            assertEquals("HEALTHY", requireString(pricing, "health", "pricing"), "pricing health");
                            if (world.worldKind() == WorldKind.IN_MEMORY) {
                                assertEquals("IDE", requireString(pricing, "provenance", "pricing"), "pricing provenance");
                            }
                        })
                    ),
                    ShowcaseSpec.of(
                        "procomp discovers an Unmanaged IDE Process and reuses it on start",
                        "status"
                    )
                ),
                ScenarioStep.showcased(
                    "stop-spares-unmanaged",
                    "Run procomp stop pricing and verify Unmanaged Process is spared",
                    StepAction.Mixed.of(
                        StepAction.ProcompCommand.of("stop", "pricing"),
                        new StepAction.Assert("stop spares Unmanaged pricing Process", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            JsonValue.JsonObject pricing = findServiceRow(root, "pricing");
                            assertEquals("UNMANAGED", requireString(pricing, "owner", "pricing"),
                                "Unmanaged pricing must remain running after stop");
                            assertEquals("HEALTHY", requireString(pricing, "health", "pricing"),
                                "Unmanaged pricing must remain HEALTHY after stop");
                        })
                    ),
                    ShowcaseSpec.of(
                        "procomp stop spares Unmanaged Processes (never kill what you do not own)",
                        "status"
                    )
                ),
                ScenarioStep.showcased(
                    "port-conflict",
                    "Bind foreign Conflict Process on orders port and fail fast on start",
                    StepAction.Mixed.of(
                        new StepAction.ExternalActor(new ExternalActorEvent.SpawnConflict("orders")),
                        StepAction.ProcompCommand.of("start", "orders"),
                        new StepAction.Assert("inspect orders exposes Conflict process diagnostics", world -> {
                            CliResult inspect = assertCliOk(world.runProcomp("inspect", "orders", "--json"), "inspect orders");
                            assertNoSecretLeak(inspect.stdout(), "inspect orders --json");
                            JsonValue.JsonObject root = parseJsonObject(inspect.stdout(), "inspect orders --json");
                            assertEquals("CONFLICT", requireString(root, "owner", "inspect orders"), "orders owner");
                            String cmdLine = optionalString(root, "commandLine").orElse("");
                            assertTrue(!cmdLine.isBlank(), "Conflict inspection should report commandLine: " + inspect.stdout());
                        })
                    ),
                    1,
                    ShowcaseSpec.of(
                        "A foreign port Conflict fails fast on start and inspect reveals the occupying process",
                        "inspect", "orders"
                    )
                ),
                ScenarioStep.showcased(
                    "unmanaged-gateway",
                    "Spawn Unmanaged Gateway, reuse and reconcile it, and refuse gateway stop",
                    StepAction.Mixed.of(
                        new StepAction.ExternalActor(new ExternalActorEvent.SpawnUnmanagedGateway()),
                        new StepAction.ProcompBatch(List.of(
                            List.of("gateway", "start"),
                            List.of("gateway", "reconcile")
                        )),
                        new StepAction.Assert("Unmanaged Gateway is IN_SYNC and gateway stop refuses to kill it", world -> {
                            CliResult gwStatus = assertCliOk(world.runProcomp("gateway", "status", "--json"), "gateway status");
                            JsonValue.JsonObject gwJson = parseJsonObject(gwStatus.stdout(), "gateway status --json");
                            assertEquals("UNMANAGED", requireString(gwJson, "owner", "gateway"), "gateway owner");
                            assertEquals("IN_SYNC", requireString(gwJson, "syncStatus", "gateway"), "gateway syncStatus");

                            CliResult stopRes = world.runProcomp("gateway", "stop");
                            assertNotEquals(0, stopRes.exitCode(), "gateway stop must refuse to kill an Unmanaged Gateway");

                            CliResult afterStop = assertCliOk(world.runProcomp("gateway", "status", "--json"),
                                "gateway status after stop");
                            JsonValue.JsonObject afterJson = parseJsonObject(afterStop.stdout(), "gateway status after stop");
                            assertEquals("UNMANAGED", requireString(afterJson, "owner", "gateway"),
                                "Unmanaged Gateway must remain alive after gateway stop");
                            assertEquals("HEALTHY", requireString(afterJson, "health", "gateway"),
                                "Unmanaged Gateway must remain HEALTHY after gateway stop");
                        })
                    ),
                    ShowcaseSpec.of(
                        "An Unmanaged Gateway is reused and reconciled, while gateway stop refuses to terminate it",
                        "gateway", "status"
                    )
                )
            )
        );
    }
}
