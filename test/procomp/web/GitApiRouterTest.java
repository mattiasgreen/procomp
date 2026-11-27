package procomp.web;

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
import procomp.shell.ProcompRuntime;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GitApiRouterTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    public static void testGitApiStatusBranchesSwitchStashAndEchoesVerbatimCliCommands() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String json = """
            {
              "project": "acme",
              "repositoriesRoot": "..",
              "runtimes": { "rt": { "command": ["echo"] } },
              "services": {
                "sap-facade": { "runtime": "rt", "repositoryUrl": "https://git.example/external-facades.git" },
                "salesforce-facade": { "runtime": "rt", "repositoryUrl": "https://git.example/external-facades.git" }
              }
            }
            """;
        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, json, _ -> procomp.core.common.Result.ok(Optional.empty()), Map.of()),
            "resolve"
        );
        RepositoryRef facades = project.repository("external-facades").orElseThrow();

        FakeClock clock = new FakeClock(NOW);
        InMemoryConfigSource configSource = new InMemoryConfigSource(configPath.getParent()).withFile(configPath, json);
        InMemoryStateStore stateStore = new InMemoryStateStore();
        FakeProcessRunner runner = new FakeProcessRunner(clock);
        FakeGitRepositoryReader gitReader = new FakeGitRepositoryReader();
        gitReader.putObservation(new RepositoryObservation.Cloned(
            facades, new RepositoryObservation.HeadState.Branch("main"), Optional.of("origin/main"),
            0, 0, 0, 0, 1, 0, Optional.of(NOW)
        ));
        gitReader.putBranches(facades.root(), List.of(
            new BranchInfo("feature/other", BranchInfo.RefOrigin.REMOTE, NOW, "Other team feat", false),
            new BranchInfo("main", BranchInfo.RefOrigin.LOCAL_AND_REMOTE, NOW.minusSeconds(3600), "Init", true)
        ));

        ProcompRuntime runtime = ProcompRuntime.builder()
            .configSource(configSource)
            .stateStore(stateStore)
            .processRunner(runner)
            .portScanner(new FakePortScanner())
            .httpClient(new FakeHttpClient())
            .projectFiles(new FakeProjectFiles())
            .gitRepositoryReader(gitReader)
            .clock(clock)
            .build();
        LiveProjectHolder holder = new LiveProjectHolder(
            Assert.assertOk(runtime.projectLoader(), "projectLoader"),
            Optional.of(configPath)
        );
        ApiRouter router = new ApiRouter(runtime, holder, 9090);

        Map<String, String> getHeaders = Map.of("Host", "127.0.0.1:9090");
        Map<String, String> postHeaders = Map.of(
            "Host", "127.0.0.1:9090",
            "Origin", "http://127.0.0.1:9090",
            "X-Procomp-Request", "1"
        );

        // 1. GET /api/git/branches/sap-facade -> lists branches, shared services, dirty=true
        ApiRouter.ApiResponse brRes = router.handle(new ApiRouter.ApiRequest(
            "GET", "/api/git/branches/sap-facade", "", getHeaders, ""
        ));
        Assert.assertEquals(200, brRes.statusCode(), "branches 200");
        Assert.assertTrue(brRes.body().contains("\"feature/other\""), "contains remote feature branch");
        Assert.assertTrue(brRes.body().contains("\"dirty\":true"), "dirty flag present");
        Assert.assertTrue(brRes.body().contains("\"salesforce-facade\""), "lists shared monorepo services");

        // 2. POST /api/git/switch while dirty -> 409 with GIT_DIRTY_WORKING_TREE + verbatim cliCommand
        ApiRouter.ApiResponse dirtySwitch = router.handle(new ApiRouter.ApiRequest(
            "POST", "/api/git/switch", "", postHeaders,
            "{\"branch\":\"feature/other\",\"targets\":[\"external-facades\"]}"
        ));
        Assert.assertEquals(409, dirtySwitch.statusCode(), "dirty switch returns 409");
        Assert.assertTrue(dirtySwitch.body().contains("GIT_DIRTY_WORKING_TREE"), "dirty error code");
        Assert.assertTrue(dirtySwitch.body().contains("git switch feature/other external-facades"), "echoes verbatim CLI command");

        // 3. POST /api/git/stash with includeUntracked: true -> 200 + verbatim cliCommand with -u
        ApiRouter.ApiResponse stashRes = router.handle(new ApiRouter.ApiRequest(
            "POST", "/api/git/stash", "", postHeaders,
            "{\"targets\":[\"external-facades\"],\"includeUntracked\":true}"
        ));
        Assert.assertEquals(200, stashRes.statusCode(), "stash 200");
        Assert.assertTrue(stashRes.body().contains("git stash push external-facades -u"), "echoes stash -u CLI command");
    }
}
