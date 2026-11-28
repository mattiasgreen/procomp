package procomp.core.project;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ProfileConfigEditorTest {

    private static final Path CONFIG_PATH = Path.of("/work/procomp.json");

    public static void testUpsertAddsNewProfileAndPreservesSurroundingFormatting() {
        String rawJson = """
            {
                "project": "acme",
                "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                },
                "services": {
                    "orders": { "port": 9001, "runtime": "java", "workingDir": "." },
                    "billing": { "port": 9002, "runtime": "java", "workingDir": "." }
                },
                "profiles": {
                    "one-local": {
                        "local": ["orders"]
                    }
                }
            }
            """;
        Project project = resolve(rawJson);

        String updated = Assert.assertOk(
            ProfileConfigEditor.upsertProfile(rawJson, project, "both-local", List.of("orders", "billing", "orders")),
            "upsert new profile"
        );

        String expected = """
            {
                "project": "acme",
                "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                },
                "services": {
                    "orders": { "port": 9001, "runtime": "java", "workingDir": "." },
                    "billing": { "port": 9002, "runtime": "java", "workingDir": "." }
                },
                "profiles": {
                    "one-local": {
                        "local": ["orders"]
                    },
                    "both-local": {
                        "local": ["orders", "billing"]
                    }
                }
            }
            """;
        Assert.assertEquals(expected, updated, "surrounding 4-space formatting and single-line arrays preserved");
        Project reloaded = resolve(updated);
        Assert.assertEquals(List.of("orders", "billing"), reloaded.profile("both-local").orElseThrow().localServices(), "reloaded profile");
    }

    public static void testUpsertOverwritesExistingProfileInPlace() {
        String rawJson = """
            {
              "project": "acme",
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": "." },
                "billing": { "port": 9002, "runtime": "java", "workingDir": "." }
              },
              "profiles": {
                "alpha": {
                  "local": ["orders"]
                },
                "beta": {
                  "local": ["billing"]
                }
              }
            }
            """;
        Project project = resolve(rawJson);

        String updated = Assert.assertOk(
            ProfileConfigEditor.upsertProfile(rawJson, project, "alpha", List.of()),
            "overwrite existing profile with empty local list"
        );

        String expected = """
            {
              "project": "acme",
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": "." },
                "billing": { "port": 9002, "runtime": "java", "workingDir": "." }
              },
              "profiles": {
                "alpha": {
                  "local": []
                },
                "beta": {
                  "local": ["billing"]
                }
              }
            }
            """;
        Assert.assertEquals(expected, updated, "alpha updated in place preserving beta order");
    }

    public static void testUpsertInsertsProfilesBlockWhenAbsent() {
        String rawJson = """
            {
              "project": "acme",
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": "." }
              }
            }
            """;
        Project project = resolve(rawJson);

        String updated = Assert.assertOk(
            ProfileConfigEditor.upsertProfile(rawJson, project, "orders-only", List.of("orders")),
            "insert profiles block when absent"
        );

        String expected = """
            {
              "project": "acme",
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": "." }
              },
              "profiles": {
                "orders-only": {
                  "local": ["orders"]
                }
              }
            }
            """;
        Assert.assertEquals(expected, updated, "profiles block inserted cleanly before closing brace");
    }

    public static void testUpsertIgnoresNestedProfilesKeyInVars() {
        String rawJson = """
            {
              "project": "acme",
              "defaults": {
                "vars": {
                  "profiles": "spring-local"
                }
              },
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": "." }
              }
            }
            """;
        Project project = resolve(rawJson);

        String updated = Assert.assertOk(
            ProfileConfigEditor.upsertProfile(rawJson, project, "slice", List.of("orders")),
            "nested profiles key must not be touched"
        );

        Assert.assertContains(updated, "\"profiles\": \"spring-local\"", "nested var preserved");
        Project reloaded = resolve(updated);
        Assert.assertEquals(List.of("orders"), reloaded.profile("slice").orElseThrow().localServices(), "top-level profile added");
    }

    public static void testDeleteRemovesProfileAndLeavesEmptyObjectWhenLastDeleted() {
        String rawJson = """
            {
              "project": "acme",
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": "." }
              },
              "profiles": {
                "solo": {
                  "local": ["orders"]
                }
              }
            }
            """;
        Project project = resolve(rawJson);

        String updated = Assert.assertOk(
            ProfileConfigEditor.deleteProfile(rawJson, project, "solo"),
            "delete last profile"
        );

        String expected = """
            {
              "project": "acme",
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": "." }
              },
              "profiles": {}
            }
            """;
        Assert.assertEquals(expected, updated, "deleting last profile leaves profiles: {}");
        Project reloaded = resolve(updated);
        Assert.assertTrue(reloaded.profiles().isEmpty(), "reloaded project has no profiles");
    }

    public static void testRejectsInvalidProfileNamesAndUnknownServicesAndMissingDeletes() {
        String rawJson = """
            {
              "project": "acme",
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": "." }
              }
            }
            """;
        Project project = resolve(rawJson);

        for (String badName : List.of("", "   ", "-flag", "--none", "has space", "a/b", "a\\b", "tab\tname")) {
            DomainError err = Assert.assertErr(
                ProfileConfigEditor.upsertProfile(rawJson, project, badName, List.of("orders")),
                "invalid name: " + badName
            );
            Assert.assertEquals("PROFILE_INVALID_NAME", err.code(), "error code for " + badName);
        }

        DomainError unknownSvc = Assert.assertErr(
            ProfileConfigEditor.upsertProfile(rawJson, project, "valid-name", List.of("ghost")),
            "unknown service"
        );
        Assert.assertEquals("UNKNOWN_SERVICE", unknownSvc.code(), "unknown service error code");

        DomainError unknownProf = Assert.assertErr(
            ProfileConfigEditor.deleteProfile(rawJson, project, "missing"),
            "delete missing profile"
        );
        Assert.assertEquals("UNKNOWN_PROFILE", unknownProf.code(), "unknown profile error code");
    }

    private static Project resolve(String rawJson) {
        Result<Project, DomainError> res = ProjectResolver.resolve(
            CONFIG_PATH,
            rawJson,
            _ -> Result.ok(Optional.empty()),
            Map.of(),
            Optional.of("tester")
        );
        return Assert.assertOk(res, "resolve test project");
    }
}
