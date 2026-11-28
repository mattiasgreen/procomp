package procomp.core.git;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Evaluated mandatory Git hook status for a single Repository in a Project.
 */
public sealed interface RepositoryHookStatus
    permits RepositoryHookStatus.NotCloned, RepositoryHookStatus.NotGitRepository, RepositoryHookStatus.Checked {

    Path repositoryRoot();

    Optional<String> remoteUrl();

    default String displayName() {
        return GitHooksEngine.displayName(repositoryRoot());
    }

    record NotCloned(Path repositoryRoot, Optional<String> remoteUrl) implements RepositoryHookStatus {
        public NotCloned {
            Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
            Objects.requireNonNull(remoteUrl, "remoteUrl must not be null");
        }
    }

    record NotGitRepository(Path repositoryRoot, Optional<String> remoteUrl) implements RepositoryHookStatus {
        public NotGitRepository {
            Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
            Objects.requireNonNull(remoteUrl, "remoteUrl must not be null");
        }
    }

    record Checked(
        Path repositoryRoot,
        Optional<String> remoteUrl,
        Path hooksDir,
        List<HookCheckResult> hooks
    ) implements RepositoryHookStatus {
        public Checked {
            Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
            Objects.requireNonNull(remoteUrl, "remoteUrl must not be null");
            Objects.requireNonNull(hooksDir, "hooksDir must not be null");
            hooks = List.copyOf(hooks);
        }

        public List<HookCheckResult> missingHooks() {
            return hooks.stream().filter(h -> h.state() == HookState.MISSING).toList();
        }

        public List<HookCheckResult> outdatedHooks() {
            return hooks.stream()
                .filter(h -> h.state() == HookState.OUTDATED || h.state() == HookState.NOT_EXECUTABLE)
                .toList();
        }

        public boolean allInstalled() {
            return hooks.stream().allMatch(h -> h.state() == HookState.INSTALLED);
        }

        public boolean hasIssues() {
            return !allInstalled();
        }
    }
}
