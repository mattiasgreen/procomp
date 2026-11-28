package procomp.core.git;

import procomp.core.project.GitHookInstallMode;

import java.util.List;
import java.util.Objects;

/**
 * Aggregated result of installing/upgrading mandatory Git hooks across Repositories.
 */
public record GitHooksInstallResult(
    String project,
    GitHookInstallMode mode,
    List<RepositoryInstallOutcome> repositories
) {
    public GitHooksInstallResult {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project must be non-blank");
        }
        Objects.requireNonNull(mode, "mode must not be null");
        repositories = List.copyOf(repositories);
    }

    public int totalInstalled() {
        int total = 0;
        for (RepositoryInstallOutcome repo : repositories) {
            if (repo instanceof RepositoryInstallOutcome.Applied applied) {
                total += applied.installedCount();
            }
        }
        return total;
    }

    public int totalUpgraded() {
        int total = 0;
        for (RepositoryInstallOutcome repo : repositories) {
            if (repo instanceof RepositoryInstallOutcome.Applied applied) {
                total += applied.upgradedCount();
            }
        }
        return total;
    }

    public int totalUnchanged() {
        int total = 0;
        for (RepositoryInstallOutcome repo : repositories) {
            if (repo instanceof RepositoryInstallOutcome.Applied applied) {
                total += applied.unchangedCount();
            }
        }
        return total;
    }
}
