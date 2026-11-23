package procomp.core.build;

import java.util.Objects;

/**
 * Pure decision plan produced by {@link BuildEngine#planCancel} for cancelling an active or unknown Build Run.
 */
public sealed interface CancelPlan permits
    CancelPlan.StopAndMarkFailed,
    CancelPlan.ClearUnknownAsFailed,
    CancelPlan.NotRunning {

    record StopAndMarkFailed(BuildRunRecord.Running activeRun) implements CancelPlan {
        public StopAndMarkFailed {
            Objects.requireNonNull(activeRun, "activeRun must not be null");
        }
    }

    record ClearUnknownAsFailed(BuildRunRecord.Running unknownRun) implements CancelPlan {
        public ClearUnknownAsFailed {
            Objects.requireNonNull(unknownRun, "unknownRun must not be null");
        }
    }

    record NotRunning(String serviceName) implements CancelPlan {
        public NotRunning {
            Objects.requireNonNull(serviceName, "serviceName must not be null");
        }
    }
}
