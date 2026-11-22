package e2e.scenarios.repl;

import e2e.scenarios.engine.LagProfile;
import e2e.scenarios.inmemory.InMemoryWorld;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScenarioReplTest {

    private String runRepl(String input, InMemoryWorld world, Optional<String> initialTarget, LagProfile initialLag) {
        StringWriter outBuf = new StringWriter();
        ScenarioRepl repl = new ScenarioRepl(new StringReader(input), new PrintWriter(outBuf, true), world);
        repl.run(initialTarget, initialLag);
        return outBuf.toString().replace("\r\n", "\n");
    }

    @Test
    void executesRealProcompCommandsAndPrintsExitCodeOnFailure() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            String output = runRepl(
                """
                status
                procomp route orders local
                route nonexistent local
                :quit
                """,
                world,
                Optional.of("hybrid-routing/bootstrap"),
                LagProfile.NONE
            );

            assertTrue(output.contains("orders"), "Expected status output to list orders:\n" + output);
            assertTrue(output.contains("LOCAL") || output.contains("orders"), "Expected route command output:\n" + output);
            assertTrue(output.contains("[exit "), "Expected non-zero exit code for invalid service:\n" + output);
        }
    }

    @Test
    void supportsQuotedArgumentsInCliCommands() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            String output = runRepl(
                """
                profile use "orders-local"
                route 'pricing' "local"
                status --json
                :exit
                """,
                world,
                Optional.of("hybrid-routing/bootstrap"),
                LagProfile.NONE
            );

            assertFalse(output.contains("[exit "), "Expected all quoted commands to succeed:\n" + output);
            assertTrue(output.contains("\"service\":\"pricing\""), "Expected JSON status output:\n" + output);
        }
    }

    @Test
    void listsScenariosAndStepsAndSupportsJumpAndNext() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            String output = runRepl(
                """
                :scenarios
                :steps
                :jump hybrid-routing/gateway-ready
                :next
                status --json
                :quit
                """,
                world,
                Optional.empty(),
                LagProfile.NONE
            );

            assertTrue(output.contains("hybrid-routing"), "Expected hybrid-routing in :scenarios:\n" + output);
            assertTrue(output.contains("ownership-and-conflicts"), "Expected ownership-and-conflicts in :scenarios:\n" + output);
            assertTrue(output.contains("failures-logs-and-stale"), "Expected failures-logs-and-stale in :scenarios:\n" + output);
            assertTrue(output.contains("large-estate"), "Expected large-estate in :scenarios:\n" + output);
            assertTrue(output.contains("gateway-ready"), "Expected gateway-ready in :steps:\n" + output);
            assertTrue(output.contains("hybrid-running"), "Expected :next to advance to hybrid-running:\n" + output);
            assertTrue(output.contains("\"health\":\"HEALTHY\""), "Expected orders HEALTHY after :next:\n" + output);
        }
    }

    @Test
    void switchesLagProfileInteractivelyAndPreservesLagAcrossJumpAndReset() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            String output = runRepl(
                """
                :lag fast
                :jump hybrid-routing/bootstrap
                :lag realistic
                :reset
                :lag none
                :quit
                """,
                world,
                Optional.of("hybrid-routing/bootstrap"),
                LagProfile.NONE
            );

            assertTrue(output.contains("FAST"), "Expected FAST lag confirmation:\n" + output);
            assertTrue(output.contains("REALISTIC"), "Expected REALISTIC lag confirmation:\n" + output);
            assertTrue(output.contains("NONE"), "Expected NONE lag confirmation:\n" + output);
            assertEquals(LagProfile.NONE, world.lag());
        }
    }

    @Test
    void triggersExternalActorsFromReplMetaCommands() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            String output = runRepl(
                """
                :jump hybrid-routing/hybrid-running
                :actor unmanaged-pricing
                :actor kill-orders
                :actor rebuild-notifications
                status --json
                :quit
                """,
                world,
                Optional.empty(),
                LagProfile.NONE
            );

            assertTrue(output.contains("\"owner\":\"UNMANAGED\""), "Expected pricing UNMANAGED:\n" + output);
            assertTrue(output.contains("\"health\":\"CRASHED\""), "Expected orders CRASHED:\n" + output);
            assertTrue(output.contains("\"stale\":true"), "Expected notifications stale=true:\n" + output);
        }
    }

    @Test
    void triggersConflictAndUnmanagedGatewayActors() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            String output = runRepl(
                """
                :jump ownership-and-conflicts/bootstrap
                :actor conflict-orders
                :actor unmanaged-gateway
                inspect orders --json
                gateway status --json
                :quit
                """,
                world,
                Optional.empty(),
                LagProfile.NONE
            );

            assertTrue(output.contains("\"owner\":\"CONFLICT\""), "Expected orders CONFLICT:\n" + output);
            assertTrue(output.contains("\"owner\":\"UNMANAGED\""), "Expected gateway UNMANAGED:\n" + output);
        }
    }

    @Test
    void helpAndInvalidInputsDoNotCrashRepl() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            String output = runRepl(
                """
                :help
                :unknown-cmd
                :jump does-not-exist
                :lag turbo
                :actor unknown-event
                status
                :quit
                """,
                world,
                Optional.of("hybrid-routing/bootstrap"),
                LagProfile.NONE
            );

            assertTrue(output.contains(":scenarios"), "Expected :help output:\n" + output);
            assertTrue(output.contains("Unknown"), "Expected unknown command message:\n" + output);
            assertTrue(output.contains("orders"), "Expected REPL to keep running after errors:\n" + output);
        }
    }
}
