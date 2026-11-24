package procomp.shell;

import procomp.core.build.BuildEngine;
import procomp.core.build.BuildPlan;
import procomp.core.build.BuildRunRecord;
import procomp.core.build.CancelPlan;
import procomp.core.build.ObservedBuildRun;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.project.Project;
import procomp.core.project.ServiceBuildConfig;
import procomp.core.project.ServiceDefinition;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.ports.Clock;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProcessRunner.ExitOutcome;
import procomp.shell.ports.ProcessRunner.RunningProcess;
import procomp.shell.ports.ProcessRunner.SpawnRequest;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.DiagnosticCategory;
import procomp.shell.ports.ProgressObserver.ProgressEvent;
import procomp.shell.ports.ProjectFiles;
import procomp.shell.ports.ProjectFiles.ArtifactInfo;
import procomp.shell.ports.StateStore;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Modality-agnostic orchestrator for Service Build Runs: inspection, attached build execution,
 * timeout/interrupt handling, and graceful cancellation.
 */
public final class BuildShell {

    public static final Duration DEFAULT_CANCEL_GRACEFUL_TIMEOUT = Duration.ofSeconds(10);

    private final StateStore stateStore;
    private final ProcessRunner processRunner;
    private final ProjectFiles projectFiles;
    private final RoutingShell routingShell;
    private final Clock clock;
    private final ProgressObserver observer;

    public BuildShell(
        StateStore stateStore,
        ProcessRunner processRunner,
        ProjectFiles projectFiles,
        Clock clock,
        ProgressObserver observer
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
        this.projectFiles = Objects.requireNonNull(projectFiles, "projectFiles must not be null");
        this.routingShell = new RoutingShell(stateStore);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    /**
     * Read-only observation of a Service's build readiness and latest Build Run.
     */
    public Result<ObservedBuildRun, DomainError> inspectBuild(Project project, String serviceName) {
        Objects.requireNonNull(project, "project must not be null");
        Optional<ServiceDefinition> svcOpt = project.service(serviceName);
        if (svcOpt.isEmpty()) {
            return Result.err(unknownServiceError(project, serviceName));
        }
        return inspectBuild(project, svcOpt.get());
    }

    Result<ObservedBuildRun, DomainError> inspectBuild(Project project, ServiceDefinition service) {
        Result<RecordWithLiveness, DomainError> readRes = readBuildRunWithLiveness(project.name(), service.name());
        if (readRes.isErr()) {
            return readRes.castError();
        }
        RecordWithLiveness state = ((Result.Ok<RecordWithLiveness, DomainError>) readRes).value();

        boolean artifactPresent = false;
        if (state.record().isEmpty() && service.buildConfig().isPresent() && service.artifactGlob().isPresent()) {
            Result<Optional<ArtifactInfo>, DomainError> artRes = projectFiles.findNewestArtifact(
                service.workingDir(),
                service.artifactGlob().get(),
                service.artifactExclude()
            );
            if (artRes instanceof Result.Ok<Optional<ArtifactInfo>, DomainError>(Optional<ArtifactInfo> info)) {
                artifactPresent = info.isPresent();
            }
        }
        return Result.ok(BuildEngine.evaluate(state.record(), service.buildConfig().isPresent(), state.pidAlive(), artifactPresent));
    }

    /**
     * Validates that a Service exists, has a Builder configured, and is eligible to start a Build Run
     * under the current {@code force} setting (without starting the build).
     */
    public Result<Unit, DomainError> validateCanBuild(Project project, String serviceName, boolean force) {
        Objects.requireNonNull(project, "project must not be null");
        Optional<ServiceDefinition> svcOpt = project.service(serviceName);
        if (svcOpt.isEmpty()) {
            return Result.err(unknownServiceError(project, serviceName));
        }
        Result<RecordWithLiveness, DomainError> readRes = readBuildRunWithLiveness(project.name(), serviceName);
        if (readRes.isErr()) {
            return readRes.castError();
        }
        RecordWithLiveness current = ((Result.Ok<RecordWithLiveness, DomainError>) readRes).value();
        BuildPlan plan = BuildEngine.planBuild(svcOpt.get(), current.record(), current.pidAlive(), force);
        return switch (plan) {
            case BuildPlan.RejectNoBuilder(String svcName) -> Result.err(noBuilderError(svcName));
            case BuildPlan.RejectAlreadyRunning(String svcName, long pid) -> Result.err(alreadyRunningError(svcName, pid));
            case BuildPlan.RejectUnknownState(String svcName, long pid) -> Result.err(unknownRunError(svcName, pid));
            case BuildPlan.Start _, BuildPlan.CancelThenStart _, BuildPlan.ClearUnknownThenStart _ -> Result.ok(Unit.VALUE);
        };
    }

    /**
     * Executes sequential, fail-fast Build Runs for the requested Services (or all LOCAL-routed Services
     * with a configured Builder when {@code request.serviceNames()} is empty).
     */
    public Result<BuildResult, DomainError> build(Project project, BuildRequest request) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Result<List<ServiceDefinition>, DomainError> targetsRes = resolveBuildTargets(project, request.serviceNames());
        if (targetsRes.isErr()) {
            return targetsRes.castError();
        }
        List<ServiceDefinition> targets = ((Result.Ok<List<ServiceDefinition>, DomainError>) targetsRes).value();

        List<BuildServiceOutcome> outcomes = new ArrayList<>(targets.size());
        for (ServiceDefinition service : targets) {
            Result<BuildServiceOutcome, DomainError> singleRes = buildSingle(project, service, request);
            if (singleRes.isErr()) {
                return singleRes.castError();
            }
            outcomes.add(((Result.Ok<BuildServiceOutcome, DomainError>) singleRes).value());
        }
        return Result.ok(new BuildResult(outcomes));
    }

    /**
     * Cancels an active Build Run (stopping its process tree gracefully) or clears an UNKNOWN Build Run.
     */
    public Result<CancelResult, DomainError> cancel(Project project, String serviceName) {
        Objects.requireNonNull(project, "project must not be null");
        if (project.service(serviceName).isEmpty()) {
            return Result.err(unknownServiceError(project, serviceName));
        }

        Result<RecordWithLiveness, DomainError> readRes = readBuildRunWithLiveness(project.name(), serviceName);
        if (readRes.isErr()) {
            return readRes.castError();
        }
        RecordWithLiveness current = ((Result.Ok<RecordWithLiveness, DomainError>) readRes).value();

        CancelPlan plan = BuildEngine.planCancel(serviceName, current.record(), current.pidAlive());
        return switch (plan) {
            case CancelPlan.StopAndMarkFailed(BuildRunRecord.Running active) -> {
                observer.onEvent(new ProgressEvent.Diagnostic(
                    DiagnosticCategory.PROCESS,
                    serviceName + ": cancelling Build Run PID " + active.pid()
                ));
                Result<Unit, DomainError> stopRes = processRunner.stopTree(active.pid(), DEFAULT_CANCEL_GRACEFUL_TIMEOUT);
                if (stopRes.isErr()) {
                    yield stopRes.castError();
                }
                BuildRunRecord.Failed failed = new BuildRunRecord.Failed(
                    active.pid(),
                    active.startedAt(),
                    clock.now(),
                    Optional.empty(),
                    "Cancelled by user",
                    active.commandSummary(),
                    active.stdoutLog(),
                    active.stderrLog()
                );
                Result<Unit, DomainError> writeRes = stateStore.writeBuildRun(project.name(), serviceName, failed);
                if (writeRes.isErr()) {
                    yield writeRes.castError();
                }
                yield Result.ok(new CancelResult(serviceName, CancelDisposition.CANCELLED, Optional.of(failed)));
            }
            case CancelPlan.ClearUnknownAsFailed(BuildRunRecord.Running unknown) -> {
                BuildRunRecord.Failed failed = new BuildRunRecord.Failed(
                    unknown.pid(),
                    unknown.startedAt(),
                    clock.now(),
                    Optional.empty(),
                    "Cleared unknown Build Run",
                    unknown.commandSummary(),
                    unknown.stdoutLog(),
                    unknown.stderrLog()
                );
                Result<Unit, DomainError> writeRes = stateStore.writeBuildRun(project.name(), serviceName, failed);
                if (writeRes.isErr()) {
                    yield writeRes.castError();
                }
                yield Result.ok(new CancelResult(serviceName, CancelDisposition.CLEARED_UNKNOWN, Optional.of(failed)));
            }
            case CancelPlan.NotRunning _ ->
                Result.ok(new CancelResult(serviceName, CancelDisposition.NOT_RUNNING, Optional.empty()));
        };
    }

    private Result<BuildServiceOutcome, DomainError> buildSingle(
        Project project,
        ServiceDefinition service,
        BuildRequest request
    ) {
        Result<RecordWithLiveness, DomainError> readRes = readBuildRunWithLiveness(project.name(), service.name());
        if (readRes.isErr()) {
            return readRes.castError();
        }
        RecordWithLiveness current = ((Result.Ok<RecordWithLiveness, DomainError>) readRes).value();

        BuildPlan plan = BuildEngine.planBuild(service, current.record(), current.pidAlive(), request.force());
        ServiceBuildConfig config;
        switch (plan) {
            case BuildPlan.RejectNoBuilder(String svcName) -> {
                return Result.err(noBuilderError(svcName));
            }
            case BuildPlan.RejectAlreadyRunning(String svcName, long pid) -> {
                return Result.err(alreadyRunningError(svcName, pid));
            }
            case BuildPlan.RejectUnknownState(String svcName, long pid) -> {
                return Result.err(unknownRunError(svcName, pid));
            }
            case BuildPlan.CancelThenStart(ServiceBuildConfig cfg, BuildRunRecord.Running active) -> {
                observer.onEvent(new ProgressEvent.StatusMessage(
                    service.name(),
                    "Cancelling previous Build Run (PID " + active.pid() + ")..."
                ));
                Result<Unit, DomainError> stopRes = processRunner.stopTree(active.pid(), DEFAULT_CANCEL_GRACEFUL_TIMEOUT);
                if (stopRes.isErr()) {
                    return stopRes.castError();
                }
                config = cfg;
            }
            case BuildPlan.ClearUnknownThenStart(ServiceBuildConfig cfg, BuildRunRecord.Running _) -> config = cfg;
            case BuildPlan.Start(ServiceBuildConfig cfg) -> config = cfg;
        }

        Path stdoutLog = stateStore.logPath(project.name(), ProcessKey.build(service.name()), false);
        Path stderrLog = stateStore.logPath(project.name(), ProcessKey.build(service.name()), true);
        SpawnRequest spawnReq = new SpawnRequest(
            config.command(),
            config.workingDir(),
            config.env().asMap(),
            stdoutLog,
            stderrLog
        );

        observer.onEvent(new ProgressEvent.StatusMessage(service.name(), "Building " + service.name() + " (" + config.builder() + ")..."));
        Result<RunningProcess, DomainError> spawnRes = processRunner.spawnAttached(spawnReq);
        if (spawnRes.isErr()) {
            return spawnRes.castError();
        }
        RunningProcess running = ((Result.Ok<RunningProcess, DomainError>) spawnRes).value();

        BuildRunRecord.Running runningRecord = new BuildRunRecord.Running(
            running.pid(),
            clock.now(),
            config.command(),
            stdoutLog,
            stderrLog
        );
        Result<Unit, DomainError> writeRunningRes = stateStore.writeBuildRun(project.name(), service.name(), runningRecord);
        if (writeRunningRes.isErr()) {
            processRunner.stopTree(running.pid(), DEFAULT_CANCEL_GRACEFUL_TIMEOUT);
            return writeRunningRes.castError();
        }

        ExitOutcome exitOutcome = running.awaitExit(request.timeout());
        Instant finishedAt = clock.now();
        return switch (exitOutcome) {
            case ExitOutcome.Exited(int code) -> {
                if (code == 0) {
                    BuildRunRecord.Succeeded succeeded = new BuildRunRecord.Succeeded(
                        running.pid(),
                        runningRecord.startedAt(),
                        finishedAt,
                        config.command(),
                        stdoutLog,
                        stderrLog
                    );
                    Result<Unit, DomainError> writeRes = stateStore.writeBuildRun(project.name(), service.name(), succeeded);
                    if (writeRes.isErr()) {
                        yield writeRes.castError();
                    }
                    Duration elapsed = Duration.between(runningRecord.startedAt(), finishedAt);
                    yield Result.ok(new BuildServiceOutcome(service.name(), config.builder(), succeeded, elapsed));
                }
                Optional<BuildRunRecord.Failed> alreadyCancelled = readAlreadyCancelledRecord(project.name(), service.name(), running.pid());
                if (alreadyCancelled.isPresent()) {
                    yield Result.err(DomainError.of(
                        "BUILD_CANCELLED",
                        "Build Run for service '" + service.name() + "' was cancelled",
                        Map.of("service", service.name(), "stdoutLog", stdoutLog.toString(), "stderrLog", stderrLog.toString())
                    ));
                }
                BuildRunRecord.Failed failed = new BuildRunRecord.Failed(
                    running.pid(),
                    runningRecord.startedAt(),
                    finishedAt,
                    Optional.of(code),
                    "Build exited with code " + code,
                    config.command(),
                    stdoutLog,
                    stderrLog
                );
                stateStore.writeBuildRun(project.name(), service.name(), failed);
                yield Result.err(DomainError.of(
                    "BUILD_FAILED",
                    "Build Run failed for service '" + service.name() + "' (exit code " + code + ")",
                    Map.of(
                        "service", service.name(),
                        "exitCode", Integer.toString(code),
                        "stdoutLog", stdoutLog.toString(),
                        "stderrLog", stderrLog.toString()
                    )
                ));
            }
            case ExitOutcome.TimedOut _ -> {
                processRunner.stopTree(running.pid(), DEFAULT_CANCEL_GRACEFUL_TIMEOUT);
                long secs = request.timeout().map(Duration::toSeconds).orElse(0L);
                BuildRunRecord.Failed failed = new BuildRunRecord.Failed(
                    running.pid(),
                    runningRecord.startedAt(),
                    clock.now(),
                    Optional.empty(),
                    "Build timed out after " + secs + "s",
                    config.command(),
                    stdoutLog,
                    stderrLog
                );
                stateStore.writeBuildRun(project.name(), service.name(), failed);
                yield Result.err(DomainError.of(
                    "BUILD_TIMED_OUT",
                    "Build Run timed out after " + secs + "s for service '" + service.name() + "'",
                    Map.of(
                        "service", service.name(),
                        "timeoutSeconds", Long.toString(secs),
                        "stdoutLog", stdoutLog.toString(),
                        "stderrLog", stderrLog.toString()
                    )
                ));
            }
            case ExitOutcome.Interrupted _ -> {
                processRunner.stopTree(running.pid(), DEFAULT_CANCEL_GRACEFUL_TIMEOUT);
                BuildRunRecord.Failed failed = new BuildRunRecord.Failed(
                    running.pid(),
                    runningRecord.startedAt(),
                    clock.now(),
                    Optional.empty(),
                    "Build cancelled",
                    config.command(),
                    stdoutLog,
                    stderrLog
                );
                stateStore.writeBuildRun(project.name(), service.name(), failed);
                yield Result.err(DomainError.of(
                    "BUILD_CANCELLED",
                    "Build Run for service '" + service.name() + "' was cancelled",
                    Map.of("service", service.name(), "stdoutLog", stdoutLog.toString(), "stderrLog", stderrLog.toString())
                ));
            }
        };
    }

    private record RecordWithLiveness(Optional<BuildRunRecord> record, boolean pidAlive) {}

    private Result<RecordWithLiveness, DomainError> readBuildRunWithLiveness(String project, String serviceName) {
        Result<Optional<BuildRunRecord>, DomainError> readRes = stateStore.readBuildRun(project, serviceName);
        if (readRes.isErr()) {
            return readRes.castError();
        }
        Optional<BuildRunRecord> record = ((Result.Ok<Optional<BuildRunRecord>, DomainError>) readRes).value();
        boolean pidAlive = record.isPresent()
            && record.get() instanceof BuildRunRecord.Running running
            && processRunner.isAlive(running.pid());
        return Result.ok(new RecordWithLiveness(record, pidAlive));
    }

    private static DomainError noBuilderError(String serviceName) {
        return DomainError.of(
            "NO_BUILDER_CONFIGURED",
            "Service '" + serviceName + "' has no Builder configured",
            Map.of("service", serviceName)
        );
    }

    private static DomainError alreadyRunningError(String serviceName, long pid) {
        return DomainError.of(
            "BUILD_ALREADY_RUNNING",
            "Build Run for service '" + serviceName + "' is already RUNNING (PID " + pid + ")",
            Map.of("service", serviceName, "pid", Long.toString(pid))
        );
    }

    private static DomainError unknownRunError(String serviceName, long pid) {
        return DomainError.of(
            "BUILD_RUN_UNKNOWN",
            "Previous Build Run for service '" + serviceName + "' (PID " + pid
                + ") is UNKNOWN (process is no longer alive); force rebuild or cancel to clear it",
            Map.of("service", serviceName, "pid", Long.toString(pid))
        );
    }

    private Optional<BuildRunRecord.Failed> readAlreadyCancelledRecord(String project, String serviceName, long pid) {
        Result<Optional<BuildRunRecord>, DomainError> current = stateStore.readBuildRun(project, serviceName);
        if (current instanceof Result.Ok<Optional<BuildRunRecord>, DomainError>(Optional<BuildRunRecord> recOpt)
            && recOpt.isPresent()
            && recOpt.get() instanceof BuildRunRecord.Failed failed
            && failed.pid() == pid) {
            return Optional.of(failed);
        }
        return Optional.empty();
    }

    private Result<List<ServiceDefinition>, DomainError> resolveBuildTargets(Project project, List<String> requested) {
        if (!requested.isEmpty()) {
            Set<String> dedup = new LinkedHashSet<>(requested);
            List<ServiceDefinition> targets = new ArrayList<>(dedup.size());
            for (String svcName : dedup) {
                Optional<ServiceDefinition> svcOpt = project.service(svcName);
                if (svcOpt.isEmpty()) {
                    return Result.err(unknownServiceError(project, svcName));
                }
                targets.add(svcOpt.get());
            }
            return Result.ok(targets);
        }

        Result<RoutingTable, DomainError> routingRes = routingShell.showRoutes(project);
        if (routingRes instanceof Result.Err<RoutingTable, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        RoutingTable table = ((Result.Ok<RoutingTable, DomainError>) routingRes).value();
        List<ServiceDefinition> targets = new ArrayList<>();
        for (ServiceDefinition svc : project.services().values()) {
            if (table.routeOf(svc.name()).orElse(Route.REMOTE) == Route.LOCAL && svc.buildConfig().isPresent()) {
                targets.add(svc);
            }
        }
        return Result.ok(targets);
    }

    private static DomainError unknownServiceError(Project project, String serviceName) {
        return DomainError.of(
            "UNKNOWN_SERVICE",
            "Unknown service '" + serviceName + "' in project '" + project.name() + "'",
            Map.of("service", serviceName, "project", project.name())
        );
    }

    public record BuildRequest(
        List<String> serviceNames,
        boolean force,
        Optional<Duration> timeout
    ) {
        public BuildRequest {
            serviceNames = List.copyOf(serviceNames);
            Objects.requireNonNull(timeout, "timeout must not be null");
            timeout.ifPresent(d -> {
                if (d.isNegative() || d.isZero()) {
                    throw new IllegalArgumentException("Build timeout must be positive");
                }
            });
        }
    }

    public record BuildServiceOutcome(
        String service,
        String builder,
        BuildRunRecord.Succeeded record,
        Duration duration
    ) {
        public BuildServiceOutcome {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("service must be non-blank");
            }
            if (builder == null || builder.isBlank()) {
                throw new IllegalArgumentException("builder must be non-blank");
            }
            Objects.requireNonNull(record, "record must not be null");
            Objects.requireNonNull(duration, "duration must not be null");
        }
    }

    public record BuildResult(List<BuildServiceOutcome> outcomes) {
        public BuildResult {
            outcomes = List.copyOf(outcomes);
        }
    }

    public enum CancelDisposition {
        CANCELLED,
        CLEARED_UNKNOWN,
        NOT_RUNNING
    }

    public record CancelResult(
        String service,
        CancelDisposition disposition,
        Optional<BuildRunRecord.Failed> record
    ) {
        public CancelResult {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("service must be non-blank");
            }
            Objects.requireNonNull(disposition, "disposition must not be null");
            Objects.requireNonNull(record, "record must not be null");
        }
    }
}
