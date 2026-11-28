package procomp.shell;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.JsonValue;
import procomp.core.gateway.GatewayDriftReport;
import procomp.core.gateway.GatewaySyncStatus;
import procomp.core.gateway.RouteDrift;
import procomp.core.gateway.StaleManagedMapping;
import procomp.core.process.DiscoveryResult;
import procomp.core.process.HealthState;
import procomp.core.process.InspectionReport;
import procomp.core.process.ObservedProcess;
import procomp.core.process.Ownership;
import procomp.core.process.ParentProcess;
import procomp.core.process.ProcessState;
import procomp.core.project.GatewayDefinition;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.Project;
import procomp.core.project.SecretEnv;
import procomp.core.project.ServiceDefinition;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.GatewayShell.GatewayReconcileResult;
import procomp.shell.GatewayShell.GatewayStartDisposition;
import procomp.shell.GatewayShell.GatewayStartResult;
import procomp.shell.GatewayShell.GatewayStatusReport;
import procomp.shell.GatewayShell.GatewayStopDisposition;
import procomp.shell.GatewayShell.GatewayStopResult;
import procomp.shell.ProcessShell.GatewayStopOutcome;
import procomp.shell.ProcessShell.StartDisposition;
import procomp.shell.ProcessShell.StartResult;
import procomp.shell.ProcessShell.StartServiceOutcome;
import procomp.shell.ProcessShell.StopDisposition;
import procomp.shell.ProcessShell.StopResult;
import procomp.shell.ProcessShell.StopServiceOutcome;
import procomp.shell.StatusShell.EstateStatus;
import procomp.shell.StatusShell.GatewaySummary;
import procomp.shell.StatusShell.ServiceStatusRow;
import procomp.shell.ports.ProgressObserver.ProgressEvent;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DomainJsonAndCliFormatTest {

    public static void testDomainJsonSerializesEstateStatusConfiguredAndUnconfiguredGateway() {
        GatewayDefinition gwDef = sampleGateway();
        ObservedProcess gwProc = new ObservedProcess(
            8080L, true, Optional.of(Instant.parse("2026-11-20T11:57:55Z")),
            Optional.of("java -jar wiremock.jar"), List.of(), List.of(), Optional.empty(), Optional.of("java")
        );
        DiscoveryResult gwDiscovery = new DiscoveryResult(
            "gateway",
            new ProcessState.Managed.Healthy(8080L, Instant.parse("2026-11-20T11:57:55Z"), false, gwProc),
            Optional.of("http://127.0.0.1:8080/__admin/health"),
            "HTTP 200"
        );
        GatewayDriftReport drift = new GatewayDriftReport(
            GatewaySyncStatus.DRIFTED,
            List.of(new RouteDrift("orders", Route.LOCAL, "REMOTE")),
            List.of(new StaleManagedMapping(Optional.of("m-1"), Optional.of("legacy"), Optional.of("http://127.0.0.1:9999"))),
            Map.of("orders", "REMOTE")
        );
        GatewayStatusReport gwReport = new GatewayStatusReport(
            "acme", gwDef, gwDiscovery, Optional.of(Duration.ofSeconds(125)), drift
        );

        ServiceStatusRow row = new ServiceStatusRow(
            "orders",
            Route.LOCAL,
            Ownership.UNMANAGED,
            Optional.of("IDE"),
            HealthState.HEALTHY,
            Optional.of(9001),
            Optional.of(4200L),
            Optional.of(Instant.parse("2026-11-20T11:45:00Z")),
            Optional.of(Duration.ofSeconds(900)),
            true,
            Optional.of("http://127.0.0.1:9001/health"),
            Optional.of("HTTP 200")
        );

        EstateStatus configured = new EstateStatus("acme", GatewaySummary.of(gwReport), List.of(row));
        JsonValue.JsonObject obj = DomainJson.estateStatus(configured);
        Assert.assertEquals("acme", str(obj, "project"), "estate project");

        JsonValue.JsonObject gwObj = obj(obj, "gateway");
        Assert.assertEquals(true, bool(gwObj, "configured"), "gateway configured");
        Assert.assertEquals("wiremock", str(gwObj, "type"), "gateway type");
        Assert.assertEquals(8080L, num(gwObj, "port"), "gateway port");
        Assert.assertEquals("http://127.0.0.1:8080", str(gwObj, "adminUrl"), "gateway adminUrl");
        Assert.assertEquals("MANAGED", str(gwObj, "owner"), "gateway owner");
        Assert.assertEquals("procomp", str(gwObj, "provenance"), "gateway provenance procomp");
        Assert.assertEquals("HEALTHY", str(gwObj, "health"), "gateway health");
        Assert.assertEquals(8080L, num(gwObj, "pid"), "gateway pid");
        Assert.assertEquals(125L, num(gwObj, "uptimeSeconds"), "gateway uptimeSeconds");
        Assert.assertEquals("DRIFTED", str(gwObj, "syncStatus"), "gateway syncStatus");
        Assert.assertEquals(1, arr(gwObj, "drifted").size(), "drifted count");
        Assert.assertEquals(1, arr(gwObj, "staleMappings").size(), "staleMappings count");

        List<JsonValue> services = arr(obj, "services");
        Assert.assertEquals(1, services.size(), "services count");
        JsonValue.JsonObject svcObj = (JsonValue.JsonObject) services.get(0);
        Assert.assertEquals("orders", str(svcObj, "service"), "service name");
        Assert.assertEquals("LOCAL", str(svcObj, "route"), "service route");
        Assert.assertEquals("UNMANAGED", str(svcObj, "owner"), "service owner");
        Assert.assertEquals("IDE", str(svcObj, "provenance"), "service provenance");
        Assert.assertEquals("HEALTHY", str(svcObj, "health"), "service health");
        Assert.assertEquals(9001L, num(svcObj, "port"), "service port");
        Assert.assertEquals(4200L, num(svcObj, "pid"), "service pid");
        Assert.assertEquals("2026-11-20T11:45:00Z", str(svcObj, "startedAt"), "service startedAt");
        Assert.assertEquals(900L, num(svcObj, "uptimeSeconds"), "service uptimeSeconds");
        Assert.assertEquals(true, bool(svcObj, "stale"), "service stale");
        Assert.assertEquals("http://127.0.0.1:9001/health", str(svcObj, "healthEndpoint"), "service healthEndpoint");
        Assert.assertEquals("HTTP 200", str(svcObj, "healthNote"), "service healthNote");

        EstateStatus unconfigured = new EstateStatus("nogw", GatewaySummary.notConfigured(), List.of());
        JsonValue.JsonObject unconfGw = obj(DomainJson.estateStatus(unconfigured), "gateway");
        Assert.assertEquals(false, bool(unconfGw, "configured"), "unconfigured gateway");
        Assert.assertTrue(unconfGw.get("type").orElseThrow() instanceof JsonValue.JsonNull, "unconfigured type null");
        Assert.assertTrue(unconfGw.get("syncStatus").orElseThrow() instanceof JsonValue.JsonNull, "unconfigured syncStatus null");
        Assert.assertEquals(0, arr(unconfGw, "drifted").size(), "unconfigured drifted empty");
        Assert.assertEquals(0, arr(unconfGw, "staleMappings").size(), "unconfigured staleMappings empty");
    }

    public static void testDomainJsonSerializesRoutingTableProfilesInspectionAndGatewayStatus() {
        Project project = sampleProject();
        RoutingTable table = new RoutingTable(Map.of("orders", Route.LOCAL, "billing", Route.REMOTE));

        JsonValue.JsonObject routesObj = DomainJson.routingTable(project, table);
        Assert.assertEquals("acme", str(routesObj, "project"), "routes project");
        List<JsonValue> routeItems = arr(routesObj, "routes");
        Assert.assertEquals(2, routeItems.size(), "routes count");

        JsonValue.JsonObject profilesObj = DomainJson.profiles(project, List.copyOf(project.profiles().values()));
        Assert.assertEquals("acme", str(profilesObj, "project"), "profiles project");
        List<JsonValue> profileItems = arr(profilesObj, "profiles");
        Assert.assertEquals(1, profileItems.size(), "profiles count");
        JsonValue.JsonObject p0 = (JsonValue.JsonObject) profileItems.get(0);
        Assert.assertEquals("checkout", str(p0, "name"), "profile name");
        Assert.assertEquals(1, arr(p0, "local").size(), "profile local services count");

        InspectionReport report = new InspectionReport(
            "orders",
            Optional.of(9001),
            Optional.of("LOCAL"),
            Optional.of("LOCAL"),
            Ownership.MANAGED,
            Optional.empty(),
            HealthState.HEALTHY,
            Optional.of(5001L),
            Optional.of(Duration.ofSeconds(60)),
            Optional.of("java -jar orders.jar"),
            List.of("-jar", "orders.jar"),
            List.of(new ParentProcess(100L, Optional.of("bash"), Optional.of("/bin/bash"))),
            Optional.of(1048576L),
            Optional.of("http://127.0.0.1:9001/health"),
            "HTTP 200",
            Path.of("/state/orders.out.log"),
            Path.of("/state/orders.err.log"),
            false
        );
        JsonValue.JsonObject inspObj = DomainJson.inspectionReport(project, report);
        Assert.assertEquals("acme", str(inspObj, "project"), "inspect project");
        Assert.assertEquals("orders", str(inspObj, "service"), "inspect service");
        Assert.assertEquals(9001L, num(inspObj, "port"), "inspect port");
        Assert.assertEquals("LOCAL", str(inspObj, "desiredRoute"), "inspect desiredRoute");
        Assert.assertEquals("LOCAL", str(inspObj, "liveGatewayRoute"), "inspect liveGatewayRoute");
        Assert.assertEquals("MANAGED", str(inspObj, "owner"), "inspect owner");
        Assert.assertEquals(1048576L, num(inspObj, "rssBytes"), "inspect rssBytes");
        Assert.assertEquals(2, arr(inspObj, "arguments").size(), "inspect arguments");
        Assert.assertEquals(1, arr(inspObj, "parentChain").size(), "inspect parentChain");
        Assert.assertEquals(false, bool(inspObj, "stale"), "inspect stale");

        GatewayStatusReport gwReport = new GatewayStatusReport(
            "acme",
            sampleGateway(),
            new DiscoveryResult(
                "gateway", ProcessState.Stopped.INSTANCE, Optional.empty(), "stopped"
            ),
            Optional.empty(),
            new GatewayDriftReport(GatewaySyncStatus.OFFLINE, List.of(), List.of(), Map.of())
        );
        JsonValue.JsonObject gwStatusObj = DomainJson.gatewayStatus(gwReport);
        Assert.assertEquals("acme", str(gwStatusObj, "project"), "gw status project");
        Assert.assertEquals("OFFLINE", str(gwStatusObj, "syncStatus"), "gw status syncStatus");
        Assert.assertEquals("STOPPED", str(gwStatusObj, "health"), "gw status health");
    }

    public static void testDomainJsonSerializesMutationsErrorsAndProgressEvents() {
        ObservedProcess ordersProc = new ObservedProcess(
            3001L, true, Optional.of(Instant.parse("2026-11-20T12:00:00Z")),
            Optional.of("java -jar orders.jar"), List.of(), List.of(), Optional.empty(), Optional.of("java")
        );
        DiscoveryResult disc = new DiscoveryResult(
            "orders",
            new ProcessState.Managed.Healthy(3001L, Instant.parse("2026-11-20T12:00:00Z"), false, ordersProc),
            Optional.of("http://127.0.0.1:9001/health"),
            "HTTP 200"
        );
        StartResult startRes = new StartResult(
            true,
            false,
            List.of(new StartServiceOutcome("orders", StartDisposition.SPAWNED, disc, Route.LOCAL))
        );
        JsonValue.JsonObject startObj = DomainJson.startResult(startRes);
        Assert.assertEquals(true, bool(startObj, "explicitTargets"), "start explicitTargets");
        Assert.assertEquals(false, bool(startObj, "detached"), "start detached");
        JsonValue.JsonObject startItem = (JsonValue.JsonObject) arr(startObj, "outcomes").get(0);
        Assert.assertEquals("orders", str(startItem, "service"), "start outcome service");
        Assert.assertEquals("SPAWNED", str(startItem, "disposition"), "start outcome disposition");
        Assert.assertEquals("LOCAL", str(startItem, "desiredRoute"), "start outcome desiredRoute");
        Assert.assertEquals(3001L, num(startItem, "pid"), "start outcome pid");

        StopResult stopRes = new StopResult(
            List.of(new StopServiceOutcome("orders", StopDisposition.STOPPED_MANAGED, Optional.of(3001L), Optional.empty())),
            GatewayStopOutcome.LEFT_RUNNING,
            Optional.of(8080L)
        );
        JsonValue.JsonObject stopObj = DomainJson.stopResult(stopRes);
        Assert.assertEquals("LEFT_RUNNING", str(stopObj, "gatewayOutcome"), "stop gatewayOutcome");
        Assert.assertEquals(8080L, num(stopObj, "gatewayPid"), "stop gatewayPid");
        JsonValue.JsonObject stopItem = (JsonValue.JsonObject) arr(stopObj, "outcomes").get(0);
        Assert.assertEquals("STOPPED_MANAGED", str(stopItem, "disposition"), "stop outcome disposition");

        GatewayStartResult gwStart = new GatewayStartResult(
            GatewayStartDisposition.SPAWNED, disc, sampleGateway(), true
        );
        JsonValue.JsonObject gwStartObj = DomainJson.gatewayStartResult(gwStart);
        Assert.assertEquals("SPAWNED", str(gwStartObj, "disposition"), "gw start disposition");
        Assert.assertEquals(true, bool(gwStartObj, "detached"), "gw start detached");
        Assert.assertEquals(8080L, num(gwStartObj, "port"), "gw start port");

        GatewayStopResult gwStop = new GatewayStopResult(GatewayStopDisposition.STOPPED_MANAGED, Optional.of(8080L));
        JsonValue.JsonObject gwStopObj = DomainJson.gatewayStopResult(gwStop);
        Assert.assertEquals("STOPPED_MANAGED", str(gwStopObj, "disposition"), "gw stop disposition");
        Assert.assertEquals(8080L, num(gwStopObj, "pid"), "gw stop pid");

        GatewayReconcileResult gwRec = new GatewayReconcileResult(
            "acme",
            sampleGateway(),
            2,
            1,
            false,
            new GatewayDriftReport(GatewaySyncStatus.IN_SYNC, List.of(), List.of(), Map.of())
        );
        JsonValue.JsonObject gwRecObj = DomainJson.gatewayReconcileResult(gwRec);
        Assert.assertEquals("acme", str(gwRecObj, "project"), "reconcile project");
        Assert.assertEquals(2L, num(gwRecObj, "appliedMappings"), "reconcile appliedMappings");
        Assert.assertEquals(1L, num(gwRecObj, "deletedPriorMappings"), "reconcile deletedPriorMappings");
        Assert.assertEquals(false, bool(gwRecObj, "usedSingleMappingFallback"), "reconcile fallback");
        Assert.assertEquals("IN_SYNC", str(gwRecObj, "syncStatus"), "reconcile syncStatus");

        DomainError err = DomainError.of("PORT_CONFLICT", "Port 9001 in use", Map.of("pid", "9999"));
        JsonValue.JsonObject errObj = DomainJson.domainError(err);
        Assert.assertEquals("PORT_CONFLICT", str(errObj, "code"), "error code");
        Assert.assertEquals("Port 9001 in use", str(errObj, "message"), "error message");
        Assert.assertEquals("9999", str(obj(errObj, "details"), "pid"), "error details pid");

        JsonValue.JsonObject statusEvt = DomainJson.progressEvent(new ProgressEvent.StatusMessage("orders", "Starting..."));
        Assert.assertEquals("status", str(statusEvt, "type"), "status event type");
        Assert.assertEquals("orders", str(statusEvt, "target"), "status event target");
        Assert.assertEquals("Starting...", str(statusEvt, "message"), "status event message");

        JsonValue.JsonObject logEvt = DomainJson.progressEvent(new ProgressEvent.LogLine("orders", true, "ERROR boom\n"));
        Assert.assertEquals("log", str(logEvt, "type"), "log event type");
        Assert.assertEquals("orders", str(logEvt, "service"), "log event service");
        Assert.assertEquals(true, bool(logEvt, "stderr"), "log event stderr");
        Assert.assertEquals("ERROR boom\n", str(logEvt, "line"), "log event line");
    }

    public static void testCliCommandFormatterFormatsAllV1CommandsAndQuotesUnsafeArgs() {
        CliCommandFormatter plain = CliCommandFormatter.plain();
        Assert.assertEquals("procomp status", plain.status(false), "status");
        Assert.assertEquals("procomp status --json", plain.status(true), "status --json");
        Assert.assertEquals("procomp route", plain.showRoutes(false), "route");
        Assert.assertEquals("procomp route --json", plain.showRoutes(true), "route --json");
        Assert.assertEquals(
            "procomp route orders billing local",
            plain.updateRoutes(List.of("orders", "billing"), Route.LOCAL),
            "route update local"
        );
        Assert.assertEquals(
            "procomp route orders remote",
            plain.updateRoutes(List.of("orders"), Route.REMOTE),
            "route update remote"
        );
        Assert.assertEquals("procomp profile list", plain.listProfiles(false), "profile list");
        Assert.assertEquals("procomp profile list --json", plain.listProfiles(true), "profile list --json");
        Assert.assertEquals("procomp profile use checkout", plain.useProfile("checkout"), "profile use");
        Assert.assertEquals(
            "procomp start",
            plain.start(List.of(), false, ProcessShell.StartRequest.DEFAULT_TIMEOUT),
            "start default"
        );
        Assert.assertEquals(
            "procomp start orders -d --timeout 60",
            plain.start(List.of("orders"), true, Duration.ofSeconds(60)),
            "start explicit with flags"
        );
        Assert.assertEquals("procomp stop", plain.stop(List.of(), false, false), "stop default");
        Assert.assertEquals(
            "procomp stop orders --kill-conflict",
            plain.stop(List.of("orders"), false, true),
            "stop kill-conflict"
        );
        Assert.assertEquals("procomp stop --all", plain.stop(List.of(), true, false), "stop --all");
        Assert.assertEquals("procomp inspect orders", plain.inspect("orders", false), "inspect");
        Assert.assertEquals("procomp inspect orders --json", plain.inspect("orders", true), "inspect --json");
        Assert.assertEquals(
            "procomp logs orders",
            plain.logs("orders", false, false, LogsShell.LogsRequest.DEFAULT_LINES, false),
            "logs default"
        );
        Assert.assertEquals(
            "procomp logs orders -n 50 -f --err",
            plain.logs("orders", true, false, 50, true),
            "logs follow err custom lines"
        );
        Assert.assertEquals(
            "procomp logs orders --err --path",
            plain.logs("orders", true, true, 100, false),
            "logs path"
        );
        Assert.assertEquals(
            "procomp gateway start",
            plain.gatewayStart(false, GatewayShell.GatewayStartRequest.DEFAULT_TIMEOUT),
            "gateway start default"
        );
        Assert.assertEquals(
            "procomp gateway start -d --timeout 45",
            plain.gatewayStart(true, Duration.ofSeconds(45)),
            "gateway start flags"
        );
        Assert.assertEquals("procomp gateway stop", plain.gatewayStop(), "gateway stop");
        Assert.assertEquals("procomp gateway status", plain.gatewayStatus(false), "gateway status");
        Assert.assertEquals("procomp gateway status --json", plain.gatewayStatus(true), "gateway status --json");
        Assert.assertEquals("procomp gateway reconcile", plain.gatewayReconcile(), "gateway reconcile");

        // Config override quoting
        Path simpleCfg = Path.of("acme/procomp.json");
        Assert.assertEquals(
            "procomp --config " + simpleCfg + " status",
            CliCommandFormatter.withConfig(simpleCfg).status(false),
            "simple config path unquoted"
        );

        Path spacedCfg = Path.of("my project/procomp.json");
        Assert.assertEquals(
            "procomp --config \"" + spacedCfg + "\" gateway reconcile",
            CliCommandFormatter.withConfig(spacedCfg).gatewayReconcile(),
            "spaced config path quoted"
        );

        Assert.assertEquals(
            "procomp profile use \"my \\\"quoted\\\" profile\"",
            plain.useProfile("my \"quoted\" profile"),
            "quotes escaped"
        );
    }

    private static GatewayDefinition sampleGateway() {
        return new GatewayDefinition(
            "wiremock",
            8080,
            Optional.of("http://127.0.0.1:8080/__admin"),
            List.of("java", "-jar", "wiremock.jar"),
            Optional.of(Path.of("C:/work/acme/wiremock")),
            SecretEnv.EMPTY
        );
    }

    private static Project sampleProject() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        ServiceDefinition orders = new ServiceDefinition(
            "orders",
            Optional.of(9001),
            "java",
            Optional.empty(),
            Optional.empty(),
            root.resolve("orders"),
            Optional.of("/health"),
            "/orders/.*",
            Optional.of("https://dev.example.com/orders"),
            Optional.empty(),
            Optional.empty(),
            List.of("java", "-jar", "orders.jar"),
            SecretEnv.EMPTY,
            List.of()
        );
        ServiceDefinition billing = new ServiceDefinition(
            "billing",
            Optional.of(9002),
            "java",
            Optional.empty(),
            Optional.empty(),
            root.resolve("billing"),
            Optional.of("/health"),
            "/billing/.*",
            Optional.of("https://dev.example.com/billing"),
            Optional.empty(),
            Optional.empty(),
            List.of("java", "-jar", "billing.jar"),
            SecretEnv.EMPTY,
            List.of()
        );
        Map<String, ServiceDefinition> services = new LinkedHashMap<>();
        services.put("orders", orders);
        services.put("billing", billing);
        Map<String, ProfileDefinition> profiles = new LinkedHashMap<>();
        profiles.put("checkout", new ProfileDefinition("checkout", List.of("orders")));
        return new Project(
            "acme",
            root.resolve("procomp.json"),
            root,
            Optional.of(root),
            Map.of(),
            Map.of(),
            Optional.of(sampleGateway()),
            services,
            profiles,
            List.of()
        );
    }

    private static String str(JsonValue.JsonObject obj, String key) {
        return ((JsonValue.JsonString) obj.get(key).orElseThrow()).value();
    }

    private static boolean bool(JsonValue.JsonObject obj, String key) {
        return ((JsonValue.JsonBoolean) obj.get(key).orElseThrow()).value();
    }

    private static long num(JsonValue.JsonObject obj, String key) {
        return ((JsonValue.JsonNumber) obj.get(key).orElseThrow()).asLong().orElseThrow();
    }

    private static JsonValue.JsonObject obj(JsonValue.JsonObject obj, String key) {
        return (JsonValue.JsonObject) obj.get(key).orElseThrow();
    }

    private static List<JsonValue> arr(JsonValue.JsonObject obj, String key) {
        return ((JsonValue.JsonArray) obj.get(key).orElseThrow()).elements();
    }
}
