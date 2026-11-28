package procomp.core.git;

import procomp.core.common.DomainError;
import procomp.core.project.RepositoryRef;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Pure planning outcome for a mutating {@code Git Run} on a single {@code Repository}.
 */
public sealed interface GitPlan permits GitPlan.Execute, GitPlan.Skip, GitPlan.Reject {

    RepositoryRef repository();

    GitOperation operation();

    record Execute(
        RepositoryRef repository,
        GitOperation operation,
        List<String> command,
        Path workingDir
    ) implements GitPlan {
        public Execute {
            Objects.requireNonNull(repository, "repository must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
            if (command == null || command.isEmpty()) {
                throw new IllegalArgumentException("command must not be empty");
            }
            command = List.copyOf(command);
            Objects.requireNonNull(workingDir, "workingDir must not be null");
        }
    }

    record Skip(
        RepositoryRef repository,
        GitOperation operation,
        String reason
    ) implements GitPlan {
        public Skip {
            Objects.requireNonNull(repository, "repository must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must be non-blank");
            }
        }
    }

    record Reject(
        RepositoryRef repository,
        GitOperation operation,
        DomainError error
    ) implements GitPlan {
        public Reject {
            Objects.requireNonNull(repository, "repository must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
            Objects.requireNonNull(error, "error must not be null");
        }
    }
}
