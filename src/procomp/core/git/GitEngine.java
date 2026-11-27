package procomp.core.git;

import procomp.core.common.DomainError;
import procomp.core.project.RepositoryRef;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure functional core for {@code Repository} Git planning, safety guards, {@code Git Run} liveness evaluation,
 * and compact status display formatting.
 */
public final class GitEngine {

    private GitEngine() {}

    public static Optional<ObservedGitRun> evaluateRun(Optional<GitRunRecord> persisted, boolean pidAlive) {
        if (persisted.isEmpty()) {
            return Optional.empty();
        }
        GitRunRecord rec = persisted.get();
        GitRunStatus effective = (rec.status() == GitRunStatus.RUNNING && !pidAlive)
            ? GitRunStatus.UNKNOWN
            : rec.status();
        return Optional.of(new ObservedGitRun(effective, rec));
    }

    public static boolean isFetchAgeWarning(RepositoryObservation obs, Instant now, int fetchWarnAfterHours) {
        if (fetchWarnAfterHours <= 0) {
            return false;
        }
        if (!(obs instanceof RepositoryObservation.Cloned cloned)) {
            return false;
        }
        if (cloned.lastFetchedAt().isEmpty()) {
            return false;
        }
        Duration age = Duration.between(cloned.lastFetchedAt().get(), now);
        return age.compareTo(Duration.ofHours(fetchWarnAfterHours)) >= 0;
    }

    public static GitPlan planClone(
        RepositoryRef repo,
        RepositoryObservation obs,
        Optional<GitRunRecord> latestRun,
        boolean pidAlive,
        boolean force
    ) {
        Optional<DomainError> lockErr = checkRunLock(repo, latestRun, pidAlive, force);
        if (lockErr.isPresent()) {
            return new GitPlan.Reject(repo, GitOperation.CLONE, lockErr.get());
        }
        if (obs instanceof RepositoryObservation.Cloned) {
            return new GitPlan.Skip(repo, GitOperation.CLONE, "already cloned");
        }
        if (obs instanceof RepositoryObservation.NotAGitRepository nonGit && !nonGit.empty()) {
            return new GitPlan.Reject(repo, GitOperation.CLONE, DomainError.of(
                "GIT_NOT_A_REPOSITORY",
                "Directory '" + repo.root() + "' exists and is not an empty or Git directory",
                Map.of("repository", repo.name(), "root", repo.root().toString())
            ));
        }
        if (repo.remoteUrl().isEmpty()) {
            return new GitPlan.Reject(repo, GitOperation.CLONE, DomainError.of(
                "GIT_NO_REMOTE_URL",
                "Repository '" + repo.name() + "' has no repositoryUrl configured",
                Map.of("repository", repo.name())
            ));
        }
        Path parentDir = repo.root().getParent() != null ? repo.root().getParent() : repo.root();
        List<String> cmd = List.of("git", "clone", "--", repo.remoteUrl().get(), repo.root().toString());
        return new GitPlan.Execute(repo, GitOperation.CLONE, cmd, parentDir);
    }

    public static GitPlan planFetch(
        RepositoryRef repo,
        RepositoryObservation obs,
        Optional<GitRunRecord> latestRun,
        boolean pidAlive,
        boolean force
    ) {
        Optional<DomainError> pre = checkClonedAndUnlocked(repo, obs, GitOperation.FETCH, latestRun, pidAlive, force);
        if (pre.isPresent()) {
            return new GitPlan.Reject(repo, GitOperation.FETCH, pre.get());
        }
        return new GitPlan.Execute(repo, GitOperation.FETCH, List.of("git", "fetch", "--prune"), repo.root());
    }

    public static GitPlan planPull(
        RepositoryRef repo,
        RepositoryObservation obs,
        Optional<GitRunRecord> latestRun,
        boolean pidAlive,
        boolean force
    ) {
        Optional<DomainError> pre = checkClonedAndUnlocked(repo, obs, GitOperation.PULL, latestRun, pidAlive, force);
        if (pre.isPresent()) {
            return new GitPlan.Reject(repo, GitOperation.PULL, pre.get());
        }
        RepositoryObservation.Cloned cloned = (RepositoryObservation.Cloned) obs;
        if (cloned.isDirty()) {
            return new GitPlan.Reject(repo, GitOperation.PULL, dirtyTreeError(repo, cloned, "pull"));
        }
        return new GitPlan.Execute(repo, GitOperation.PULL, List.of("git", "pull", "--ff-only"), repo.root());
    }

    public static GitPlan planSwitch(
        RepositoryRef repo,
        RepositoryObservation obs,
        String targetBranch,
        Optional<GitRunRecord> latestRun,
        boolean pidAlive,
        boolean force
    ) {
        Optional<DomainError> pre = checkClonedAndUnlocked(repo, obs, GitOperation.SWITCH, latestRun, pidAlive, force);
        if (pre.isPresent()) {
            return new GitPlan.Reject(repo, GitOperation.SWITCH, pre.get());
        }
        if (targetBranch == null || targetBranch.isBlank() || targetBranch.startsWith("-")) {
            return new GitPlan.Reject(repo, GitOperation.SWITCH, DomainError.of(
                "GIT_INVALID_BRANCH",
                "Invalid target branch '" + targetBranch + "'",
                Map.of("repository", repo.name())
            ));
        }
        RepositoryObservation.Cloned cloned = (RepositoryObservation.Cloned) obs;
        if (cloned.isDirty()) {
            return new GitPlan.Reject(repo, GitOperation.SWITCH, dirtyTreeError(repo, cloned, "switch"));
        }
        return new GitPlan.Execute(repo, GitOperation.SWITCH, List.of("git", "switch", targetBranch), repo.root());
    }

    public static GitPlan planStash(
        RepositoryRef repo,
        RepositoryObservation obs,
        Optional<String> message,
        boolean includeUntracked,
        Optional<GitRunRecord> latestRun,
        boolean pidAlive,
        boolean force
    ) {
        Optional<DomainError> pre = checkClonedAndUnlocked(repo, obs, GitOperation.STASH, latestRun, pidAlive, force);
        if (pre.isPresent()) {
            return new GitPlan.Reject(repo, GitOperation.STASH, pre.get());
        }
        RepositoryObservation.Cloned cloned = (RepositoryObservation.Cloned) obs;
        boolean hasStashableChanges = includeUntracked
            ? cloned.isDirty()
            : (cloned.stagedCount() > 0 || cloned.unstagedCount() > 0);
        if (!hasStashableChanges) {
            return new GitPlan.Skip(repo, GitOperation.STASH, "clean");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("stash");
        cmd.add("push");
        if (includeUntracked) {
            cmd.add("--include-untracked");
        }
        if (message.isPresent() && !message.get().isBlank()) {
            cmd.add("-m");
            cmd.add(message.get());
        }
        return new GitPlan.Execute(repo, GitOperation.STASH, cmd, repo.root());
    }

    public static GitCancelPlan planCancel(RepositoryRef repo, Optional<GitRunRecord> latestRun, boolean pidAlive) {
        if (latestRun.isEmpty()) {
            return new GitCancelPlan.NoActiveRun(repo, Optional.empty());
        }
        GitRunRecord rec = latestRun.get();
        if (!(rec instanceof GitRunRecord.Running running)) {
            return new GitCancelPlan.NoActiveRun(repo, latestRun);
        }
        if (pidAlive) {
            return new GitCancelPlan.Terminate(repo, running.pid(), running);
        }
        return new GitCancelPlan.RejectUnknown(repo, running);
    }

    public static String formatCompactDisplay(
        Optional<RepositoryObservation> observation,
        Optional<ObservedGitRun> latestRun,
        Instant now,
        int fetchWarnAfterHours
    ) {
        if (observation.isEmpty()) {
            return "NO_REPO";
        }
        if (latestRun.isPresent()) {
            ObservedGitRun run = latestRun.get();
            if (run.effectiveStatus() == GitRunStatus.RUNNING) {
                return "RUNNING(" + run.record().operation().cliName() + ")";
            }
            if (run.effectiveStatus() == GitRunStatus.UNKNOWN) {
                return "UNKNOWN(" + run.record().operation().cliName() + ")";
            }
        }
        RepositoryObservation obs = observation.get();
        return switch (obs) {
            case RepositoryObservation.NotCloned _ -> "NOT_CLONED";
            case RepositoryObservation.NotAGitRepository _ -> "NOT_GIT";
            case RepositoryObservation.Cloned cloned -> {
                StringBuilder sb = new StringBuilder();
                switch (cloned.head()) {
                    case RepositoryObservation.HeadState.Branch(String name) -> sb.append(name);
                    case RepositoryObservation.HeadState.Detached(String sha) -> sb.append("DETACHED@").append(sha);
                }
                if (cloned.isDirty()) {
                    sb.append('*');
                }
                if (cloned.ahead() > 0 || cloned.behind() > 0) {
                    sb.append(' ');
                    if (cloned.ahead() > 0) {
                        sb.append('↑').append(cloned.ahead());
                    }
                    if (cloned.behind() > 0) {
                        sb.append('↓').append(cloned.behind());
                    }
                }
                if (isFetchAgeWarning(cloned, now, fetchWarnAfterHours) && cloned.lastFetchedAt().isPresent()) {
                    sb.append(" (").append(formatShortAge(Duration.between(cloned.lastFetchedAt().get(), now))).append("!)");
                }
                yield sb.toString();
            }
        };
    }

    public static String formatShortAge(Duration age) {
        long seconds = Math.max(0L, age.toSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + "h";
        }
        long days = hours / 24;
        return days + "d";
    }

    private static Optional<DomainError> checkClonedAndUnlocked(
        RepositoryRef repo,
        RepositoryObservation obs,
        GitOperation op,
        Optional<GitRunRecord> latestRun,
        boolean pidAlive,
        boolean force
    ) {
        Optional<DomainError> lockErr = checkRunLock(repo, latestRun, pidAlive, force);
        if (lockErr.isPresent()) {
            return lockErr;
        }
        return switch (obs) {
            case RepositoryObservation.NotCloned _ -> Optional.of(DomainError.of(
                "GIT_NOT_CLONED",
                "Repository '" + repo.name() + "' is not cloned at '" + repo.root() + "'. Run 'procomp git clone " + repo.name() + "' first.",
                Map.of("repository", repo.name(), "operation", op.cliName())
            ));
            case RepositoryObservation.NotAGitRepository _ -> Optional.of(DomainError.of(
                "GIT_NOT_A_REPOSITORY",
                "Directory '" + repo.root() + "' is not a Git repository",
                Map.of("repository", repo.name(), "operation", op.cliName())
            ));
            case RepositoryObservation.Cloned _ -> Optional.empty();
        };
    }

    private static Optional<DomainError> checkRunLock(
        RepositoryRef repo,
        Optional<GitRunRecord> latestRun,
        boolean pidAlive,
        boolean force
    ) {
        if (latestRun.isEmpty()) {
            return Optional.empty();
        }
        GitRunRecord rec = latestRun.get();
        if (rec.status() != GitRunStatus.RUNNING) {
            return Optional.empty();
        }
        if (pidAlive) {
            return Optional.of(DomainError.of(
                "GIT_RUN_IN_PROGRESS",
                "Repository '" + repo.name() + "' already has a RUNNING Git Run (" + rec.operation().cliName() + ", PID " + rec.pid() + ")",
                Map.of("repository", repo.name(), "pid", String.valueOf(rec.pid()))
            ));
        }
        if (!force) {
            return Optional.of(DomainError.of(
                "GIT_RUN_UNKNOWN_STATE",
                "Repository '" + repo.name() + "' has an UNKNOWN Git Run (" + rec.operation().cliName() + ", dead PID " + rec.pid()
                    + "). Pass --force to start a new Git Run.",
                Map.of("repository", repo.name(), "pid", String.valueOf(rec.pid()))
            ));
        }
        return Optional.empty();
    }

    private static DomainError dirtyTreeError(RepositoryRef repo, RepositoryObservation.Cloned cloned, String opName) {
        return DomainError.of(
            "GIT_DIRTY_WORKING_TREE",
            "Cannot " + opName + " Repository '" + repo.name() + "': working tree has uncommitted or untracked changes ("
                + cloned.stagedCount() + " staged, " + cloned.unstagedCount() + " unstaged, " + cloned.untrackedCount()
                + " untracked). Run 'procomp git stash " + repo.name() + " -u' first.",
            Map.of(
                "repository", repo.name(),
                "staged", String.valueOf(cloned.stagedCount()),
                "unstaged", String.valueOf(cloned.unstagedCount()),
                "untracked", String.valueOf(cloned.untrackedCount())
            )
        );
    }
}
