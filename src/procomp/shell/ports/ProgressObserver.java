package procomp.shell.ports;

import java.util.Objects;

/**
 * Modality-supplied callback receiving progress updates and streamed log output from shell use cases.
 */
public interface ProgressObserver {

    void onEvent(ProgressEvent event);

    static ProgressObserver noop() {
        return event -> {};
    }

    sealed interface ProgressEvent permits ProgressEvent.StatusMessage, ProgressEvent.LogLine {

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
    }
}
