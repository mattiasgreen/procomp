package e2e.runner;

import e2e.scenarios.catalog.ScenarioCatalog;
import e2e.scenarios.engine.Scenario;
import e2e.scenarios.engine.ScenarioRunner;
import e2e.scenarios.engine.ScenarioStep;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interactive real-OS sandbox entrypoint ({@code :e2e:runner:sandbox}):
 * fast-forwards {@link RealTopologyWorld} to any dual-world {@code <scenario>[/<step>]},
 * generates local {@code p.cmd} / {@code ./p} wrapper shims pre-wired with {@code --config}
 * and {@code PROCOMP_STATE_DIR}, prints a DevEx cheat sheet, and pauses until {@code Enter}
 * or {@code Ctrl+C} with guaranteed process-tree teardown.
 */
public final class RealSandboxMain {

    public record SandboxArgs(String scenarioId, Optional<String> stepId) {
        public SandboxArgs {
            if (scenarioId == null || scenarioId.isBlank()) {
                throw new IllegalArgumentException("scenarioId must be non-blank");
            }
            Objects.requireNonNull(stepId, "stepId must not be null");
        }

        public static SandboxArgs parse(String[] args) {
            String scenario = ScenarioCatalog.HYBRID_ROUTING;
            Optional<String> step = Optional.empty();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--scenario".equals(arg) && i + 1 < args.length) {
                    scenario = args[++i];
                } else if (arg.startsWith("--scenario=")) {
                    scenario = arg.substring("--scenario=".length());
                } else if ("--step".equals(arg) && i + 1 < args.length) {
                    step = Optional.of(args[++i]);
                } else if (arg.startsWith("--step=")) {
                    step = Optional.of(arg.substring("--step=".length()));
                } else if (!arg.startsWith("-")) {
                    int slash = arg.indexOf('/');
                    if (slash > 0 && slash < arg.length() - 1) {
                        scenario = arg.substring(0, slash);
                        step = Optional.of(arg.substring(slash + 1));
                    } else {
                        scenario = arg;
                    }
                } else {
                    throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }
            return new SandboxArgs(scenario, step);
        }
    }

    private RealSandboxMain() {}

    public static void main(String[] args) throws Exception {
        SandboxArgs parsed = SandboxArgs.parse(args);
        Scenario scenario = ScenarioCatalog.byId(parsed.scenarioId())
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown scenario '" + parsed.scenarioId() + "'. Dual-world scenarios: " + dualWorldScenarioIds()
            ));
        if (!scenario.dualWorld()) {
            throw new IllegalArgumentException(
                "Scenario '" + scenario.id() + "' is InMemoryWorld-only (dualWorld=false). "
                    + "Dual-world scenarios: " + dualWorldScenarioIds()
            );
        }

        RealTopologyWorld world = RealTopologyWorld.fromSystemProperties();
        AtomicBoolean cleanedUp = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (cleanedUp.compareAndSet(false, true)) {
                System.out.println("\nTearing down RealTopologyWorld sandbox and all processes...");
                world.close();
                System.out.println("Cleanup complete.");
            }
        };
        Thread shutdownHook = new Thread(cleanup, "real-sandbox-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            ScenarioRunner runner = new ScenarioRunner();
            ScenarioRunner.ScenarioRunResult runResult;
            if (parsed.stepId().isPresent()) {
                System.out.println("Fast-forwarding RealTopologyWorld to " + scenario.id() + "/" + parsed.stepId().get() + "...");
                runResult = runner.jumpToStep(scenario, world, parsed.stepId().get());
            } else {
                System.out.println("Running full RealTopologyWorld scenario: " + scenario.id() + "...");
                runResult = runner.runAll(scenario, world);
            }

            ShimWriter.ShimPaths shims = ShimWriter.writeShims(
                world.sandboxDir(),
                world.repoRoot(),
                world.configFile(),
                world.stateDir(),
                world.javaExe()
            );

            ScenarioStep landed = scenario.steps().get(runResult.lastStepIndex());
            printCheatSheet(System.out, scenario, landed, world, shims);

            System.out.print("Press Enter (or Ctrl+C) to tear down all Managed and external processes... ");
            System.out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            reader.readLine();
        } finally {
            cleanup.run();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM already shutting down
            }
        }
    }

    static void printCheatSheet(
        PrintStream out,
        Scenario scenario,
        ScenarioStep landedStep,
        RealTopologyWorld world,
        ShimWriter.ShimPaths shims
    ) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String shimCmd = isWindows ? ".\\p.cmd" : "./p";

        out.println();
        out.println("========================================================================");
        out.println("  procomp RealTopologyWorld Sandbox Ready");
        out.println("========================================================================");
        out.println("  Scenario     : " + scenario.id() + " (" + scenario.title() + ")");
        out.println("  Step         : " + landedStep.id() + " — " + landedStep.label());
        out.println("  Sandbox Dir  : " + world.sandboxDir());
        out.println("  Config File  : " + world.configFile());
        out.println("  State Dir    : " + world.stateDir());
        out.println("  Gateway URL  : " + world.gatewayBaseUrl());
        out.println("  Remote URL   : " + world.remoteBaseUrl());
        out.print("  Services     : ");
        int idx = 0;
        for (Map.Entry<String, Integer> entry : world.servicePorts().entrySet()) {
            if (idx++ > 0) {
                out.print(", ");
            }
            out.print(entry.getKey() + "=:" + entry.getValue());
        }
        out.println();
        out.println("  Wrapper Shim : " + (isWindows ? shims.windowsCmd() : shims.posixSh()));
        out.println();
        out.println("  Open another terminal and run:");
        out.println("    cd \"" + world.sandboxDir() + "\"");
        out.println("    " + shimCmd + " status");
        out.println("    " + shimCmd + " inspect orders");
        out.println("    " + shimCmd + " route pricing local");
        out.println("    " + shimCmd + " gateway reconcile");
        out.println("    curl -X POST " + world.gatewayBaseUrl() + "/orders/create");
        out.println("========================================================================");
        out.println();
    }

    private static List<String> dualWorldScenarioIds() {
        return ScenarioCatalog.all().stream()
            .filter(Scenario::dualWorld)
            .map(Scenario::id)
            .toList();
    }
}
