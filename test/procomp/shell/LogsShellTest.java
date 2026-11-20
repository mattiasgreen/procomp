package procomp.shell;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.project.Project;
import procomp.fakes.FakeClock;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.fakes.RecordingObserver;
import procomp.shell.LogsShell.LogsRequest;
import procomp.shell.LogsShell.LogsResult;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProgressObserver.ProgressEvent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public final class LogsShellTest {

    public static void testResolvesPathWithoutRequiringFileAndSelectsOutOrErr() {
        Project project = sampleProject();
        var stateStore = new InMemoryStateStore();
        var shell = new LogsShell(stateStore, new FakeClock(), new RecordingObserver());

        LogsResult outRes = Assert.assertOk(
            shell.run(project, new LogsRequest("orders", false, true, 100, false)),
            "--path for stdout should succeed without log file"
        );
        Assert.assertEquals(
            new LogsResult.ResolvedPath(stateStore.logPath("acme", ProcessKey.service("orders"), false)),
            outRes,
            "resolved stdout path matches stateStore"
        );

        LogsResult errRes = Assert.assertOk(
            shell.run(project, new LogsRequest("orders", true, true, 100, false)),
            "--path --err should resolve stderr path"
        );
        Assert.assertEquals(
            new LogsResult.ResolvedPath(stateStore.logPath("acme", ProcessKey.service("orders"), true)),
            errRes,
            "resolved stderr path matches stateStore"
        );
    }

    public static void testReadsTailLinesAndFailsOnUnknownServiceOrMissingLog() {
        Project project = sampleProject();
        var stateStore = new InMemoryStateStore();
        Path outLog = stateStore.logPath("acme", ProcessKey.service("orders"), false);
        Path errLog = stateStore.logPath("acme", ProcessKey.service("orders"), true);
        stateStore.appendLog(outLog, "line1\nline2\nline3\n");
        stateStore.appendLog(errLog, "err1\r\nerr2\r\nerr3\r\n");

        var shell = new LogsShell(stateStore, new FakeClock(), new RecordingObserver());

        LogsResult tail2 = Assert.assertOk(
            shell.run(project, new LogsRequest("orders", false, false, 2, false)),
            "read last 2 stdout lines"
        );
        Assert.assertEquals(new LogsResult.RawTail(outLog, "line2\nline3\n"), tail2, "stdout tail 2");

        LogsResult errTail1 = Assert.assertOk(
            shell.run(project, new LogsRequest("orders", true, false, 1, false)),
            "read last 1 stderr line"
        );
        Assert.assertEquals(new LogsResult.RawTail(errLog, "err3\r\n"), errTail1, "stderr CRLF tail 1");

        LogsResult zeroTail = Assert.assertOk(
            shell.run(project, new LogsRequest("orders", false, false, 0, false)),
            "read 0 lines succeeds when file exists"
        );
        Assert.assertEquals(new LogsResult.RawTail(outLog, ""), zeroTail, "0 lines returns empty text");

        DomainError unknownSvc = Assert.assertErr(
            shell.run(project, new LogsRequest("billing", false, true, 100, false)),
            "unknown service must fail fast even with --path"
        );
        Assert.assertEquals("UNKNOWN_SERVICE", unknownSvc.code(), "unknown service error code");

        DomainError missingFile = Assert.assertErr(
            shell.run(project, new LogsRequest("payments", false, false, 100, false)),
            "missing log file fails fast on non-path read"
        );
        Assert.assertEquals("LOG_NOT_FOUND", missingFile.code(), "missing log error code");
    }

    public static void testFollowStreamsInitialTailAndIncrementalChunksAndHandlesTruncation() {
        Project project = sampleProject();
        var stateStore = new InMemoryStateStore();
        Path outLog = stateStore.logPath("acme", ProcessKey.service("orders"), false);
        stateStore.appendLog(outLog, "old1\nold2\nold3\n");

        var observer = new RecordingObserver();
        var clock = new FakeClock().onSleep(step -> switch (step) {
            case 1 -> {
                stateStore.appendLog(outLog, "new1\nnew2\n");
                yield true;
            }
            case 2 -> true; // no new bytes on this poll
            case 3 -> {
                stateStore.replaceLog(outLog, "reset\n");
                yield true;
            }
            default -> false;
        });

        var shell = new LogsShell(stateStore, clock, observer);
        LogsResult result = Assert.assertOk(
            shell.run(project, new LogsRequest("orders", false, false, 2, true)),
            "follow should complete cleanly when sleep stops"
        );
        Assert.assertEquals(new LogsResult.Followed(outLog, 6L), result, "final offset matches truncated content length");
        Assert.assertEquals(
            List.of(
                new ProgressEvent.LogLine("orders", false, "old2\n"),
                new ProgressEvent.LogLine("orders", false, "old3\n"),
                new ProgressEvent.LogLine("orders", false, "new1\n"),
                new ProgressEvent.LogLine("orders", false, "new2\n"),
                new ProgressEvent.LogLine("orders", false, "reset\n")
            ),
            observer.events(),
            "observer receives initial tail and incremental/truncated chunks in order"
        );
    }

    public static void testFollowSupportsMaxPollsHookAndFailsOnMissingLog() {
        Project project = sampleProject();
        var stateStore = new InMemoryStateStore();
        var observer = new RecordingObserver();
        var clock = new FakeClock();
        var shell = new LogsShell(stateStore, clock, observer);

        DomainError missing = Assert.assertErr(
            shell.run(project, new LogsRequest("orders", false, false, 10, true)),
            "follow on missing log fails immediately"
        );
        Assert.assertEquals("LOG_NOT_FOUND", missing.code(), "follow missing log error code");
        Assert.assertEquals(List.of(), observer.events(), "no events emitted on missing log");

        Path errLog = stateStore.logPath("acme", ProcessKey.service("orders"), true);
        stateStore.appendLog(errLog, "e1\n");
        AtomicInteger polls = new AtomicInteger(0);
        LogsResult bounded = Assert.assertOk(
            shell.run(
                project,
                new LogsRequest("orders", true, false, 10, true),
                Duration.ofMillis(50),
                () -> {
                    int n = polls.getAndIncrement();
                    if (n == 1) {
                        stateStore.appendLog(errLog, "e2\n");
                    }
                    return n < 2;
                }
            ),
            "bounded keepFollowing predicate stops follow deterministically"
        );
        Assert.assertEquals(new LogsResult.Followed(errLog, 6L), bounded, "bounded follow final offset");
        Assert.assertEquals(
            List.of(
                new ProgressEvent.LogLine("orders", true, "e1\n"),
                new ProgressEvent.LogLine("orders", true, "e2\n")
            ),
            observer.events(),
            "bounded follow streams stderr lines"
        );
    }

    private static Project sampleProject() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var source = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
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
        return Assert.assertOk(new ProjectLoader(source).load(Optional.empty()), "sample project loads");
    }
}
