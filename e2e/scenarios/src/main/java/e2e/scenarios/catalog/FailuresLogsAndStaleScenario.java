package e2e.scenarios.catalog;

import e2e.scenarios.engine.CliResult;
import e2e.scenarios.engine.EstateSpec;
import e2e.scenarios.engine.ExternalActorEvent;
import e2e.scenarios.engine.Scenario;
import e2e.scenarios.engine.ScenarioStep;
import e2e.scenarios.engine.ShowcaseSpec;
import e2e.scenarios.engine.StepAction;
import procomp.core.common.JsonValue;

import java.util.List;

import static e2e.scenarios.catalog.ScenarioAssertions.assertCliOk;
import static e2e.scenarios.catalog.ScenarioAssertions.assertEquals;
import static e2e.scenarios.catalog.ScenarioAssertions.assertNoSecretLeak;
import static e2e.scenarios.catalog.ScenarioAssertions.assertTrue;
import static e2e.scenarios.catalog.ScenarioAssertions.findServiceRow;
import static e2e.scenarios.catalog.ScenarioAssertions.gatewayObject;
import static e2e.scenarios.catalog.ScenarioAssertions.parseJsonObject;
import static e2e.scenarios.catalog.ScenarioAssertions.requireBoolean;
import static e2e.scenarios.catalog.ScenarioAssertions.requireString;

/**
 * Dual-world Scenario exercising day-2 diagnostics: {@code CRASHED} detection and recovery,
 * {@code Stale} Artifact flagging in {@code status} and {@code inspect}, raw {@code logs}
 * ({@code -n}, {@code --err}, {@code --path}), and secret hygiene.
 */
final class FailuresLogsAndStaleScenario {

    private FailuresLogsAndStaleScenario() {}

    static Scenario build() {
        return new Scenario(
            ScenarioCatalog.FAILURES_LOGS_AND_STALE,
            "Failures, Logs, and Stale Artifacts",
            "Day-2 diagnostics: external kill into CRASHED state, recovery via start, Stale Artifact detection "
                + "in status and inspect, raw logs (-n, --err, --path), and secret redaction.",
            true,
            EstateSpec.StandardTopology.INSTANCE,
            List.of(
                ScenarioStep.showcased(
                    "hybrid-running",
                    "Apply orders-local Profile, reconcile Gateway, and start LOCAL slice",
                    StepAction.Mixed.of(
                        new StepAction.ProcompBatch(List.of(
                            List.of("profile", "use", "orders-local"),
                            List.of("gateway", "start"),
                            List.of("gateway", "reconcile"),
                            List.of("start")
                        )),
                        new StepAction.Assert("LOCAL slice is HEALTHY and Gateway IN_SYNC", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            assertNoSecretLeak(status.stdout(), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            assertEquals("IN_SYNC", requireString(gatewayObject(root), "syncStatus", "gateway"),
                                "Gateway syncStatus");
                            assertEquals("HEALTHY", requireString(findServiceRow(root, "orders"), "health", "orders"),
                                "orders health");
                        })
                    ),
                    ShowcaseSpec.of(
                        "Baseline healthy LOCAL slice prior to failure and staleness checks",
                        "status"
                    )
                ),
                ScenarioStep.showcased(
                    "service-crashed",
                    "Externally kill Managed orders Process and detect CRASHED state",
                    StepAction.Mixed.of(
                        new StepAction.ExternalActor(new ExternalActorEvent.KillManagedService("orders")),
                        new StepAction.Assert("orders is reported as CRASHED with clean stderr logs", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            assertNoSecretLeak(status.stdout(), "status --json");
                            JsonValue.JsonObject root = parseJsonObject(status.stdout(), "status --json");
                            JsonValue.JsonObject orders = findServiceRow(root, "orders");
                            assertEquals("CRASHED", requireString(orders, "health", "orders"), "orders health");

                            CliResult errLogs = assertCliOk(world.runProcomp("logs", "orders", "--err"), "logs --err");
                            assertNoSecretLeak(errLogs.stdout(), "logs orders --err");
                        })
                    ),
                    ShowcaseSpec.of(
                        "An externally killed Managed Process is surfaced as CRASHED in status",
                        "status"
                    )
                ),
                ScenarioStep.showcased(
                    "stale-artifact",
                    "Recover CRASHED orders via start and rebuild Artifact to trigger Stale",
                    StepAction.Mixed.of(
                        StepAction.ProcompCommand.of("start", "orders"),
                        new StepAction.ExternalActor(new ExternalActorEvent.RebuildArtifact("orders")),
                        new StepAction.Assert("orders recovers to HEALTHY and reports stale=true in status and inspect", world -> {
                            CliResult status = assertCliOk(world.runProcomp("status", "--json"), "status --json");
                            assertNoSecretLeak(status.stdout(), "status --json");
                            JsonValue.JsonObject statusRoot = parseJsonObject(status.stdout(), "status --json");
                            JsonValue.JsonObject ordersRow = findServiceRow(statusRoot, "orders");
                            assertEquals("HEALTHY", requireString(ordersRow, "health", "orders"),
                                "orders must recover from CRASHED to HEALTHY");
                            assertTrue(requireBoolean(ordersRow, "stale", "orders status"),
                                "orders must be marked stale=true in status --json");

                            CliResult inspect = assertCliOk(world.runProcomp("inspect", "orders", "--json"), "inspect orders");
                            assertNoSecretLeak(inspect.stdout(), "inspect orders --json");
                            JsonValue.JsonObject inspectRoot = parseJsonObject(inspect.stdout(), "inspect orders --json");
                            assertTrue(requireBoolean(inspectRoot, "stale", "orders inspect"),
                                "orders must be marked stale=true in inspect --json");
                        })
                    ),
                    ShowcaseSpec.of(
                        "Recovering orders and rebuilding its Artifact flags Stale: YES in status and inspect",
                        "inspect", "orders"
                    )
                ),
                ScenarioStep.showcased(
                    "logs-streaming",
                    "Append stdout/stderr log lines and verify raw logs -n, --err, --path, and secret hygiene",
                    StepAction.Mixed.of(
                        new StepAction.ExternalActor(
                            new ExternalActorEvent.AppendServiceLog("orders", "order-processed id=101", false)
                        ),
                        new StepAction.ExternalActor(
                            new ExternalActorEvent.AppendServiceLog("orders", "order-processed id=102", false)
                        ),
                        new StepAction.ExternalActor(
                            new ExternalActorEvent.AppendServiceLog("orders", "warn: retryable upstream timeout", true)
                        ),
                        new StepAction.Assert("logs -n, --err, and --path return raw output with secret hygiene", world -> {
                            CliResult tail2 = assertCliOk(world.runProcomp("logs", "orders", "-n", "2"), "logs -n 2");
                            assertNoSecretLeak(tail2.stdout(), "logs orders -n 2");
                            String normalized = tail2.stdout().replace("\r\n", "\n");
                            assertEquals("order-processed id=101\norder-processed id=102\n", normalized,
                                "logs -n 2 should return exact last 2 raw stdout lines");

                            CliResult errLogs = assertCliOk(world.runProcomp("logs", "orders", "--err"), "logs --err");
                            assertNoSecretLeak(errLogs.stdout(), "logs orders --err");
                            assertTrue(errLogs.stdout().contains("warn: retryable upstream timeout"),
                                "logs --err should contain appended stderr line: " + errLogs.stdout());

                            CliResult logPath = assertCliOk(world.runProcomp("logs", "orders", "--path"), "logs --path");
                            assertTrue(logPath.stdout().trim().endsWith("orders.out.log"),
                                "logs --path should end with orders.out.log: " + logPath.stdout());
                        })
                    ),
                    ShowcaseSpec.of(
                        "Raw stdout/stderr log inspection via logs -n, --err, and --path",
                        "logs", "orders", "-n", "5"
                    )
                )
            )
        );
    }
}
