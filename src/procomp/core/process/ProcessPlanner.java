package procomp.core.process;

import java.util.Objects;

/**
 * Pure lifecycle decision planner for {@code procomp start} and {@code procomp stop}.
 */
public final class ProcessPlanner {

    public enum StartAction {
        SPAWN,
        REUSE_HEALTHY,
        REUSE_WAIT,
        FAIL_CONFLICT
    }

    public enum StopAction {
        KILL_MANAGED,
        SPARE_UNMANAGED,
        SPARE_CONFLICT,
        KILL_CONFLICT_WITH_CONSENT,
        ALREADY_STOPPED
    }

    private ProcessPlanner() {}

    public static StartAction planStart(DiscoveryResult discovery) {
        Objects.requireNonNull(discovery, "discovery must not be null");
        return switch (discovery.ownership()) {
            case NONE -> StartAction.SPAWN;
            case CONFLICT -> StartAction.FAIL_CONFLICT;
            case MANAGED -> discovery.health() == HealthState.CRASHED
                ? StartAction.SPAWN
                : (discovery.health() == HealthState.HEALTHY ? StartAction.REUSE_HEALTHY : StartAction.REUSE_WAIT);
            case UNMANAGED -> discovery.health() == HealthState.HEALTHY
                ? StartAction.REUSE_HEALTHY
                : StartAction.REUSE_WAIT;
        };
    }

    public static StopAction planStop(DiscoveryResult discovery, boolean killConflictWithConsent) {
        Objects.requireNonNull(discovery, "discovery must not be null");
        return switch (discovery.ownership()) {
            case NONE -> StopAction.ALREADY_STOPPED;
            case MANAGED -> StopAction.KILL_MANAGED;
            case UNMANAGED -> StopAction.SPARE_UNMANAGED;
            case CONFLICT -> killConflictWithConsent
                ? StopAction.KILL_CONFLICT_WITH_CONSENT
                : StopAction.SPARE_CONFLICT;
        };
    }
}
