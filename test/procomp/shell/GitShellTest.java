package procomp.shell;

import procomp.Assert;
import procomp.core.git.BranchInfo;
import procomp.core.git.GitOperation;
import procomp.core.git.GitRunRecord;
import procomp.core.git.GitRunStatus;
import procomp.core.git.RepositoryObservation;
import procomp.core.process.ObservedProcess;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.core.project.RepositoryRef;
import procomp.core.routing.Route;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeGitRepositoryReader;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.InMemoryStateStore;
import procomp.fakes.RecordingObserver;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GitShellTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    private static Project sampleProject() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String json = """
            {
              "project": "acme",
              "repositoriesRoot": "..",
              "runtimes": { "rt": { "command": ["echo"] } },
              "services": {
                "sap-facade": { "runtime": "rt", "repositoryUrl": "https://git.example/external-facades.git" },
                "salesforce-facade": { "runtime": "rt", "repositoryUrl": "https://git.example/external-facades.git" },
                "payments": { "runtime": "rt", "repositoryUrl": "https://git.example/payments.git" }
              }
            }
            """;
        return Assert.assertOk(
            ProjectResolver.resolve(configPath, json, _ -> procomp.core.common.Result.ok(Optional.empty()), Map.of()),
            "resolve sample project"
        );
    }

    public static void testDeduplicatesMonorepoTargetsAndScopesMutatingOpsToLocalByDefault() {
        Project project = sampleProject();
        InMemoryStateStore store = new InMemoryStateStore();
        FakeClock clock = new FakeClock(NOW);
        FakeProcessRunner runner = new FakeProcessRunner(clock);
        FakeGitRepositoryReader gitReader = new FakeGitRepositoryReader();
        RecordingObserver observer = new RecordingObserver();

        RepositoryRef facades = project.repository("external-facades").orElseThrow();
        RepositoryRef payments = project.repository("payments").orElseThrow();
        gitReader.putObservation(cleanCloned(facades, "main"));
        gitReader.putObservation(cleanCloned(payments, "main"));

        RoutingShell routingShell = new RoutingShell(store);
        Assert.assertOk(routingShell.updateRoutes(project, List.of("sap-facade", "salesforce-facade"), Route.LOCAL), "route facades local");

        GitShell gitShell = new GitShell(store, runner, gitReader, clock, observer);

        // Bare fetch targets only LOCAL services' deduplicated repositories (external-facades once, NOT payments)
        GitShell.GitBatchReport fetchReport = Assert.assertOk(
            gitShell.fetch(project, List.of(), false, false, Optional.empty()),
            "bare fetch succeeds"
        );
        Assert.assertEquals(1, fetchReport.outcomes().size(), "only 1 unique LOCAL repo fetched");
        Assert.assertEquals("external-facades", fetchReport.outcomes().get(0).repository().name(), "external-facades fetched");
        Assert.assertTrue(fetchReport.outcomes().get(0) instanceof GitShell.GitRepoOutcome.Succeeded, "fetch succeeded");

        // Passing both service names from the same monorepo still deduplicates to 1 execution
        GitShell.GitBatchReport dedupReport = Assert.assertOk(
            gitShell.fetch(project, List.of("sap-facade", "salesforce-facade"), false, false, Optional.empty()),
            "explicit monorepo services deduplicated"
        );
        Assert.assertEquals(1, dedupReport.outcomes().size(), "deduplicated to 1 repo");

        // Verify persisted GitRunRecord + GIT_TERMINAL_PROMPT=0
        GitRunRecord rec = Assert.assertOk(store.readGitRun("acme", "external-facades"), "read git run").orElseThrow();
        Assert.assertEquals(GitOperation.FETCH, rec.operation(), "operation recorded");
        Assert.assertEquals(GitRunStatus.SUCCEEDED, rec.status(), "status SUCCEEDED");
        Assert.assertEquals(Optional.of(0), rec.exitCode(), "exit code 0");
    }

    public static void testContinueOnErrorAndSwitchDefaultAndDirtyGuard() {
        Project project = sampleProject();
        InMemoryStateStore store = new InMemoryStateStore();
        FakeClock clock = new FakeClock(NOW);
        FakeProcessRunner runner = new FakeProcessRunner(clock);
        FakeGitRepositoryReader gitReader = new FakeGitRepositoryReader();

        RepositoryRef facades = project.repository("external-facades").orElseThrow();
        RepositoryRef payments = project.repository("payments").orElseThrow();

        // facades is dirty (untracked file!), payments is clean with origin/HEAD -> main
        gitReader.putObservation(new RepositoryObservation.Cloned(
            facades, new RepositoryObservation.HeadState.Branch("feature/a"), Optional.empty(),
            0, 0, 0, 0, 1, 0, Optional.of(NOW)
        ));
        gitReader.putObservation(cleanCloned(payments, "feature/b"));
        gitReader.putDefaultBranch(facades.root(), Optional.of("main"));
        gitReader.putDefaultBranch(payments.root(), Optional.of("main"));

        GitShell gitShell = new GitShell(store, runner, gitReader, clock, new RecordingObserver());

        GitShell.GitBatchReport report = Assert.assertOk(
            gitShell.switchBranch(project, Optional.empty(), true, List.of(), true, false, Optional.empty()),
            "switch --default --all runs continue-on-error"
        );
        Assert.assertTrue(report.hasFailures(), "dirty repo causes batch failure");
        Assert.assertEquals(2, report.outcomes().size(), "both repos processed");
        Assert.assertTrue(report.outcomes().get(0) instanceof GitShell.GitRepoOutcome.Failed, "dirty facades failed");
        Assert.assertEquals(
            "GIT_DIRTY_WORKING_TREE",
            ((GitShell.GitRepoOutcome.Failed) report.outcomes().get(0)).error().code(),
            "dirty error code"
        );
        Assert.assertTrue(report.outcomes().get(1) instanceof GitShell.GitRepoOutcome.Succeeded, "clean payments succeeded");
    }

    public static void testBranchesAndCancelRunningGitRun() {
        Project project = sampleProject();
        InMemoryStateStore store = new InMemoryStateStore();
        FakeClock clock = new FakeClock(NOW);
        FakeProcessRunner runner = new FakeProcessRunner(clock);
        FakeGitRepositoryReader gitReader = new FakeGitRepositoryReader();

        RepositoryRef facades = project.repository("external-facades").orElseThrow();
        gitReader.putObservation(cleanCloned(facades, "main"));
        gitReader.putBranches(facades.root(), List.of(
            new BranchInfo("feature/new", BranchInfo.RefOrigin.REMOTE, NOW, "New feat", false),
            new BranchInfo("main", BranchInfo.RefOrigin.LOCAL_AND_REMOTE, NOW.minusSeconds(3600), "Init", true)
        ));

        GitShell gitShell = new GitShell(store, runner, gitReader, clock, new RecordingObserver());
        GitShell.BranchesReport br = Assert.assertOk(gitShell.branches(project, "sap-facade"), "branches via service name");
        Assert.assertEquals("external-facades", br.repository().name(), "resolved to monorepo");
        Assert.assertEquals(2, br.branches().size(), "2 branches returned");
        Assert.assertEquals(List.of("sap-facade", "salesforce-facade"), br.services(), "shared services listed");

        // Simulate a live RUNNING git run and cancel it
        long pid = 4242L;
        runner.registerProcess(new ObservedProcess(
            pid, true, Optional.of(NOW), Optional.of("git clone"), List.of("clone"), List.of(), Optional.empty(), Optional.of("git")
        ));
        GitRunRecord running = new GitRunRecord.Running(
            facades.name(), GitOperation.CLONE, pid, NOW, "git clone ...",
            store.logPath("acme", procomp.shell.ports.ProcessKey.git(facades.name()), false),
            store.logPath("acme", procomp.shell.ports.ProcessKey.git(facades.name()), true)
        );
        Assert.assertOk(store.writeGitRun("acme", facades.name(), running), "seed running git run");

        GitShell.GitCancelReport cancelReport = Assert.assertOk(
            gitShell.cancel(project, List.of("external-facades"), false),
            "cancel succeeds"
        );
        Assert.assertFalse(cancelReport.hasFailures(), "no cancel failures");
        Assert.assertTrue(cancelReport.outcomes().get(0) instanceof GitShell.CancelOutcome.Cancelled, "cancelled outcome");
        GitRunRecord afterCancel = Assert.assertOk(store.readGitRun("acme", facades.name()), "read after cancel").orElseThrow();
        Assert.assertEquals(GitRunStatus.FAILED, afterCancel.status(), "cancelled run marked FAILED");
        Assert.assertEquals(Optional.of("Cancelled by user"), afterCancel.reason(), "cancel reason recorded");
    }

    private static RepositoryObservation.Cloned cleanCloned(RepositoryRef repo, String branch) {
        return new RepositoryObservation.Cloned(
            repo,
            new RepositoryObservation.HeadState.Branch(branch),
            Optional.of("origin/" + branch),
            0, 0, 0, 0, 0, 0,
            Optional.of(NOW)
        );
    }
}
