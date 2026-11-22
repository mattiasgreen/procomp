package e2e.scenarios.engine;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Executable action carried by a {@link ScenarioStep}.
 */
public sealed interface StepAction permits
    StepAction.ProcompCommand,
    StepAction.ProcompBatch,
    StepAction.ExternalActor,
    StepAction.Mixed,
    StepAction.Assert {

    record ProcompCommand(List<String> args) implements StepAction {
        public ProcompCommand {
            if (args == null || args.isEmpty()) {
                throw new IllegalArgumentException("args must not be empty");
            }
            args = List.copyOf(args);
        }

        public static ProcompCommand of(String... args) {
            return new ProcompCommand(List.of(args));
        }
    }

    record ProcompBatch(List<List<String>> commands) implements StepAction {
        public ProcompBatch {
            if (commands == null || commands.isEmpty()) {
                throw new IllegalArgumentException("commands must not be empty");
            }
            commands = commands.stream()
                .map(cmd -> {
                    if (cmd == null || cmd.isEmpty()) {
                        throw new IllegalArgumentException("each command in ProcompBatch must not be empty");
                    }
                    return List.copyOf(cmd);
                })
                .toList();
        }
    }

    record ExternalActor(ExternalActorEvent event) implements StepAction {
        public ExternalActor {
            Objects.requireNonNull(event, "event must not be null");
        }
    }

    record Mixed(List<StepAction> actions) implements StepAction {
        public Mixed {
            if (actions == null || actions.isEmpty()) {
                throw new IllegalArgumentException("actions must not be empty");
            }
            actions = List.copyOf(actions);
        }

        public static Mixed of(StepAction... actions) {
            return new Mixed(List.of(actions));
        }
    }

    record Assert(String description, Consumer<ScenarioWorld> assertion) implements StepAction {
        public Assert {
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("description must be non-blank");
            }
            Objects.requireNonNull(assertion, "assertion must not be null");
        }
    }
}
