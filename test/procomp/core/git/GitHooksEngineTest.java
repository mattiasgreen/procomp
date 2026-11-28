package procomp.core.git;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.GitConfig;
import procomp.core.project.GitHookInstallMode;
import procomp.core.project.GitHooksConfig;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.core.project.RepositoryRef;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GitHooksEngineTest {

    public static void testEvaluateHookCopyAndSymlinkModes() {
        Path source = Path.of("C:/work/acme/hooks/pre-commit").normalize();
        Path hooksDir = Path.of("C:/work/repos/orders/.git/hooks").normalize();
        HookSpec spec = new HookSpec("pre-commit", source, "digest-v2");

        // Missing -> MISSING
        HookCheckResult missing = GitHooksEngine.evaluateHook(
            spec, hooksDir, new ObservedHookFile.Missing(), GitHookInstallMode.COPY, true
        );
        Assert.assertEquals(HookState.MISSING, missing.state(), "missing hook");

        // Copy mode: matching digest + executable -> INSTALLED
        HookCheckResult okCopy = GitHooksEngine.evaluateHook(
            spec,
            hooksDir,
            new ObservedHookFile.RegularFile("digest-v2", true),
            GitHookInstallMode.COPY,
            true
        );
        Assert.assertEquals(HookState.INSTALLED, okCopy.state(), "copy mode installed");

        // Copy mode: matching digest + not executable -> NOT_EXECUTABLE on POSIX, INSTALLED on non-POSIX
        ObservedHookFile notExec = new ObservedHookFile.RegularFile("digest-v2", false);
        Assert.assertEquals(
            HookState.NOT_EXECUTABLE,
            GitHooksEngine.evaluateHook(spec, hooksDir, notExec, GitHookInstallMode.COPY, true).state(),
            "posix requires executable bit"
        );
        Assert.assertEquals(
            HookState.INSTALLED,
            GitHooksEngine.evaluateHook(spec, hooksDir, notExec, GitHookInstallMode.COPY, false).state(),
            "non-posix ignores executable bit"
        );

        // Copy mode: different digest -> OUTDATED
        HookCheckResult outdated = GitHooksEngine.evaluateHook(
            spec,
            hooksDir,
            new ObservedHookFile.RegularFile("digest-v1", true),
            GitHookInstallMode.COPY,
            true
        );
        Assert.assertEquals(HookState.OUTDATED, outdated.state(), "outdated digest");

        // Copy mode: symlink when copy mode requested -> OUTDATED
        HookCheckResult symlinkInCopy = GitHooksEngine.evaluateHook(
            spec,
            hooksDir,
            new ObservedHookFile.Symlink(source, Optional.of("digest-v2"), true),
            GitHookInstallMode.COPY,
            true
        );
        Assert.assertEquals(HookState.OUTDATED, symlinkInCopy.state(), "symlink in copy mode is outdated");

        // Symlink mode: matching symlink + digest -> INSTALLED
        HookCheckResult okSymlink = GitHooksEngine.evaluateHook(
            spec,
            hooksDir,
            new ObservedHookFile.Symlink(source, Optional.of("digest-v2"), true),
            GitHookInstallMode.SYMLINK,
            true
        );
        Assert.assertEquals(HookState.INSTALLED, okSymlink.state(), "symlink mode installed");

        // Symlink mode: wrong target -> OUTDATED
        HookCheckResult wrongSymlink = GitHooksEngine.evaluateHook(
            spec,
            hooksDir,
            new ObservedHookFile.Symlink(Path.of("C:/other/pre-commit"), Optional.of("digest-v2"), true),
            GitHookInstallMode.SYMLINK,
            true
        );
        Assert.assertEquals(HookState.OUTDATED, wrongSymlink.state(), "symlink pointing elsewhere is outdated");

        // Unreadable (e.g., directory at hook path) -> OUTDATED
        Assert.assertEquals(
            HookState.OUTDATED,
            GitHooksEngine.evaluateHook(spec, hooksDir, new ObservedHookFile.Unreadable(), GitHookInstallMode.COPY, true).state(),
            "unreadable hook is outdated"
        );
    }

    public static void testCollectTargetRepositoriesDeduplicatesAndPreservesOrder() {
        Path root = Path.of("C:/work/acme").normalize();
        Path repoA = Path.of("C:/work/repos/service-a").normalize();
        Path repoB = Path.of("C:/work/repos/service-b").normalize();
        Path extraRepo = Path.of("C:/work/repos/gitops").normalize();

        Project project = new Project(
            "acme",
            root.resolve("procomp.json"),
            root,
            Optional.of(Path.of("C:/work/repos").normalize()),
            Map.of(),
            Map.of(),
            Optional.empty(),
            Map.of(),
            Map.of(),
            List.of(
                new RepositoryRef(repoA, Optional.of("https://example.com/a.git")),
                new RepositoryRef(repoB, Optional.empty())
            ),
            Optional.of(new GitConfig(
                Optional.of(new GitHooksConfig(Optional.of(root.resolve("hooks")), List.of("pre-commit"), Map.of(), GitHookInstallMode.COPY)),
                List.of(repoA, extraRepo)
            ))
        );

        List<RepositoryRef> targets = GitHooksEngine.collectTargetRepositories(project);
        Assert.assertEquals(3, targets.size(), "deduplicated repo count");
        Assert.assertEquals(repoA, targets.get(0).root(), "first repo");
        Assert.assertEquals(Optional.of("https://example.com/a.git"), targets.get(0).remoteUrl(), "preserves remoteUrl");
        Assert.assertEquals(repoB, targets.get(1).root(), "second repo");
        Assert.assertEquals(extraRepo, targets.get(2).root(), "extra repo appended");
    }

    public static void testSelectRepositoriesAndPlannedAction() {
        Path repoA = Path.of("C:/work/repos/service-a").normalize();
        Path repoB = Path.of("C:/work/repos/service-b").normalize();
        List<RepositoryHookStatus> statuses = List.of(
            new RepositoryHookStatus.NotCloned(repoA, Optional.empty()),
            new RepositoryHookStatus.NotGitRepository(repoB, Optional.empty())
        );

        Assert.assertEquals(2, Assert.assertOk(GitHooksEngine.selectRepositories(statuses, List.of()), "empty filter").size(), "all repos");
        List<RepositoryHookStatus> byName = Assert.assertOk(GitHooksEngine.selectRepositories(statuses, List.of("service-b")), "by name");
        Assert.assertEquals(1, byName.size(), "matched 1");
        Assert.assertEquals(repoB, byName.get(0).repositoryRoot(), "matched service-b");

        DomainError unknown = Assert.assertErr(GitHooksEngine.selectRepositories(statuses, List.of("missing-repo")), "unknown repo");
        Assert.assertEquals("GIT_REPOSITORY_NOT_FOUND", unknown.code(), "unknown repo error code");

        Assert.assertEquals(HookInstallAction.UNCHANGED, GitHooksEngine.plannedActionFor(HookState.INSTALLED), "installed -> unchanged");
        Assert.assertEquals(HookInstallAction.INSTALLED, GitHooksEngine.plannedActionFor(HookState.MISSING), "missing -> installed");
        Assert.assertEquals(HookInstallAction.UPGRADED, GitHooksEngine.plannedActionFor(HookState.OUTDATED), "outdated -> upgraded");
        Assert.assertEquals(HookInstallAction.UPGRADED, GitHooksEngine.plannedActionFor(HookState.NOT_EXECUTABLE), "not executable -> upgraded");
    }

    public static void testProjectResolverParsesAndValidatesGitConfig() {
        Path configPath = Path.of("C:/work/acme/procomp.json").normalize();
        String validJson = """
            {
              "project": "acme",
              "repositoriesRoot": "../repos",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "services": {
                "orders": { "runtime": "java", "repositoryUrl": "https://git.example/orders.git" }
              },
              "git": {
                "hooks": {
                  "sourceDir": "{{configDir}}/githooks",
                  "required": ["pre-commit", "commit-msg"],
                  "files": {
                    "pre-push": "./custom/pre-push"
                  },
                  "mode": "symlink"
                },
                "extraRepositories": ["{{configDir}}", "{{repositoriesRoot}}/shared-config"]
              }
            }
            """;
        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, validJson, p -> Result.ok(Optional.empty()), Map.of()),
            "resolve valid git config"
        );
        Assert.assertTrue(project.git().isPresent(), "git config present");
        GitConfig git = project.git().get();
        Assert.assertTrue(git.hooks().isPresent(), "git.hooks present");
        GitHooksConfig hooks = git.hooks().get();
        Assert.assertEquals(Optional.of(Path.of("C:/work/acme/githooks").normalize()), hooks.sourceDir(), "sourceDir resolved");
        Assert.assertEquals(List.of("pre-commit", "commit-msg"), hooks.requiredHooks(), "required hooks");
        Assert.assertEquals(Path.of("C:/work/acme/custom/pre-push").normalize(), hooks.explicitFiles().get("pre-push"), "explicit file resolved");
        Assert.assertEquals(GitHookInstallMode.SYMLINK, hooks.mode(), "symlink mode");
        Assert.assertEquals(2, git.extraRepositories().size(), "extra repositories count");

        // Reject invalid mode
        String badModeJson = """
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "git": { "hooks": { "sourceDir": "./hooks", "mode": "hardlink" } }
            }
            """;
        DomainError badMode = Assert.assertErr(
            ProjectResolver.resolve(configPath, badModeJson, p -> Result.ok(Optional.empty()), Map.of()),
            "reject invalid mode"
        );
        Assert.assertEquals("CONFIG_INVALID_VALUE", badMode.code(), "invalid mode code");

        // Reject unknown field under git.hooks
        String badFieldJson = """
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "git": { "hooks": { "sourceDir": "./hooks", "unknownKey": true } }
            }
            """;
        DomainError badField = Assert.assertErr(
            ProjectResolver.resolve(configPath, badFieldJson, p -> Result.ok(Optional.empty()), Map.of()),
            "reject unknown git.hooks field"
        );
        Assert.assertEquals("CONFIG_UNRECOGNIZED_FIELD", badField.code(), "unrecognized field code");

        // Reject required without sourceDir
        String reqWithoutDirJson = """
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "git": { "hooks": { "required": ["pre-commit"], "files": { "pre-commit": "./pre-commit" } } }
            }
            """;
        DomainError reqWithoutDir = Assert.assertErr(
            ProjectResolver.resolve(configPath, reqWithoutDirJson, p -> Result.ok(Optional.empty()), Map.of()),
            "reject required without sourceDir"
        );
        Assert.assertEquals("CONFIG_MISSING_FIELD", reqWithoutDir.code(), "missing sourceDir code");

        // Reject empty git.hooks (neither sourceDir nor files)
        String emptyHooksJson = """
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "git": { "hooks": {} }
            }
            """;
        DomainError emptyHooks = Assert.assertErr(
            ProjectResolver.resolve(configPath, emptyHooksJson, p -> Result.ok(Optional.empty()), Map.of()),
            "reject empty git.hooks"
        );
        Assert.assertEquals("CONFIG_MISSING_FIELD", emptyHooks.code(), "empty hooks missing field code");

        // Reject path-traversal hook name
        String traversalHookJson = """
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "git": { "hooks": { "sourceDir": "./hooks", "required": ["../evil"] } }
            }
            """;
        DomainError traversalHook = Assert.assertErr(
            ProjectResolver.resolve(configPath, traversalHookJson, p -> Result.ok(Optional.empty()), Map.of()),
            "reject path-traversal hook name"
        );
        Assert.assertEquals("CONFIG_INVALID_VALUE", traversalHook.code(), "path traversal invalid value code");
    }
}
