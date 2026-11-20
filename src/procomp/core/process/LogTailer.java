package procomp.core.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure raw log slicing helpers with zero I/O or newline rewriting.
 */
public final class LogTailer {

    private LogTailer() {}

    /**
     * Returns the last {@code lines} lines of {@code text}, preserving raw line endings verbatim.
     */
    public static String tailLines(String text, int lines) {
        Objects.requireNonNull(text, "text must not be null");
        if (lines < 0) {
            throw new IllegalArgumentException("lines must be non-negative: " + lines);
        }
        if (lines == 0 || text.isEmpty()) {
            return "";
        }
        int end = text.length();
        // A single trailing LF terminates the final line rather than starting a new empty line.
        int startScan = text.charAt(end - 1) == '\n' ? end - 2 : end - 1;
        int seenLineBreaks = 0;
        for (int i = startScan; i >= 0; i--) {
            if (text.charAt(i) == '\n') {
                seenLineBreaks++;
                if (seenLineBreaks == lines) {
                    return text.substring(i + 1);
                }
            }
        }
        return text;
    }

    /**
     * Splits raw text into line slices while retaining exact line endings and any trailing partial line.
     */
    public static List<String> splitRawLines(String text) {
        Objects.requireNonNull(text, "text must not be null");
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        int start = 0;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < len) {
            lines.add(text.substring(start));
        }
        return List.copyOf(lines);
    }
}
