package e2e.scenarios.engine;

import java.util.Objects;
import java.util.Optional;

/**
 * Named, addressable step within a {@link Scenario}.
 */
public record ScenarioStep(
    String id,
    String label,
    StepAction action,
    Optional<Integer> expectedExitCode,
    Optional<ShowcaseSpec> showcase
) {
    public ScenarioStep {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("step id must be non-blank");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("step label must be non-blank");
        }
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(expectedExitCode, "expectedExitCode must not be null");
        Objects.requireNonNull(showcase, "showcase must not be null");
    }

    public static ScenarioStep of(String id, String label, StepAction action) {
        return new ScenarioStep(id, label, action, Optional.empty(), Optional.empty());
    }

    public static ScenarioStep of(String id, String label, StepAction action, int expectedExitCode) {
        return new ScenarioStep(id, label, action, Optional.of(expectedExitCode), Optional.empty());
    }

    public static ScenarioStep showcased(String id, String label, StepAction action, ShowcaseSpec showcase) {
        return new ScenarioStep(id, label, action, Optional.empty(), Optional.of(showcase));
    }

    public static ScenarioStep showcased(
        String id,
        String label,
        StepAction action,
        int expectedExitCode,
        ShowcaseSpec showcase
    ) {
        return new ScenarioStep(id, label, action, Optional.of(expectedExitCode), Optional.of(showcase));
    }
}
