package procomp.core.project;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ProjectResolverTest {

    private static ProjectResolver.FileLookup filesOf(Map<Path, String> files) {
        return path -> Result.ok(Optional.ofNullable(files.get(path)));
    }

    private static ProjectResolver.FileLookup emptyFiles() {
        return _ -> Result.ok(Optional.empty());
    }

    public static void testMergesCatalogAndOverridesSplicesArgvAndDerivesSharedRepos() {
        String configJson = """
            {
              "project": "acme",
              "repositoriesRoot": "..",
              "envFiles": [
                ".env",
                { "path": ".env.local", "optional": true }
              ],
              "defaults": {
                "proxyPathTemplate": "/{{service}}",
                "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}",
                "vars": {
                  "jvmArgs": ["-Xmx384m", "-XX:+UseG1GC"]
                },
                "env": {
                  "SPRING_PROFILES_ACTIVE": "local",
                  "DB_PASSWORD": "${env:ACME_DB_PASSWORD}",
                  "SERVICE_URL": "http://localhost:{{port}}"
                }
              },
              "runtimes": {
                "java": {
                  "command": ["java", "{{jvmArgs}}", "-Dserver.port={{port}}", "-jar", "{{artifact}}"],
                  "healthPath": "/actuator/health",
                  "artifactGlob": "{{subproject}}/build/libs/*.jar",
                  "artifactExclude": "*-plain.jar",
                  "env": {
                    "RUNTIME_VAR": "from-runtime"
                  }
                }
              },
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "healthPath": "/__admin/health",
                "command": ["java", "-jar", "{{repositoriesRoot}}/wiremock/wiremock.jar", "--port", "{{port}}"],
                "workingDir": "{{repositoriesRoot}}/wiremock"
              },
              "catalog": {
                "path": "./service-index.json",
                "itemsPath": "apps",
                "map": {
                  "name": "servicename",
                  "port": "localport",
                  "runtime": "type",
                  "repositoryUrl": "servicerepositoryurl",
                  "vars.subproject": "subproject"
                }
              },
              "services": {
                "service1": {
                  "vars": {
                    "jvmArgs": ["-Xmx512m"]
                  },
                  "env": {
                    "SPRING_PROFILES_ACTIVE": "svc1-override"
                  }
                },
                "service2": {
                  "vars": {
                    "jvmArgs": []
                  }
                },
                "standalone-mock": {
                  "port": 9099,
                  "runtime": "java",
                  "workingDir": "{{configDir}}/mocks/{{service}}",
                  "vars": {
                    "subproject": "mock"
                  }
                }
              },
              "profiles": {
                "one-local": {
                  "local": ["service1"]
                }
              }
            }
            """;

        String catalogJson = """
            {
              "apps": [
                {
                  "servicename": "service1",
                  "localport": "9001",
                  "subproject": "svc1-mod",
                  "type": "java",
                  "servicerepositoryurl": "ssh://git@scm.corp:7999/acme/monorepo.git"
                },
                {
                  "servicename": "service2",
                  "localport": "9002",
                  "subproject": "svc2-mod",
                  "type": "java",
                  "servicerepositoryurl": "ssh://git@scm.corp:7999/acme/monorepo.git"
                }
              ]
            }
            """;

        Path configDir = Path.of("C:/dev/acme/config").toAbsolutePath().normalize();
        Path configPath = configDir.resolve("procomp.json");
        Path catalogPath = configDir.resolve("service-index.json");
        Path envPath = configDir.resolve(".env");

        Map<Path, String> files = Map.of(
            catalogPath, catalogJson,
            envPath, "DB_PASSWORD=from-dotenv\nEXTRA_DOTENV=hello\n"
        );
        Map<String, String> hostEnv = Map.of("ACME_DB_PASSWORD", "secret-from-host");

        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, configJson, filesOf(files), hostEnv),
            "project resolution should succeed without checking repo dirs on disk"
        );

        Assert.assertEquals("acme", project.name(), "project name");
        Path expectedReposRoot = Path.of("C:/dev/acme").toAbsolutePath().normalize();
        Assert.assertEquals(Optional.of(expectedReposRoot), project.repositoriesRoot(), "repositoriesRoot");
        Assert.assertEquals(1, project.repositories().size(), "shared monorepo deduplicated to 1 RepositoryRef");
        Assert.assertEquals(expectedReposRoot.resolve("monorepo"), project.repositories().get(0).root(), "derived repo root");

        ServiceDefinition s1 = project.service("service1").orElseThrow();
        Assert.assertEquals(
            List.of("java", "-Xmx512m", "-Dserver.port=9001", "-jar", "{{artifact}}"),
            s1.command(),
            "service1 splices overridden jvmArgs and preserves {{artifact}}"
        );
        Assert.assertEquals("svc1-mod/build/libs/*.jar", s1.artifactGlob().orElseThrow(), "artifactGlob expanded");
        Assert.assertEquals(expectedReposRoot.resolve("monorepo"), s1.workingDir(), "workingDir defaults to {{repository}}");
        Assert.assertEquals("/service1", s1.proxyPath(), "proxyPath");
        Assert.assertEquals("https://shared-dev.mycorp/service1", s1.remoteUrl().orElseThrow(), "remoteUrl");
        Assert.assertEquals("svc1-override", s1.env().asMap().get("SPRING_PROFILES_ACTIVE"), "service env overrides default");
        Assert.assertEquals("secret-from-host", s1.env().asMap().get("DB_PASSWORD"), "defaults.env overrides .env");
        Assert.assertEquals("hello", s1.env().asMap().get("EXTRA_DOTENV"), ".env value preserved");
        Assert.assertEquals("from-runtime", s1.env().asMap().get("RUNTIME_VAR"), "runtime env applied");
        Assert.assertEquals("http://localhost:9001", s1.env().asMap().get("SERVICE_URL"), "template expanded in env");

        ServiceDefinition s2 = project.service("service2").orElseThrow();
        Assert.assertEquals(
            List.of("java", "-Dserver.port=9002", "-jar", "{{artifact}}"),
            s2.command(),
            "empty string[] splices zero arguments"
        );

        ServiceDefinition standalone = project.service("standalone-mock").orElseThrow();
        Assert.assertTrue(standalone.repository().isEmpty(), "non-git service has empty repository");
        Assert.assertEquals(configDir.resolve("mocks/standalone-mock"), standalone.workingDir(), "standalone workingDir");

        GatewayDefinition gw = project.gateway().orElseThrow();
        Assert.assertEquals("http://127.0.0.1:8080", gw.adminUrl(), "gateway adminUrl");
        Assert.assertEquals(
            List.of("java", "-jar", expectedReposRoot + "/wiremock/wiremock.jar", "--port", "8080"),
            gw.command(),
            "gateway command templates expanded"
        );
        Assert.assertEquals(Optional.of(expectedReposRoot.resolve("wiremock")), gw.workingDir(), "gateway workingDir");

        Assert.assertNotContains(project.toString(), "secret-from-host", "Project.toString must not leak secret env");
        Assert.assertNotContains(s1.toString(), "secret-from-host", "ServiceDefinition.toString must not leak secret env");
    }

    public static void testResolvesCatalogPathUsingRepositoriesRootAndServiceEnvFilesPrecedence() {
        String configJson = """
            {
              "project": "acme",
              "repositoriesRoot": "../repos",
              "envFiles": [".env"],
              "defaults": {
                "env": {
                  "FROM_DEFAULT": "default-val"
                }
              },
              "runtimes": {
                "node": {
                  "command": ["node", "index.js"],
                  "env": {
                    "FROM_DEFAULT": "runtime-val"
                  }
                }
              },
              "catalog": {
                "path": "{{repositoriesRoot}}/gitops/service-index.json",
                "map": {
                  "name": "name",
                  "runtime": "runtime",
                  "repositoryUrl": "repo"
                }
              },
              "services": {
                "web": {
                  "envFiles": [".env.web"],
                  "env": {
                    "SVC_ONLY": "svc-val"
                  }
                }
              }
            }
            """;

        Path configDir = Path.of("C:/dev/acme/cfg").toAbsolutePath().normalize();
        Path configPath = configDir.resolve("procomp.json");
        Path catalogPath = Path.of("C:/dev/acme/repos/gitops/service-index.json").toAbsolutePath().normalize();
        Path webRepo = Path.of("C:/dev/acme/repos/web").toAbsolutePath().normalize();

        Map<Path, String> files = Map.of(
            catalogPath, "[{\"name\":\"web\",\"runtime\":\"node\",\"repo\":\"git@git.corp:acme/web.git\"}]",
            configDir.resolve(".env"), "SHARED_KEY=from-top\nSVC_KEY=from-top\n",
            webRepo.resolve(".env.web"), "SVC_KEY=from-service-envfile\n"
        );

        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, configJson, filesOf(files), Map.of()),
            "project resolves catalog via {{repositoriesRoot}} and applies envFiles precedence"
        );
        ServiceDefinition web = project.service("web").orElseThrow();
        Assert.assertEquals("from-top", web.env().asMap().get("SHARED_KEY"), "top .env applied");
        Assert.assertEquals("from-service-envfile", web.env().asMap().get("SVC_KEY"), "service envFile overrides top envFile");
        Assert.assertEquals("runtime-val", web.env().asMap().get("FROM_DEFAULT"), "runtime env overrides default env");
        Assert.assertEquals("svc-val", web.env().asMap().get("SVC_ONLY"), "service env applied");
    }

    public static void testFailsFastOnUndefinedTemplateUnknownRuntimeUnknownProfileServiceOrMissingWorkingDir() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();

        // 1. Undefined {{var}}
        assertResolveErr(
            configPath,
            """
            {"project":"acme","runtimes":{"rt":{"command":["echo","{{missingVar}}"]}},"services":{"s1":{"runtime":"rt","workingDir":"."}}}
            """,
            "CONFIG_UNDEFINED_TEMPLATE_VAR"
        );

        // 2. Unknown runtime
        assertResolveErr(
            configPath,
            """
            {"project":"acme","runtimes":{},"services":{"s1":{"runtime":"ghost","workingDir":"."}}}
            """,
            "CONFIG_UNKNOWN_RUNTIME"
        );

        // 3. Unknown service in profile
        assertResolveErr(
            configPath,
            """
            {"project":"acme","runtimes":{"rt":{"command":["echo"]}},"services":{},"profiles":{"p":{"local":["ghost"]}}}
            """,
            "CONFIG_UNKNOWN_SERVICE"
        );

        // 4. Missing workingDir when no repository
        assertResolveErr(
            configPath,
            """
            {"project":"acme","runtimes":{"rt":{"command":["echo"]}},"services":{"s1":{"runtime":"rt"}}}
            """,
            "CONFIG_MISSING_WORKING_DIR"
        );

        // 5. Embedded string[] var inside scalar string
        assertResolveErr(
            configPath,
            """
            {"project":"acme","defaults":{"vars":{"args":["-a"]}},"runtimes":{"rt":{"command":["echo","--flag={{args}}"]}},"services":{"s1":{"runtime":"rt","workingDir":"."}}}
            """,
            "CONFIG_INVALID_TEMPLATE_TYPE"
        );

        // 6. Unset ${env:VAR}
        assertResolveErr(
            configPath,
            """
            {"project":"acme","defaults":{"env":{"TOK":"${env:MISSING_HOST_ENV}"}},"runtimes":{"rt":{"command":["echo"]}},"services":{"s1":{"runtime":"rt","workingDir":"."}}}
            """,
            "CONFIG_UNSET_ENV_VAR"
        );

        // 7. Missing required .env file
        assertResolveErr(
            configPath,
            """
            {"project":"acme","envFiles":[".env.required"],"runtimes":{"rt":{"command":["echo"]}}}
            """,
            "CONFIG_MISSING_ENV_FILE"
        );

        // 8. {{artifact}} used without artifactGlob
        assertResolveErr(
            configPath,
            """
            {"project":"acme","runtimes":{"rt":{"command":["java","-jar","{{artifact}}"]}},"services":{"s1":{"runtime":"rt","workingDir":"."}}}
            """,
            "CONFIG_UNDEFINED_TEMPLATE_VAR"
        );

        // 9. Missing catalog file
        assertResolveErr(
            configPath,
            """
            {"project":"acme","runtimes":{"rt":{"command":["echo"]}},"catalog":{"path":"missing-catalog.json","map":{"name":"n"}}}
            """,
            "CATALOG_NOT_FOUND"
        );

        // 10. FileLookup I/O error propagates cleanly
        DomainError ioErr = Assert.assertErr(
            ProjectResolver.resolve(
                configPath,
                """
                {"project":"acme","envFiles":[".env"],"runtimes":{"rt":{"command":["echo"]}}}
                """,
                _ -> Result.err(DomainError.of("CONFIG_READ_ERROR", "Permission denied")),
                Map.of()
            ),
            "fileLookup error must propagate"
        );
        Assert.assertEquals("CONFIG_READ_ERROR", ioErr.code(), "propagated fileLookup error code");
    }

    public static void testExplicitEmptyHealthPathOverridesRuntimeAndCatalogHealthPath() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String configJson = """
            {
              "project": "acme",
              "defaults": {
                "workingDir": "."
              },
              "runtimes": {
                "java": {
                  "command": ["java", "-jar", "app.jar"],
                  "healthPath": "/actuator/health"
                },
                "tcp": {
                  "command": ["redis-server"],
                  "healthPath": ""
                }
              },
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "healthPath": ""
              },
              "catalog": {
                "path": "./idx.json",
                "map": {
                  "name": "name",
                  "runtime": "runtime",
                  "healthPath": "health"
                }
              },
              "services": {
                "tcp-override": { "runtime": "java", "port": 9001, "healthPath": "" },
                "inherited": { "runtime": "java", "port": 9002 },
                "cat-cleared-inline": { "port": 9003, "healthPath": "" }
              }
            }
            """;
        String catalogJson = """
            [
              { "name": "cat-cleared", "runtime": "java", "health": "" },
              { "name": "cat-cleared-inline", "runtime": "java", "health": "/svc-health" }
            ]
            """;

        Path catalogPath = configPath.getParent().resolve("idx.json");
        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, configJson, filesOf(Map.of(catalogPath, catalogJson)), Map.of()),
            "project resolves"
        );

        Assert.assertEquals(Optional.empty(), project.service("tcp-override").orElseThrow().healthPath(),
            "explicit empty service healthPath overrides runtime /actuator/health");
        Assert.assertEquals(Optional.of("/actuator/health"), project.service("inherited").orElseThrow().healthPath(),
            "omitted service healthPath inherits runtime /actuator/health");
        Assert.assertEquals(Optional.empty(), project.service("cat-cleared").orElseThrow().healthPath(),
            "catalog explicit empty healthPath overrides runtime /actuator/health");
        Assert.assertEquals(Optional.empty(), project.service("cat-cleared-inline").orElseThrow().healthPath(),
            "inline explicit empty healthPath overrides catalog /svc-health");
        Assert.assertEquals(Optional.empty(), project.runtimes().get("tcp").healthPath(),
            "runtime explicit empty healthPath normalizes to Optional.empty()");
        Assert.assertEquals(Optional.empty(), project.gateway().orElseThrow().healthPath(),
            "gateway explicit empty healthPath normalizes to Optional.empty()");
    }

    public static void testValidatesTemplatesInOverriddenDefaultsAndUnusedRuntimes() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();

        // 1. Overridden defaults templates with undefined {{typo}} fail fast across all defaults fields
        List<String> badDefaults = List.of(
            """
            {"project":"acme","defaults":{"proxyPathTemplate":"/{{typo}}"},"runtimes":{"rt":{"command":["echo"]}},"services":{"s1":{"runtime":"rt","workingDir":".","proxyPath":"/s1"}}}
            """,
            """
            {"project":"acme","defaults":{"remoteUrlTemplate":"https://dev/{{typo}}"},"runtimes":{"rt":{"command":["echo"]}},"services":{"s1":{"runtime":"rt","workingDir":".","remoteUrl":"https://dev/s1"}}}
            """,
            """
            {"project":"acme","defaults":{"workingDir":"{{typo}}"},"runtimes":{"rt":{"command":["echo"]}},"services":{"s1":{"runtime":"rt","workingDir":"."}}}
            """,
            """
            {"project":"acme","defaults":{"env":{"BAD":"{{typo}}"}},"runtimes":{"rt":{"command":["echo"]}},"services":{"s1":{"runtime":"rt","workingDir":".","env":{"BAD":"override"}}}}
            """,
            """
            {"project":"acme","defaults":{"workingDir":"{{repositoriesRoot}}/sub"},"runtimes":{"rt":{"command":["echo"]}},"services":{"s1":{"runtime":"rt","workingDir":"."}}}
            """
        );
        for (String bad : badDefaults) {
            assertResolveErr(configPath, bad, "CONFIG_UNDEFINED_TEMPLATE_VAR");
        }

        // 2. Unused runtime templates with undefined {{typo}} or unset {{repositoriesRoot}} fail fast across all runtime fields
        List<String> badUnusedRuntimes = List.of(
            """
            {"project":"acme","runtimes":{"used":{"command":["echo"]},"unused":{"command":["java","{{typo}}"]}},"services":{"s1":{"runtime":"used","workingDir":"."}}}
            """,
            """
            {"project":"acme","runtimes":{"used":{"command":["echo"]},"unused":{"command":["java"],"workingDir":"{{typo}}"}},"services":{"s1":{"runtime":"used","workingDir":"."}}}
            """,
            """
            {"project":"acme","runtimes":{"used":{"command":["echo"]},"unused":{"command":["java"],"artifactGlob":"{{typo}}/*.jar"}},"services":{"s1":{"runtime":"used","workingDir":"."}}}
            """,
            """
            {"project":"acme","runtimes":{"used":{"command":["echo"]},"unused":{"command":["java"],"env":{"BAD":"{{typo}}"}}},"services":{"s1":{"runtime":"used","workingDir":"."}}}
            """,
            """
            {"project":"acme","runtimes":{"used":{"command":["echo"]},"unused":{"command":["java"],"workingDir":"{{repositoriesRoot}}/app"}},"services":{"s1":{"runtime":"used","workingDir":"."}}}
            """
        );
        for (String bad : badUnusedRuntimes) {
            assertResolveErr(configPath, bad, "CONFIG_UNDEFINED_TEMPLATE_VAR");
        }

        // 3. {{subproject}} in an unused runtime fails if nowhere provided
        assertResolveErr(
            configPath,
            """
            {"project":"acme","runtimes":{"used":{"command":["echo"]},"unused":{"command":["java","-jar","{{artifact}}"],"artifactGlob":"{{subproject}}/build/libs/*.jar"}},"services":{"s1":{"runtime":"used","workingDir":"."}}}
            """,
            "CONFIG_UNDEFINED_TEMPLATE_VAR"
        );

        // 4. {{subproject}} in an unused runtime passes when provided by a service, and built-in vars + configured repositoriesRoot pass in overridden defaults & unused runtimes
        String validConfig = """
            {
              "project": "acme",
              "repositoriesRoot": "..",
              "defaults": {
                "proxyPathTemplate": "/{{service}}",
                "remoteUrlTemplate": "https://dev.corp/{{service}}/{{port}}",
                "workingDir": "{{repositoriesRoot}}/{{service}}",
                "vars": {
                  "jvmArgs": ["-Xmx256m"]
                },
                "env": {
                  "CFG": "{{configDir}}",
                  "REPO": "{{repository}}"
                }
              },
              "runtimes": {
                "used": { "command": ["echo", "{{service}}"] },
                "unused": {
                  "command": ["java", "{{jvmArgs}}", "-Dserver.port={{port}}", "-jar", "{{artifact}}"],
                  "workingDir": "{{repositoriesRoot}}/{{subproject}}",
                  "artifactGlob": "{{subproject}}/build/libs/*.jar",
                  "env": {
                    "SVC": "{{service}}",
                    "SUB": "{{subproject}}"
                  }
                }
              },
              "services": {
                "s1": {
                  "runtime": "used",
                  "port": 9001,
                  "workingDir": ".",
                  "proxyPath": "/custom",
                  "remoteUrl": "https://other/s1",
                  "vars": { "subproject": "mod-a" },
                  "env": { "CFG": "override", "REPO": "override" }
                }
              }
            }
            """;
        Assert.assertOk(
            ProjectResolver.resolve(configPath, validConfig, emptyFiles(), Map.of()),
            "valid built-in and service.vars references in overridden defaults and unused runtimes pass"
        );
    }

    public static void testHostEnvSecretNotReparsedAsTemplateAndPathSafeServiceNames() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String json = """
            {
              "project": "acme",
              "runtimes": {
                "rt": {
                  "artifactGlob": "*.jar",
                  "command": ["echo", "{{service}}", "--format={{rawFmt}}", "{{artifact}}"]
                }
              },
              "services": {
                "orders": {
                  "runtime": "rt",
                  "port": 9001,
                  "workingDir": ".",
                  "vars": { "rawFmt": "{{.Names}}" },
                  "env": {
                    "DB_PASS": "${env:SECRET_WITH_BRACES}",
                    "TAG": "{{service}}-${env:SECRET_WITH_BRACES}"
                  }
                }
              }
            }
            """;
        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, json, emptyFiles(), Map.of("SECRET_WITH_BRACES", "p@ss{{not_a_var}}")),
            "host secret containing {{...}} must not be re-parsed by TemplateExpander"
        );
        ServiceDefinition orders = project.service("orders").orElseThrow();
        Assert.assertEquals("p@ss{{not_a_var}}", orders.env().asMap().get("DB_PASS"), "secret preserved verbatim");
        Assert.assertEquals("orders-p@ss{{not_a_var}}", orders.env().asMap().get("TAG"), "template expanded before host env");

        List<String> resolvedCmd = Assert.assertOk(
            ProjectResolver.resolveServiceCommand(orders, Optional.of(Path.of("orders.jar"))),
            "resolveServiceCommand replaces only {{artifact}} without re-parsing {{.Names}}"
        );
        Assert.assertEquals(List.of("echo", "orders", "--format={{.Names}}", "orders.jar"), resolvedCmd, "command preserved");

        String traversalJson = """
            {
              "project": "acme",
              "runtimes": { "rt": { "command": ["echo"] } },
              "services": { "../../evil": { "runtime": "rt", "workingDir": "." } }
            }
            """;
        assertResolveErr(configPath, traversalJson, "CONFIG_INVALID_VALUE");
    }

    public static void testHostEnvRefsExpandInCommandWorkingDirAndVarsAndFailFastWhenUnset() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String configJson = """
            {
              "project": "acme",
              "defaults": {
                "vars": {
                  "javaBin": "${env:JAVA_HOME}/bin/java",
                  "extraArgs": ["-Djdk.home=${env:JAVA_HOME}"]
                }
              },
              "runtimes": {
                "java": {
                  "command": ["${env:JAVA_HOME}/bin/java", "{{extraArgs}}", "-Dserver.port={{port}}", "-jar", "app.jar"],
                  "workingDir": "${env:SERVICES_ROOT}/{{service}}"
                },
                "var-java": {
                  "command": ["{{javaBin}}", "{{svcFlag}}"]
                }
              },
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "command": ["${env:JAVA_HOME}/bin/java", "-jar", "wiremock.jar", "--port", "{{port}}"],
                "workingDir": "${env:SERVICES_ROOT}/wiremock"
              },
              "services": {
                "orders": {
                  "runtime": "java",
                  "port": 9001
                },
                "billing": {
                  "runtime": "var-java",
                  "port": 9002,
                  "workingDir": ".",
                  "vars": {
                    "svcFlag": "-Denv=${env:BILLING_ENV}"
                  }
                }
              }
            }
            """;

        Map<String, String> hostEnv = Map.of(
            "JAVA_HOME", "C:/jdks/jdk-25",
            "SERVICES_ROOT", "C:/dev/services",
            "BILLING_ENV", "local-dev"
        );
        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, configJson, emptyFiles(), hostEnv),
            "host env refs in command, workingDir, and vars resolve"
        );

        ServiceDefinition orders = project.service("orders").orElseThrow();
        Assert.assertEquals(
            List.of("C:/jdks/jdk-25/bin/java", "-Djdk.home=C:/jdks/jdk-25", "-Dserver.port=9001", "-jar", "app.jar"),
            orders.command(),
            "service command expands ${env:JAVA_HOME} directly and via array vars"
        );
        Assert.assertEquals(
            Path.of("C:/dev/services/orders").normalize(),
            orders.workingDir(),
            "service workingDir expands ${env:SERVICES_ROOT}"
        );

        ServiceDefinition billing = project.service("billing").orElseThrow();
        Assert.assertEquals(
            List.of("C:/jdks/jdk-25/bin/java", "-Denv=local-dev"),
            billing.command(),
            "service command expands ${env:...} from defaults.vars and service.vars"
        );

        GatewayDefinition gw = project.gateway().orElseThrow();
        Assert.assertEquals(
            List.of("C:/jdks/jdk-25/bin/java", "-jar", "wiremock.jar", "--port", "8080"),
            gw.command(),
            "gateway command expands ${env:JAVA_HOME}"
        );
        Assert.assertEquals(
            Optional.of(Path.of("C:/dev/services/wiremock").normalize()),
            gw.workingDir(),
            "gateway workingDir expands ${env:SERVICES_ROOT}"
        );

        // Fail fast when any referenced host env var is missing (including in command, workingDir, vars, or unused runtime)
        List<String> missingEnvConfigs = List.of(
            """
            {"project":"acme","runtimes":{"rt":{"command":["${env:MISSING_JAVA}/bin/java"]}},"services":{"s1":{"runtime":"rt","workingDir":"."}}}
            """,
            """
            {"project":"acme","runtimes":{"rt":{"command":["echo"],"workingDir":"${env:MISSING_DIR}"}},"services":{"s1":{"runtime":"rt"}}}
            """,
            """
            {"project":"acme","defaults":{"vars":{"bin":"${env:MISSING_BIN}"}},"runtimes":{"rt":{"command":["{{bin}}"]}},"services":{"s1":{"runtime":"rt","workingDir":"."}}}
            """,
            """
            {"project":"acme","runtimes":{"used":{"command":["echo"]},"unused":{"command":["${env:MISSING_UNUSED}/bin/java"]}},"services":{"s1":{"runtime":"used","workingDir":"."}}}
            """,
            """
            {"project":"acme","runtimes":{"rt":{"command":["echo"]}},"gateway":{"type":"wiremock","port":8080,"command":["${env:MISSING_GW}/bin/java"]},"services":{"s1":{"runtime":"rt","workingDir":"."}}}
            """
        );
        for (String bad : missingEnvConfigs) {
            assertResolveErr(configPath, bad, "CONFIG_UNSET_ENV_VAR");
        }
    }

    public static void testResolvesBuildersDefaultsCatalogAndEnvPrecedence() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        Path catalogPath = Path.of("C:/dev/acme/catalog.json").toAbsolutePath().normalize();
        Path envFilePath = Path.of("C:/dev/acme/.env").toAbsolutePath().normalize();

        String configJson = """
            {
              "project": "acme",
              "repositoriesRoot": "../repos",
              "envFiles": [".env"],
              "defaults": {
                "builder": "gradle",
                "vars": { "gradleFlags": ["--no-daemon", "-q"] },
                "env": {
                  "SHARED": "from-defaults",
                  "FROM_DEFAULT": "yes"
                }
              },
              "builders": {
                "gradle": {
                  "command": ["./gradlew", "{{subproject}}:assemble", "{{gradleFlags}}"],
                  "workingDir": "{{repository}}",
                  "env": {
                    "SHARED": "from-builder",
                    "JAVA_HOME": "${env:JDK_HOME}"
                  }
                },
                "npm": {
                  "command": ["npm", "run", "build"]
                }
              },
              "runtimes": {
                "java": {
                  "command": ["java", "-jar", "{{artifact}}"],
                  "artifactGlob": "build/libs/*.jar",
                  "workingDir": "{{repository}}/app",
                  "env": {
                    "RUNTIME_ONLY": "must-not-leak",
                    "SHARED": "from-runtime"
                  }
                }
              },
              "catalog": {
                "path": "catalog.json",
                "map": {
                  "name": "svc",
                  "runtime": "rt",
                  "builder": "bld",
                  "repositoryUrl": "repoUrl",
                  "vars.subproject": "sub"
                }
              },
              "services": {
                "orders": {
                  "env": { "SVC_FLAG": "1" }
                },
                "web": {
                  "runtime": "java",
                  "builder": "npm",
                  "workingDir": "web-dir"
                }
              }
            }
            """;

        String catalogJson = """
            [
              {
                "svc": "orders",
                "rt": "java",
                "repoUrl": "https://git.example/orders-repo.git",
                "sub": ":orders-api"
              },
              {
                "svc": "billing",
                "rt": "java",
                "bld": "npm",
                "repoUrl": "https://git.example/billing-repo.git",
                "sub": ":billing-api"
              }
            ]
            """;

        Map<Path, String> files = Map.of(
            catalogPath, catalogJson,
            envFilePath, "FROM_ENV_FILE=loaded\nSHARED=from-env-file\n"
        );
        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, configJson, filesOf(files), Map.of("JDK_HOME", "C:/jdks/25")),
            "resolve with builders"
        );

        Assert.assertEquals(2, project.builders().size(), "builders map size");
        Assert.assertTrue(project.builder("gradle").isPresent(), "gradle builder present");

        // orders inherits defaults.builder ("gradle"), uses builder.workingDir ({{repository}}), excludes runtime.env
        ServiceDefinition orders = project.service("orders").orElseThrow();
        ServiceBuildConfig ordersBuild = orders.buildConfig().orElseThrow();
        Assert.assertEquals("gradle", ordersBuild.builder(), "orders builder inherited from defaults");
        Assert.assertEquals(
            List.of("./gradlew", ":orders-api:assemble", "--no-daemon", "-q"),
            ordersBuild.command(),
            "orders build command expands vars and splices array"
        );
        Path expectedOrdersRepo = Path.of("C:/dev/repos/orders-repo").toAbsolutePath().normalize();
        Assert.assertEquals(expectedOrdersRepo, ordersBuild.workingDir(), "builder.workingDir overrides runtime workingDir");
        Assert.assertEquals(expectedOrdersRepo.resolve("app"), orders.workingDir(), "runtime workingDir unchanged");

        Map<String, String> buildEnv = ordersBuild.env().asMap();
        Assert.assertEquals("loaded", buildEnv.get("FROM_ENV_FILE"), "build env includes envFiles");
        Assert.assertEquals("yes", buildEnv.get("FROM_DEFAULT"), "build env includes defaults.env");
        Assert.assertEquals("from-builder", buildEnv.get("SHARED"), "builder.env overrides defaults.env and envFiles");
        Assert.assertEquals("C:/jdks/25", buildEnv.get("JAVA_HOME"), "builder.env expands ${env:...}");
        Assert.assertEquals("1", buildEnv.get("SVC_FLAG"), "build env includes service.env");
        Assert.assertFalse(buildEnv.containsKey("RUNTIME_ONLY"), "build env excludes runtime.env");

        // billing gets builder from catalog.map ("npm"), defaults build workingDir to service workingDir
        ServiceDefinition billing = project.service("billing").orElseThrow();
        ServiceBuildConfig billingBuild = billing.buildConfig().orElseThrow();
        Assert.assertEquals("npm", billingBuild.builder(), "billing builder from catalog.map");
        Assert.assertEquals(billing.workingDir(), billingBuild.workingDir(), "npm builder falls back to service workingDir");

        // Missing artifact hint mentions procomp build <service> when builder is configured
        DomainError missingArtifact = Assert.assertErr(
            ProjectResolver.resolveServiceCommand(orders, Optional.empty()),
            "missing artifact must error"
        );
        Assert.assertEquals("ARTIFACT_NOT_FOUND", missingArtifact.code(), "missing artifact code");
        Assert.assertEquals("gradle", missingArtifact.details().get("builder"), "builder detail present");
        Assert.assertContains(missingArtifact.message(), "build the Service", "modality-neutral build hint");
    }

    public static void testRejectsInvalidBuilderConfigs() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();

        // Unknown field inside builder
        assertResolveErr(
            configPath,
            """
            {"project":"acme","builders":{"g":{"command":["echo"],"bogus":true}},"runtimes":{"rt":{"command":["echo"]}}}
            """,
            "CONFIG_UNRECOGNIZED_FIELD"
        );
        // Empty builder command
        assertResolveErr(
            configPath,
            """
            {"project":"acme","builders":{"g":{"command":[]}},"runtimes":{"rt":{"command":["echo"]}}}
            """,
            "CONFIG_INVALID_VALUE"
        );
        // Unknown defaults.builder
        assertResolveErr(
            configPath,
            """
            {"project":"acme","defaults":{"builder":"missing"},"runtimes":{"rt":{"command":["echo"]}}}
            """,
            "CONFIG_UNKNOWN_BUILDER"
        );
        // Unknown service.builder
        assertResolveErr(
            configPath,
            """
            {"project":"acme","runtimes":{"rt":{"command":["echo"]}},"services":{"s1":{"runtime":"rt","builder":"missing","workingDir":"."}}}
            """,
            "CONFIG_UNKNOWN_BUILDER"
        );
        // Undefined {{artifact}} inside builder command
        assertResolveErr(
            configPath,
            """
            {"project":"acme","builders":{"g":{"command":["echo","{{artifact}}"]}},"runtimes":{"rt":{"command":["echo"]}}}
            """,
            "CONFIG_UNDEFINED_TEMPLATE_VAR"
        );
        // Unset ${env:...} inside unused builder
        assertResolveErr(
            configPath,
            """
            {"project":"acme","builders":{"unused":{"command":["${env:NO_SUCH_VAR}/gradlew"]}},"runtimes":{"rt":{"command":["echo"]}}}
            """,
            "CONFIG_UNSET_ENV_VAR"
        );
    }

    public static void testResolvesGatewayProxyHeadersWithUserAndHostEnv() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String json = """
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "proxyHeaders": {
                  "USERNAME": "{{user}}",
                  "X-TEAM": "${env:ACME_TEAM}"
                }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": "." }
              }
            }
            """;

        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, json, emptyFiles(), Map.of("ACME_TEAM", "checkout"), Optional.of("mattias")),
            "resolve gateway proxyHeaders"
        );
        GatewayDefinition gw = project.gateway().orElseThrow();
        Assert.assertEquals(
            Map.of("USERNAME", "mattias", "X-TEAM", "checkout"),
            gw.proxyHeaders(),
            "resolved proxyHeaders"
        );

        // Fails fast when {{user}} is unavailable
        DomainError missingUser = Assert.assertErr(
            ProjectResolver.resolve(configPath, json, emptyFiles(), Map.of("ACME_TEAM", "checkout"), Optional.empty()),
            "missing user must fail fast"
        );
        Assert.assertEquals("CONFIG_UNDEFINED_TEMPLATE_VAR", missingUser.code(), "missing {{user}} code");

        // Fails fast when header name is blank
        assertResolveErr(
            configPath,
            """
            {"project":"acme","runtimes":{"rt":{"command":["echo"]}},"gateway":{"type":"wiremock","port":8080,"proxyHeaders":{" ":"x"}}}
            """,
            "CONFIG_INVALID_VALUE"
        );
    }

    public static void testResolvesGatewayFallbackRemoteAndRejectsInvalidFields() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String json = """
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "fallbackRemote": {
                  "targetUrl": "${env:SHARED_DEV_URL}",
                  "stripPrefix": "/some-prefix"
                }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": "." }
              }
            }
            """;

        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, json, emptyFiles(), Map.of("SHARED_DEV_URL", "http://shared-dev-env")),
            "resolve gateway.fallbackRemote"
        );
        FallbackRemote fb = project.gateway().orElseThrow().fallbackRemote().orElseThrow();
        Assert.assertEquals("http://shared-dev-env", fb.targetUrl(), "fallbackRemote targetUrl");
        Assert.assertEquals(Optional.of("/some-prefix"), fb.stripPrefix(), "fallbackRemote stripPrefix");

        // Rejects unknown field in fallbackRemote
        assertResolveErr(
            configPath,
            """
            {"project":"acme","runtimes":{"rt":{"command":["echo"]}},"gateway":{"type":"wiremock","port":8080,"fallbackRemote":{"targetUrl":"http://dev","bogus":1}}}
            """,
            "CONFIG_UNRECOGNIZED_FIELD"
        );
        // Rejects missing targetUrl
        assertResolveErr(
            configPath,
            """
            {"project":"acme","runtimes":{"rt":{"command":["echo"]}},"gateway":{"type":"wiremock","port":8080,"fallbackRemote":{"stripPrefix":"/api"}}}
            """,
            "CONFIG_MISSING_FIELD"
        );
    }

    public static void testResolvesRepositoriesSettingsAndDefaults() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();

        Project defaultProj = Assert.assertOk(
            ProjectResolver.resolve(
                configPath,
                """
                {"project":"acme","runtimes":{"rt":{"command":["echo"]}}}
                """,
                emptyFiles(),
                Map.of()
            ),
            "default repositories settings"
        );
        Assert.assertEquals(24, defaultProj.repositorySettings().fetchWarnAfterHours(), "default fetchWarnAfterHours");
        Assert.assertEquals(120, defaultProj.repositorySettings().timeoutSeconds(), "default timeoutSeconds");

        Project customProj = Assert.assertOk(
            ProjectResolver.resolve(
                configPath,
                """
                {"project":"acme","repositories":{"fetchWarnAfterHours":0,"timeoutSeconds":45},"runtimes":{"rt":{"command":["echo"]}}}
                """,
                emptyFiles(),
                Map.of()
            ),
            "custom repositories settings"
        );
        Assert.assertEquals(0, customProj.repositorySettings().fetchWarnAfterHours(), "custom fetchWarnAfterHours");
        Assert.assertEquals(45, customProj.repositorySettings().timeoutSeconds(), "custom timeoutSeconds");

        assertResolveErr(
            configPath,
            """
            {"project":"acme","repositories":{"unknownKey":1},"runtimes":{"rt":{"command":["echo"]}}}
            """,
            "CONFIG_UNRECOGNIZED_FIELD"
        );
        assertResolveErr(
            configPath,
            """
            {"project":"acme","repositories":{"fetchWarnAfterHours":-1},"runtimes":{"rt":{"command":["echo"]}}}
            """,
            "CONFIG_INVALID_VALUE"
        );
        assertResolveErr(
            configPath,
            """
            {"project":"acme","repositories":{"timeoutSeconds":0},"runtimes":{"rt":{"command":["echo"]}}}
            """,
            "CONFIG_INVALID_VALUE"
        );
    }

    public static void testDerivesRepositoryNamesAndRejectsAmbiguousCollisions() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();

        Project okProj = Assert.assertOk(
            ProjectResolver.resolve(
                configPath,
                """
                {
                  "project": "acme",
                  "repositoriesRoot": "..",
                  "runtimes": { "rt": { "command": ["echo"] } },
                  "services": {
                    "sap-facade": { "runtime": "rt", "repositoryUrl": "https://git.example/team/external-facades.git" },
                    "salesforce-facade": { "runtime": "rt", "repositoryUrl": "https://git.example/team/external-facades.git" },
                    "payments": { "runtime": "rt", "repositoryUrl": "https://git.example/team/payments.git" }
                  }
                }
                """,
                emptyFiles(),
                Map.of()
            ),
            "shared monorepo and 1:1 service/repo equality allowed"
        );
        Assert.assertEquals(2, okProj.repositories().size(), "deduplicated repository count");
        Assert.assertEquals("external-facades", okProj.repositories().get(0).name(), "monorepo derived name");
        Assert.assertEquals("payments", okProj.repositories().get(1).name(), "1:1 repo derived name");
        Assert.assertEquals(
            "external-facades",
            okProj.service("sap-facade").orElseThrow().repository().orElseThrow().name(),
            "service repository name"
        );

        // Collision 1: two distinct Repository roots with same basename
        assertResolveErr(
            configPath,
            """
            {
              "project": "acme",
              "runtimes": { "rt": { "command": ["echo"] } },
              "services": {
                "svc-a": { "runtime": "rt", "repository": "../team-a/backend" },
                "svc-b": { "runtime": "rt", "repository": "../team-b/backend" }
              }
            }
            """,
            "CONFIG_REPOSITORY_NAME_COLLISION"
        );

        // Collision 2: Repository basename collides with a different Service's name
        assertResolveErr(
            configPath,
            """
            {
              "project": "acme",
              "runtimes": { "rt": { "command": ["echo"] } },
              "services": {
                "svc-a": { "runtime": "rt", "repository": "../payments" },
                "payments": { "runtime": "rt", "repository": "../legacy-monorepo" }
              }
            }
            """,
            "CONFIG_REPOSITORY_NAME_COLLISION"
        );
    }

    private static void assertResolveErr(Path configPath, String json, String expectedCode) {
        DomainError err = Assert.assertErr(
            ProjectResolver.resolve(configPath, json, emptyFiles(), Map.of()),
            "expected resolve error " + expectedCode + " for: " + json
        );
        Assert.assertEquals(expectedCode, err.code(), "error code for: " + json);
    }
}
