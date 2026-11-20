package procomp.platform;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.RecordingObserver;
import procomp.shell.LogsShell;
import procomp.shell.LogsShell.LogsRequest;
import procomp.shell.LogsShell.LogsResult;
import procomp.shell.ProjectLoader;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProgressObserver.ProgressEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

public final class FsLogsFollowTest {

    public static void testFollowStreamsRealFileAppendsAndTruncationAndStopsOnInterrupt() throws Exception {
        Path tempRoot = Files.createTempDirectory("procomp-logs-follow-test");
        try {
            FsStateStore store = new FsStateStore(
                System.getProperty("os.name"),
                Map.of("PROCOMP_STATE_DIR", tempRoot.toString()),
                tempRoot
            );
            Project project = sampleProject(tempRoot);
            Path outLog = store.logPath("acme", ProcessKey.service("orders"), false);
            Files.createDirectories(outLog.getParent());
            Files.writeString(outLog, "boot-1\nboot-2\nboot-3\n", StandardCharsets.UTF_8);

            RecordingObserver observer = new RecordingObserver();
            LogsShell shell = new LogsShell(store, new SystemClock(), observer);
            AtomicReference<Result<LogsResult, DomainError>> resultRef = new AtomicReference<>();

            Thread follower = new Thread(() -> {
                Result<LogsResult, DomainError> res = shell.run(
                    project,
                    new LogsRequest("orders", false, false, 2, true),
                    Duration.ofMillis(20),
                    () -> !Thread.currentThread().isInterrupted()
                );
                resultRef.set(res);
            }, "logs-follow-test");
            follower.start();

            awaitCondition(() -> observer.events().size() >= 2, "initial tail should emit 2 lines");

            Files.writeString(outLog, "appended-1\nappended-2\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            awaitCondition(() -> observer.events().size() >= 4, "appended lines should stream in real time");

            Files.writeString(outLog, "truncated-reset\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            awaitCondition(() -> observer.events().size() >= 5, "truncated file replacement should reset offset and emit");

            follower.interrupt();
            follower.join(5000);
            Assert.assertFalse(follower.isAlive(), "follower thread should terminate cleanly on interrupt");

            Result<LogsResult, DomainError> res = resultRef.get();
            Assert.assertNotNull(res, "follower thread should store a Result");
            LogsResult followed = Assert.assertOk(res, "follow should return Ok on clean interrupt");
            Assert.assertEquals(
                new LogsResult.Followed(outLog, "truncated-reset\n".getBytes(StandardCharsets.UTF_8).length),
                followed,
                "final offset reflects truncated file size"
            );
            Assert.assertEquals(
                List.of(
                    new ProgressEvent.LogLine("orders", false, "boot-2\n"),
                    new ProgressEvent.LogLine("orders", false, "boot-3\n"),
                    new ProgressEvent.LogLine("orders", false, "appended-1\n"),
                    new ProgressEvent.LogLine("orders", false, "appended-2\n"),
                    new ProgressEvent.LogLine("orders", false, "truncated-reset\n")
                ),
                observer.events(),
                "real filesystem follow captures initial tail, appends, and truncation in order"
            );
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private static void awaitCondition(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadlineNanos) {
                Assert.fail("Timed out waiting: " + failureMessage);
            }
            Thread.sleep(15);
        }
    }

    private static Project sampleProject(Path root) {
        var source = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "." }
                  }
                }
                """);
        return Assert.assertOk(new ProjectLoader(source).load(Optional.empty()), "sample project loads");
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
