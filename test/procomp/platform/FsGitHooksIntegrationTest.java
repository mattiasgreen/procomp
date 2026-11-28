package procomp.platform;

import procomp.Assert;
import procomp.core.common.Result;
import procomp.core.git.GitHooksInstallResult;
import procomp.core.git.GitHooksReport;
import procomp.core.git.HookState;
import procomp.core.git.RepositoryHookStatus;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.shell.GitHooksShell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Level 3 filesystem integration test for {@link FsGitHooksStore} + {@link GitHooksShell}
 * covering standard {@code .git/} directories and Git worktree {@code .git} + {@code commondir} layouts.
 */
public final class FsGitHooksIntegrationTest {

    public static void testRealFilesystemDetectInstallAndUpgradeIncludingWorktree() throws IOException {
        Path tempRoot = Files.createTempDirectory("procomp-githooks-it-");
        try {
            Path configDir = tempRoot.resolve("project");
            Path hooksSourceDir = configDir.resolve("githooks");
            Path reposRoot = tempRoot.resolve("repos");
            Path stdRepo = reposRoot.resolve("service-a");
            Path mainRepo = reposRoot.resolve("main-repo");
            Path worktreeRepo = reposRoot.resolve("service-b-wt");
            Path nonGitDir = reposRoot.resolve("plain-dir");

            Files.createDirectories(hooksSourceDir);
            String preCommitContent = "#!/bin/sh\necho mandatory-pre-commit-v2\n";
            Files.writeString(hooksSourceDir.resolve("pre-commit"), preCommitContent, StandardCharsets.UTF_8);

            // Standard .git directory with outdated pre-commit
            Files.createDirectories(stdRepo.resolve(".git/hooks"));
            Files.writeString(stdRepo.resolve(".git/hooks/pre-commit"), "#!/bin/sh\necho old-v1\n", StandardCharsets.UTF_8);

            // Simulated Git worktree pointing to main-repo/.git/worktrees/service-b-wt with commondir -> ../..
            Path mainGitDir = mainRepo.resolve(".git");
            Path wtGitDir = mainGitDir.resolve("worktrees/service-b-wt");
            Files.createDirectories(mainGitDir.resolve("hooks"));
            Files.createDirectories(wtGitDir);
            Files.createDirectories(worktreeRepo);
            Files.writeString(wtGitDir.resolve("commondir"), "../..\n", StandardCharsets.UTF_8);
            Files.writeString(worktreeRepo.resolve(".git"), "gitdir: " + wtGitDir + "\n", StandardCharsets.UTF_8);

            // Non-git directory
            Files.createDirectories(nonGitDir);

            String configJson = """
                {
                  "project": "it-hooks",
                  "repositoriesRoot": "../repos",
                  "runtimes": { "sh": { "command": ["sh", "-c", "echo ok"] } },
                  "services": {
                    "service-a": { "runtime": "sh", "repository": "../repos/service-a" },
                    "service-b": { "runtime": "sh", "repository": "../repos/service-b-wt" },
                    "missing-repo": { "runtime": "sh", "repository": "../repos/not-cloned" }
                  },
                  "git": {
                    "hooks": {
                      "sourceDir": "./githooks",
                      "required": ["pre-commit"],
                      "mode": "copy"
                    },
                    "extraRepositories": ["../repos/plain-dir"]
                  }
                }
                """;

            Project project = Assert.assertOk(
                ProjectResolver.resolve(configDir.resolve("procomp.json"), configJson, p -> Result.ok(Optional.empty()), Map.of()),
                "resolve project"
            );

            FsGitHooksStore store = new FsGitHooksStore();
            GitHooksShell shell = new GitHooksShell(store);

            // 1. Initial status: service-a outdated, service-b-wt missing, not-cloned skipped, plain-dir non-git
            GitHooksReport.Configured before =
                (GitHooksReport.Configured) Assert.assertOk(shell.status(project), "initial status");
            Assert.assertTrue(before.hasWarnings(), "warnings detected");
            Assert.assertEquals(2, before.checkedRepositoryCount(), "2 git repos checked");
            Assert.assertEquals(2, before.repositoriesWithIssuesCount(), "2 repos with issues");
            Assert.assertEquals(1, before.missingHookCount(), "1 missing");
            Assert.assertEquals(1, before.outdatedHookCount(), "1 outdated");

            RepositoryHookStatus.Checked stdChecked = (RepositoryHookStatus.Checked) before.repositories().get(0);
            Assert.assertEquals(HookState.OUTDATED, stdChecked.hooks().get(0).state(), "service-a outdated");
            RepositoryHookStatus.Checked wtChecked = (RepositoryHookStatus.Checked) before.repositories().get(1);
            Assert.assertEquals(HookState.MISSING, wtChecked.hooks().get(0).state(), "worktree missing");
            Assert.assertEquals(mainGitDir.resolve("hooks").normalize(), wtChecked.hooksDir().normalize(), "worktree resolves commondir hooks");

            // 2. Install/upgrade all
            GitHooksInstallResult installed = Assert.assertOk(shell.install(project, List.of()), "install all");
            Assert.assertEquals(1, installed.totalInstalled(), "1 installed");
            Assert.assertEquals(1, installed.totalUpgraded(), "1 upgraded");

            // 3. Verify files on disk (and POSIX execute bit when supported)
            Path stdHookPath = stdRepo.resolve(".git/hooks/pre-commit");
            Path wtHookPath = mainGitDir.resolve("hooks/pre-commit");
            Assert.assertEquals(
                preCommitContent,
                Files.readString(stdHookPath, StandardCharsets.UTF_8),
                "service-a hook upgraded on disk"
            );
            Assert.assertEquals(
                preCommitContent,
                Files.readString(wtHookPath, StandardCharsets.UTF_8),
                "worktree hook installed into commondir hooks"
            );
            if (store.supportsPosixExecutableBit()) {
                Assert.assertTrue(Files.isExecutable(stdHookPath), "service-a hook is executable on POSIX");
                Assert.assertTrue(Files.isExecutable(wtHookPath), "worktree hook is executable on POSIX");
            }

            // 4. Re-check status: zero warnings
            GitHooksReport.Configured after =
                (GitHooksReport.Configured) Assert.assertOk(shell.status(project), "status after install");
            Assert.assertFalse(after.hasWarnings(), "zero warnings after install");
            Assert.assertEquals(0, after.missingHookCount(), "0 missing");
            Assert.assertEquals(0, after.outdatedHookCount(), "0 outdated");

            // 5. Verify COPY mode replaces an existing symlink without modifying the symlink's target file
            Path externalTarget = tempRoot.resolve("do-not-touch-target.sh");
            String sentinel = "#!/bin/sh\necho keep-untouched\n";
            Files.writeString(externalTarget, sentinel, StandardCharsets.UTF_8);
            Files.deleteIfExists(stdHookPath);
            boolean createdSymlink = false;
            try {
                Files.createSymbolicLink(stdHookPath, externalTarget);
                createdSymlink = true;
            } catch (IOException | UnsupportedOperationException ignored) {
                // Windows without Developer Mode / symlink privilege skips symlink creation cleanly.
            }
            if (createdSymlink) {
                Assert.assertOk(shell.install(project, List.of("service-a")), "replace symlink in copy mode");
                Assert.assertFalse(Files.isSymbolicLink(stdHookPath), "hook is now a regular file, not a symlink");
                Assert.assertEquals(preCommitContent, Files.readString(stdHookPath, StandardCharsets.UTF_8), "hook content replaced");
                Assert.assertEquals(sentinel, Files.readString(externalTarget, StandardCharsets.UTF_8), "original symlink target untouched");
            }
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            });
        }
    }
}
