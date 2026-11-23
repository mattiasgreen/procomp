package e2e.scenarios.engine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata marking a {@link ScenarioStep} for deterministic Markdown demo capture.
 */
public record ShowcaseSpec(String caption, Optional<List<String>> inspectCommand) {

    public ShowcaseSpec {
        if (caption == null || caption.isBlank()) {
            throw new IllegalArgumentException("caption must be non-blank");
        }
        Objects.requireNonNull(inspectCommand, "inspectCommand must not be null");
        inspectCommand = inspectCommand.map(List::copyOf);
    }

    public static ShowcaseSpec of(String caption) {
        return new ShowcaseSpec(caption, Optional.empty());
    }

    public static ShowcaseSpec of(String caption, String... inspectCommand) {
        return new ShowcaseSpec(caption, Optional.of(List.of(inspectCommand)));
    }
}
