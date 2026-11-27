package procomp.core.git;

import procomp.core.project.RepositoryRef;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure planning outcome for cancelling a {@code Git Run} on a {@code Repository}.
 */
public sealed interface GitCancelPlan
    permits GitCancelPlan.Terminate, GitCancelPlan.NoActiveRun, GitCancelPlan.RejectUnknown {

    RepositoryRef repository();

    record Terminate(RepositoryRef repository, long pid, GitRunRecord.Running runningRecord) implements GitCancelPlan {
        public Terminate {
            Objects.requireNonNull(repository, "repository must not be null");
            if (pid <= 0) {
                throw new IllegalArgumentException("pid must be positive");
            }
            Objects.requireNonNull(runningRecord, "runningRecord must not be null");
        }
    }

    record NoActiveRun(RepositoryRef repository, Optional<GitRunRecord> latestRun) implements GitCancelPlan {
        public NoActiveRun {
            Objects.requireNonNull(repository, "repository must not be null");
            Objects.requireNonNull(latestRun, "latestRun must not be null");
        }
    }

    record RejectUnknown(RepositoryRef repository, GitRunRecord.Running unknownRecord) implements GitCancelPlan {
        public RejectUnknown {
            Objects.requireNonNull(repository, "repository must not be null");
            Objects.requireNonNull(unknownRecord, "unknownRecord must not be null");
        }
    }
}
