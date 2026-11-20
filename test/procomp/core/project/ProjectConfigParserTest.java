package procomp.core.project;

import procomp.Assert;
import procomp.core.common.DomainError;

import java.util.List;
import java.util.Optional;

public final class ProjectConfigParserTest {

    public static void testParsesFullValidConfig() {
        String json = """
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
                "workingDir": "{{repository}}",
                "vars": {
                  "jvmArgs": ["-Xmx384m"],
                  "region": "eu"
                },
                "env": {
                  "SPRING_PROFILES_ACTIVE": "local",
                  "DB_PASSWORD": "${env:ACME_DB_PASSWORD}"
                }
              },
              "runtimes": {
                "java": {
                  "command": ["java", "{{jvmArgs}}", "-Dserver.port={{port}}", "-jar", "{{artifact}}"],
                  "healthPath": "/actuator/health",
                  "artifactGlob": "{{subproject}}/build/libs/*.jar",
                  "artifactExclude": "*-plain.jar",
                  "workingDir": "{{repository}}/{{subproject}}",
                  "env": {
                    "JAVA_TOOL_OPTIONS": "-Dfile.encoding=UTF-8"
                  }
                }
              },
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "healthPath": "/__admin/health",
                "command": ["java", "-jar", "{{repositoriesRoot}}/wiremock/wiremock.jar"],
                "workingDir": "{{repositoriesRoot}}/wiremock",
                "env": {
                  "WIREMOCK_OPTS": "--verbose"
                }
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
                  "port": 9001,
                  "runtime": "java",
                  "vars": {
                    "jvmArgs": ["-Xmx512m"]
                  },
                  "envFiles": [".env.svc1"]
                }
              },
              "profiles": {
                "one-local": {
                  "local": ["service1"]
                }
              }
            }
            """;

        RawProjectConfig cfg = Assert.assertOk(ProjectConfigParser.parse(json), "valid procomp.json should parse");
        Assert.assertEquals("acme", cfg.project(), "project name");
        Assert.assertEquals("..", cfg.repositoriesRoot().orElseThrow(), "repositoriesRoot");
        Assert.assertEquals(2, cfg.envFiles().size(), "envFiles count");
        Assert.assertEquals(new EnvFileSpec(".env", false), cfg.envFiles().get(0), "string envFile defaults optional=false");
        Assert.assertEquals(new EnvFileSpec(".env.local", true), cfg.envFiles().get(1), "object envFile optional=true");
        Assert.assertTrue(cfg.runtimes().containsKey("java"), "java runtime parsed");
        Assert.assertEquals(8080, cfg.gateway().orElseThrow().port(), "gateway port");
        Assert.assertEquals("apps", cfg.catalog().orElseThrow().itemsPath().orElseThrow(), "catalog itemsPath");
        Assert.assertEquals(List.of("service1"), cfg.profiles().get("one-local").localServices(), "profile local list");
    }

    public static void testRejectsUnrecognizedFieldsAtEveryLevel() {
        List<String> configsWithUnknownFields = List.of(
            "{\"project\": \"acme\", \"runtimes\": {}, \"unknownTop\": 1}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"envFiles\": [{\"path\": \".env\", \"extra\": true}]}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"defaults\": {\"unknownDefault\": \"x\"}}",
            "{\"project\": \"acme\", \"runtimes\": {\"java\": {\"command\": [\"java\"], \"bogus\": 1}}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"gateway\": {\"type\": \"wiremock\", \"port\": 8080, \"extra\": 1}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"catalog\": {\"path\": \"a.json\", \"map\": {\"name\": \"n\"}, \"unknown\": 1}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"catalog\": {\"path\": \"a.json\", \"map\": {\"name\": \"n\", \"bogusField\": \"x\"}}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"services\": {\"s1\": {\"bogus\": 1}}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"profiles\": {\"p1\": {\"local\": [], \"remote\": []}}}"
        );

        for (String bad : configsWithUnknownFields) {
            DomainError err = Assert.assertErr(ProjectConfigParser.parse(bad), "should reject unknown field: " + bad);
            Assert.assertEquals("CONFIG_UNRECOGNIZED_FIELD", err.code(), "error code for: " + bad);
        }
    }

    public static void testRejectsMissingRequiredFieldsAndInvalidTypes() {
        List<String> invalidConfigs = List.of(
            "[]",
            "{\"runtimes\": {}}",
            "{\"project\": \"\", \"runtimes\": {}}",
            "{\"project\": \"../bad\", \"runtimes\": {}}",
            "{\"project\": \"a/b\", \"runtimes\": {}}",
            "{\"project\": \"acme\"}",
            "{\"project\": \"acme\", \"runtimes\": {\"java\": {}}}",
            "{\"project\": \"acme\", \"runtimes\": {\"java\": {\"command\": []}}}",
            "{\"project\": \"acme\", \"runtimes\": {\"java\": {\"command\": [123]}}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"gateway\": {\"type\": \"nginx\", \"port\": 8080}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"gateway\": {\"type\": \"wiremock\", \"port\": \"8080\"}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"gateway\": {\"type\": \"wiremock\", \"port\": 8080.5}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"gateway\": {\"type\": \"wiremock\", \"port\": 70000}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"services\": {\"s1\": {\"port\": \"9001\"}}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"services\": {\"s1\": {\"port\": 0}}}",
            "{\"project\": \"acme\", \"runtimes\": {}, \"catalog\": {\"path\": \"c.json\", \"map\": {\"port\": \"p\"}}}"
        );

        for (String bad : invalidConfigs) {
            DomainError err = Assert.assertErr(ProjectConfigParser.parse(bad), "should reject invalid config: " + bad);
            Assert.assertTrue(
                err.code().startsWith("CONFIG_"),
                "expected CONFIG_* error code for " + bad + ", got " + err.code()
            );
        }
    }

    public static void testPreservesExplicitEmptyHealthPathVersusOmittedField() {
        String json = """
            {
              "project": "acme",
              "runtimes": {
                "java": { "command": ["java"], "healthPath": "/actuator/health" },
                "tcp": { "command": ["redis"], "healthPath": "" }
              },
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "healthPath": ""
              },
              "services": {
                "cleared": { "runtime": "java", "port": 9001, "workingDir": ".", "healthPath": "" },
                "inherited": { "runtime": "java", "port": 9002, "workingDir": "." }
              }
            }
            """;

        RawProjectConfig cfg = Assert.assertOk(ProjectConfigParser.parse(json), "config parses");
        Assert.assertEquals(Optional.of("/actuator/health"), cfg.runtimes().get("java").healthPath(), "runtime healthPath preserved");
        Assert.assertEquals(Optional.of(""), cfg.runtimes().get("tcp").healthPath(), "runtime explicit empty healthPath preserved");
        Assert.assertEquals(Optional.of(""), cfg.gateway().orElseThrow().healthPath(), "gateway explicit empty healthPath preserved");
        Assert.assertEquals(Optional.of(""), cfg.services().get("cleared").healthPath(), "service explicit empty healthPath preserved");
        Assert.assertEquals(Optional.empty(), cfg.services().get("inherited").healthPath(), "service omitted healthPath is empty");
    }
}
