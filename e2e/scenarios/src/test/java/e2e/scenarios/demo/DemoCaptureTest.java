package e2e.scenarios.demo;

import e2e.scenarios.catalog.ScenarioCatalog;
import e2e.scenarios.engine.Scenario;
import e2e.scenarios.inmemory.InMemoryConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DemoCaptureTest {

    private final DemoCapture capture = new DemoCapture();

    @Test
    void capturesStructuredMarkdownWithConsoleBlocksAndLfLineEndings() {
        Scenario scenario = ScenarioCatalog.hybridRouting();
        String md = capture.capture(scenario);

        assertTrue(md.contains("# Demo: " + scenario.title()), "Missing title:\n" + md);
        assertTrue(md.contains(scenario.description()), "Missing description:\n" + md);
        assertTrue(md.contains("## Step 1: "), "Missing Step 1 heading:\n" + md);
        assertTrue(md.contains("```console\n$ procomp "), "Missing console block with $ procomp prompt:\n" + md);
        assertFalse(md.contains("\r"), "Demo Markdown must use LF line endings only");
        assertTrue(md.endsWith("\n"), "Demo Markdown must end with LF");
    }

    @Test
    void captureIsByteForByteDeterministicAcrossRepeatedRunsAndLeaksNoSecrets() {
        for (Scenario scenario : ScenarioCatalog.all()) {
            String first = capture.capture(scenario);
            String second = capture.capture(scenario);

            assertEquals(first, second, "Demo capture was not deterministic for scenario '" + scenario.id() + "'");
            assertFalse(
                first.contains(InMemoryConfigSource.DEMO_SECRET_VALUE),
                "Demo capture leaked secret for scenario '" + scenario.id() + "'"
            );
            assertFalse(first.contains("\r"), "Demo capture contained CR for scenario '" + scenario.id() + "'");
        }
    }

    @Test
    void generateAndCheckReportFreshMissingAndStaleFiles(@TempDir Path tempDir) throws IOException {
        // Empty directory -> missing files
        DemoCapture.CheckResult missingResult = capture.checkAll(tempDir);
        assertFalse(missingResult.isFresh());
        assertEquals(ScenarioCatalog.all().size(), missingResult.missingFiles().size());

        // Generate -> fresh
        StringWriter genOut = new StringWriter();
        StringWriter genErr = new StringWriter();
        int genExit = DemoCaptureMain.run(
            new String[]{"--generate", tempDir.toString()},
            new PrintWriter(genOut, true),
            new PrintWriter(genErr, true)
        );
        assertEquals(0, genExit, "generate failed: " + genErr);

        StringWriter checkOut = new StringWriter();
        StringWriter checkErr = new StringWriter();
        int checkExit = DemoCaptureMain.run(
            new String[]{"--check", tempDir.toString()},
            new PrintWriter(checkOut, true),
            new PrintWriter(checkErr, true)
        );
        assertEquals(0, checkExit, "check failed on fresh demos: " + checkErr);

        // Mutate one file -> stale
        Path hybridFile = tempDir.resolve("hybrid-routing.md");
        String original = Files.readString(hybridFile, StandardCharsets.UTF_8);
        Files.writeString(hybridFile, original + "\nstale-line\n", StandardCharsets.UTF_8);

        StringWriter staleOut = new StringWriter();
        StringWriter staleErr = new StringWriter();
        int staleExit = DemoCaptureMain.run(
            new String[]{"--check", tempDir.toString()},
            new PrintWriter(staleOut, true),
            new PrintWriter(staleErr, true)
        );
        assertNotEquals(0, staleExit, "check must fail when a demo file is stale");
        String errText = staleErr.toString();
        assertTrue(errText.contains("hybrid-routing.md"), "Expected stale file name in diff summary:\n" + errText);
    }
}
