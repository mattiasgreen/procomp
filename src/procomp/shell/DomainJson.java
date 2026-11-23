package procomp.shell;

import procomp.core.build.BuildRunRecord;
import procomp.core.build.BuildStatus;
import procomp.core.build.ObservedBuildRun;
import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.gateway.GatewayDriftReport;
import procomp.core.gateway.RouteDrift;
import procomp.core.gateway.StaleManagedMapping;
import procomp.core.process.InspectionReport;
import procomp.core.process.ParentProcess;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.Project;
import procomp.core.project.ServiceDefinition;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.BuildShell.BuildResult;
import procomp.shell.BuildShell.BuildServiceOutcome;
import procomp.shell.BuildShell.CancelResult;
import procomp.shell.GatewayShell.GatewayReconcileResult;
import procomp.shell.GatewayShell.GatewayStartResult;
import procomp.shell.GatewayShell.GatewayStatusReport;
import procomp.shell.GatewayShell.GatewayStopResult;
import procomp.shell.ProcessShell.StartResult;
import procomp.shell.ProcessShell.StartServiceOutcome;
import procomp.shell.ProcessShell.StopResult;
import procomp.shell.ProcessShell.StopServiceOutcome;
import procomp.shell.StatusShell.EstateStatus;
import procomp.shell.StatusShell.GatewaySummary;
import procomp.shell.StatusShell.ServiceStatusRow;
import procomp.shell.ports.ProgressObserver.DiagnosticCategory;
import procomp.shell.ports.ProgressObserver.ProgressEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure, zero-I/O canonical JSON serializer for domain and shell reports, mutation results,
 * {@link DomainError}, and {@link ProgressEvent}. Shared by CLI {@code --json} and Web HTTP/SSE APIs.
 */
public final class DomainJson {

    private DomainJson() {}

    public static JsonValue.JsonObject estateStatus(EstateStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(status.project()));
        root.put("gateway", gatewaySummary(status.gateway()));

        List<JsonValue> serviceNodes = new ArrayList<>(status.services().size());
        for (ServiceStatusRow row : status.services()) {
            Map<String, JsonValue> sObj = new LinkedHashMap<>();
            sObj.put("service", Json.str(row.service()));
            sObj.put("route", Json.str(row.route().name()));
            sObj.put("owner", Json.str(row.ownership().name()));
            sObj.put("provenance", strOrNull(row.provenance()));
            sObj.put("health", Json.str(row.health().name()));
            sObj.put("port", numOrNull(row.port()));
            sObj.put("pid", numOrNull(row.pid()));
            sObj.put("startedAt", strOrNull(row.startedAt().map(Instant::toString)));
            sObj.put("uptimeSeconds", uptimeOrNull(row.uptime()));
            sObj.put("stale", Json.bool(row.stale()));
            sObj.put("healthEndpoint", strOrNull(row.healthEndpoint()));
            sObj.put("healthNote", strOrNull(row.healthNote()));
            sObj.put("buildRun", observedBuildRun(row.build()));
            serviceNodes.add(Json.obj(sObj));
        }
        root.put("services", Json.arr(serviceNodes));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject gatewaySummary(GatewaySummary summary) {
        Objects.requireNonNull(summary, "summary must not be null");
        Map<String, JsonValue> gw = new LinkedHashMap<>();
        if (summary.report().isEmpty()) {
            gw.put("configured", Json.bool(false));
            gw.put("type", Json.nil());
            gw.put("port", Json.nil());
            gw.put("adminUrl", Json.nil());
            gw.put("owner", Json.nil());
            gw.put("provenance", Json.nil());
            gw.put("health", Json.nil());
            gw.put("pid", Json.nil());
            gw.put("uptimeSeconds", Json.nil());
            gw.put("healthEndpoint", Json.nil());
            gw.put("healthResponse", Json.nil());
            gw.put("syncStatus", Json.nil());
            gw.put("drifted", Json.arr(List.of()));
            gw.put("staleMappings", Json.arr(List.of()));
            return Json.obj(gw);
        }
        gw.put("configured", Json.bool(true));
        appendGatewayStatusFields(gw, summary.report().get());
        return Json.obj(gw);
    }

    public static JsonValue.JsonObject gatewayStatus(GatewayStatusReport report) {
        Objects.requireNonNull(report, "report must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(report.projectName()));
        appendGatewayStatusFields(root, report);
        return Json.obj(root);
    }

    public static JsonValue.JsonObject routingTable(Project project, RoutingTable table) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(table, "table must not be null");
        List<JsonValue> items = new ArrayList<>(table.size());
        for (Map.Entry<String, Route> entry : table.routes().entrySet()) {
            String svcName = entry.getKey();
            Route route = entry.getValue();
            ServiceDefinition svc = project.service(svcName).orElseThrow();

            Map<String, JsonValue> obj = new LinkedHashMap<>();
            obj.put("service", Json.str(svcName));
            obj.put("route", Json.str(route.name()));
            obj.put("proxyPath", Json.str(svc.proxyPath()));
            obj.put("port", numOrNull(svc.port()));
            obj.put("remoteUrl", strOrNull(svc.remoteUrl()));
            items.add(Json.obj(obj));
        }
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(project.name()));
        root.put("routes", Json.arr(items));
        return Json.obj(root);
    }

    public static JsonValue.JsonArray profilesArray(List<ProfileDefinition> profiles) {
        Objects.requireNonNull(profiles, "profiles must not be null");
        List<JsonValue> items = new ArrayList<>(profiles.size());
        for (ProfileDefinition profile : profiles) {
            List<JsonValue> localNodes = new ArrayList<>(profile.localServices().size());
            for (String svc : profile.localServices()) {
                localNodes.add(Json.str(svc));
            }
            Map<String, JsonValue> obj = new LinkedHashMap<>();
            obj.put("name", Json.str(profile.name()));
            obj.put("local", Json.arr(localNodes));
            items.add(Json.obj(obj));
        }
        return Json.arr(items);
    }

    public static JsonValue.JsonObject profiles(Project project, List<ProfileDefinition> profiles) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(profiles, "profiles must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(project.name()));
        root.put("profiles", profilesArray(profiles));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject observedBuildRun(ObservedBuildRun build) {
        Objects.requireNonNull(build, "build must not be null");
        Map<String, JsonValue> obj = new LinkedHashMap<>();
        obj.put("displayState", Json.str(build.displayState().name()));
        obj.put("status", strOrNull(build.status().map(BuildStatus::name)));
        if (build.record().isEmpty()) {
            obj.put("pid", Json.nil());
            obj.put("startedAt", Json.nil());
            obj.put("finishedAt", Json.nil());
            obj.put("exitCode", Json.nil());
            obj.put("reason", Json.nil());
            obj.put("stdoutLog", Json.nil());
            obj.put("stderrLog", Json.nil());
            return Json.obj(obj);
        }
        BuildRunRecord rec = build.record().get();
        obj.put("pid", Json.num(rec.pid()));
        obj.put("startedAt", Json.str(rec.startedAt().toString()));
        switch (rec) {
            case BuildRunRecord.Running _ -> {
                obj.put("finishedAt", Json.nil());
                obj.put("exitCode", Json.nil());
                obj.put("reason", Json.nil());
            }
            case BuildRunRecord.Succeeded s -> {
                obj.put("finishedAt", Json.str(s.finishedAt().toString()));
                obj.put("exitCode", Json.num(0));
                obj.put("reason", Json.nil());
            }
            case BuildRunRecord.Failed f -> {
                obj.put("finishedAt", Json.str(f.finishedAt().toString()));
                obj.put("exitCode", numOrNull(f.exitCode()));
                obj.put("reason", Json.str(f.reason()));
            }
        }
        obj.put("stdoutLog", Json.str(rec.stdoutLog().toString()));
        obj.put("stderrLog", Json.str(rec.stderrLog().toString()));
        return Json.obj(obj);
    }

    public static JsonValue.JsonObject buildResult(Project project, BuildResult result) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(project.name()));
        List<JsonValue> outcomes = new ArrayList<>(result.outcomes().size());
        for (BuildServiceOutcome item : result.outcomes()) {
            Map<String, JsonValue> oObj = new LinkedHashMap<>();
            oObj.put("service", Json.str(item.service()));
            oObj.put("builder", Json.str(item.builder()));
            oObj.put("status", Json.str(BuildStatus.SUCCEEDED.name()));
            oObj.put("pid", Json.num(item.record().pid()));
            oObj.put("startedAt", Json.str(item.record().startedAt().toString()));
            oObj.put("finishedAt", Json.str(item.record().finishedAt().toString()));
            oObj.put("durationMillis", Json.num(Math.max(0L, item.duration().toMillis())));
            oObj.put("stdoutLog", Json.str(item.record().stdoutLog().toString()));
            oObj.put("stderrLog", Json.str(item.record().stderrLog().toString()));
            outcomes.add(Json.obj(oObj));
        }
        root.put("outcomes", Json.arr(outcomes));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject cancelResult(Project project, CancelResult result) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(project.name()));
        root.put("service", Json.str(result.service()));
        root.put("disposition", Json.str(result.disposition().name()));
        if (result.record().isPresent()) {
            BuildRunRecord.Failed f = result.record().get();
            root.put("pid", Json.num(f.pid()));
            root.put("reason", Json.str(f.reason()));
            root.put("stdoutLog", Json.str(f.stdoutLog().toString()));
            root.put("stderrLog", Json.str(f.stderrLog().toString()));
        } else {
            root.put("pid", Json.nil());
            root.put("reason", Json.nil());
            root.put("stdoutLog", Json.nil());
            root.put("stderrLog", Json.nil());
        }
        return Json.obj(root);
    }

    public static JsonValue.JsonObject inspectionReport(Project project, InspectionReport report) {
        return inspectionReport(project, report, Optional.empty());
    }

    public static JsonValue.JsonObject inspectionReport(
        Project project,
        InspectionReport report,
        Optional<ObservedBuildRun> buildRun
    ) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(buildRun, "buildRun must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(project.name()));
        root.put("service", Json.str(report.service()));
        root.put("port", numOrNull(report.port()));
        root.put("desiredRoute", strOrNull(report.desiredRoute()));
        root.put("liveGatewayRoute", strOrNull(report.liveGatewayRoute()));
        root.put("owner", Json.str(report.ownership().name()));
        root.put("provenance", strOrNull(report.provenance()));
        root.put("health", Json.str(report.health().name()));
        root.put("pid", numOrNull(report.pid()));
        root.put("uptimeSeconds", uptimeOrNull(report.uptime()));
        root.put("commandLine", strOrNull(report.commandLine()));

        List<JsonValue> argNodes = new ArrayList<>(report.arguments().size());
        for (String arg : report.arguments()) {
            argNodes.add(Json.str(arg));
        }
        root.put("arguments", Json.arr(argNodes));

        List<JsonValue> parentNodes = new ArrayList<>(report.parentChain().size());
        for (ParentProcess parent : report.parentChain()) {
            Map<String, JsonValue> pObj = new LinkedHashMap<>();
            pObj.put("pid", Json.num(parent.pid()));
            pObj.put("commandLine", strOrNull(parent.commandLine()));
            pObj.put("executable", strOrNull(parent.executable()));
            parentNodes.add(Json.obj(pObj));
        }
        root.put("parentChain", Json.arr(parentNodes));

        root.put("rssBytes", numOrNull(report.rssBytes()));
        root.put("healthEndpoint", strOrNull(report.healthEndpoint()));
        root.put("healthResponse", Json.str(report.healthResponse()));
        root.put("outLogPath", Json.str(report.outLogPath().toString()));
        root.put("errLogPath", Json.str(report.errLogPath().toString()));
        root.put("stale", Json.bool(report.stale()));
        buildRun.ifPresent(b -> root.put("buildRun", observedBuildRun(b)));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject startResult(StartResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("explicitTargets", Json.bool(result.explicitTargets()));
        root.put("detached", Json.bool(result.detached()));
        List<JsonValue> outcomes = new ArrayList<>(result.outcomes().size());
        for (StartServiceOutcome item : result.outcomes()) {
            Map<String, JsonValue> oObj = new LinkedHashMap<>();
            oObj.put("service", Json.str(item.service()));
            oObj.put("disposition", Json.str(item.disposition().name()));
            oObj.put("desiredRoute", Json.str(item.desiredRoute().name()));
            oObj.put("owner", Json.str(item.discovery().ownership().name()));
            oObj.put("provenance", strOrNull(item.discovery().provenance()));
            oObj.put("health", Json.str(item.discovery().health().name()));
            oObj.put("pid", numOrNull(item.discovery().pid()));
            oObj.put("healthEndpoint", strOrNull(item.discovery().healthEndpoint()));
            oObj.put("healthResponse", Json.str(item.discovery().healthResponse()));
            outcomes.add(Json.obj(oObj));
        }
        root.put("outcomes", Json.arr(outcomes));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject stopResult(StopResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        List<JsonValue> outcomes = new ArrayList<>(result.outcomes().size());
        for (StopServiceOutcome item : result.outcomes()) {
            Map<String, JsonValue> oObj = new LinkedHashMap<>();
            oObj.put("service", Json.str(item.service()));
            oObj.put("disposition", Json.str(item.disposition().name()));
            oObj.put("pid", numOrNull(item.pid()));
            oObj.put("provenance", strOrNull(item.provenance()));
            outcomes.add(Json.obj(oObj));
        }
        root.put("outcomes", Json.arr(outcomes));
        root.put("gatewayOutcome", Json.str(result.gatewayOutcome().name()));
        root.put("gatewayPid", numOrNull(result.gatewayPid()));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject gatewayStartResult(GatewayStartResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("disposition", Json.str(result.disposition().name()));
        root.put("detached", Json.bool(result.detached()));
        root.put("type", Json.str(result.gateway().type()));
        root.put("port", Json.num(result.gateway().port()));
        root.put("adminUrl", Json.str(result.gateway().adminUrl()));
        root.put("owner", Json.str(result.discovery().ownership().name()));
        root.put("provenance", strOrNull(result.discovery().provenance()));
        root.put("health", Json.str(result.discovery().health().name()));
        root.put("pid", numOrNull(result.discovery().pid()));
        root.put("healthEndpoint", strOrNull(result.discovery().healthEndpoint()));
        root.put("healthResponse", Json.str(result.discovery().healthResponse()));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject gatewayStopResult(GatewayStopResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("disposition", Json.str(result.disposition().name()));
        root.put("pid", numOrNull(result.pid()));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject gatewayReconcileResult(GatewayReconcileResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(result.projectName()));
        root.put("type", Json.str(result.gateway().type()));
        root.put("port", Json.num(result.gateway().port()));
        root.put("adminUrl", Json.str(result.gateway().adminUrl()));
        root.put("appliedMappings", Json.num(result.appliedMappings()));
        root.put("deletedPriorMappings", Json.num(result.deletedPriorMappings()));
        root.put("usedSingleMappingFallback", Json.bool(result.usedSingleMappingFallback()));
        appendDriftFields(root, result.drift());
        return Json.obj(root);
    }

    public static JsonValue.JsonObject domainError(DomainError error) {
        Objects.requireNonNull(error, "error must not be null");
        Map<String, JsonValue> detailsObj = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : error.details().entrySet()) {
            detailsObj.put(entry.getKey(), Json.str(entry.getValue()));
        }
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("code", Json.str(error.code()));
        root.put("message", Json.str(error.message()));
        root.put("details", Json.obj(detailsObj));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject progressEvent(ProgressEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        switch (event) {
            case ProgressEvent.StatusMessage(String target, String message) -> {
                root.put("type", Json.str("status"));
                root.put("target", Json.str(target));
                root.put("message", Json.str(message));
            }
            case ProgressEvent.LogLine(String service, boolean stderr, String line) -> {
                root.put("type", Json.str("log"));
                root.put("service", Json.str(service));
                root.put("stderr", Json.bool(stderr));
                root.put("line", Json.str(line));
            }
            case ProgressEvent.Diagnostic(DiagnosticCategory category, String message) -> {
                root.put("type", Json.str("diagnostic"));
                root.put("category", Json.str(category.label()));
                root.put("message", Json.str(message));
            }
        }
        return Json.obj(root);
    }

    private static void appendGatewayStatusFields(Map<String, JsonValue> target, GatewayStatusReport report) {
        target.put("type", Json.str(report.gateway().type()));
        target.put("port", Json.num(report.gateway().port()));
        target.put("adminUrl", Json.str(report.gateway().adminUrl()));
        target.put("owner", Json.str(report.discovery().ownership().name()));
        target.put("provenance", strOrNull(report.discovery().provenance()));
        target.put("health", Json.str(report.discovery().health().name()));
        target.put("pid", numOrNull(report.discovery().pid()));
        target.put("uptimeSeconds", uptimeOrNull(report.uptime()));
        target.put("healthEndpoint", strOrNull(report.discovery().healthEndpoint()));
        target.put("healthResponse", Json.str(report.discovery().healthResponse()));
        appendDriftFields(target, report.drift());
    }

    private static void appendDriftFields(Map<String, JsonValue> target, GatewayDriftReport drift) {
        target.put("syncStatus", Json.str(drift.syncStatus().name()));

        List<JsonValue> driftedNodes = new ArrayList<>(drift.drifted().size());
        for (RouteDrift item : drift.drifted()) {
            Map<String, JsonValue> dObj = new LinkedHashMap<>();
            dObj.put("service", Json.str(item.service()));
            dObj.put("desired", Json.str(item.desired().name()));
            dObj.put("live", Json.str(item.live()));
            driftedNodes.add(Json.obj(dObj));
        }
        target.put("drifted", Json.arr(driftedNodes));

        List<JsonValue> staleNodes = new ArrayList<>(drift.staleMappings().size());
        for (StaleManagedMapping stale : drift.staleMappings()) {
            Map<String, JsonValue> sObj = new LinkedHashMap<>();
            sObj.put("id", strOrNull(stale.id()));
            sObj.put("service", strOrNull(stale.service()));
            sObj.put("targetUrl", strOrNull(stale.targetUrl()));
            staleNodes.add(Json.obj(sObj));
        }
        target.put("staleMappings", Json.arr(staleNodes));
    }

    private static JsonValue strOrNull(Optional<String> value) {
        return value.<JsonValue>map(Json::str).orElse(Json.nil());
    }

    private static JsonValue numOrNull(Optional<? extends Number> value) {
        return value.<JsonValue>map(n -> Json.num(n.longValue())).orElse(Json.nil());
    }

    private static JsonValue uptimeOrNull(Optional<Duration> uptime) {
        return uptime.<JsonValue>map(d -> Json.num(d.toSeconds())).orElse(Json.nil());
    }
}
