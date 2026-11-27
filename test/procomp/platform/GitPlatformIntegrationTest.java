package procomp.platform;

import procomp.Assert;
import procomp.core.git.BranchInfo;
import procomp.core.git.RepositoryObservation;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.core.project.RepositoryRef;
import procomp.shell.GitShell;
import procomp.shell.ports.ProgressObserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Level 3 integration tests exercising real {@code git} subprocesses against temporary bare local remotes
 * (zero internet required; runs first-class on Windows and POSIX).
 */
public final class GitPlatformIntegrationTest {

    public static void testCloneFetchPullSwitchStashAndBranchesAgainstLocalBareRemote() throws Exception {
        Path tempRoot = Files.createTempDirectory("procomp-git-int-");
        try {
            Path bareRemote = tempRoot.resolve("remote-orders.git");
            Path seedCheckout = tempRoot.resolve("seed");
            Path reposRoot = tempRoot.resolve("repos");
            Path stateDir = tempRoot.resolve("state");
            Files.createDirectories(reposRoot);

            // 1. Create bare remote + seed with main and feature/pricing branches
            runGit(tempRoot, "git", "init", "--bare", "-b", "main", bareRemote.toString());
            runGit(tempRoot, "git", "clone", bareRemote.toString(), seedCheckout.toString());
            runGit(seedCheckout, "git", "config", "user.email", "test@procomp.local");
            runGit(seedCheckout, "git", "config", "user.name", "Procomp Test");
            Files.writeString(seedCheckout.resolve("README.md"), "v1\n", StandardCharsets.UTF_8);
            runGit(seedCheckout, "git", "add", ".");
            runGit(seedCheckout, "git", "commit", "-m", "initial commit");
            runGit(seedCheckout, "git", "push", "-u", "origin", "main");

            // Create a feature branch with a newer commit
            runGit(seedCheckout, "git", "switch", "-c", "feature/pricing");
            Files.writeString(seedCheckout.resolve("pricing.txt"), "pricing\n", StandardCharsets.UTF_8);
            runGit(seedCheckout, "git", "add", ".");
            runGit(seedCheckout, "git", "commit", "-m", "add pricing");
            runGit(seedCheckout, "git", "push", "-u", "origin", "feature/pricing");
            runGit(seedCheckout, "git", "switch", "main");

            // Build Project pointing at reposRoot/orders
            Path configPath = tempRoot.resolve("procomp.json");
            String configJson = """
                {
                  "project": "gitint",
                  "repositoriesRoot": "./repos",
                  "runtimes": { "rt": { "command": ["echo"] } },
                  "services": {
                    "orders": {
                      "runtime": "rt",
                      "repository": "./repos/orders",
                      "repositoryUrl": "%s"
                    }
                  }
                }
                """.formatted(bareRemote.toUri().toString());
            Project project = Assert.assertOk(
                ProjectResolver.resolve(configPath, configJson, _ -> procomp.core.common.Result.ok(Optional.empty()), Map.of()),
                "resolve project"
            );
            RepositoryRef ordersRepo = project.repository("orders").orElseThrow();

            FsStateStore stateStore = new FsStateStore(
                System.getProperty("os.name", "windows"),
                Map.of("PROCOMP_STATE_DIR", stateDir.toString()),
                tempRoot
            );
            OsProcessRunner processRunner = new OsProcessRunner();
            OsGitRepositoryReader gitReader = new OsGitRepositoryReader();
            GitShell gitShell = new GitShell(stateStore, processRunner, gitReader, new SystemClock(), ProgressObserver.noop());

            // 2. Initially NOT_CLONED
            GitShell.RepositoryStatusRow initial = Assert.assertOk(gitShell.observeRepository(project, ordersRepo), "observe not cloned");
            Assert.assertTrue(initial.observation() instanceof RepositoryObservation.NotCloned, "initially NOT_CLONED");
            Assert.assertEquals("NOT_CLONED", initial.compactDisplay(), "compact NOT_CLONED");

            // 3. Explicit clone succeeds, second clone skips idempotently
            GitShell.GitBatchReport clone1 = Assert.assertOk(
                gitShell.cloneRepositories(project, List.of("orders"), false, false, Optional.empty()),
                "clone orders"
            );
            Assert.assertFalse(clone1.hasFailures(), "clone succeeded");
            Assert.assertTrue(clone1.outcomes().get(0) instanceof GitShell.GitRepoOutcome.Succeeded, "clone outcome Succeeded");

            GitShell.GitBatchReport clone2 = Assert.assertOk(
                gitShell.cloneRepositories(project, List.of("orders"), false, false, Optional.empty()),
                "second clone idempotent"
            );
            Assert.assertTrue(clone2.outcomes().get(0) instanceof GitShell.GitRepoOutcome.Skipped, "second clone skipped");

            // Configure user in cloned repo so stash works on clean CI environments
            runGit(ordersRepo.root(), "git", "config", "user.email", "test@procomp.local");
            runGit(ordersRepo.root(), "git", "config", "user.name", "Procomp Test");

            // 4. Branches list includes both main and feature/pricing
            GitShell.BranchesReport br = Assert.assertOk(gitShell.branches(project, "orders"), "list branches");
            Assert.assertEquals(2, br.branches().size(), "2 deduplicated branches");
            boolean hasPricing = br.branches().stream().anyMatch(b -> b.name().equals("feature/pricing") && b.origin() == BranchInfo.RefOrigin.REMOTE);
            Assert.assertTrue(hasPricing, "contains remote feature/pricing");

            // 5. Dirty untracked file blocks switch; stash -u clears it; switch succeeds
            Files.writeString(ordersRepo.root().resolve("scratch.tmp"), "untracked\n", StandardCharsets.UTF_8);
            GitShell.GitBatchReport blockedSwitch = Assert.assertOk(
                gitShell.switchBranch(project, Optional.of("feature/pricing"), false, List.of("orders"), false, false, Optional.empty()),
                "switch with untracked file returns batch report"
            );
            Assert.assertTrue(blockedSwitch.hasFailures(), "untracked file blocks switch");
            Assert.assertEquals(
                "GIT_DIRTY_WORKING_TREE",
                ((GitShell.GitRepoOutcome.Failed) blockedSwitch.outcomes().get(0)).error().code(),
                "dirty working tree code"
            );

            GitShell.GitBatchReport stashRes = Assert.assertOk(
                gitShell.stashPush(project, List.of("orders"), false, Optional.of("park scratch"), true, false, Optional.empty()),
                "stash -u succeeds"
            );
            Assert.assertFalse(stashRes.hasFailures(), "stash succeeded");

            List<GitShell.RepositoryStashes> stashes = Assert.assertOk(
                gitShell.stashList(project, List.of("orders"), false),
                "stash list"
            );
            Assert.assertEquals(1, stashes.get(0).stashes().size(), "1 stash entry recorded");
            Assert.assertTrue(stashes.get(0).stashes().get(0).message().contains("park scratch"), "stash message preserved");

            GitShell.GitBatchReport switched = Assert.assertOk(
                gitShell.switchBranch(project, Optional.of("feature/pricing"), false, List.of("orders"), false, false, Optional.empty()),
                "switch to feature/pricing"
            );
            Assert.assertFalse(switched.hasFailures(), "switch succeeded after stash");
            RepositoryObservation.Cloned afterSwitch = (RepositoryObservation.Cloned) Assert.assertOk(
                gitReader.observe(ordersRepo), "observe after switch"
            );
            Assert.assertEquals(Optional.of("feature/pricing"), afterSwitch.branchName(), "now on feature/pricing");

            // 6. Switch back via --default, then push a new commit to bare remote, fetch -> behind 1, pull --ff-only -> behind 0
            GitShell.GitBatchReport switchedDefault = Assert.assertOk(
                gitShell.switchBranch(project, Optional.empty(), true, List.of("orders"), false, false, Optional.empty()),
                "switch --default"
            );
            Assert.assertFalse(switchedDefault.hasFailures(), "switch --default succeeded");
            RepositoryObservation.Cloned onMain = (RepositoryObservation.Cloned) Assert.assertOk(
                gitReader.observe(ordersRepo), "observe on default"
            );
            Assert.assertEquals(Optional.of("main"), onMain.branchName(), "back on main");

            Files.writeString(seedCheckout.resolve("README.md"), "v2\n", StandardCharsets.UTF_8);
            runGit(seedCheckout, "git", "add", ".");
            runGit(seedCheckout, "git", "commit", "-m", "v2 on main");
            runGit(seedCheckout, "git", "push", "origin", "main");

            GitShell.GitBatchReport fetched = Assert.assertOk(
                gitShell.fetch(project, List.of("orders"), false, false, Optional.empty()),
                "fetch orders"
            );
            Assert.assertFalse(fetched.hasFailures(), "fetch succeeded");
            RepositoryObservation.Cloned afterFetch = (RepositoryObservation.Cloned) Assert.assertOk(
                gitReader.observe(ordersRepo), "observe after fetch"
            );
            Assert.assertEquals(1, afterFetch.behind(), "1 commit behind origin/main");
            Assert.assertTrue(afterFetch.lastFetchedAt().isPresent(), "lastFetchedAt populated");

            GitShell.GitBatchReport pulled = Assert.assertOk(
                gitShell.pull(project, List.of("orders"), false, false, Optional.empty()),
                "pull orders"
            );
            Assert.assertFalse(pulled.hasFailures(), "pull --ff-only succeeded");
            RepositoryObservation.Cloned afterPull = (RepositoryObservation.Cloned) Assert.assertOk(
                gitReader.observe(ordersRepo), "observe after pull"
            );
            Assert.assertEquals(0, afterPull.behind(), "0 commits behind after pull");
            Assert.assertEquals("v2", Files.readString(ordersRepo.root().resolve("README.md"), StandardCharsets.UTF_8).trim(), "updated file content");
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private static void runGit(Path dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("Command failed (" + code + "): " + String.join(" ", cmd) + "\n" + out);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup on Windows.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
