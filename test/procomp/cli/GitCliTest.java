package procomp.cli;

import procomp.Assert;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeGitHooksStore;
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

public final class GitCliTest {

    public static void testStatusWarningAndGitHooksInstallAndUpgradeCommands() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Path reposRoot = Path.of("C:/work/repos").toAbsolutePath().normalize();
        Path ordersRepo = reposRoot.resolve("orders");
        Path billingRepo = reposRoot.resolve("billing");

        var config = new InMemoryConfigSource(root).withFile(root.resolve("procomp.json"), """
            {
              "project": "acme",
              "repositoriesRoot": "../repos",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "services": {
                "orders": { "runtime": "java", "port": 9001, "repositoryUrl": "https://git.example/orders.git" },
                "billing": { "runtime": "java", "port": 9002, "repositoryUrl": "https://git.example/billing.git" }
              },
              "git": {
                "hooks": {
                  "sourceDir": "{{configDir}}/hooks",
                  "required": ["pre-commit"]
                }
              }
            }
            """);

        var gitHooksStore = new FakeGitHooksStore()
            .withSourceFile(root.resolve("hooks/pre-commit"), "#!/bin/sh\necho strict-v2\n")
            .withGitRepository(ordersRepo)
            .withGitRepository(billingRepo)
            .withInstalledHook(billingRepo, "pre-commit", "#!/bin/sh\necho old-v1\n", true);

        var stateStore = new InMemoryStateStore();
        var clock = new FakeClock(Instant.parse("2026-11-25T05:00:00Z"));
        var processRunner = new FakeProcessRunner(clock);
        var portScanner = new FakePortScanner();
        var httpClient = new FakeHttpClient();
        var projectFiles = new FakeProjectFiles()
            .withDirectory(ordersRepo)
            .withDirectory(billingRepo);

        // 1. `procomp status` surfaces non-mutating Git hooks warning
        CliResult statusBefore = runCli(config, stateStore, processRunner, portScanner, httpClient, projectFiles, gitHooksStore, clock, "status");
        Assert.assertEquals(0, statusBefore.exitCode(), "status exits 0; stderr=" + statusBefore.stderr());
        Assert.assertContains(statusBefore.stdout(), "Warning: Mandatory Git hooks need attention", "status prints git hooks warning");
        Assert.assertContains(statusBefore.stdout(), "procomp git hooks install", "status hints install command");

        // 2. `procomp git hooks status --json`
        CliResult jsonStatus = runCli(config, stateStore, processRunner, portScanner, httpClient, projectFiles, gitHooksStore, clock, "git", "hooks", "status", "--json");
        Assert.assertEquals(0, jsonStatus.exitCode(), "git hooks status --json exits 0");
        JsonValue.JsonObject statusObj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(jsonStatus.stdout()), "valid json");
        Assert.assertEquals(true, ((JsonValue.JsonBoolean) statusObj.get("hasWarnings").orElseThrow()).value(), "hasWarnings true");

        // 3. `procomp git hooks install orders` installs only orders
        CliResult installOrders = runCli(config, stateStore, processRunner, portScanner, httpClient, projectFiles, gitHooksStore, clock, "git", "hooks", "install", "orders");
        Assert.assertEquals(0, installOrders.exitCode(), "install orders exits 0; stderr=" + installOrders.stderr());
        Assert.assertContains(installOrders.stdout(), "1 installed, 0 upgraded, 0 unchanged", "orders installed");

        // 4. `procomp git hooks upgrade` upgrades remaining billing repo
        CliResult upgradeAll = runCli(config, stateStore, processRunner, portScanner, httpClient, projectFiles, gitHooksStore, clock, "git", "hooks", "upgrade");
        Assert.assertEquals(0, upgradeAll.exitCode(), "upgrade exits 0; stderr=" + upgradeAll.stderr());
        Assert.assertContains(upgradeAll.stdout(), "0 installed, 1 upgraded, 1 unchanged", "billing upgraded");

        // 5. `procomp status` no longer prints the warning
        CliResult statusAfter = runCli(config, stateStore, processRunner, portScanner, httpClient, projectFiles, gitHooksStore, clock, "status");
        Assert.assertEquals(0, statusAfter.exitCode(), "status after exits 0");
        Assert.assertFalse(statusAfter.stdout().contains("Warning: Mandatory Git hooks"), "warning cleared after install");

        // 6. Invalid subcommand returns exit code 2
        CliResult invalid = runCli(config, stateStore, processRunner, portScanner, httpClient, projectFiles, gitHooksStore, clock, "git", "bogus");
        Assert.assertEquals(2, invalid.exitCode(), "invalid subcommand exits 2");

        // 7. Missing hook source dir: `procomp status` degrades gracefully to warning, `procomp git hooks status` fails fast
        var emptyHooksStore = new FakeGitHooksStore().withGitRepository(ordersRepo);
        CliResult degradedStatus = runCli(config, stateStore, processRunner, portScanner, httpClient, projectFiles, emptyHooksStore, clock, "status");
        Assert.assertEquals(0, degradedStatus.exitCode(), "status still exits 0 when hook source is missing; stderr=" + degradedStatus.stderr());
        Assert.assertContains(degradedStatus.stdout(), "orders", "status still renders services table");
        Assert.assertContains(degradedStatus.stdout(), "Warning: Mandatory Git hooks could not be checked", "status prints source-unavailable warning");

        CliResult failFastHooks = runCli(config, stateStore, processRunner, portScanner, httpClient, projectFiles, emptyHooksStore, clock, "git", "hooks", "status");
        Assert.assertEquals(1, failFastHooks.exitCode(), "git hooks status fails fast on missing source");
        Assert.assertContains(failFastHooks.stderr(), "GIT_HOOK_SOURCE_NOT_FOUND", "reports GIT_HOOK_SOURCE_NOT_FOUND");
    }

    private static CliResult runCli(
        InMemoryConfigSource config,
        InMemoryStateStore stateStore,
        FakeProcessRunner processRunner,
        FakePortScanner portScanner,
        FakeHttpClient httpClient,
        FakeProjectFiles projectFiles,
        FakeGitHooksStore gitHooksStore,
        FakeClock clock,
        String... args
    ) {
        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        CliRuntime runtime = CliRuntime.builder(new PrintWriter(outBuf), new PrintWriter(errBuf))
            .configSource(config)
            .stateStore(stateStore)
            .processRunner(processRunner)
            .portScanner(portScanner)
            .httpClient(httpClient)
            .projectFiles(projectFiles)
            .gitHooksStore(gitHooksStore)
            .clock(clock)
            .build();
        int code = Procomp.run(args, runtime);
        return new CliResult(code, outBuf.toString(), errBuf.toString());
    }

    private record CliResult(int exitCode, String stdout, String stderr) {}
}
