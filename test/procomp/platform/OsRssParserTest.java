package procomp.platform;

import procomp.Assert;

import java.util.Map;

public final class OsRssParserTest {

    public static void testParsesWindowsTasklistCsvAcrossLocales() {
        String output = String.join("\r\n",
            "\"java.exe\",\"4201\",\"Console\",\"1\",\"245,120 K\"",
            "\"java.exe\",\"4202\",\"Console\",\"1\",\"180\u00A0500 K\"",
            "\"node.exe\",\"4203\",\"Console\",\"1\",\"64.000 K\"",
            "\"wiremock,test.exe\",\"4204\",\"Console\",\"1\",\"92 100 K\"",
            "INFO: No tasks are running which match the specified criteria.",
            ""
        );

        Map<Long, Long> parsed = OsRssParser.parseWindowsTasklistCsv(output);
        Assert.assertEquals(4, parsed.size(), "parsed 4 valid rows");
        Assert.assertEquals(245_120L * 1024L, parsed.get(4201L), "US comma KiB -> bytes");
        Assert.assertEquals(180_500L * 1024L, parsed.get(4202L), "Swedish/French NBSP KiB -> bytes");
        Assert.assertEquals(64_000L * 1024L, parsed.get(4203L), "German dot KiB -> bytes");
        Assert.assertEquals(92_100L * 1024L, parsed.get(4204L), "Image name with comma + space separator");
    }

    public static void testParsesPosixPsRssOutput() {
        String output = String.join("\n",
            "  4201  245120",
            "4202\t180500",
            "   invalid line",
            "  -5  100",
            "  4203  0",
            ""
        );

        Map<Long, Long> parsed = OsRssParser.parsePosixPsRss(output);
        Assert.assertEquals(3, parsed.size(), "parsed 3 valid POSIX rows");
        Assert.assertEquals(245_120L * 1024L, parsed.get(4201L), "PID 4201");
        Assert.assertEquals(180_500L * 1024L, parsed.get(4202L), "PID 4202");
        Assert.assertEquals(0L, parsed.get(4203L), "PID 4203 with 0 KiB");
    }
}
