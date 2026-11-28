package procomp.core.process;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.core.project.ServiceDefinition;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InspectionAndPlannerTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-20T10:05:00Z");

    public static void testInspectionReportIncludesDiagnosticsAndNeverIncludesEnvSecrets() {
        ProcessTarget target = new ProcessTarget(
            "orders",
            Optional.of(9001),
            Optional.of("/actuator/health"),
            Path.of("/state/acme/logs/orders.out.log"),
            Path.of("/state/acme/logs/orders.err.log")
        );
        ManagedProcessRecord rec = new ManagedProcessRecord.Running(
            4201L,
            T0,
            List.of("java", "-Xmx512m", "-jar", "orders.jar"),
            Optional.of(T0),
            target.stdoutLog(),
            target.stderrLog()
        );
        ObservedProcess proc = new ObservedProcess(
            4201L,
            true,
            Optional.of(T0),
            Optional.of("java -Xmx512m -jar orders.jar"),
            List.of("-Xmx512m", "-jar", "orders.jar"),
            List.of(new ParentProcess(100L, Optional.of("procomp start orders"), Optional.of("java"))),
            Optional.of(320L * 1024 * 1024),
            Optional.of("java")
        );
        DiscoveryResult discovery = DiscoveryEngine.discover(new DiscoveryInput(
            target,
            Optional.of(rec),
            Optional.empty(),
            Optional.of(proc),
            new HealthCheck.HttpResult(200, "OK"),
            Optional.of(T1),
            T1
        ));

        InspectionReport report = DiscoveryEngine.inspect(
            target,
            discovery,
            Optional.of(rec),
            Optional.of("LOCAL"),
            Optional.empty(),
            T1
        );

        Assert.assertEquals("orders", report.service(), "service name");
        Assert.assertEquals(Optional.of(9001), report.port(), "port");
        Assert.assertEquals(Optional.of("LOCAL"), report.desiredRoute(), "desiredRoute");
        Assert.assertEquals(Optional.empty(), report.liveGatewayRoute(), "liveGatewayRoute");
        Assert.assertEquals(Ownership.MANAGED, report.ownership(), "ownership");
        Assert.assertEquals(Optional.of("procomp"), report.provenance(), "provenance");
        Assert.assertEquals(HealthState.HEALTHY, report.health(), "health");
        Assert.assertEquals(Optional.of(4201L), report.pid(), "pid");
        Assert.assertEquals(Optional.of(Duration.ofMinutes(5)), report.uptime(), "uptime");
        Assert.assertEquals(Optional.of("java -Xmx512m -jar orders.jar"), report.commandLine(), "commandLine");
        Assert.assertEquals(List.of("-Xmx512m", "-jar", "orders.jar"), report.arguments(), "arguments");
        Assert.assertEquals(1, report.parentChain().size(), "parentChain");
        Assert.assertEquals(Optional.of(320L * 1024 * 1024), report.rssBytes(), "rssBytes");
        Assert.assertEquals(Optional.of("http://127.0.0.1:9001/actuator/health"), report.healthEndpoint(), "healthEndpoint");
        Assert.assertContains(report.healthResponse(), "HTTP 200 OK", "healthResponse");
        Assert.assertEquals(target.stdoutLog(), report.outLogPath(), "outLogPath");
        Assert.assertEquals(target.stderrLog(), report.errLogPath(), "errLogPath");
        Assert.assertTrue(report.stale(), "stale propagated");
    }

    public static void testProcessPlannerCoversAllStartAndStopActions() {
        ProcessTarget target = new ProcessTarget(
            "orders",
            Optional.of(9001),
            Optional.of("/actuator/health"),
            Path.of("/state/acme/logs/orders.out.log"),
            Path.of("/state/acme/logs/orders.err.log")
        );
        ManagedProcessRecord rec = new ManagedProcessRecord.Running(4201L, T0, List.of("java"), Optional.empty(), target.stdoutLog(), target.stderrLog());
        ManagedProcessRecord stoppingRec = rec.requestStop(T1);
        ObservedProcess aliveProc = new ObservedProcess(4201L, true, Optional.of(T0), Optional.of("java"), List.of(), List.of(), Optional.empty(), Optional.of("java"));
        ObservedProcess ideProc = new ObservedProcess(
            5501L,
            true,
            Optional.of(T0),
            Optional.of("java"),
            List.of(),
            List.of(new ParentProcess(200L, Optional.empty(), Optional.of("idea64.exe"))),
            Optional.empty(),
            Optional.of("java")
        );
        PortBinding binding = new PortBinding(9001, Optional.of(5501L), "127.0.0.1:9001");

        DiscoveryResult noneStopped = DiscoveryEngine.discover(new DiscoveryInput(
            target, Optional.empty(), Optional.empty(), Optional.empty(), HealthCheck.None.INSTANCE, Optional.empty(), T1
        ));
        DiscoveryResult managedHealthy = DiscoveryEngine.discover(new DiscoveryInput(
            target, Optional.of(rec), Optional.empty(), Optional.of(aliveProc), new HealthCheck.HttpResult(200, "OK"), Optional.empty(), T1
        ));
        DiscoveryResult managedStarting = DiscoveryEngine.discover(new DiscoveryInput(
            target, Optional.of(rec), Optional.empty(), Optional.of(aliveProc), new HealthCheck.HttpResult(503, "Unavailable"), Optional.empty(), T1
        ));
        DiscoveryResult managedStopping = DiscoveryEngine.discover(new DiscoveryInput(
            target, Optional.of(stoppingRec), Optional.empty(), Optional.of(aliveProc), new HealthCheck.HttpFailure("refused"), Optional.empty(), T1.plusSeconds(3)
        ));
        DiscoveryResult managedCrashed = DiscoveryEngine.discover(new DiscoveryInput(
            target, Optional.of(rec), Optional.empty(), Optional.empty(), HealthCheck.None.INSTANCE, Optional.empty(), T1
        ));
        DiscoveryResult unmanagedHealthy = DiscoveryEngine.discover(new DiscoveryInput(
            target, Optional.empty(), Optional.of(binding), Optional.of(ideProc), new HealthCheck.HttpResult(200, "OK"), Optional.empty(), T1
        ));
        DiscoveryResult unmanagedStarting = DiscoveryEngine.discover(new DiscoveryInput(
            target, Optional.empty(), Optional.of(binding), Optional.of(ideProc), new HealthCheck.HttpFailure("refused"), Optional.empty(), T1
        ));
        DiscoveryResult conflict = DiscoveryEngine.discover(new DiscoveryInput(
            target, Optional.empty(), Optional.of(new PortBinding(9001, Optional.of(7777L), "127.0.0.1:9001")),
            Optional.of(new ObservedProcess(7777L, true, Optional.of(T0), Optional.of("python"), List.of(), List.of(), Optional.empty(), Optional.of("python"))),
            new HealthCheck.HttpFailure("refused"), Optional.empty(), T1
        ));

        Assert.assertEquals(ProcessPlanner.StartAction.SPAWN, ProcessPlanner.planStart(noneStopped), "NONE -> SPAWN");
        Assert.assertEquals(ProcessPlanner.StartAction.SPAWN, ProcessPlanner.planStart(managedCrashed), "CRASHED -> SPAWN");
        Assert.assertEquals(ProcessPlanner.StartAction.REUSE_HEALTHY, ProcessPlanner.planStart(managedHealthy), "MANAGED HEALTHY -> REUSE_HEALTHY");
        Assert.assertEquals(ProcessPlanner.StartAction.REUSE_HEALTHY, ProcessPlanner.planStart(unmanagedHealthy), "UNMANAGED HEALTHY -> REUSE_HEALTHY");
        Assert.assertEquals(ProcessPlanner.StartAction.REUSE_WAIT, ProcessPlanner.planStart(managedStarting), "MANAGED STARTING -> REUSE_WAIT");
        Assert.assertEquals(ProcessPlanner.StartAction.REUSE_WAIT, ProcessPlanner.planStart(unmanagedStarting), "UNMANAGED STARTING -> REUSE_WAIT");
        Assert.assertEquals(ProcessPlanner.StartAction.FAIL_STOPPING, ProcessPlanner.planStart(managedStopping), "MANAGED STOPPING -> FAIL_STOPPING");
        Assert.assertEquals(ProcessPlanner.StartAction.FAIL_CONFLICT, ProcessPlanner.planStart(conflict), "CONFLICT -> FAIL_CONFLICT");

        Assert.assertEquals(ProcessPlanner.StopAction.KILL_MANAGED, ProcessPlanner.planStop(managedHealthy, false), "MANAGED -> KILL_MANAGED");
        Assert.assertEquals(ProcessPlanner.StopAction.KILL_MANAGED, ProcessPlanner.planStop(managedStopping, false), "MANAGED STOPPING -> KILL_MANAGED");
        Assert.assertEquals(ProcessPlanner.StopAction.KILL_MANAGED, ProcessPlanner.planStop(managedCrashed, false), "CRASHED -> KILL_MANAGED (cleanup)");
        Assert.assertEquals(ProcessPlanner.StopAction.SPARE_UNMANAGED, ProcessPlanner.planStop(unmanagedHealthy, false), "UNMANAGED -> SPARE_UNMANAGED");
        Assert.assertEquals(ProcessPlanner.StopAction.SPARE_UNMANAGED, ProcessPlanner.planStop(unmanagedHealthy, true), "UNMANAGED spared even with killConflict flag");
        Assert.assertEquals(ProcessPlanner.StopAction.SPARE_CONFLICT, ProcessPlanner.planStop(conflict, false), "CONFLICT without consent -> SPARE_CONFLICT");
        Assert.assertEquals(ProcessPlanner.StopAction.KILL_CONFLICT_WITH_CONSENT, ProcessPlanner.planStop(conflict, true), "CONFLICT with consent -> KILL_CONFLICT_WITH_CONSENT");
        Assert.assertEquals(ProcessPlanner.StopAction.ALREADY_STOPPED, ProcessPlanner.planStop(noneStopped, false), "NONE -> ALREADY_STOPPED");

        Assert.assertEquals(ProcessPlanner.RestartAction.SPAWN, ProcessPlanner.planRestart(noneStopped), "NONE -> SPAWN");
        Assert.assertEquals(ProcessPlanner.RestartAction.CLEAN_AND_SPAWN, ProcessPlanner.planRestart(managedCrashed), "CRASHED -> CLEAN_AND_SPAWN");
        Assert.assertEquals(ProcessPlanner.RestartAction.STOP_AND_SPAWN, ProcessPlanner.planRestart(managedHealthy), "MANAGED HEALTHY -> STOP_AND_SPAWN");
        Assert.assertEquals(ProcessPlanner.RestartAction.STOP_AND_SPAWN, ProcessPlanner.planRestart(managedStarting), "MANAGED STARTING -> STOP_AND_SPAWN");
        Assert.assertEquals(ProcessPlanner.RestartAction.FAIL_STOPPING, ProcessPlanner.planRestart(managedStopping), "MANAGED STOPPING -> FAIL_STOPPING");
        Assert.assertEquals(ProcessPlanner.RestartAction.SPARE_UNMANAGED, ProcessPlanner.planRestart(unmanagedHealthy), "UNMANAGED HEALTHY -> SPARE_UNMANAGED");
        Assert.assertEquals(ProcessPlanner.RestartAction.SPARE_UNMANAGED, ProcessPlanner.planRestart(unmanagedStarting), "UNMANAGED STARTING -> SPARE_UNMANAGED");
        Assert.assertEquals(ProcessPlanner.RestartAction.FAIL_CONFLICT, ProcessPlanner.planRestart(conflict), "CONFLICT -> FAIL_CONFLICT");
    }

    public static void testResolvesDeferredArtifactTemplateAtLaunchTimeAndFailsFastWhenMissing() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String configJson = """
            {
              "project": "acme",
              "runtimes": {
                "java": {
                  "command": ["java", "-Dserver.port={{port}}", "-jar", "{{artifact}}"],
                  "artifactGlob": "build/libs/*.jar"
                }
              },
              "services": {
                "orders": { "runtime": "java", "port": 9001, "workingDir": "." }
              }
            }
            """;
        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, configJson, _ -> Result.ok(Optional.empty()), Map.of()),
            "project resolves"
        );
        ServiceDefinition orders = project.service("orders").orElseThrow();

        Path jar = Path.of("C:/dev/acme/build/libs/orders-1.0.jar").toAbsolutePath().normalize();
        List<String> resolved = Assert.assertOk(
            ProjectResolver.resolveServiceCommand(orders, Optional.of(jar)),
            "artifact expands cleanly"
        );
        Assert.assertEquals(
            List.of("java", "-Dserver.port=9001", "-jar", jar.toString()),
            resolved,
            "resolved launch argv replaces {{artifact}}"
        );

        DomainError missingErr = Assert.assertErr(
            ProjectResolver.resolveServiceCommand(orders, Optional.empty()),
            "missing artifact fails fast"
        );
        Assert.assertEquals("ARTIFACT_NOT_FOUND", missingErr.code(), "missing artifact error code");
    }
}
