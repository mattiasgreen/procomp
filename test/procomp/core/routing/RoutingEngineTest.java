package procomp.core.routing;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.Project;
import procomp.core.project.RuntimeDefinition;
import procomp.core.project.SecretEnv;
import procomp.core.project.ServiceDefinition;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RoutingEngineTest {

    public static void testRouteParseIsStrictAndCaseInsensitive() {
        Assert.assertEquals(Route.LOCAL, Assert.assertOk(Route.parse("local"), "lowercase local"), "local parses");
        Assert.assertEquals(Route.LOCAL, Assert.assertOk(Route.parse("LOCAL"), "uppercase LOCAL"), "LOCAL parses");
        Assert.assertEquals(Route.REMOTE, Assert.assertOk(Route.parse("Remote"), "mixed case Remote"), "Remote parses");
        Assert.assertEquals(Route.REMOTE, Assert.assertOk(Route.parse("remote"), "lowercase remote"), "remote parses");

        DomainError invalid = Assert.assertErr(Route.parse("mock"), "invalid route rejected");
        Assert.assertEquals("ROUTE_INVALID_VALUE", invalid.code(), "invalid route code");
        Assert.assertEquals("ROUTE_INVALID_VALUE", Assert.assertErr(Route.parse(""), "blank route rejected").code(), "blank route");
        Assert.assertEquals("ROUTE_INVALID_VALUE", Assert.assertErr(Route.parse(" local"), "padded route rejected").code(), "padded route");
        Assert.assertEquals("ROUTE_INVALID_VALUE", Assert.assertErr(Route.parse(null), "null route rejected").code(), "null route");
    }

    public static void testFreshProjectDefaultsAllServicesToRemotePreservingOrder() {
        Project project = sampleProject(List.of("zeta", "alpha", "beta"), Map.of());

        RoutingTable table = Assert.assertOk(
            RoutingEngine.resolve(project, Optional.empty()),
            "fresh project should resolve"
        );

        Assert.assertEquals(
            List.of("zeta", "alpha", "beta"),
            List.copyOf(table.routes().keySet()),
            "routing table preserves project service order"
        );
        Assert.assertEquals(Route.REMOTE, table.routeOf("zeta").orElseThrow(), "zeta defaults to REMOTE");
        Assert.assertEquals(Route.REMOTE, table.routeOf("alpha").orElseThrow(), "alpha defaults to REMOTE");
        Assert.assertEquals(Route.REMOTE, table.routeOf("beta").orElseThrow(), "beta defaults to REMOTE");
        Assert.assertEquals(List.of(), table.localServices(), "no local services initially");
        Assert.assertEquals(List.of("zeta", "alpha", "beta"), table.remoteServices(), "all services remote initially");
    }

    public static void testResolveMergesPersistedRoutesDefaultsNewServicesIgnoresRemovedAndRejectsInvalidValues() {
        Project project = sampleProject(List.of("zeta", "alpha", "beta"), Map.of());

        Map<String, String> persisted = new LinkedHashMap<>();
        persisted.put("removed-svc", "LOCAL");
        persisted.put("alpha", "remote");
        persisted.put("zeta", "local");

        RoutingTable table = Assert.assertOk(
            RoutingEngine.resolve(project, Optional.of(persisted)),
            "persisted routing resolves cleanly"
        );

        Assert.assertEquals(
            List.of("zeta", "alpha", "beta"),
            List.copyOf(table.routes().keySet()),
            "resolved table ignores removed service and keeps project order"
        );
        Assert.assertEquals(Route.LOCAL, table.routeOf("zeta").orElseThrow(), "zeta resolved from persisted");
        Assert.assertEquals(Route.REMOTE, table.routeOf("alpha").orElseThrow(), "alpha resolved from persisted");
        Assert.assertEquals(Route.REMOTE, table.routeOf("beta").orElseThrow(), "new service beta defaults to REMOTE");

        Map<String, String> serialized = RoutingEngine.toPersistedMap(table);
        Assert.assertEquals(
            List.of("zeta", "alpha", "beta"),
            List.copyOf(serialized.keySet()),
            "persisted map preserves project service order"
        );
        Assert.assertEquals("LOCAL", serialized.get("zeta"), "zeta serialized as LOCAL");
        Assert.assertEquals("REMOTE", serialized.get("alpha"), "alpha serialized as REMOTE");
        Assert.assertEquals("REMOTE", serialized.get("beta"), "beta serialized as REMOTE");

        Map<String, String> invalidPersisted = Map.of("zeta", "LOCAL", "alpha", "BOGUS");
        DomainError err = Assert.assertErr(
            RoutingEngine.resolve(project, Optional.of(invalidPersisted)),
            "invalid persisted route value must fail fast"
        );
        Assert.assertEquals("ROUTE_INVALID_VALUE", err.code(), "invalid persisted route error code");
        Assert.assertEquals("alpha", err.details().get("service"), "error details identify offending service");
    }

    public static void testApplyRouteUpdatesSingleAndMultipleServicesAndFailsFastOnUnknownService() {
        Project project = sampleProject(List.of("zeta", "alpha", "beta"), Map.of());
        RoutingTable initial = Assert.assertOk(RoutingEngine.resolve(project, Optional.empty()), "initial resolve");

        RoutingTable oneLocal = Assert.assertOk(
            RoutingEngine.applyRoute(project, initial, List.of("alpha"), Route.LOCAL),
            "single update succeeds"
        );
        Assert.assertEquals(Route.REMOTE, oneLocal.routeOf("zeta").orElseThrow(), "zeta stays REMOTE");
        Assert.assertEquals(Route.LOCAL, oneLocal.routeOf("alpha").orElseThrow(), "alpha becomes LOCAL");
        Assert.assertEquals(Route.REMOTE, oneLocal.routeOf("beta").orElseThrow(), "beta stays REMOTE");

        RoutingTable multiLocal = Assert.assertOk(
            RoutingEngine.applyRoute(project, oneLocal, List.of("zeta", "beta"), Route.LOCAL),
            "multi update succeeds"
        );
        Assert.assertEquals(List.of("zeta", "alpha", "beta"), multiLocal.localServices(), "all three local");

        RoutingTable backToRemote = Assert.assertOk(
            RoutingEngine.applyRoute(project, multiLocal, List.of("alpha", "zeta"), Route.REMOTE),
            "multi remote update succeeds"
        );
        Assert.assertEquals(List.of("beta"), backToRemote.localServices(), "only beta remains LOCAL");
        Assert.assertEquals(List.of("zeta", "alpha", "beta"), List.copyOf(backToRemote.routes().keySet()), "order preserved");

        DomainError unknownErr = Assert.assertErr(
            RoutingEngine.applyRoute(project, oneLocal, List.of("zeta", "ghost"), Route.LOCAL),
            "mixed valid and unknown service must fail fast"
        );
        Assert.assertEquals("UNKNOWN_SERVICE", unknownErr.code(), "unknown service code");
        Assert.assertEquals("ghost", unknownErr.details().get("service"), "offending service recorded");

        DomainError emptyErr = Assert.assertErr(
            RoutingEngine.applyRoute(project, oneLocal, List.of(), Route.LOCAL),
            "empty service list rejected"
        );
        Assert.assertEquals("ROUTE_MISSING_SERVICES", emptyErr.code(), "empty service list code");
    }

    public static void testApplyProfileSetsListedLocalAndOthersRemoteAndRejectsUnknownProfile() {
        Project project = sampleProject(
            List.of("zeta", "alpha", "beta"),
            Map.of(
                "one-local", List.of("alpha"),
                "all-remote", List.of()
            )
        );

        RoutingTable oneLocal = Assert.assertOk(
            RoutingEngine.applyProfile(project, "one-local"),
            "known profile applies"
        );
        Assert.assertEquals(
            List.of("zeta", "alpha", "beta"),
            List.copyOf(oneLocal.routes().keySet()),
            "profile application preserves project service order"
        );
        Assert.assertEquals(Route.REMOTE, oneLocal.routeOf("zeta").orElseThrow(), "zeta is REMOTE");
        Assert.assertEquals(Route.LOCAL, oneLocal.routeOf("alpha").orElseThrow(), "alpha is LOCAL");
        Assert.assertEquals(Route.REMOTE, oneLocal.routeOf("beta").orElseThrow(), "beta is REMOTE");

        RoutingTable allRemote = Assert.assertOk(
            RoutingEngine.applyProfile(project, "all-remote"),
            "empty-local profile applies"
        );
        Assert.assertEquals(List.of(), allRemote.localServices(), "all-remote sets all services REMOTE");

        DomainError unknownErr = Assert.assertErr(
            RoutingEngine.applyProfile(project, "missing-profile"),
            "unknown profile fails fast"
        );
        Assert.assertEquals("UNKNOWN_PROFILE", unknownErr.code(), "unknown profile error code");
        Assert.assertEquals("missing-profile", unknownErr.details().get("profile"), "profile name in error details");
    }

    private static Project sampleProject(List<String> serviceNames, Map<String, List<String>> profileDefs) {
        Path cfgDir = Path.of("C:/work/acme").toAbsolutePath().normalize();
        RuntimeDefinition rt = new RuntimeDefinition(
            "java", List.of("java", "-jar", "app.jar"),
            Optional.of("/actuator/health"), Optional.empty(), Optional.empty(), Optional.empty(), SecretEnv.EMPTY
        );
        Map<String, ServiceDefinition> services = new LinkedHashMap<>();
        int port = 9001;
        for (String name : serviceNames) {
            services.put(name, new ServiceDefinition(
                name,
                Optional.of(port++),
                "java",
                Optional.empty(),
                cfgDir.resolve(name),
                Optional.of("/actuator/health"),
                "/" + name,
                Optional.of("https://shared.corp/" + name),
                Optional.empty(),
                Optional.empty(),
                List.of("java", "-jar", name + ".jar"),
                SecretEnv.EMPTY,
                List.of()
            ));
        }
        Map<String, ProfileDefinition> profiles = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : profileDefs.entrySet()) {
            profiles.put(e.getKey(), new ProfileDefinition(e.getKey(), e.getValue()));
        }
        return new Project(
            "acme",
            cfgDir.resolve("procomp.json"),
            cfgDir,
            Optional.empty(),
            Map.of("java", rt),
            Optional.empty(),
            services,
            profiles,
            List.of()
        );
    }
}
