package procomp.cli;

import procomp.Assert;
import procomp.fakes.FakeClock;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.fakes.RecordingObserver;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProgressObserver.ProgressEvent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

public final class LogsCliTest {

    public static void testPathFlagPrintsResolvedPathWithoutRequiringExistingFile() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = sampleConfig(cwd);
        var stateStore = new InMemoryStateStore();

        StringWriter out1 = new StringWriter();
        StringWriter err1 = new StringWriter();
        int code1 = Procomp.run(
            new String[]{"logs", "orders", "--path"},
            configSource,
            stateStore,
            new PrintWriter(out1, true),
            new PrintWriter(err1, true)
        );
        Assert.assertEquals(0, code1, "--path should exit 0 without log file");
        Assert.assertEquals("", err1.toString(), "no stderr on --path");
        Assert.assertEquals(
            stateStore.logPath("acme", ProcessKey.service("orders"), false) + System.lineSeparator(),
            out1.toString(),
            "--path prints exact stdout log path"
        );

        StringWriter out2 = new StringWriter();
        StringWriter err2 = new StringWriter();
        int code2 = Procomp.run(
            new String[]{"logs", "--err", "--path", "orders"},
            configSource,
            stateStore,
            new PrintWriter(out2, true),
            new PrintWriter(err2, true)
        );
        Assert.assertEquals(0, code2, "--err --path should exit 0");
        Assert.assertEquals(
            stateStore.logPath("acme", ProcessKey.service("orders"), true) + System.lineSeparator(),
            out2.toString(),
            "--err --path prints exact stderr log path"
        );
    }

    public static void testReadsRawLogsWithDefaultAndCustomLineCounts() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = sampleConfig(cwd);
        var stateStore = new InMemoryStateStore();
        Path outLog = stateStore.logPath("acme", ProcessKey.service("orders"), false);
        Path errLog = stateStore.logPath("acme", ProcessKey.service("orders"), true);

        StringBuilder manyLines = new StringBuilder();
        for (int i = 1; i <= 105; i++) {
            manyLines.append("line-").append(i).append("\n");
        }
        stateStore.appendLog(outLog, manyLines.toString());
        stateStore.appendLog(errLog, "err-1\r\nerr-2\r\nerr-3\r\n");

        StringWriter outDefault = new StringWriter();
        StringWriter errDefault = new StringWriter();
        int codeDefault = Procomp.run(
            new String[]{"logs", "orders"},
            configSource,
            stateStore,
            new PrintWriter(outDefault, true),
            new PrintWriter(errDefault, true)
        );
        Assert.assertEquals(0, codeDefault, "default logs read should exit 0");
        Assert.assertNotContains(outDefault.toString(), "line-5\n", "default tail omits lines older than 100");
        Assert.assertContains(outDefault.toString(), "line-6\n", "default tail includes 100th line from end");
        Assert.assertContains(outDefault.toString(), "line-105\n", "default tail includes last line");

        StringWriter outN2 = new StringWriter();
        StringWriter errN2 = new StringWriter();
        int codeN2 = Procomp.run(
            new String[]{"logs", "orders", "-n", "2"},
            configSource,
            stateStore,
            new PrintWriter(outN2, true),
            new PrintWriter(errN2, true)
        );
        Assert.assertEquals(0, codeN2, "-n 2 exits 0");
        Assert.assertEquals("line-104\nline-105\n", outN2.toString(), "-n 2 prints exact last 2 raw lines");

        StringWriter outErr = new StringWriter();
        StringWriter errErr = new StringWriter();
        int codeErr = Procomp.run(
            new String[]{"logs", "orders", "--err", "--lines=2"},
            configSource,
            stateStore,
            new PrintWriter(outErr, true),
            new PrintWriter(errErr, true)
        );
        Assert.assertEquals(0, codeErr, "--err --lines=2 exits 0");
        Assert.assertEquals("err-2\r\nerr-3\r\n", outErr.toString(), "--err --lines=2 preserves CRLF");
    }

    public static void testFollowStreamsChunksViaObserverAndWriter() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = sampleConfig(cwd);
        var stateStore = new InMemoryStateStore();
        Path outLog = stateStore.logPath("acme", ProcessKey.service("orders"), false);
        stateStore.appendLog(outLog, "boot1\nboot2\n");

        var observer = new RecordingObserver();
        var clock = new FakeClock().onSleep(step -> switch (step) {
            case 1 -> {
                stateStore.appendLog(outLog, "ready\n");
                yield true;
            }
            case 2 -> {
                stateStore.appendLog(outLog, "req-1\n");
                yield true;
            }
            default -> false;
        });

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int code = Procomp.run(
            new String[]{"logs", "orders", "-n", "1", "-f"},
            configSource,
            stateStore,
            clock,
            observer,
            new PrintWriter(outBuf, true),
            new PrintWriter(errBuf, true)
        );

        Assert.assertEquals(0, code, "-f exits 0 on clean stop");
        Assert.assertEquals("", errBuf.toString(), "no stderr on successful follow");
        Assert.assertEquals("boot2\nready\nreq-1\n", outBuf.toString(), "stdout streams initial tail plus appended lines");
        Assert.assertEquals(
            List.of(
                new ProgressEvent.LogLine("orders", false, "boot2\n"),
                new ProgressEvent.LogLine("orders", false, "ready\n"),
                new ProgressEvent.LogLine("orders", false, "req-1\n")
            ),
            observer.events(),
            "RecordingObserver captures streamed LogLine events"
        );
    }

    public static void testFailsFastOnInvalidCliArgsUnknownServiceAndMissingLogFile() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = sampleConfig(cwd);
        var stateStore = new InMemoryStateStore();

        assertExitCode(new String[]{"logs"}, configSource, stateStore, 2, "missing <service>");
        assertExitCode(new String[]{"logs", "orders", "payments"}, configSource, stateStore, 2, "multi-service rejected");
        assertExitCode(new String[]{"logs", "orders", "-n"}, configSource, stateStore, 2, "missing -n value");
        assertExitCode(new String[]{"logs", "orders", "-n", "-1"}, configSource, stateStore, 2, "negative -n rejected");
        assertExitCode(new String[]{"logs", "orders", "-n", "abc"}, configSource, stateStore, 2, "non-integer -n rejected");
        assertExitCode(new String[]{"logs", "orders", "--lines=1.5"}, configSource, stateStore, 2, "decimal --lines rejected");
        assertExitCode(new String[]{"logs", "orders", "--json"}, configSource, stateStore, 2, "unsupported --json rejected");

        StringWriter unknownOut = new StringWriter();
        StringWriter unknownErr = new StringWriter();
        int unknownCode = Procomp.run(
            new String[]{"logs", "nope"},
            configSource,
            stateStore,
            new PrintWriter(unknownOut, true),
            new PrintWriter(unknownErr, true)
        );
        Assert.assertEquals(1, unknownCode, "unknown service exits 1");
        Assert.assertContains(unknownErr.toString(), "UNKNOWN_SERVICE", "stderr reports UNKNOWN_SERVICE");

        StringWriter missingOut = new StringWriter();
        StringWriter missingErr = new StringWriter();
        int missingCode = Procomp.run(
            new String[]{"logs", "orders"},
            configSource,
            stateStore,
            new PrintWriter(missingOut, true),
            new PrintWriter(missingErr, true)
        );
        Assert.assertEquals(1, missingCode, "missing log file exits 1");
        Assert.assertContains(missingErr.toString(), "LOG_NOT_FOUND", "stderr reports LOG_NOT_FOUND");
    }

    private static void assertExitCode(
        String[] args,
        InMemoryConfigSource configSource,
        InMemoryStateStore stateStore,
        int expectedCode,
        String label
    ) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Procomp.run(args, configSource, stateStore, new PrintWriter(out, true), new PrintWriter(err, true));
        Assert.assertEquals(expectedCode, code, label);
        Assert.assertFalse(err.toString().isBlank(), label + " should write an error message to stderr");
    }

    private static InMemoryConfigSource sampleConfig(Path cwd) {
        return new InMemoryConfigSource(cwd)
            .withFile(cwd.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "." },
                    "payments": { "runtime": "java", "port": 9002, "workingDir": "." }
                  }
                }
                """);
    }
}
