package procomp.cli;

import procomp.Assert;
import procomp.core.git.BranchInfo;
import procomp.core.git.RepositoryObservation;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.core.project.RepositoryRef;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeGitRepositoryReader;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeProjectFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GitCliTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    public static void testGitCliStatusBranchesFetchSwitchAndRejectsAuthoringFlags() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String json = """
            {
              "project": "acme",
              "repositoriesRoot": "..",
              "runtimes": { "rt": { "command": ["echo"] } },
              "services": {
                "orders": { "runtime": "rt", "repositoryUrl": "https://git.example/orders.git" }
              }
            }
            """;
        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, json, _ -> procomp.core.common.Result.ok(Optional.empty()), Map.of()),
            "resolve"
        );
        RepositoryRef ordersRepo = project.repository("orders").orElseThrow();

        FakeClock clock = new FakeClock(NOW);
        InMemoryConfigSource configSource = new InMemoryConfigSource(configPath.getParent()).withFile(configPath, json);
        InMemoryStateStore stateStore = new InMemoryStateStore();
        FakeProcessRunner runner = new FakeProcessRunner(clock);
        FakeGitRepositoryReader gitReader = new FakeGitRepositoryReader();
        gitReader.putObservation(new RepositoryObservation.Cloned(
            ordersRepo, new RepositoryObservation.HeadState.Branch("main"), Optional.of("origin/main"),
            0, 1, 0, 0, 0, 0, Optional.of(NOW.minusSeconds(3600))
        ));
        gitReader.putBranches(ordersRepo.root(), List.of(
            new BranchInfo("feature/a", BranchInfo.RefOrigin.REMOTE, NOW, "Feat A", false),
            new BranchInfo("main", BranchInfo.RefOrigin.LOCAL_AND_REMOTE, NOW.minusSeconds(7200), "Init", true)
        ));

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        CliRuntime runtime = CliRuntime.builder(new PrintWriter(outBuf), new PrintWriter(errBuf))
            .configSource(configSource)
            .stateStore(stateStore)
            .processRunner(runner)
            .portScanner(new FakePortScanner())
            .httpClient(new FakeHttpClient())
            .projectFiles(new FakeProjectFiles())
            .gitRepositoryReader(gitReader)
            .clock(clock)
            .build();

        // 1. procomp git status
        int rcStatus = Procomp.run(new String[] {"--config", configPath.toString(), "git", "status"}, runtime);
        Assert.assertEquals(0, rcStatus, "git status exits 0");
        Assert.assertTrue(outBuf.toString().contains("orders"), "git status includes orders");
        Assert.assertTrue(outBuf.toString().contains("main"), "git status includes branch main");

        // 2. procomp status includes GIT column
        outBuf.getBuffer().setLength(0);
        int rcEstate = Procomp.run(new String[] {"--config", configPath.toString(), "status"}, runtime);
        Assert.assertEquals(0, rcEstate, "procomp status exits 0");
        Assert.assertTrue(outBuf.toString().contains("GIT"), "procomp status renders GIT column");
        Assert.assertTrue(outBuf.toString().contains("main ↓1"), "procomp status renders compact git state");

        // 3. procomp git branches orders --json
        outBuf.getBuffer().setLength(0);
        int rcBranches = Procomp.run(new String[] {"--config", configPath.toString(), "git", "branches", "orders", "--json"}, runtime);
        Assert.assertEquals(0, rcBranches, "git branches exits 0");
        Assert.assertTrue(outBuf.toString().contains("\"feature/a\""), "branches JSON contains feature/a");

        // 4. Rejects authoring flags (-c on switch, pop on stash)
        errBuf.getBuffer().setLength(0);
        int rcCreate = Procomp.run(new String[] {"--config", configPath.toString(), "git", "switch", "-c", "new-branch"}, runtime);
        Assert.assertEquals(2, rcCreate, "git switch -c rejected with exit 2");
        Assert.assertTrue(errBuf.toString().contains("Unknown option '-c'"), "rejects -c");
    }
}
