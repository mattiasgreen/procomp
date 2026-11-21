package procomp.core.process;

import procomp.Assert;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class DiscoveryEngineTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-20T10:05:00Z");
    private static final Instant T2 = Instant.parse("2026-11-20T10:10:00Z");

    public static void testManagedAliveEvaluatesHealthWithoutAssumingStartingAndDetectsStale() {
        ProcessTarget target = new ProcessTarget(
            "orders",
            Optional.of(9001),
            Optional.of("/actuator/health"),
            Path.of("/state/acme/logs/orders.out.log"),
            Path.of("/state/acme/logs/orders.err.log")
        );
        ManagedProcessRecord record = new ManagedProcessRecord(
            4201L,
            T1,
            List.of("java", "-jar", "orders.jar"),
            Optional.of(T0),
            target.stdoutLog(),
            target.stderrLog()
        );
        ObservedProcess aliveProc = new ObservedProcess(
            4201L,
            true,
            Optional.of(T1),
            Optional.of("java -jar orders.jar"),
            List.of("-jar", "orders.jar"),
            List.of(),
            Optional.of(256L * 1024 * 1024),
            Optional.of("java")
        );

        // Even right after start, HTTP 200 yields HEALTHY (never assumes STARTING)
        DiscoveryResult healthyFresh = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.of(record),
            Optional.empty(),
            Optional.of(aliveProc),
            new HealthCheck.HttpResult(200, "OK"),
            Optional.of(T0)
        ));
        Assert.assertEquals("orders", healthyFresh.target(), "target name");
        Assert.assertEquals(Ownership.MANAGED, healthyFresh.ownership(), "MANAGED ownership");
        Assert.assertEquals(Optional.of("procomp"), healthyFresh.provenance(), "procomp provenance");
        Assert.assertEquals(HealthState.HEALTHY, healthyFresh.health(), "HTTP 200 yields HEALTHY");
        Assert.assertEquals(Optional.of(4201L), healthyFresh.pid(), "managed pid");
        Assert.assertFalse(healthyFresh.stale(), "same artifact timestamp is not stale");
        Assert.assertEquals(Optional.of(Duration.ofMinutes(5)), healthyFresh.uptime(T2), "uptime computed from startTime");

        // HTTP 503 while alive yields STARTING, and newer artifact timestamp yields stale=true
        DiscoveryResult startingStale = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.of(record),
            Optional.empty(),
            Optional.of(aliveProc),
            new HealthCheck.HttpResult(503, "Service Unavailable"),
            Optional.of(T2)
        ));
        Assert.assertEquals(Ownership.MANAGED, startingStale.ownership(), "still MANAGED");
        Assert.assertEquals(HealthState.STARTING, startingStale.health(), "HTTP 503 yields STARTING");
        Assert.assertTrue(startingStale.stale(), "newer artifact timestamp marks process stale");
    }

    public static void testManagedDeadYieldsCrashedWithoutUptimeOrStale() {
        ProcessTarget target = new ProcessTarget(
            "orders",
            Optional.of(9001),
            Optional.of("/actuator/health"),
            Path.of("/state/acme/logs/orders.out.log"),
            Path.of("/state/acme/logs/orders.err.log")
        );
        ManagedProcessRecord record = new ManagedProcessRecord(
            4201L,
            T1,
            List.of("java", "-jar", "orders.jar"),
            Optional.of(T0),
            target.stdoutLog(),
            target.stderrLog()
        );

        // Even if some unrelated port listener exists, recorded Managed PID takes priority -> CRASHED
        DiscoveryResult crashed = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.of(record),
            Optional.of(new PortBinding(9001, Optional.of(9999L), "127.0.0.1:9001")),
            Optional.empty(),
            new HealthCheck.HttpResult(200, "OK"),
            Optional.of(T2)
        ));
        Assert.assertEquals(Ownership.MANAGED, crashed.ownership(), "recorded managed process keeps MANAGED ownership");
        Assert.assertEquals(Optional.of("procomp"), crashed.provenance(), "procomp provenance");
        Assert.assertEquals(HealthState.CRASHED, crashed.health(), "dead managed process is CRASHED");
        Assert.assertEquals(Optional.of(4201L), crashed.pid(), "retains crashed pid");
        Assert.assertFalse(crashed.stale(), "crashed process is never marked stale");
        Assert.assertEquals(Optional.empty(), crashed.uptime(T2), "crashed process has no uptime");
    }

    public static void testPortListenerClassifiesUnmanagedIdeShellAndConflict() {
        ProcessTarget target = new ProcessTarget(
            "billing",
            Optional.of(9002),
            Optional.of("/actuator/health"),
            Path.of("/state/acme/logs/billing.out.log"),
            Path.of("/state/acme/logs/billing.err.log")
        );
        PortBinding listener = new PortBinding(9002, Optional.of(5510L), "127.0.0.1:9002");

        ObservedProcess ideProcess = new ObservedProcess(
            5510L,
            true,
            Optional.of(T1),
            Optional.of("java -agentlib:jdwp -jar billing.jar"),
            List.of("-agentlib:jdwp", "-jar", "billing.jar"),
            List.of(
                new ParentProcess(2200L, Optional.of("\"C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe\""), Optional.of("C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe")),
                new ParentProcess(1000L, Optional.empty(), Optional.of("C:\\Windows\\explorer.exe"))
            ),
            Optional.empty(),
            Optional.of("java")
        );

        // 1. IDE ancestor + failing health -> UNMANAGED ("IDE"), STARTING (NOT Conflict!)
        DiscoveryResult ideBooting = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.empty(),
            Optional.of(listener),
            Optional.of(ideProcess),
            new HealthCheck.HttpFailure("Connection refused"),
            Optional.empty()
        ));
        Assert.assertEquals(Ownership.UNMANAGED, ideBooting.ownership(), "booting IDE child is UNMANAGED");
        Assert.assertEquals(Optional.of("IDE"), ideBooting.provenance(), "IDE provenance");
        Assert.assertEquals(HealthState.STARTING, ideBooting.health(), "booting IDE is STARTING");
        Assert.assertEquals(Optional.of(5510L), ideBooting.pid(), "listener pid");
        Assert.assertFalse(ideBooting.stale(), "unmanaged process is never stale");

        // 2. IDE ancestor + passing health -> UNMANAGED ("IDE"), HEALTHY
        DiscoveryResult ideHealthy = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.empty(),
            Optional.of(listener),
            Optional.of(ideProcess),
            new HealthCheck.HttpResult(200, "OK"),
            Optional.empty()
        ));
        Assert.assertEquals(Ownership.UNMANAGED, ideHealthy.ownership(), "healthy IDE child is UNMANAGED");
        Assert.assertEquals(Optional.of("IDE"), ideHealthy.provenance(), "IDE provenance");
        Assert.assertEquals(HealthState.HEALTHY, ideHealthy.health(), "healthy IDE is HEALTHY");

        // 3. VS Code Insiders / Cursor / macOS path variations recognized as IDE
        ObservedProcess vscodeInsiders = new ObservedProcess(
            5511L,
            true,
            Optional.of(T1),
            Optional.of("node server.js"),
            List.of("server.js"),
            List.of(new ParentProcess(2300L, Optional.of("\"C:\\Users\\dev\\Programs\\Code - Insiders.exe\" --ms-enable"), Optional.empty())),
            Optional.empty(),
            Optional.of("node")
        );
        DiscoveryResult vsCodeBooting = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.empty(),
            Optional.of(new PortBinding(9002, Optional.of(5511L), "127.0.0.1:9002")),
            Optional.of(vscodeInsiders),
            new HealthCheck.HttpResult(503, "Unavailable"),
            Optional.empty()
        ));
        Assert.assertEquals(Ownership.UNMANAGED, vsCodeBooting.ownership(), "VS Code Insiders ancestor is UNMANAGED");
        Assert.assertEquals(Optional.of("IDE"), vsCodeBooting.provenance(), "VS Code Insiders provenance");

        // 4. Shell-started healthy process -> UNMANAGED ("shell"), HEALTHY (and folder named 'code' is NOT IDE!)
        ObservedProcess shellProcess = new ObservedProcess(
            5512L,
            true,
            Optional.of(T1),
            Optional.of("C:\\code\\billing\\python.exe -m http.server 9002"),
            List.of("-m", "http.server", "9002"),
            List.of(new ParentProcess(2400L, Optional.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"), Optional.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"))),
            Optional.empty(),
            Optional.of("C:\\code\\billing\\python.exe")
        );
        DiscoveryResult shellHealthy = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.empty(),
            Optional.of(new PortBinding(9002, Optional.of(5512L), "127.0.0.1:9002")),
            Optional.of(shellProcess),
            new HealthCheck.HttpResult(204, "No Content"),
            Optional.empty()
        ));
        Assert.assertEquals(Ownership.UNMANAGED, shellHealthy.ownership(), "healthy shell listener is UNMANAGED");
        Assert.assertEquals(Optional.of("shell"), shellHealthy.provenance(), "folder named 'code' must not match IDE");
        Assert.assertEquals(HealthState.HEALTHY, shellHealthy.health(), "204 is 2xx -> HEALTHY");

        // 5. Non-IDE listener failing health -> CONFLICT, STARTING
        DiscoveryResult conflict = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.empty(),
            Optional.of(new PortBinding(9002, Optional.of(5512L), "127.0.0.1:9002")),
            Optional.of(shellProcess),
            new HealthCheck.HttpResult(404, "Not Found"),
            Optional.empty()
        ));
        Assert.assertEquals(Ownership.CONFLICT, conflict.ownership(), "non-IDE listener failing health is CONFLICT");
        Assert.assertEquals(HealthState.STARTING, conflict.health(), "CONFLICT health is STARTING");
        Assert.assertEquals(Optional.of(5512L), conflict.pid(), "CONFLICT pid retained");

        // 6. No managed record and no listener -> NONE, STOPPED
        DiscoveryResult stopped = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            HealthCheck.None.INSTANCE,
            Optional.empty()
        ));
        Assert.assertEquals(Ownership.NONE, stopped.ownership(), "no process -> NONE");
        Assert.assertEquals(Optional.empty(), stopped.provenance(), "NONE has empty provenance");
        Assert.assertEquals(HealthState.STOPPED, stopped.health(), "no process -> STOPPED");
        Assert.assertEquals(Optional.empty(), stopped.pid(), "NONE has empty pid");
    }

    public static void testHealthEvaluationSupportsHttpTcpAndProcessAliveVariants() {
        // Port only (no healthPath) -> TCP listening determines HEALTHY vs STARTING
        ProcessTarget tcpOnly = new ProcessTarget(
            "redis-mock",
            Optional.of(6379),
            Optional.empty(),
            Path.of("/state/acme/logs/redis.out.log"),
            Path.of("/state/acme/logs/redis.err.log")
        );
        ManagedProcessRecord tcpRec = new ManagedProcessRecord(6001L, T0, List.of("redis-server"), Optional.empty(), tcpOnly.stdoutLog(), tcpOnly.stderrLog());
        ObservedProcess tcpProc = new ObservedProcess(6001L, true, Optional.of(T0), Optional.of("redis-server"), List.of(), List.of(), Optional.empty(), Optional.of("redis-server"));

        DiscoveryResult tcpListening = DiscoveryEngine.discover(new DiscoveryInput(
            tcpOnly, Optional.of(tcpRec), Optional.empty(), Optional.of(tcpProc), new HealthCheck.TcpResult(true), Optional.empty()
        ));
        Assert.assertEquals(HealthState.HEALTHY, tcpListening.health(), "port-only target is HEALTHY when TCP listening");

        DiscoveryResult tcpNotYet = DiscoveryEngine.discover(new DiscoveryInput(
            tcpOnly, Optional.of(tcpRec), Optional.empty(), Optional.of(tcpProc), new HealthCheck.TcpResult(false), Optional.empty()
        ));
        Assert.assertEquals(HealthState.STARTING, tcpNotYet.health(), "port-only target is STARTING when TCP not listening");

        // Neither port nor healthPath -> process alive determines HEALTHY
        ProcessTarget workerOnly = new ProcessTarget(
            "worker",
            Optional.empty(),
            Optional.empty(),
            Path.of("/state/acme/logs/worker.out.log"),
            Path.of("/state/acme/logs/worker.err.log")
        );
        ManagedProcessRecord workerRec = new ManagedProcessRecord(7001L, T0, List.of("node", "worker.js"), Optional.empty(), workerOnly.stdoutLog(), workerOnly.stderrLog());
        ObservedProcess workerProc = new ObservedProcess(7001L, true, Optional.of(T0), Optional.of("node worker.js"), List.of("worker.js"), List.of(), Optional.empty(), Optional.of("node"));

        DiscoveryResult workerHealthy = DiscoveryEngine.discover(new DiscoveryInput(
            workerOnly, Optional.of(workerRec), Optional.empty(), Optional.of(workerProc), HealthCheck.None.INSTANCE, Optional.empty()
        ));
        Assert.assertEquals(HealthState.HEALTHY, workerHealthy.health(), "no port/healthPath is HEALTHY whenever process is alive");
    }
}
