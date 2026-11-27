package procomp.shell.ports;

import java.util.Objects;

/**
 * Backward-paginated raw UTF-8 log slice with exact byte offsets for cursor-based loading.
 */
public record LogPage(
    String text,
    long startOffset,
    long endOffset,
    boolean hasOlder,
    boolean truncated
) {

    public LogPage {
        Objects.requireNonNull(text, "text must not be null");
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must be non-negative: " + startOffset);
        }
        if (endOffset < startOffset) {
            throw new IllegalArgumentException("endOffset must be >= startOffset: " + endOffset + " < " + startOffset);
        }
    }
}
