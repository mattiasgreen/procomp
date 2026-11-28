package procomp.core.git;

import procomp.core.project.GitHookInstallMode;

import java.util.List;
import java.util.Objects;

/**
 * Aggregated read-only report of mandatory Git Hooks across all Repositories in a Project.
 */
public sealed interface GitHooksReport
    permits GitHooksReport.NotConfigured, GitHooksReport.SourceUnavailable, GitHooksReport.Configured {

    String project();

    boolean hasWarnings();

    static NotConfigured notConfigured(String project) {
        return new NotConfigured(project);
    }

    static SourceUnavailable sourceUnavailable(String project, String reason) {
        return new SourceUnavailable(project, reason);
    }

    record NotConfigured(String project) implements GitHooksReport {
        public NotConfigured {
            if (project == null || project.isBlank()) {
                throw new IllegalArgumentException("project must be non-blank");
            }
        }

        @Override
        public boolean hasWarnings() {
            return false;
        }
    }

    record SourceUnavailable(String project, String reason) implements GitHooksReport {
        public SourceUnavailable {
            if (project == null || project.isBlank()) {
                throw new IllegalArgumentException("project must be non-blank");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must be non-blank");
            }
        }

        @Override
        public boolean hasWarnings() {
            return true;
        }
    }

    record Configured(
        String project,
        GitHookInstallMode mode,
        List<String> requiredHooks,
        List<RepositoryHookStatus> repositories
    ) implements GitHooksReport {
        public Configured {
            if (project == null || project.isBlank()) {
                throw new IllegalArgumentException("project must be non-blank");
            }
            Objects.requireNonNull(mode, "mode must not be null");
            requiredHooks = List.copyOf(requiredHooks);
            repositories = List.copyOf(repositories);
        }

        public int checkedRepositoryCount() {
            int count = 0;
            for (RepositoryHookStatus repo : repositories) {
                if (repo instanceof RepositoryHookStatus.Checked) {
                    count++;
                }
            }
            return count;
        }

        public int repositoriesWithIssuesCount() {
            int count = 0;
            for (RepositoryHookStatus repo : repositories) {
                if (repo instanceof RepositoryHookStatus.Checked checked && checked.hasIssues()) {
                    count++;
                }
            }
            return count;
        }

        public int missingHookCount() {
            int count = 0;
            for (RepositoryHookStatus repo : repositories) {
                if (repo instanceof RepositoryHookStatus.Checked checked) {
                    count += checked.missingHooks().size();
                }
            }
            return count;
        }

        public int outdatedHookCount() {
            int count = 0;
            for (RepositoryHookStatus repo : repositories) {
                if (repo instanceof RepositoryHookStatus.Checked checked) {
                    count += checked.outdatedHooks().size();
                }
            }
            return count;
        }

        @Override
        public boolean hasWarnings() {
            return repositoriesWithIssuesCount() > 0;
        }
    }
}
