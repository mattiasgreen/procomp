package procomp.core.git;

import procomp.Assert;
import procomp.core.project.RepositoryRef;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class GitEngineTest {

    private static final RepositoryRef REPO = new RepositoryRef(
        "external-facades",
        Path.of("C:/dev/repos/external-facades"),
        Optional.of("https://git.example/team/external-facades.git")
    );
    private static final RepositoryRef REPO_NO_URL = new RepositoryRef(
        "local-only",
        Path.of("C:/dev/repos/local-only"),
        Optional.empty()
    );
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    public static void testParsesPorcelainV2BranchDirtyAheadBehindAndDetached() {
        String porcelain = """
            # branch.oid 1234567890abcdef1234567890abcdef12345678
            # branch.head feature/pricing
            # branch.upstream origin/feature/pricing
            # branch.ab +2 -5
            1 M. N... 100644 100644 100644 abc def src/Main.java
            1 .M N... 100644 100644 100644 abc def src/Other.java
            1 MM N... 100644 100644 100644 abc def src/Both.java
            ? untracked.txt
            """;
        GitOutputParser.ParsedStatus parsed = GitOutputParser.parseStatusPorcelainV2(porcelain);
        Assert.assertEquals(new RepositoryObservation.HeadState.Branch("feature/pricing"), parsed.head(), "branch head");
        Assert.assertEquals(Optional.of("origin/feature/pricing"), parsed.upstream(), "upstream");
        Assert.assertEquals(2, parsed.ahead(), "ahead count");
        Assert.assertEquals(5, parsed.behind(), "behind count");
        Assert.assertEquals(2, parsed.stagedCount(), "staged count (M. + MM)");
        Assert.assertEquals(2, parsed.unstagedCount(), "unstaged count (.M + MM)");
        Assert.assertEquals(1, parsed.untrackedCount(), "untracked count");

        String detached = """
            # branch.oid a1b2c3d4e5f6789012345678901234567890abcd
            # branch.head (detached)
            """;
        GitOutputParser.ParsedStatus detachedParsed = GitOutputParser.parseStatusPorcelainV2(detached);
        Assert.assertEquals(new RepositoryObservation.HeadState.Detached("a1b2c3d"), detachedParsed.head(), "detached short sha");
        Assert.assertEquals(Optional.empty(), detachedParsed.upstream(), "detached has no upstream");
    }

    public static void testParsesAndDeduplicatesBranchesOrderedByCommitterDateDesc() {
        String output = String.join("\n",
            "refs/heads/main\t2026-11-23T10:00:00Z\tLatest on main",
            "refs/remotes/origin/main\t2026-11-22T09:00:00Z\tOlder on origin/main",
            "refs/remotes/origin/HEAD\t2026-11-24T11:00:00Z\tSymbolic HEAD ignored",
            "refs/remotes/origin/feature/new-api\t2026-11-24T08:30:00Z\tAdd new API",
            "refs/heads/local-wip\t2026-11-20T15:00:00Z\tWIP"
        );
        List<BranchInfo> branches = GitOutputParser.parseBranchesForEachRef(output, Optional.of("main"));
        Assert.assertEquals(3, branches.size(), "deduplicated branch count (excluding origin/HEAD)");

        Assert.assertEquals("feature/new-api", branches.get(0).name(), "newest branch first");
        Assert.assertEquals(BranchInfo.RefOrigin.REMOTE, branches.get(0).origin(), "remote only");
        Assert.assertFalse(branches.get(0).current(), "not current");

        Assert.assertEquals("main", branches.get(1).name(), "second newest");
        Assert.assertEquals(BranchInfo.RefOrigin.LOCAL_AND_REMOTE, branches.get(1).origin(), "local + remote merged");
        Assert.assertTrue(branches.get(1).current(), "current branch marked");
        Assert.assertEquals(Instant.parse("2026-11-23T10:00:00Z"), branches.get(1).lastCommitAt(), "keeps newest commit timestamp");

        Assert.assertEquals("local-wip", branches.get(2).name(), "oldest branch last");
        Assert.assertEquals(BranchInfo.RefOrigin.LOCAL, branches.get(2).origin(), "local only");
    }

    public static void testParsesStashListAndOriginHead() {
        List<StashEntry> stashes = GitOutputParser.parseStashList(
            "stash@{0}: WIP on main: abc1234 fix\nstash@{1}: On feature: custom msg\n"
        );
        Assert.assertEquals(2, stashes.size(), "stash count");
        Assert.assertEquals("stash@{0}", stashes.get(0).ref(), "stash ref");
        Assert.assertEquals("WIP on main: abc1234 fix", stashes.get(0).message(), "stash message");

        Assert.assertEquals(Optional.of("main"), GitOutputParser.parseOriginHead("refs/remotes/origin/main\n"), "origin/HEAD full ref");
        Assert.assertEquals(Optional.of("master"), GitOutputParser.parseOriginHead("origin/master\n"), "origin/HEAD short ref");
        Assert.assertEquals(Optional.empty(), GitOutputParser.parseOriginHead(""), "empty origin/HEAD");
    }

    public static void testPlanCloneHandlesNotClonedAlreadyClonedNonGitAndMissingUrl() {
        GitPlan exec = GitEngine.planClone(REPO, new RepositoryObservation.NotCloned(REPO), Optional.empty(), false, false);
        Assert.assertTrue(exec instanceof GitPlan.Execute, "not cloned -> execute clone");
        GitPlan.Execute cloneExec = (GitPlan.Execute) exec;
        Assert.assertEquals(GitOperation.CLONE, cloneExec.operation(), "operation CLONE");
        Assert.assertEquals(
            List.of("git", "clone", "--", REPO.remoteUrl().orElseThrow(), REPO.root().toString()),
            cloneExec.command(),
            "clone argv"
        );

        RepositoryObservation.Cloned cloned = cleanCloned(REPO, "main", 0, 0, Optional.of(NOW));
        GitPlan skip = GitEngine.planClone(REPO, cloned, Optional.empty(), false, false);
        Assert.assertTrue(skip instanceof GitPlan.Skip, "already cloned -> skip idempotently");

        GitPlan nonEmpty = GitEngine.planClone(REPO, new RepositoryObservation.NotAGitRepository(REPO, false), Optional.empty(), false, false);
        Assert.assertTrue(nonEmpty instanceof GitPlan.Reject, "non-empty non-git dir -> reject");
        Assert.assertEquals("GIT_NOT_A_REPOSITORY", ((GitPlan.Reject) nonEmpty).error().code(), "non-git error code");

        GitPlan noUrl = GitEngine.planClone(REPO_NO_URL, new RepositoryObservation.NotCloned(REPO_NO_URL), Optional.empty(), false, false);
        Assert.assertTrue(noUrl instanceof GitPlan.Reject, "missing remoteUrl -> reject");
        Assert.assertEquals("GIT_NO_REMOTE_URL", ((GitPlan.Reject) noUrl).error().code(), "missing url error code");
    }

    public static void testPlanPullAndSwitchEnforceStrictDirtyAndUntrackedGuard() {
        // Untracked-only file MUST block both switch and pull!
        RepositoryObservation.Cloned untrackedOnly = new RepositoryObservation.Cloned(
            REPO,
            new RepositoryObservation.HeadState.Branch("main"),
            Optional.of("origin/main"),
            0, 1, 0, 0, 1, 0,
            Optional.of(NOW)
        );
        GitPlan switchRejected = GitEngine.planSwitch(REPO, untrackedOnly, "feature/other", Optional.empty(), false, false);
        Assert.assertTrue(switchRejected instanceof GitPlan.Reject, "untracked file rejects switch");
        Assert.assertEquals("GIT_DIRTY_WORKING_TREE", ((GitPlan.Reject) switchRejected).error().code(), "dirty error code");

        GitPlan pullRejected = GitEngine.planPull(REPO, untrackedOnly, Optional.empty(), false, false);
        Assert.assertTrue(pullRejected instanceof GitPlan.Reject, "untracked file rejects pull");
        Assert.assertEquals("GIT_DIRTY_WORKING_TREE", ((GitPlan.Reject) pullRejected).error().code(), "dirty error code on pull");

        // Clean repo allows switch and pull --ff-only
        RepositoryObservation.Cloned clean = cleanCloned(REPO, "main", 0, 2, Optional.of(NOW));
        GitPlan.Execute pullExec = (GitPlan.Execute) GitEngine.planPull(REPO, clean, Optional.empty(), false, false);
        Assert.assertEquals(List.of("git", "pull", "--ff-only"), pullExec.command(), "pull uses --ff-only");

        GitPlan.Execute switchExec = (GitPlan.Execute) GitEngine.planSwitch(REPO, clean, "feature/other", Optional.empty(), false, false);
        Assert.assertEquals(List.of("git", "switch", "feature/other"), switchExec.command(), "switch uses git switch <branch>");
    }

    public static void testPlanStashSkipsCleanAndEnforcesRunningOrUnknownLock() {
        RepositoryObservation.Cloned clean = cleanCloned(REPO, "main", 0, 0, Optional.of(NOW));
        GitPlan skip = GitEngine.planStash(REPO, clean, Optional.empty(), true, Optional.empty(), false, false);
        Assert.assertTrue(skip instanceof GitPlan.Skip, "clean repo skips stash");

        RepositoryObservation.Cloned dirty = new RepositoryObservation.Cloned(
            REPO, new RepositoryObservation.HeadState.Branch("main"), Optional.empty(),
            0, 0, 1, 0, 1, 0, Optional.of(NOW)
        );
        GitPlan.Execute stashExec = (GitPlan.Execute) GitEngine.planStash(
            REPO, dirty, Optional.of("park wip"), true, Optional.empty(), false, false
        );
        Assert.assertEquals(List.of("git", "stash", "push", "--include-untracked", "-m", "park wip"), stashExec.command(), "stash argv");

        GitRunRecord runningRecord = new GitRunRecord.Running(
            REPO.name(), GitOperation.FETCH, 9999L,
            NOW.minusSeconds(10),
            "git fetch --prune", Path.of("out.log"), Path.of("err.log")
        );
        GitPlan rejectRunning = GitEngine.planFetch(REPO, clean, Optional.of(runningRecord), true, false);
        Assert.assertTrue(rejectRunning instanceof GitPlan.Reject, "live RUNNING run blocks new run");
        Assert.assertEquals("GIT_RUN_IN_PROGRESS", ((GitPlan.Reject) rejectRunning).error().code(), "in progress code");

        GitPlan rejectUnknown = GitEngine.planFetch(REPO, clean, Optional.of(runningRecord), false, false);
        Assert.assertTrue(rejectUnknown instanceof GitPlan.Reject, "dead PID UNKNOWN blocks without --force");
        Assert.assertEquals("GIT_RUN_UNKNOWN_STATE", ((GitPlan.Reject) rejectUnknown).error().code(), "unknown state code");

        GitPlan forceAllowed = GitEngine.planFetch(REPO, clean, Optional.of(runningRecord), false, true);
        Assert.assertTrue(forceAllowed instanceof GitPlan.Execute, "--force overrides UNKNOWN dead-PID run");
    }

    public static void testCompactGitDisplayFormattingAndFetchAgeWarning() {
        Assert.assertEquals("NO_REPO", GitEngine.formatCompactDisplay(Optional.empty(), Optional.empty(), NOW, 24), "no repo");
        Assert.assertEquals(
            "NOT_CLONED",
            GitEngine.formatCompactDisplay(Optional.of(new RepositoryObservation.NotCloned(REPO)), Optional.empty(), NOW, 24),
            "not cloned"
        );

        RepositoryObservation.Cloned behindAndOld = new RepositoryObservation.Cloned(
            REPO, new RepositoryObservation.HeadState.Branch("main"), Optional.of("origin/main"),
            0, 3, 1, 0, 0, 0, Optional.of(NOW.minusSeconds(72 * 3600L))
        );
        Assert.assertTrue(GitEngine.isFetchAgeWarning(behindAndOld, NOW, 24), "72h > 24h triggers warning");
        Assert.assertFalse(GitEngine.isFetchAgeWarning(behindAndOld, NOW, 0), "0 disables fetch age warning");
        Assert.assertEquals(
            "main* ↓3 (3d!)",
            GitEngine.formatCompactDisplay(Optional.of(behindAndOld), Optional.empty(), NOW, 24),
            "compact dirty + behind + old fetch"
        );

        GitRunRecord activePull = new GitRunRecord.Running(
            REPO.name(), GitOperation.PULL, 1234L,
            NOW,
            "git pull --ff-only", Path.of("o"), Path.of("e")
        );
        Assert.assertEquals(
            "RUNNING(pull)",
            GitEngine.formatCompactDisplay(
                Optional.of(behindAndOld),
                Optional.of(new ObservedGitRun(GitRunStatus.RUNNING, activePull)),
                NOW,
                24
            ),
            "active running git run takes precedence"
        );
    }

    private static RepositoryObservation.Cloned cleanCloned(
        RepositoryRef repo, String branch, int ahead, int behind, Optional<Instant> fetchedAt
    ) {
        return new RepositoryObservation.Cloned(
            repo,
            new RepositoryObservation.HeadState.Branch(branch),
            Optional.of("origin/" + branch),
            ahead, behind, 0, 0, 0, 0,
            fetchedAt
        );
    }
}
