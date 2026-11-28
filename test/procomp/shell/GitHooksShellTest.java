package procomp.shell;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.git.GitHooksInstallResult;
import procomp.core.git.GitHooksReport;
import procomp.core.git.HookInstallAction;
import procomp.core.git.HookState;
import procomp.core.git.RepositoryHookStatus;
import procomp.core.git.RepositoryInstallOutcome;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.fakes.FakeGitHooksStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GitHooksShellTest {

    public static void testStatusAndInstallUpgradeLifecycleAcrossRepositories() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Path reposRoot = Path.of("C:/work/repos").toAbsolutePath().normalize();
        Path hooksDir = root.resolve("githooks");

        String configJson = """
            {
              "project": "acme",
              "repositoriesRoot": "../repos",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "services": {
                "orders": { "runtime": "java", "repositoryUrl": "https://git.example/orders.git" },
                "billing": { "runtime": "java", "repositoryUrl": "https://git.example/billing.git" },
                "uncloned": { "runtime": "java", "repositoryUrl": "https://git.example/uncloned.git" }
              },
              "git": {
                "hooks": {
                  "sourceDir": "{{configDir}}/githooks"
                },
                "extraRepositories": ["{{configDir}}"]
              }
            }
            """;

        Project project = Assert.assertOk(
            ProjectResolver.resolve(root.resolve("procomp.json"), configJson, p -> Result.ok(Optional.empty()), Map.of()),
            "resolve project"
        );

        Path ordersRepo = reposRoot.resolve("orders");
        Path billingRepo = reposRoot.resolve("billing");

        FakeGitHooksStore store = new FakeGitHooksStore()
            .withSourceFile(hooksDir.resolve("pre-commit"), "#!/bin/sh\necho v2\n")
            .withSourceFile(hooksDir.resolve("commit-msg"), "#!/bin/sh\necho msg-v2\n")
            .withGitRepository(ordersRepo)
            .withInstalledHook(ordersRepo, "pre-commit", "#!/bin/sh\necho v1\n", true) // outdated
            // commit-msg missing in orders
            .withGitRepository(billingRepo)
            .withInstalledHook(billingRepo, "pre-commit", "#!/bin/sh\necho v2\n", true)
            .withInstalledHook(billingRepo, "commit-msg", "#!/bin/sh\necho msg-v2\n", true)
            .withNonGitDirectory(root); // extraRepo exists but is not a git repo

        GitHooksShell shell = new GitHooksShell(store);

        // 1. Initial status detects warnings in orders only
        GitHooksReport.Configured before =
            (GitHooksReport.Configured) Assert.assertOk(shell.status(project), "initial status");
        Assert.assertTrue(before.hasWarnings(), "has warnings");
        Assert.assertEquals(2, before.checkedRepositoryCount(), "2 checked git repos");
        Assert.assertEquals(1, before.repositoriesWithIssuesCount(), "1 repo with issues");
        Assert.assertEquals(1, before.missingHookCount(), "1 missing hook");
        Assert.assertEquals(1, before.outdatedHookCount(), "1 outdated hook");

        RepositoryHookStatus.Checked ordersBefore = (RepositoryHookStatus.Checked) before.repositories().get(0);
        Assert.assertEquals(HookState.MISSING, ordersBefore.hooks().get(0).state(), "commit-msg missing");
        Assert.assertEquals(HookState.OUTDATED, ordersBefore.hooks().get(1).state(), "pre-commit outdated");

        // 2. Install/upgrade across all repositories
        GitHooksInstallResult installRes = Assert.assertOk(shell.install(project, List.of()), "install all");
        Assert.assertEquals(1, installRes.totalInstalled(), "1 installed");
        Assert.assertEquals(1, installRes.totalUpgraded(), "1 upgraded");
        Assert.assertEquals(2, installRes.totalUnchanged(), "2 unchanged in billing");
        Assert.assertTrue(installRes.repositories().get(2) instanceof RepositoryInstallOutcome.SkippedNotCloned, "uncloned skipped");
        Assert.assertTrue(installRes.repositories().get(3) instanceof RepositoryInstallOutcome.SkippedNotGitRepository, "non-git skipped");

        RepositoryInstallOutcome.Applied ordersApplied = (RepositoryInstallOutcome.Applied) installRes.repositories().get(0);
        Assert.assertEquals(HookInstallAction.INSTALLED, ordersApplied.hooks().get(0).action(), "commit-msg installed");
        Assert.assertEquals(HookInstallAction.UPGRADED, ordersApplied.hooks().get(1).action(), "pre-commit upgraded");

        // 3. Re-check status: zero warnings
        GitHooksReport.Configured after =
            (GitHooksReport.Configured) Assert.assertOk(shell.status(project), "status after install");
        Assert.assertFalse(after.hasWarnings(), "no warnings after install");
        Assert.assertEquals(0, after.missingHookCount(), "0 missing");
        Assert.assertEquals(0, after.outdatedHookCount(), "0 outdated");
    }

    public static void testUnconfiguredGitHooksAndMissingSourceErrors() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        String noGitJson = """
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } }
            }
            """;
        Project noGitProject = Assert.assertOk(
            ProjectResolver.resolve(root.resolve("procomp.json"), noGitJson, p -> Result.ok(Optional.empty()), Map.of()),
            "resolve no-git project"
        );
        GitHooksShell shell = new GitHooksShell(new FakeGitHooksStore());
        GitHooksReport report = Assert.assertOk(shell.status(noGitProject), "status unconfigured");
        Assert.assertTrue(report instanceof GitHooksReport.NotConfigured, "not configured variant");
        Assert.assertFalse(report.hasWarnings(), "no warnings");

        DomainError installErr = Assert.assertErr(shell.install(noGitProject, List.of()), "install unconfigured");
        Assert.assertEquals("GIT_HOOKS_NOT_CONFIGURED", installErr.code(), "not configured error");

        // Missing source file fails fast in status() but degrades to SourceUnavailable in statusForEstate()
        String missingSourceJson = """
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "git": { "hooks": { "files": { "pre-commit": "./missing/pre-commit" } } }
            }
            """;
        Project missingSourceProject = Assert.assertOk(
            ProjectResolver.resolve(root.resolve("procomp.json"), missingSourceJson, p -> Result.ok(Optional.empty()), Map.of()),
            "resolve missing source project"
        );
        DomainError sourceErr = Assert.assertErr(shell.status(missingSourceProject), "missing source fails fast");
        Assert.assertEquals("GIT_HOOK_SOURCE_NOT_FOUND", sourceErr.code(), "source not found code");

        GitHooksReport estateReport = Assert.assertOk(shell.statusForEstate(missingSourceProject), "estate status degrades cleanly");
        Assert.assertTrue(estateReport instanceof GitHooksReport.SourceUnavailable, "SourceUnavailable variant");
        Assert.assertTrue(estateReport.hasWarnings(), "SourceUnavailable surfaces warning");
    }
}
