package e2e.scenarios.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic executor for {@link Scenario}s enforcing dual-world compatibility and
 * fast-forward replay invariants on step jumps.
 */
public final class ScenarioRunner {

    private static final long CLOCK_ADVANCE_PER_STEP_SECONDS = 5L;

    public record StepExecutionResult(ScenarioStep step, List<CliResult> cliResults) {
        public StepExecutionResult {
            Objects.requireNonNull(step, "step must not be null");
            cliResults = List.copyOf(cliResults);
        }
    }

    public record ScenarioRunResult(
        Scenario scenario,
        WorldKind worldKind,
        int lastStepIndex,
        List<StepExecutionResult> stepResults
    ) {
        public ScenarioRunResult {
            Objects.requireNonNull(scenario, "scenario must not be null");
            Objects.requireNonNull(worldKind, "worldKind must not be null");
            stepResults = List.copyOf(stepResults);
        }
    }

    public ScenarioRunResult runAll(Scenario scenario, ScenarioWorld world) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(world, "world must not be null");
        validateWorldCompatibility(scenario, world);

        world.reset(scenario.estateConfig());
        List<StepExecutionResult> results = new ArrayList<>(scenario.steps().size());
        for (int i = 0; i < scenario.steps().size(); i++) {
            results.add(executeStep(scenario.steps().get(i), world));
        }
        return new ScenarioRunResult(scenario, world.worldKind(), scenario.steps().size() - 1, results);
    }

    /**
     * Resets {@code world} cleanly, disables simulated lag during fast-forward replay of steps
     * {@code 0..targetIndex}, and restores the previously active {@link LagProfile} on exit.
     */
    public ScenarioRunResult jumpToStep(Scenario scenario, ScenarioWorld world, String stepId) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(world, "world must not be null");
        Objects.requireNonNull(stepId, "stepId must not be null");
        validateWorldCompatibility(scenario, world);

        int targetIndex = scenario.stepIndex(stepId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown step '" + stepId + "' in scenario '" + scenario.id() + "'"
            ));

        LagProfile activeLag = world.lag();
        world.reset(scenario.estateConfig());
        world.setLag(LagProfile.NONE);
        try {
            List<StepExecutionResult> results = new ArrayList<>(targetIndex + 1);
            for (int i = 0; i <= targetIndex; i++) {
                results.add(executeStep(scenario.steps().get(i), world));
            }
            return new ScenarioRunResult(scenario, world.worldKind(), targetIndex, results);
        } finally {
            world.setLag(activeLag);
        }
    }

    /**
     * Executes a single step at {@code stepIndex} against the current state of {@code world}
     * using its currently configured {@link LagProfile}.
     */
    public StepExecutionResult advanceStep(Scenario scenario, ScenarioWorld world, int stepIndex) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(world, "world must not be null");
        validateWorldCompatibility(scenario, world);
        if (stepIndex < 0 || stepIndex >= scenario.steps().size()) {
            throw new IllegalArgumentException(
                "Step index " + stepIndex + " out of range for scenario '" + scenario.id()
                    + "' (0.." + (scenario.steps().size() - 1) + ")"
            );
        }
        return executeStep(scenario.steps().get(stepIndex), world);
    }

    private static void validateWorldCompatibility(Scenario scenario, ScenarioWorld world) {
        if (!scenario.dualWorld() && world.worldKind() == WorldKind.REAL_TOPOLOGY) {
            throw new UnsupportedOperationException(
                "Scenario '" + scenario.id() + "' is InMemoryWorld-only (dualWorld=false) and cannot run on "
                    + world.worldKind()
            );
        }
    }

    private static StepExecutionResult executeStep(ScenarioStep step, ScenarioWorld world) {
        List<CliResult> cliResults = new ArrayList<>();
        executeAction(step, step.action(), world, cliResults);
        world.advanceClockSeconds(CLOCK_ADVANCE_PER_STEP_SECONDS);
        return new StepExecutionResult(step, cliResults);
    }

    private static void executeAction(
        ScenarioStep step,
        StepAction action,
        ScenarioWorld world,
        List<CliResult> cliResults
    ) {
        switch (action) {
            case StepAction.ProcompCommand(List<String> args) -> {
                CliResult res = world.runProcomp(args);
                cliResults.add(res);
                verifyExitCode(step, args, res);
            }
            case StepAction.ProcompBatch(List<List<String>> commands) -> {
                for (int i = 0; i < commands.size(); i++) {
                    List<String> cmd = commands.get(i);
                    CliResult res = world.runProcomp(cmd);
                    cliResults.add(res);
                    int expected = (i == commands.size() - 1) ? step.expectedExitCode().orElse(0) : 0;
                    if (res.exitCode() != expected) {
                        throw new AssertionError(formatExitMismatch(step, cmd, expected, res));
                    }
                }
            }
            case StepAction.ExternalActor(ExternalActorEvent event) -> world.triggerActor(event);
            case StepAction.Mixed(List<StepAction> actions) -> {
                for (StepAction sub : actions) {
                    executeAction(step, sub, world, cliResults);
                }
            }
            case StepAction.Assert(String description, java.util.function.Consumer<ScenarioWorld> assertion) -> {
                try {
                    assertion.accept(world);
                } catch (AssertionError e) {
                    throw new AssertionError(
                        "Step '" + step.id() + "' assertion failed (" + description + "): " + e.getMessage(),
                        e
                    );
                }
            }
        }
    }

    private static void verifyExitCode(ScenarioStep step, List<String> args, CliResult res) {
        int expected = step.expectedExitCode().orElse(0);
        if (res.exitCode() != expected) {
            throw new AssertionError(formatExitMismatch(step, args, expected, res));
        }
    }

    private static String formatExitMismatch(ScenarioStep step, List<String> args, int expected, CliResult actual) {
        return "Step '" + step.id() + "' command `procomp " + String.join(" ", args)
            + "` exited with " + actual.exitCode() + " (expected " + expected + ").\n"
            + "stdout:\n" + actual.stdout() + "\n"
            + "stderr:\n" + actual.stderr();
    }
}
