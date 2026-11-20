package procomp.core.process;

import java.util.Objects;
import java.util.Optional;

/**
 * Ancestor process entry in an OS process's parent chain.
 */
public record ParentProcess(
    long pid,
    Optional<String> commandLine,
    Optional<String> executable
) {
    public ParentProcess {
        Objects.requireNonNull(commandLine, "commandLine must not be null");
        Objects.requireNonNull(executable, "executable must not be null");
    }
}
