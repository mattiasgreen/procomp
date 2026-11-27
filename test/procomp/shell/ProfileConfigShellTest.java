package procomp.shell;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.Project;
import procomp.fakes.InMemoryConfigSource;
import procomp.shell.ProfileConfigShell.DeleteProfileResult;
import procomp.shell.ProfileConfigShell.SaveProfileResult;
import procomp.shell.ProjectLoader.LoadedProject;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class ProfileConfigShellTest {

    private static final Path WORK = Path.of("/work").toAbsolutePath().normalize();
    private static final Path CONFIG = WORK.resolve("procomp.json");

    private static final String BASE_CONFIG = """
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

    public static void testSaveAndDeleteProfileRoundTripsThroughConfigSource() {
        InMemoryConfigSource configSource = new InMemoryConfigSource(WORK)
            .withFile(CONFIG, BASE_CONFIG);
        ProcompRuntime runtime = ProcompRuntime.builder()
            .configSource(configSource)
            .build();

        ProjectLoader loader = Assert.assertOk(runtime.projectLoader(), "projectLoader");
        ProfileConfigShell shell = Assert.assertOk(runtime.profileConfigShell(), "profileConfigShell");

        LoadedProject loaded = Assert.assertOk(loader.loadWithPath(Optional.empty()), "initial load");

        // Save new profile
        SaveProfileResult saved = Assert.assertOk(
            shell.saveProfile(loaded, "both-local", List.of("orders", "billing")),
            "save new profile"
        );
        Assert.assertFalse(saved.overwrote(), "new profile did not overwrite");
        Assert.assertEquals(new ProfileDefinition("both-local", List.of("orders", "billing")), saved.profile(), "saved profile");
        Assert.assertEquals(2, saved.loaded().project().profiles().size(), "reloaded project has 2 profiles");

        // Reload independently from ConfigSource to verify persisted file is valid
        Project reloadedAfterSave = Assert.assertOk(loader.load(Optional.empty()), "reload after save");
        Assert.assertEquals(
            List.of("orders", "billing"),
            reloadedAfterSave.profile("both-local").orElseThrow().localServices(),
            "persisted profile services"
        );

        // Overwrite existing profile
        SaveProfileResult overwritten = Assert.assertOk(
            shell.saveProfile(saved.loaded(), "one-local", List.of()),
            "overwrite existing profile"
        );
        Assert.assertTrue(overwritten.overwrote(), "reported overwrite");
        Assert.assertEquals(List.of(), overwritten.profile().localServices(), "empty local services");

        // Delete profile
        DeleteProfileResult deleted = Assert.assertOk(
            shell.deleteProfile(overwritten.loaded(), "both-local"),
            "delete profile"
        );
        Assert.assertEquals("both-local", deleted.deletedName(), "deleted name");
        Assert.assertTrue(deleted.loaded().project().profile("both-local").isEmpty(), "profile removed");

        // Delete missing profile fails fast with UNKNOWN_PROFILE
        DomainError missingErr = Assert.assertErr(
            shell.deleteProfile(deleted.loaded(), "both-local"),
            "delete already deleted profile"
        );
        Assert.assertEquals("UNKNOWN_PROFILE", missingErr.code(), "missing profile error code");
    }

    public static void testSaveProfileRejectsUnknownServiceWithoutModifyingConfig() {
        InMemoryConfigSource configSource = new InMemoryConfigSource(WORK)
            .withFile(CONFIG, BASE_CONFIG);
        ProcompRuntime runtime = ProcompRuntime.builder()
            .configSource(configSource)
            .build();
        LoadedProject loaded = Assert.assertOk(
            Assert.assertOk(runtime.projectLoader(), "loader").loadWithPath(Optional.empty()),
            "load"
        );
        ProfileConfigShell shell = Assert.assertOk(runtime.profileConfigShell(), "shell");

        DomainError err = Assert.assertErr(
            shell.saveProfile(loaded, "bad-slice", List.of("orders", "nonexistent")),
            "unknown service rejected"
        );
        Assert.assertEquals("UNKNOWN_SERVICE", err.code(), "error code");
        String afterRaw = Assert.assertOk(configSource.readFile(CONFIG), "config untouched");
        Assert.assertEquals(BASE_CONFIG, afterRaw, "procomp.json unchanged on validation failure");
    }

    public static void testCliCommandFormatterFormatsSaveAndDeleteProfile() {
        CliCommandFormatter plain = CliCommandFormatter.plain();
        Assert.assertEquals("procomp profile save my-slice", plain.saveProfileSnapshot("my-slice"), "snapshot");
        Assert.assertEquals("procomp profile save my-slice orders billing", plain.saveProfile("my-slice", List.of("orders", "billing")), "explicit");
        Assert.assertEquals("procomp profile save all-remote --none", plain.saveProfile("all-remote", List.of()), "empty");
        Assert.assertEquals("procomp profile delete my-slice", plain.deleteProfile("my-slice"), "delete");
    }
}
