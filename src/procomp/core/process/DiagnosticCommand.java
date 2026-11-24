package procomp.core.process;

import java.util.Objects;
import java.util.Optional;

/**
 * Copy-pasteable, shell-agnostic terminal diagnostic command suggested during {@link InspectionReport} creation.
 */
public record DiagnosticCommand(
    String label,
    String command,
    Optional<String> note
) {
    public DiagnosticCommand {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must be non-blank");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must be non-blank");
        }
        Objects.requireNonNull(note, "note must not be null");
    }

    public static DiagnosticCommand of(String label, String command) {
        return new DiagnosticCommand(label, command, Optional.empty());
    }

    public static DiagnosticCommand withNote(String label, String command, String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("note must be non-blank");
        }
        return new DiagnosticCommand(label, command, Optional.of(note));
    }
}
