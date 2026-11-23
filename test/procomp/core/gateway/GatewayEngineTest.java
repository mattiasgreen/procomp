package procomp.core.gateway;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingEngine;
import procomp.core.routing.RoutingTable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class GatewayEngineTest {

    public static void testBuildPlanGeneratesLocalAndRemoteWireMockMappingsAndFailsFastOnMissingTargets() {
        Project project = loadProject("""
            {
              "project": "acme",
              "defaults": {
                "proxyPathTemplate": "/{{service}}",
                "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}/"
              },
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "healthPath": "/__admin/health"
              },
              "services": {
                "order-service": {
                  "runtime": "java",
                  "port": 9001,
                  "proxyPath": "/order-service/",
                  "workingDir": "."
                },
                "billing-service": {
                  "runtime": "java",
                  "port": 9002,
                  "workingDir": "."
                }
              }
            }
            """);

        RoutingTable routing = Assert.assertOk(
            RoutingEngine.applyRoute(project, Assert.assertOk(RoutingEngine.resolve(project, java.util.Optional.empty()), "resolve"), List.of("order-service"), Route.LOCAL),
            "route order-service LOCAL"
        );

        GatewayPlan plan = Assert.assertOk(GatewayEngine.buildPlan(project, routing), "buildPlan succeeds");
        Assert.assertEquals("acme", plan.projectName(), "plan project name");
        Assert.assertEquals(2, plan.mappings().size(), "one mapping per service");

        WireMockMapping orders = plan.mappings().get(0);
        Assert.assertEquals("order-service", orders.service(), "first mapping service");
        Assert.assertEquals(Route.LOCAL, orders.route(), "orders routed LOCAL");
        Assert.assertEquals("/order-service(/.*)?", orders.urlPathPattern(), "normalized prefix urlPathPattern");
        Assert.assertEquals("http://127.0.0.1:9001", orders.targetUrl(), "LOCAL target URL");
        Assert.assertEquals(java.util.Optional.of("/order-service"), orders.proxyUrlPrefixToRemove(), "prefix removed");

        WireMockMapping billing = plan.mappings().get(1);
        Assert.assertEquals("billing-service", billing.service(), "second mapping service");
        Assert.assertEquals(Route.REMOTE, billing.route(), "billing routed REMOTE");
        Assert.assertEquals("/billing-service(/.*)?", billing.urlPathPattern(), "billing urlPathPattern");
        Assert.assertEquals("https://shared-dev.mycorp/billing-service", billing.targetUrl(), "REMOTE target URL trims trailing slash");
        Assert.assertEquals(java.util.Optional.of("/billing-service"), billing.proxyUrlPrefixToRemove(), "billing prefix removed");

        // Verify WireMock import payload JSON shape and procomp metadata
        String importJson = GatewayEngine.toImportPayloadJson(plan);
        JsonValue.JsonObject importObj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(importJson), "valid import json");
        JsonValue.JsonArray arr = (JsonValue.JsonArray) importObj.get("mappings").orElseThrow();
        Assert.assertEquals(2, arr.elements().size(), "2 mappings in import payload");
        JsonValue.JsonObject first = (JsonValue.JsonObject) arr.elements().get(0);
        JsonValue.JsonObject req = (JsonValue.JsonObject) first.get("request").orElseThrow();
        JsonValue.JsonObject resp = (JsonValue.JsonObject) first.get("response").orElseThrow();
        JsonValue.JsonObject meta = (JsonValue.JsonObject) first.get("metadata").orElseThrow();

        Assert.assertEquals("ANY", ((JsonValue.JsonString) req.get("method").orElseThrow()).value(), "request method ANY");
        Assert.assertEquals("/order-service(/.*)?", ((JsonValue.JsonString) req.get("urlPathPattern").orElseThrow()).value(), "request urlPathPattern");
        Assert.assertEquals("http://127.0.0.1:9001", ((JsonValue.JsonString) resp.get("proxyBaseUrl").orElseThrow()).value(), "response proxyBaseUrl");
        Assert.assertEquals("/order-service", ((JsonValue.JsonString) resp.get("proxyUrlPrefixToRemove").orElseThrow()).value(), "response proxyUrlPrefixToRemove");
        Assert.assertEquals(Boolean.TRUE, ((JsonValue.JsonBoolean) meta.get("procomp").orElseThrow()).value(), "metadata.procomp=true");
        Assert.assertEquals("acme", ((JsonValue.JsonString) meta.get("procompProject").orElseThrow()).value(), "metadata.procompProject");
        Assert.assertEquals("order-service", ((JsonValue.JsonString) meta.get("service").orElseThrow()).value(), "metadata.service");
        Assert.assertEquals("LOCAL", ((JsonValue.JsonString) meta.get("route").orElseThrow()).value(), "metadata.route");

        // Fail fast on LOCAL without port (zero silent fallback to REMOTE)
        Project noPortLocal = loadProject("""
            {
              "project": "acme",
              "defaults": { "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}" },
              "runtimes": { "plain": { "command": ["node", "app.js"] } },
              "services": { "worker": { "runtime": "plain", "workingDir": "." } }
            }
            """);
        RoutingTable localWorker = Assert.assertOk(
            RoutingEngine.applyRoute(noPortLocal, Assert.assertOk(RoutingEngine.resolve(noPortLocal, java.util.Optional.empty()), "resolve"), List.of("worker"), Route.LOCAL),
            "route worker LOCAL"
        );
        DomainError missingPortErr = Assert.assertErr(GatewayEngine.buildPlan(noPortLocal, localWorker), "LOCAL without port fails fast");
        Assert.assertEquals("GATEWAY_MISSING_LOCAL_PORT", missingPortErr.code(), "missing local port error code");

        // Fail fast on REMOTE without remoteUrl (zero silent fallback to LOCAL)
        Project noRemoteUrl = loadProject("""
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "services": { "orders": { "runtime": "java", "port": 9001, "workingDir": "." } }
            }
            """);
        RoutingTable remoteOrders = Assert.assertOk(RoutingEngine.resolve(noRemoteUrl, java.util.Optional.empty()), "default REMOTE");
        DomainError missingRemoteErr = Assert.assertErr(GatewayEngine.buildPlan(noRemoteUrl, remoteOrders), "REMOTE without remoteUrl fails fast");
        Assert.assertEquals("GATEWAY_MISSING_REMOTE_URL", missingRemoteErr.code(), "missing remoteUrl error code");

        // Verify urlPathPattern covers /<prefix> and /<prefix>/** without matching /<prefix>-v2
        Assert.assertTrue(Pattern.matches(orders.urlPathPattern(), "/order-service"), "matches exact prefix");
        Assert.assertTrue(Pattern.matches(orders.urlPathPattern(), "/order-service/"), "matches trailing slash");
        Assert.assertTrue(Pattern.matches(orders.urlPathPattern(), "/order-service/v1/items/42"), "matches subpath");
        Assert.assertFalse(Pattern.matches(orders.urlPathPattern(), "/order-service-v2"), "does not match sibling prefix");
    }

    public static void testComputeDriftDetectsOfflineInSyncUnsetRouteFlipMismatchStaleAndIgnoresForeignMappings() {
        Project project = loadProject("""
            {
              "project": "acme",
              "defaults": {
                "proxyPathTemplate": "/{{service}}",
                "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}"
              },
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "healthPath": "/__admin/health"
              },
              "services": {
                "orders": { "runtime": "java", "port": 9001, "workingDir": "." },
                "billing": { "runtime": "java", "port": 9002, "workingDir": "." }
              }
            }
            """);
        RoutingTable base = Assert.assertOk(RoutingEngine.resolve(project, Optional.empty()), "resolve default");
        RoutingTable ordersLocal = Assert.assertOk(
            RoutingEngine.applyRoute(project, base, List.of("orders"), Route.LOCAL),
            "route orders LOCAL"
        );

        // 1. Offline gateway -> OFFLINE with empty drift lists regardless of body
        GatewayDriftReport offline = Assert.assertOk(
            GatewayEngine.computeDrift(project, ordersLocal, false, ""),
            "offline computeDrift succeeds"
        );
        Assert.assertEquals(GatewaySyncStatus.OFFLINE, offline.syncStatus(), "offline status");
        Assert.assertTrue(offline.drifted().isEmpty(), "offline has empty drifted list");
        Assert.assertTrue(offline.staleMappings().isEmpty(), "offline has empty stale list");

        // 2. Empty mappings when healthy -> DRIFTED with UNSET for every service
        GatewayDriftReport emptyMappings = Assert.assertOk(
            GatewayEngine.computeDrift(project, ordersLocal, true, "{\"mappings\":[]}"),
            "empty mappings computeDrift"
        );
        Assert.assertEquals(GatewaySyncStatus.DRIFTED, emptyMappings.syncStatus(), "empty WireMock is DRIFTED");
        Assert.assertEquals(2, emptyMappings.drifted().size(), "both services unset");
        Assert.assertEquals(new RouteDrift("orders", Route.LOCAL, "UNSET"), emptyMappings.drifted().get(0), "orders UNSET");
        Assert.assertEquals(new RouteDrift("billing", Route.REMOTE, "UNSET"), emptyMappings.drifted().get(1), "billing UNSET");

        // 3. Exact matching mappings + unrelated non-procomp stub + other project's mapping -> IN_SYNC!
        GatewayPlan plan = Assert.assertOk(GatewayEngine.buildPlan(project, ordersLocal), "buildPlan");
        String ordersJson = GatewayEngine.toMappingJson(plan.mappings().get(0));
        String billingJson = GatewayEngine.toMappingJson(plan.mappings().get(1));
        String userStubJson = """
            {"id":"11111111-1111-1111-1111-111111111111","request":{"method":"GET","urlPath":"/custom"},"response":{"status":200}}""";
        String otherProjectJson = """
            {"id":"22222222-2222-2222-2222-222222222222","request":{"method":"ANY","urlPathPattern":"/orders(/.*)?"},"response":{"proxyBaseUrl":"http://127.0.0.1:9999","proxyUrlPrefixToRemove":"/orders"},"metadata":{"procomp":true,"procompProject":"other-proj","service":"orders","route":"LOCAL"}}""";
        String adminJson = "{\"mappings\":[" + userStubJson + "," + otherProjectJson + "," + ordersJson + "," + billingJson + "]}";

        GatewayDriftReport inSync = Assert.assertOk(
            GatewayEngine.computeDrift(project, ordersLocal, true, adminJson),
            "matching mappings + foreign mappings -> IN_SYNC"
        );
        Assert.assertEquals(GatewaySyncStatus.IN_SYNC, inSync.syncStatus(), "IN_SYNC ignores unrelated & other-project stubs");
        Assert.assertTrue(inSync.drifted().isEmpty(), "no drifted routes");
        Assert.assertTrue(inSync.staleMappings().isEmpty(), "no stale mappings");

        // findManagedMappingIds selects ONLY acme's managed mapping IDs
        List<String> managedIds = Assert.assertOk(GatewayEngine.findManagedMappingIds(project, adminJson), "findManagedMappingIds");
        Assert.assertEquals(List.of(plan.mappings().get(0).id(), plan.mappings().get(1).id()), managedIds, "selects only acme managed IDs");

        // 4. Flipping Routing Table in procomp -> DRIFTED showing exact desired vs live ("LOCAL"/"REMOTE")
        RoutingTable flipped = Assert.assertOk(
            RoutingEngine.applyRoute(project, ordersLocal, List.of("orders"), Route.REMOTE),
            "flip orders REMOTE"
        );
        RoutingTable bothFlipped = Assert.assertOk(
            RoutingEngine.applyRoute(project, flipped, List.of("billing"), Route.LOCAL),
            "flip billing LOCAL"
        );
        GatewayDriftReport routeFlipped = Assert.assertOk(
            GatewayEngine.computeDrift(project, bothFlipped, true, adminJson),
            "route flip drift"
        );
        Assert.assertEquals(GatewaySyncStatus.DRIFTED, routeFlipped.syncStatus(), "DRIFTED when routes flipped");
        Assert.assertEquals(new RouteDrift("orders", Route.REMOTE, "LOCAL"), routeFlipped.drifted().get(0), "orders desired REMOTE live LOCAL");
        Assert.assertEquals(new RouteDrift("billing", Route.LOCAL, "REMOTE"), routeFlipped.drifted().get(1), "billing desired LOCAL live REMOTE");

        // 5. Wrong target URL / altered pattern / stale removed-service mapping -> MISMATCH + staleMappings -> DRIFTED
        String wrongOrdersJson = """
            {"id":"33333333-3333-3333-3333-333333333333","request":{"method":"ANY","urlPathPattern":"/orders(/.*)?"},"response":{"proxyBaseUrl":"http://127.0.0.1:9999","proxyUrlPrefixToRemove":"/orders"},"metadata":{"procomp":true,"procompProject":"acme","service":"orders","route":"LOCAL"}}""";
        String staleServiceJson = """
            {"id":"44444444-4444-4444-4444-444444444444","request":{"method":"ANY","urlPathPattern":"/legacy(/.*)?"},"response":{"proxyBaseUrl":"https://shared-dev.mycorp/legacy","proxyUrlPrefixToRemove":"/legacy"},"metadata":{"procomp":true,"procompProject":"acme","service":"legacy","route":"REMOTE"}}""";
        String driftedAdminJson = "{\"mappings\":[" + wrongOrdersJson + "," + billingJson + "," + staleServiceJson + "]}";

        GatewayDriftReport mismatchAndStale = Assert.assertOk(
            GatewayEngine.computeDrift(project, ordersLocal, true, driftedAdminJson),
            "mismatch + stale computeDrift"
        );
        Assert.assertEquals(GatewaySyncStatus.DRIFTED, mismatchAndStale.syncStatus(), "DRIFTED on mismatch & stale");
        Assert.assertEquals(List.of(new RouteDrift("orders", Route.LOCAL, "MISMATCH")), mismatchAndStale.drifted(), "orders MISMATCH");
        Assert.assertEquals(1, mismatchAndStale.staleMappings().size(), "1 stale mapping");
        Assert.assertEquals(Optional.of("legacy"), mismatchAndStale.staleMappings().get(0).service(), "stale service name");
        Assert.assertEquals(Optional.of("44444444-4444-4444-4444-444444444444"), mismatchAndStale.staleMappings().get(0).id(), "stale mapping id");

        // 6. Malformed admin JSON fails fast when healthy
        DomainError badJsonErr = Assert.assertErr(
            GatewayEngine.computeDrift(project, ordersLocal, true, "{not json"),
            "malformed admin mappings json fails fast"
        );
        Assert.assertEquals("GATEWAY_INVALID_MAPPINGS", badJsonErr.code(), "invalid mappings error code");
    }

    public static void testGatewayProxyHeadersApplyToLocalOnlyAndDetectDrift() {
        Path configPath = Path.of("C:/work/acme/procomp.json").toAbsolutePath().normalize();
        String json = """
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "proxyHeaders": {
                  "USERNAME": "{{user}}",
                  "X-GATEWAY": "local-procomp"
                }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": ".", "proxyPath": "/orders", "remoteUrl": "https://dev.corp/orders" },
                "billing": { "port": 9002, "runtime": "java", "workingDir": ".", "proxyPath": "/billing", "remoteUrl": "https://dev.corp/billing" }
              }
            }
            """;
        Project project = Assert.assertOk(
            ProjectResolver.resolve(configPath, json, _ -> Result.ok(Optional.empty()), Map.of(), Optional.of("alice")),
            "resolve project"
        );
        RoutingTable routing = new RoutingTable(Map.of("orders", Route.LOCAL, "billing", Route.REMOTE));

        GatewayPlan plan = Assert.assertOk(GatewayEngine.buildPlan(project, routing), "build plan");
        WireMockMapping ordersMap = plan.mappings().get(0);
        WireMockMapping billingMap = plan.mappings().get(1);

        Assert.assertEquals(
            Map.of("USERNAME", "alice", "X-GATEWAY", "local-procomp"),
            ordersMap.additionalProxyRequestHeaders(),
            "LOCAL service receives gateway.proxyHeaders"
        );
        Assert.assertEquals(
            Map.of(),
            billingMap.additionalProxyRequestHeaders(),
            "REMOTE service does not receive gateway.proxyHeaders"
        );

        String ordersJson = GatewayEngine.toMappingJson(ordersMap);
        String billingJson = GatewayEngine.toMappingJson(billingMap);
        Assert.assertContains(ordersJson, "\"additionalProxyRequestHeaders\"", "LOCAL JSON emits additionalProxyRequestHeaders");
        Assert.assertContains(ordersJson, "\"USERNAME\":\"alice\"", "LOCAL JSON contains resolved USERNAME");
        Assert.assertFalse(billingJson.contains("additionalProxyRequestHeaders"), "REMOTE JSON omits additionalProxyRequestHeaders");

        // Live mappings matching plan -> IN_SYNC
        String syncedLive = "{\"mappings\":[" + ordersJson + "," + billingJson + "]}";
        GatewayDriftReport inSync = Assert.assertOk(
            GatewayEngine.computeDrift(project, routing, true, syncedLive),
            "computeDrift in sync"
        );
        Assert.assertEquals(GatewaySyncStatus.IN_SYNC, inSync.syncStatus(), "should be IN_SYNC");

        // Changing USERNAME in live WireMock mapping -> DRIFTED (MISMATCH)
        String driftedLive = syncedLive.replace("\"USERNAME\":\"alice\"", "\"USERNAME\":\"bob\"");
        GatewayDriftReport drifted = Assert.assertOk(
            GatewayEngine.computeDrift(project, routing, true, driftedLive),
            "computeDrift header mismatch"
        );
        Assert.assertEquals(GatewaySyncStatus.DRIFTED, drifted.syncStatus(), "header change must be detected as DRIFTED");
        Assert.assertEquals(1, drifted.drifted().size(), "one drifted route");
        Assert.assertEquals("orders", drifted.drifted().get(0).service(), "orders drifted");
        Assert.assertEquals("MISMATCH", drifted.drifted().get(0).live(), "header diff reports MISMATCH");
    }

    public static void testGatewayCoreHasZeroDependencyOnProcessOrShellPackages() {
        for (Class<?> c : List.of(
            GatewayEngine.class, GatewayPlan.class, WireMockMapping.class,
            GatewaySyncStatus.class, RouteDrift.class, StaleManagedMapping.class, GatewayDriftReport.class
        )) {
            for (var field : c.getDeclaredFields()) {
                String typeName = field.getType().getName();
                Assert.assertFalse(typeName.startsWith("procomp.core.process."), "core.gateway must not reference core.process: " + typeName);
                Assert.assertFalse(typeName.startsWith("procomp.shell."), "core.gateway must not reference shell: " + typeName);
            }
        }
    }

    private static Project loadProject(String json) {
        Path configPath = Path.of("C:/work/acme/procomp.json").toAbsolutePath().normalize();
        return Assert.assertOk(
            ProjectResolver.resolve(configPath, json, _ -> Result.ok(Optional.empty()), Map.of()),
            "project resolves"
        );
    }
}
