package procomp.core.process;

import java.nio.charset.StandardCharsets;
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

    /**
     * Slices up to {@code maxLines} complete UTF-8 lines backward from a raw byte window
     * spanning {@code [windowStartOffset, windowStartOffset + bytes.length)}.
     *
     * <p>When {@code windowStartOffset > 0} and the window contains fewer than {@code maxLines}
     * line breaks, any partial leading line before the first newline is excluded so the caller
     * can fetch it intact on the next backward page. If the entire window contains no internal
     * newline (an oversized single line), leading UTF-8 continuation bytes are skipped so
     * pagination still makes forward progress without splitting a multi-byte codepoint.</p>
     */
    public static BackwardSlice sliceBackward(byte[] bytes, long windowStartOffset, int maxLines) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        if (windowStartOffset < 0) {
            throw new IllegalArgumentException("windowStartOffset must be non-negative: " + windowStartOffset);
        }
        if (maxLines < 0) {
            throw new IllegalArgumentException("maxLines must be non-negative: " + maxLines);
        }

        long endOffset = windowStartOffset + bytes.length;
        if (bytes.length == 0 || maxLines == 0) {
            return new BackwardSlice("", endOffset, endOffset, endOffset > 0);
        }

        int end = bytes.length;
        int startScan = bytes[end - 1] == '\n' ? end - 2 : end - 1;
        int seenLineBreaks = 0;
        for (int i = startScan; i >= 0; i--) {
            if (bytes[i] == '\n') {
                seenLineBreaks++;
                if (seenLineBreaks == maxLines) {
                    int sliceStart = i + 1;
                    long startOffset = windowStartOffset + sliceStart;
                    String text = new String(bytes, sliceStart, end - sliceStart, StandardCharsets.UTF_8);
                    return new BackwardSlice(text, startOffset, endOffset, startOffset > 0);
                }
            }
        }

        if (windowStartOffset == 0L) {
            String text = new String(bytes, 0, end, StandardCharsets.UTF_8);
            return new BackwardSlice(text, 0L, endOffset, false);
        }

        int firstNl = -1;
        int limit = bytes[end - 1] == '\n' ? end - 1 : end;
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == '\n') {
                firstNl = i;
                break;
            }
        }

        int sliceStart;
        if (firstNl >= 0) {
            sliceStart = firstNl + 1;
        } else {
            sliceStart = skipLeadingUtf8ContinuationBytes(bytes);
        }
        long startOffset = windowStartOffset + sliceStart;
        String text = new String(bytes, sliceStart, end - sliceStart, StandardCharsets.UTF_8);
        return new BackwardSlice(text, startOffset, endOffset, startOffset > 0);
    }

    private static int skipLeadingUtf8ContinuationBytes(byte[] bytes) {
        int i = 0;
        while (i < bytes.length && (bytes[i] & 0xC0) == 0x80) {
            i++;
        }
        return i;
    }

    public record BackwardSlice(String text, long startOffset, long endOffset, boolean hasOlder) {
        public BackwardSlice {
            Objects.requireNonNull(text, "text must not be null");
            if (startOffset < 0) {
                throw new IllegalArgumentException("startOffset must be non-negative: " + startOffset);
            }
            if (endOffset < startOffset) {
                throw new IllegalArgumentException("endOffset must be >= startOffset: " + endOffset + " < " + startOffset);
            }
        }
    }
}
