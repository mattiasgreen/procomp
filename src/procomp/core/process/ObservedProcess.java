package procomp.core.process;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure immutable snapshot of an OS process used by Discovery and Inspection.
 */
public record ObservedProcess(
    long pid,
    boolean alive,
    Optional<Instant> startTime,
    Optional<String> commandLine,
    List<String> arguments,
    List<ParentProcess> parentChain,
    Optional<Long> rssBytes,
    Optional<String> executable
) {
    public ObservedProcess {
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(commandLine, "commandLine must not be null");
        arguments = List.copyOf(arguments);
        parentChain = List.copyOf(parentChain);
        Objects.requireNonNull(rssBytes, "rssBytes must not be null");
        Objects.requireNonNull(executable, "executable must not be null");
    }
}
