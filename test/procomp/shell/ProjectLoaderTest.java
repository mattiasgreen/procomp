package procomp.shell;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.project.Project;
import procomp.fakes.InMemoryConfigSource;

import java.nio.file.Path;
import java.util.Optional;

public final class ProjectLoaderTest {

    public static void testDiscoversConfigByWalkingUpFromSubdirectory() {
        Path repoRoot = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Path subDir = repoRoot.resolve("services/orders/src");

        var source = new InMemoryConfigSource(subDir)
            .withFile(repoRoot.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "repositoriesRoot": ".",
                  "envFiles": [{ "path": ".env.local", "optional": true }],
                  "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                  },
                  "catalog": {
                    "path": "service-index.json",
                    "itemsPath": "apps",
                    "map": {
                      "name": "servicename",
                      "port": "localport",
                      "runtime": "type",
                      "repositoryUrl": "url"
                    }
                  }
                }
                """)
            .withFile(repoRoot.resolve("service-index.json"), """
                {
                  "apps": [
                    {
                      "servicename": "orders",
                      "localport": "9001",
                      "type": "java",
                      "url": "https://git.corp/acme/orders.git",
                      "ignored": "column"
                    }
                  ]
                }
                """);

        ProjectLoader loader = new ProjectLoader(source);
        Project project = Assert.assertOk(loader.load(Optional.empty()), "walk-up discovery should find procomp.json");
        Assert.assertEquals("acme", project.name(), "project name");
        Assert.assertEquals(repoRoot.resolve("procomp.json"), project.configPath(), "discovered configPath");
        Assert.assertEquals(9001, project.service("orders").orElseThrow().port().orElseThrow(), "catalog port coerced");
    }

    public static void testConfigOverridePrecedenceCliOverEnvOverWalkUp() {
        Path cwd = Path.of("C:/work/acme/sub").toAbsolutePath().normalize();
        Path walkUpCfg = Path.of("C:/work/acme/procomp.json").toAbsolutePath().normalize();
        Path envCfg = Path.of("C:/work/env.procomp.json").toAbsolutePath().normalize();
        Path cliCfg = Path.of("C:/work/cli.procomp.json").toAbsolutePath().normalize();

        var source = new InMemoryConfigSource(cwd)
            .withFile(walkUpCfg, "{\"project\":\"walk\",\"runtimes\":{}}")
            .withFile(envCfg, "{\"project\":\"from-env\",\"runtimes\":{}}")
            .withFile(cliCfg, "{\"project\":\"from-cli\",\"runtimes\":{}}")
            .withEnv("PROCOMP_CONFIG", envCfg.toString());

        ProjectLoader loader = new ProjectLoader(source);

        Project cliWins = Assert.assertOk(loader.load(Optional.of(Path.of("../../cli.procomp.json"))), "--config override wins");
        Assert.assertEquals("from-cli", cliWins.name(), "CLI override takes precedence over PROCOMP_CONFIG");

        Project envWins = Assert.assertOk(loader.load(Optional.empty()), "PROCOMP_CONFIG wins over walk-up");
        Assert.assertEquals("from-env", envWins.name(), "PROCOMP_CONFIG takes precedence over walk-up");
    }

    public static void testFailsFastOnMissingConfigMissingRequiredEnvFileAndUnsetHostEnvWithoutLeakingSecrets() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var emptySource = new InMemoryConfigSource(root);
        DomainError notFound = Assert.assertErr(new ProjectLoader(emptySource).load(Optional.empty()), "missing config fails");
        Assert.assertEquals("CONFIG_NOT_FOUND", notFound.code(), "missing config error code");

        var missingEnvSource = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "envFiles": [".env.must-exist"],
                  "runtimes": { "rt": { "command": ["echo"] } }
                }
                """);
        DomainError missingEnvErr = Assert.assertErr(
            new ProjectLoader(missingEnvSource).load(Optional.empty()),
            "missing required env file must fail fast"
        );
        Assert.assertEquals("CONFIG_MISSING_ENV_FILE", missingEnvErr.code(), "missing required env file code");

        var unsetHostSource = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "envFiles": [".env"],
                  "defaults": {
                    "env": {
                      "TOP_SECRET": "top-secret-from-defaults",
                      "HOST_SECRET": "${env:MISSING_SECRET}"
                    }
                  },
                  "runtimes": { "rt": { "command": ["echo"] } },
                  "services": {
                    "svc1": { "runtime": "rt", "workingDir": "." }
                  }
                }
                """)
            .withFile(root.resolve(".env"), "DOTENV_SECRET=secret-value-xyz\n");

        DomainError unsetErr = Assert.assertErr(
            new ProjectLoader(unsetHostSource).load(Optional.empty()),
            "unset ${env:VAR} must fail fast"
        );
        Assert.assertEquals("CONFIG_UNSET_ENV_VAR", unsetErr.code(), "unset host env code");
        Assert.assertNotContains(unsetErr.format(), "secret-value-xyz", "error must not leak dotenv secrets");
        Assert.assertNotContains(unsetErr.format(), "top-secret-from-defaults", "error must not leak default secrets");
    }

    public static void testLoadWithPathReturnsResolvedConfigPathAndProject() {
        Path repoRoot = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Path subDir = repoRoot.resolve("services/orders");
        Path walkUpCfg = repoRoot.resolve("procomp.json");
        Path overrideCfg = Path.of("C:/work/override.procomp.json").toAbsolutePath().normalize();

        var source = new InMemoryConfigSource(subDir)
            .withFile(walkUpCfg, "{\"project\":\"walk\",\"runtimes\":{}}")
            .withFile(overrideCfg, "{\"project\":\"override\",\"runtimes\":{}}");

        ProjectLoader loader = new ProjectLoader(source);

        ProjectLoader.LoadedProject walked = Assert.assertOk(
            loader.loadWithPath(Optional.empty()),
            "loadWithPath walk-up should succeed"
        );
        Assert.assertEquals(walkUpCfg, walked.configPath(), "walk-up configPath");
        Assert.assertEquals("walk", walked.project().name(), "walk-up project name");

        ProjectLoader.LoadedProject overridden = Assert.assertOk(
            loader.loadWithPath(Optional.of(overrideCfg)),
            "loadWithPath explicit override should succeed"
        );
        Assert.assertEquals(overrideCfg, overridden.configPath(), "override configPath");
        Assert.assertEquals("override", overridden.project().name(), "override project name");

        DomainError missing = Assert.assertErr(
            loader.loadWithPath(Optional.of(Path.of("C:/work/does-not-exist.json"))),
            "loadWithPath missing override fails"
        );
        Assert.assertEquals("CONFIG_NOT_FOUND", missing.code(), "missing override error code");
    }
}
