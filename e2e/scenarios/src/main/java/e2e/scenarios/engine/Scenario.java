package e2e.scenarios.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reusable end-to-end workflow definition composed of named {@link ScenarioStep}s.
 */
public record Scenario(
    String id,
    String title,
    String description,
    boolean dualWorld,
    EstateSpec estateConfig,
    List<ScenarioStep> steps
) {
    public Scenario {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("scenario id must be non-blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("scenario title must be non-blank");
        }
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(estateConfig, "estateConfig must not be null");
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("scenario must have at least one step");
        }
        steps = List.copyOf(steps);
        Set<String> seen = new HashSet<>();
        for (ScenarioStep step : steps) {
            if (!seen.add(step.id())) {
                throw new IllegalArgumentException("Duplicate step id '" + step.id() + "' in scenario '" + id + "'");
            }
        }
    }

    public Optional<Integer> stepIndex(String stepId) {
        Objects.requireNonNull(stepId, "stepId must not be null");
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    public Optional<ScenarioStep> step(String stepId) {
        return stepIndex(stepId).map(steps::get);
    }
}
