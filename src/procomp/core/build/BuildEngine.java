package procomp.core.build;

import procomp.core.project.ServiceBuildConfig;
import procomp.core.project.ServiceDefinition;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure, stateless decision engine for Build Run evaluation, build planning, and cancellation planning.
 */
public final class BuildEngine {

    private BuildEngine() {}

    /**
     * Derives the read-only {@link ObservedBuildRun} for a Service from its persisted record,
     * live PID check, Builder configuration, and on-disk Artifact presence.
     */
    public static ObservedBuildRun evaluate(
        Optional<BuildRunRecord> record,
        boolean hasBuilder,
        boolean pidAlive,
        boolean artifactPresent
    ) {
        Objects.requireNonNull(record, "record must not be null");
        if (record.isPresent()) {
            BuildRunRecord rec = record.get();
            return switch (rec) {
                case BuildRunRecord.Running _ -> pidAlive
                    ? new ObservedBuildRun(BuildDisplayState.RUNNING, Optional.of(BuildStatus.RUNNING), record)
                    : new ObservedBuildRun(BuildDisplayState.UNKNOWN, Optional.of(BuildStatus.UNKNOWN), record);
                case BuildRunRecord.Succeeded _ ->
                    new ObservedBuildRun(BuildDisplayState.SUCCEEDED, Optional.of(BuildStatus.SUCCEEDED), record);
                case BuildRunRecord.Failed _ ->
                    new ObservedBuildRun(BuildDisplayState.FAILED, Optional.of(BuildStatus.FAILED), record);
            };
        }
        if (!hasBuilder) {
            return new ObservedBuildRun(BuildDisplayState.NO_BUILDER, Optional.empty(), Optional.empty());
        }
        if (artifactPresent) {
            return new ObservedBuildRun(BuildDisplayState.ARTIFACT_PRESENT, Optional.empty(), Optional.empty());
        }
        return new ObservedBuildRun(BuildDisplayState.NOT_BUILT, Optional.empty(), Optional.empty());
    }

    /**
     * Decides whether a Service build can start, must stop/clear an existing run first (when forced),
     * or must be rejected.
     */
    public static BuildPlan planBuild(
        ServiceDefinition service,
        Optional<BuildRunRecord> existing,
        boolean pidAlive,
        boolean force
    ) {
        Objects.requireNonNull(service, "service must not be null");
        Objects.requireNonNull(existing, "existing must not be null");
        if (service.buildConfig().isEmpty()) {
            return new BuildPlan.RejectNoBuilder(service.name());
        }
        ServiceBuildConfig config = service.buildConfig().get();
        if (existing.isPresent() && existing.get() instanceof BuildRunRecord.Running running) {
            if (pidAlive) {
                return force
                    ? new BuildPlan.CancelThenStart(config, running)
                    : new BuildPlan.RejectAlreadyRunning(service.name(), running.pid());
            }
            return force
                ? new BuildPlan.ClearUnknownThenStart(config, running)
                : new BuildPlan.RejectUnknownState(service.name(), running.pid());
        }
        return new BuildPlan.Start(config);
    }

    /**
     * Decides how to cancel or clear an existing Build Run for a Service.
     */
    public static CancelPlan planCancel(
        String serviceName,
        Optional<BuildRunRecord> existing,
        boolean pidAlive
    ) {
        Objects.requireNonNull(serviceName, "serviceName must not be null");
        Objects.requireNonNull(existing, "existing must not be null");
        if (existing.isPresent() && existing.get() instanceof BuildRunRecord.Running running) {
            return pidAlive
                ? new CancelPlan.StopAndMarkFailed(running)
                : new CancelPlan.ClearUnknownAsFailed(running);
        }
        return new CancelPlan.NotRunning(serviceName);
    }
}
