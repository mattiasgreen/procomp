package procomp.shell;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.project.Project;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeProjectFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.fakes.RecordingObserver;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProgressObserver.ProgressEvent;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

public final class ProcompRuntimeTest {

    public static void testMissingPortsFailFastWithPortNotConfigured() {
        ProcompRuntime empty = ProcompRuntime.builder().build();

        DomainError loaderErr = Assert.assertErr(empty.projectLoader(), "missing ConfigSource");
        Assert.assertEquals("PORT_NOT_CONFIGURED", loaderErr.code(), "projectLoader error code");

        DomainError routingErr = Assert.assertErr(empty.routingShell(), "missing StateStore");
        Assert.assertEquals("PORT_NOT_CONFIGURED", routingErr.code(), "routingShell error code");

        DomainError logsErr = Assert.assertErr(empty.logsShell(), "missing StateStore and Clock");
        Assert.assertEquals("PORT_NOT_CONFIGURED", logsErr.code(), "logsShell error code");
        Assert.assertContains(logsErr.message(), "StateStore", "logsShell error lists StateStore");
        Assert.assertContains(logsErr.message(), "Clock", "logsShell error lists Clock");

        DomainError procErr = Assert.assertErr(empty.processShell(), "missing lifecycle ports");
        Assert.assertEquals("PORT_NOT_CONFIGURED", procErr.code(), "processShell error code");
        Assert.assertContains(procErr.message(), "ProcessRunner", "processShell error lists ProcessRunner");

        DomainError gwErr = Assert.assertErr(empty.gatewayShell(), "missing lifecycle ports");
        Assert.assertEquals("PORT_NOT_CONFIGURED", gwErr.code(), "gatewayShell error code");

        DomainError statusErr = Assert.assertErr(empty.statusShell(), "missing lifecycle ports");
        Assert.assertEquals("PORT_NOT_CONFIGURED", statusErr.code(), "statusShell error code");
    }

    public static void testLazyFactoriesAndWithObserverScoping() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "." }
                  }
                }
                """);
        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-20T12:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var projectFiles = new FakeProjectFiles().withDirectory(root);
        var baseObserver = new RecordingObserver();

        ProcompRuntime base = ProcompRuntime.builder()
            .configSource(configSource)
            .stateStore(stateStore)
            .processRunner(processRunner)
            .portScanner(portScanner)
            .httpClient(httpClient)
            .projectFiles(projectFiles)
            .clock(clock)
            .observer(baseObserver)
            .build();

        ProjectLoader loader1 = Assert.assertOk(base.projectLoader(), "projectLoader");
        ProjectLoader loader2 = Assert.assertOk(base.projectLoader(), "cached projectLoader");
        Assert.assertTrue(loader1 == loader2, "projectLoader should be cached");

        Assert.assertOk(base.routingShell(), "routingShell");
        Assert.assertOk(base.logsShell(), "logsShell");
        Assert.assertOk(base.processShell(), "processShell");
        Assert.assertOk(base.gatewayShell(), "gatewayShell");
        Assert.assertOk(base.statusShell(), "statusShell");

        Project project = Assert.assertOk(loader1.load(Optional.empty()), "load project");
        stateStore.appendLog(stateStore.logPath("acme", ProcessKey.service("orders"), false), "hello-stream\n");

        var requestObserver = new RecordingObserver();
        ProcompRuntime scoped = base.withObserver(requestObserver);
        LogsShell scopedLogs = Assert.assertOk(scoped.logsShell(), "scoped logsShell");
        Assert.assertTrue(scopedLogs != Assert.assertOk(base.logsShell(), "base logsShell"), "scoped runtime creates distinct LogsShell");

        clock.onSleep(_ -> false);
        Assert.assertOk(
            scopedLogs.run(project, new LogsShell.LogsRequest("orders", false, false, 10, true)),
            "follow log on scoped runtime"
        );

        Assert.assertTrue(baseObserver.events().isEmpty(), "base observer must not receive request-scoped events");
        Assert.assertTrue(
            requestObserver.events().stream().anyMatch(e -> e instanceof ProgressEvent.LogLine l && l.line().contains("hello-stream")),
            "request-scoped observer must receive streamed LogLine"
        );
    }
}
