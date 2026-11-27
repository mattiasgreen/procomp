package procomp.core.process;

import procomp.Assert;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class LogTailerTest {

    public static void testTailLinesPreservesRawLineEndingsAndHandlesEdgeCases() {
        Assert.assertEquals("", LogTailer.tailLines("", 100), "empty text returns empty string");
        Assert.assertEquals("", LogTailer.tailLines("a\nb\nc\n", 0), "0 lines returns empty string");

        Assert.assertEquals("a\nb\n", LogTailer.tailLines("a\nb\n", 10), "fewer than N lines returns all");
        Assert.assertEquals("a\nb\n", LogTailer.tailLines("a\nb\n", 2), "exact N lines returns all");
        Assert.assertEquals("b\n", LogTailer.tailLines("a\nb\n", 1), "trailing newline does not count as extra empty line");

        Assert.assertEquals("b\nc", LogTailer.tailLines("a\nb\nc", 2), "no trailing newline preserves absence of newline");
        Assert.assertEquals("c", LogTailer.tailLines("a\nb\nc", 1), "last line without trailing newline");
        Assert.assertEquals("single", LogTailer.tailLines("single", 1), "single line without newline");

        Assert.assertEquals(
            "line2\r\nline3\r\n",
            LogTailer.tailLines("line1\r\nline2\r\nline3\r\n", 2),
            "CRLF line endings are preserved verbatim"
        );
        Assert.assertEquals(
            "line2\r\nline3",
            LogTailer.tailLines("line1\r\nline2\r\nline3", 2),
            "CRLF without trailing newline is preserved verbatim"
        );

        Assert.assertEquals("\nb\n", LogTailer.tailLines("a\n\nb\n", 2), "blank middle line counts as a line");
        Assert.assertEquals("\n", LogTailer.tailLines("\n", 1), "single newline returns single newline");
        Assert.assertEquals("\n", LogTailer.tailLines("\n\n", 1), "double newline tail 1 returns single newline");
        Assert.assertEquals("\n\n", LogTailer.tailLines("\n\n", 2), "double newline tail 2 returns both newlines");

        boolean threw = false;
        try {
            LogTailer.tailLines("a\n", -1);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        Assert.assertTrue(threw, "negative line count throws IllegalArgumentException");
    }

    public static void testSplitRawLinesPreservesEndingsAndPartialLines() {
        Assert.assertEquals(List.of(), LogTailer.splitRawLines(""), "empty text produces no lines");
        Assert.assertEquals(List.of("a\n", "b\n"), LogTailer.splitRawLines("a\nb\n"), "LF lines keep trailing LF");
        Assert.assertEquals(List.of("a\r\n", "b\r\n"), LogTailer.splitRawLines("a\r\nb\r\n"), "CRLF lines keep trailing CRLF");
        Assert.assertEquals(List.of("a\n", "partial"), LogTailer.splitRawLines("a\npartial"), "partial trailing chunk preserved");
        Assert.assertEquals(List.of("\n", "\n"), LogTailer.splitRawLines("\n\n"), "blank lines preserved");
    }

    public static void testSliceBackwardAlignsLinesAndOffsetsWithoutDuplicates() {
        byte[] full = "line1\nline2\nline3\n".getBytes(StandardCharsets.UTF_8);

        // Tail 2 lines from end of file
        LogTailer.BackwardSlice page1 = LogTailer.sliceBackward(full, 0L, 2);
        Assert.assertEquals("line2\nline3\n", page1.text(), "first backward page returns last 2 lines");
        Assert.assertEquals(6L, page1.startOffset(), "startOffset points right after line1");
        Assert.assertEquals(18L, page1.endOffset(), "endOffset matches end of window");
        Assert.assertTrue(page1.hasOlder(), "older bytes remain before offset 6");

        // Page before offset 6 reaches beginning of file
        byte[] prefix = new byte[6];
        System.arraycopy(full, 0, prefix, 0, 6);
        LogTailer.BackwardSlice page2 = LogTailer.sliceBackward(prefix, 0L, 2);
        Assert.assertEquals("line1\n", page2.text(), "second backward page returns line1");
        Assert.assertEquals(0L, page2.startOffset(), "startOffset reaches 0");
        Assert.assertEquals(6L, page2.endOffset(), "endOffset matches beforeOffset");
        Assert.assertFalse(page2.hasOlder(), "no older bytes remain at offset 0");

        // Mid-file window capped by maxBytes drops partial leading line cleanly
        byte[] cappedWindow = "ne2\nline3\n".getBytes(StandardCharsets.UTF_8);
        LogTailer.BackwardSlice capped = LogTailer.sliceBackward(cappedWindow, 8L, 200);
        Assert.assertEquals("line3\n", capped.text(), "partial leading line dropped when windowStartOffset > 0");
        Assert.assertEquals(12L, capped.startOffset(), "startOffset aligns after first newline in window");
        Assert.assertEquals(18L, capped.endOffset(), "endOffset is windowStart + length");
        Assert.assertTrue(capped.hasOlder(), "older bytes remain");

        // Oversized single line exceeding window still makes forward progress and skips leading UTF-8 continuation bytes
        byte[] emojiBytes = "🚀hello\n".getBytes(StandardCharsets.UTF_8);
        // Drop first byte of 4-byte UTF-8 emoji so window starts mid-codepoint (3 continuation bytes + "hello\n")
        byte[] midUtf8 = new byte[emojiBytes.length - 1];
        System.arraycopy(emojiBytes, 1, midUtf8, 0, midUtf8.length);
        LogTailer.BackwardSlice oversized = LogTailer.sliceBackward(midUtf8, 100L, 10);
        Assert.assertEquals("hello\n", oversized.text(), "skips leading UTF-8 continuation bytes on oversized line");
        Assert.assertEquals(103L, oversized.startOffset(), "startOffset advances past skipped continuation bytes");
        Assert.assertTrue(oversized.hasOlder(), "older bytes remain");

        // Zero lines requested
        LogTailer.BackwardSlice zero = LogTailer.sliceBackward(full, 0L, 0);
        Assert.assertEquals("", zero.text(), "0 lines returns empty text");
        Assert.assertEquals(18L, zero.startOffset(), "0 lines keeps cursor at endOffset");
        Assert.assertEquals(18L, zero.endOffset(), "endOffset is window end");
        Assert.assertTrue(zero.hasOlder(), "bytes before offset 18 still exist");
    }
}
