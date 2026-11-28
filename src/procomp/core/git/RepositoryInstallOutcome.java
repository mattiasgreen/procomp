package procomp.core.git;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of installing/upgrading mandatory Git hooks for a single Repository.
 */
public sealed interface RepositoryInstallOutcome
    permits RepositoryInstallOutcome.SkippedNotCloned,
        RepositoryInstallOutcome.SkippedNotGitRepository,
        RepositoryInstallOutcome.Applied {

    Path repositoryRoot();

    default String displayName() {
        return GitHooksEngine.displayName(repositoryRoot());
    }

    record SkippedNotCloned(Path repositoryRoot) implements RepositoryInstallOutcome {
        public SkippedNotCloned {
            Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
        }
    }

    record SkippedNotGitRepository(Path repositoryRoot) implements RepositoryInstallOutcome {
        public SkippedNotGitRepository {
            Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
        }
    }

    record Applied(Path repositoryRoot, List<HookInstallOutcome> hooks) implements RepositoryInstallOutcome {
        public Applied {
            Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
            hooks = List.copyOf(hooks);
        }

        public int installedCount() {
            int c = 0;
            for (HookInstallOutcome h : hooks) {
                if (h.action() == HookInstallAction.INSTALLED) c++;
            }
            return c;
        }

        public int upgradedCount() {
            int c = 0;
            for (HookInstallOutcome h : hooks) {
                if (h.action() == HookInstallAction.UPGRADED) c++;
            }
            return c;
        }

        public int unchangedCount() {
            int c = 0;
            for (HookInstallOutcome h : hooks) {
                if (h.action() == HookInstallAction.UNCHANGED) c++;
            }
            return c;
        }
    }
}
