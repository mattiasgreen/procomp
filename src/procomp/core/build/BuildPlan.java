package procomp.core.build;

import procomp.core.project.ServiceBuildConfig;

import java.util.Objects;

/**
 * Pure decision plan produced by {@link BuildEngine#planBuild} for executing a Service build.
 */
public sealed interface BuildPlan permits
    BuildPlan.Start,
    BuildPlan.CancelThenStart,
    BuildPlan.ClearUnknownThenStart,
    BuildPlan.RejectNoBuilder,
    BuildPlan.RejectAlreadyRunning,
    BuildPlan.RejectUnknownState {

    record Start(ServiceBuildConfig config) implements BuildPlan {
        public Start {
            Objects.requireNonNull(config, "config must not be null");
        }
    }

    record CancelThenStart(ServiceBuildConfig config, BuildRunRecord.Running activeRun) implements BuildPlan {
        public CancelThenStart {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(activeRun, "activeRun must not be null");
        }
    }

    record ClearUnknownThenStart(ServiceBuildConfig config, BuildRunRecord.Running unknownRun) implements BuildPlan {
        public ClearUnknownThenStart {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(unknownRun, "unknownRun must not be null");
        }
    }

    record RejectNoBuilder(String serviceName) implements BuildPlan {
        public RejectNoBuilder {
            Objects.requireNonNull(serviceName, "serviceName must not be null");
        }
    }

    record RejectAlreadyRunning(String serviceName, long pid) implements BuildPlan {
        public RejectAlreadyRunning {
            Objects.requireNonNull(serviceName, "serviceName must not be null");
        }
    }

    record RejectUnknownState(String serviceName, long pid) implements BuildPlan {
        public RejectUnknownState {
            Objects.requireNonNull(serviceName, "serviceName must not be null");
        }
    }
}
