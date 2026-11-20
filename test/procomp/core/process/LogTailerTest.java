package procomp.core.process;

import procomp.Assert;

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
}
