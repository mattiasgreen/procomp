package procomp.shell.ports;

import java.util.Objects;

/**
 * Incremental raw log slice read from a log file alongside the next byte offset.
 */
public record LogChunk(String text, long nextOffset) {

    public LogChunk {
        Objects.requireNonNull(text, "text must not be null");
        if (nextOffset < 0) {
            throw new IllegalArgumentException("nextOffset must be non-negative: " + nextOffset);
        }
    }
}
