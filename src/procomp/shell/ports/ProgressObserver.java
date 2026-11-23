package procomp.shell.ports;

import java.util.Objects;

/**
 * Modality-supplied callback receiving progress updates and streamed log output from shell use cases.
 */
public interface ProgressObserver {

    void onEvent(ProgressEvent event);

    default boolean diagnosticsEnabled() {
        return false;
    }

    default void diagnostic(DiagnosticCategory category, String message) {
        if (diagnosticsEnabled()) {
            onEvent(new ProgressEvent.Diagnostic(category, message));
        }
    }

    static ProgressObserver noop() {
        return event -> {};
    }

    enum DiagnosticCategory {
        CONFIG("config"),
        DISCOVERY("discovery"),
        PROCESS("process"),
        GATEWAY("gateway");

        private final String label;

        DiagnosticCategory(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    sealed interface ProgressEvent permits ProgressEvent.StatusMessage, ProgressEvent.LogLine, ProgressEvent.Diagnostic {

        record StatusMessage(String target, String message) implements ProgressEvent {
            public StatusMessage {
                Objects.requireNonNull(target);
                Objects.requireNonNull(message);
            }
        }

        record LogLine(String service, boolean stderr, String line) implements ProgressEvent {
            public LogLine {
                Objects.requireNonNull(service);
                Objects.requireNonNull(line);
            }
        }

        record Diagnostic(DiagnosticCategory category, String message) implements ProgressEvent {
            public Diagnostic {
                Objects.requireNonNull(category);
                Objects.requireNonNull(message);
            }
        }
    }
}
