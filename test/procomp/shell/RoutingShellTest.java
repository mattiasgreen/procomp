package procomp.shell;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.Project;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RoutingShellTest {

    public static void testShowAndUpdateRoutesPersistAndDoNotMutateOnUnknownService() {
        Project project = loadSampleProject();
        var stateStore = new InMemoryStateStore();
        var shell = new RoutingShell(stateStore);

        RoutingTable initial = Assert.assertOk(shell.showRoutes(project), "fresh project showRoutes succeeds");
        Assert.assertEquals(Route.REMOTE, initial.routeOf("orders").orElseThrow(), "orders defaults to REMOTE");
        Assert.assertEquals(Route.REMOTE, initial.routeOf("payments").orElseThrow(), "payments defaults to REMOTE");
        Assert.assertEquals(Route.REMOTE, initial.routeOf("inventory").orElseThrow(), "inventory defaults to REMOTE");
        Assert.assertEquals(
            Optional.empty(),
            Assert.assertOk(stateStore.readRouting("acme"), "read-only showRoutes does not write state"),
            "showRoutes must not persist routing.json"
        );

        RoutingTable updated = Assert.assertOk(
            shell.updateRoutes(project, List.of("orders", "inventory"), Route.LOCAL),
            "updateRoutes succeeds"
        );
        Assert.assertEquals(List.of("orders", "inventory"), updated.localServices(), "orders and inventory become LOCAL");

        RoutingTable reloaded = Assert.assertOk(new RoutingShell(stateStore).showRoutes(project), "reloaded showRoutes");
        Assert.assertEquals(updated, reloaded, "persisted routes reload identically");

        DomainError unknownErr = Assert.assertErr(
            shell.updateRoutes(project, List.of("payments", "missing-svc"), Route.LOCAL),
            "unknown service fails fast"
        );
        Assert.assertEquals("UNKNOWN_SERVICE", unknownErr.code(), "unknown service error code");

        RoutingTable afterErr = Assert.assertOk(shell.showRoutes(project), "routing table unchanged after error");
        Assert.assertEquals(updated, afterErr, "routing state must remain untouched when updateRoutes fails");
    }

    public static void testListAndUseProfilePersistsAndDoesNotMutateOnUnknownProfile() {
        Project project = loadSampleProject();
        var stateStore = new InMemoryStateStore();
        var shell = new RoutingShell(stateStore);

        List<ProfileDefinition> profiles = Assert.assertOk(shell.listProfiles(project), "listProfiles succeeds");
        Assert.assertEquals(
            List.of("orders-only", "empty-slice"),
            profiles.stream().map(ProfileDefinition::name).toList(),
            "profiles returned in config order"
        );
        Assert.assertEquals(List.of("orders"), profiles.get(0).localServices(), "orders-only local services");

        Assert.assertOk(shell.updateRoutes(project, List.of("payments"), Route.LOCAL), "set payments local first");

        RoutingTable applied = Assert.assertOk(shell.useProfile(project, "orders-only"), "useProfile succeeds");
        Assert.assertEquals(Route.LOCAL, applied.routeOf("orders").orElseThrow(), "orders set LOCAL by profile");
        Assert.assertEquals(Route.REMOTE, applied.routeOf("payments").orElseThrow(), "previous LOCAL payments reset to REMOTE");
        Assert.assertEquals(Route.REMOTE, applied.routeOf("inventory").orElseThrow(), "inventory stays REMOTE");

        RoutingTable persisted = Assert.assertOk(shell.showRoutes(project), "profile routing persisted");
        Assert.assertEquals(applied, persisted, "persisted routing matches applied profile");

        DomainError unknownProfile = Assert.assertErr(
            shell.useProfile(project, "no-such-profile"),
            "unknown profile fails fast"
        );
        Assert.assertEquals("UNKNOWN_PROFILE", unknownProfile.code(), "unknown profile error code");
        Assert.assertEquals(
            applied,
            Assert.assertOk(shell.showRoutes(project), "routing unchanged after failed useProfile"),
            "state untouched on unknown profile"
        );
    }

    public static void testRoutingShellDependsOnlyOnStateStorePort() {
        var ctors = RoutingShell.class.getConstructors();
        Assert.assertEquals(1, ctors.length, "RoutingShell exposes exactly one public constructor");
        Assert.assertEquals(1, ctors[0].getParameterCount(), "RoutingShell takes exactly one port");
        Assert.assertEquals(
            procomp.shell.ports.StateStore.class,
            ctors[0].getParameterTypes()[0],
            "RoutingShell depends strictly on StateStore only"
        );
    }

    private static Project loadSampleProject() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "." },
                    "payments": { "runtime": "java", "port": 9002, "workingDir": "." },
                    "inventory": { "runtime": "java", "port": 9003, "workingDir": "." }
                  },
                  "profiles": {
                    "orders-only": { "local": ["orders"] },
                    "empty-slice": { "local": [] }
                  }
                }
                """);
        return Assert.assertOk(new ProjectLoader(configSource).load(Optional.empty()), "sample project loads");
    }
}
