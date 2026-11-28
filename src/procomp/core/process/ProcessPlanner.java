package procomp.core.process;

import java.util.Objects;

/**
 * Pure lifecycle decision planner for {@code procomp start}, {@code procomp stop}, and {@code procomp restart}.
 * Every method is an exhaustive switch over {@link ProcessState}.
 */
public final class ProcessPlanner {

    public enum StartAction {
        SPAWN,
        REUSE_HEALTHY,
        REUSE_WAIT,
        FAIL_CONFLICT,
        FAIL_STOPPING
    }

    public enum StopAction {
        KILL_MANAGED,
        SPARE_UNMANAGED,
        SPARE_CONFLICT,
        KILL_CONFLICT_WITH_CONSENT,
        ALREADY_STOPPED
    }

    public enum RestartAction {
        SPAWN,
        CLEAN_AND_SPAWN,
        STOP_AND_SPAWN,
        SPARE_UNMANAGED,
        FAIL_CONFLICT,
        FAIL_STOPPING
    }

    private ProcessPlanner() {}

    public static StartAction planStart(DiscoveryResult discovery) {
        Objects.requireNonNull(discovery, "discovery must not be null");
        return switch (discovery.state()) {
            case ProcessState.Stopped _ -> StartAction.SPAWN;
            case ProcessState.Managed.Crashed _ -> StartAction.SPAWN;
            case ProcessState.Managed.Healthy _ -> StartAction.REUSE_HEALTHY;
            case ProcessState.Managed.Starting _ -> StartAction.REUSE_WAIT;
            case ProcessState.Managed.Stopping _ -> StartAction.FAIL_STOPPING;
            case ProcessState.Unmanaged.Healthy _ -> StartAction.REUSE_HEALTHY;
            case ProcessState.Unmanaged.Starting _ -> StartAction.REUSE_WAIT;
            case ProcessState.Conflict _ -> StartAction.FAIL_CONFLICT;
        };
    }

    public static StopAction planStop(DiscoveryResult discovery, boolean killConflictWithConsent) {
        Objects.requireNonNull(discovery, "discovery must not be null");
        return switch (discovery.state()) {
            case ProcessState.Stopped _ -> StopAction.ALREADY_STOPPED;
            case ProcessState.Managed _ -> StopAction.KILL_MANAGED;
            case ProcessState.Unmanaged _ -> StopAction.SPARE_UNMANAGED;
            case ProcessState.Conflict _ -> killConflictWithConsent
                ? StopAction.KILL_CONFLICT_WITH_CONSENT
                : StopAction.SPARE_CONFLICT;
        };
    }

    public static RestartAction planRestart(DiscoveryResult discovery) {
        Objects.requireNonNull(discovery, "discovery must not be null");
        return switch (discovery.state()) {
            case ProcessState.Stopped _ -> RestartAction.SPAWN;
            case ProcessState.Managed.Crashed _ -> RestartAction.CLEAN_AND_SPAWN;
            case ProcessState.Managed.Starting _, ProcessState.Managed.Healthy _ -> RestartAction.STOP_AND_SPAWN;
            case ProcessState.Managed.Stopping _ -> RestartAction.FAIL_STOPPING;
            case ProcessState.Unmanaged _ -> RestartAction.SPARE_UNMANAGED;
            case ProcessState.Conflict _ -> RestartAction.FAIL_CONFLICT;
        };
    }

    /**
     * Returns {@code true} when a Service in the given state is targeted by a bare {@code procomp restart}
     * (live Managed processes, plus Managed.Stopping so bare restart fails fast rather than ignoring an in-flight stop).
     */
    public static boolean isBareRestartTarget(ProcessState state) {
        Objects.requireNonNull(state, "state must not be null");
        return state instanceof ProcessState.Managed.Starting
            || state instanceof ProcessState.Managed.Healthy
            || state instanceof ProcessState.Managed.Stopping;
    }
}
