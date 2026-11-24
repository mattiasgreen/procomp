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
import procomp.core.git.BranchInfo;
import procomp.core.git.GitRunRecord;
import procomp.core.git.ObservedGitRun;
import procomp.core.git.RepositoryObservation;
import procomp.core.git.StashEntry;
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
            sObj.put("git", row.git().<JsonValue>map(DomainJson::repositoryStatusRow).orElse(Json.nil()));
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
        return inspectionReport(project, report, Optional.empty(), Optional.empty());
    }

    public static JsonValue.JsonObject inspectionReport(
        Project project,
        InspectionReport report,
        Optional<ObservedBuildRun> buildRun
    ) {
        return inspectionReport(project, report, buildRun, Optional.empty());
    }

    public static JsonValue.JsonObject inspectionReport(
        Project project,
        InspectionReport report,
        Optional<ObservedBuildRun> buildRun,
        Optional<GitShell.RepositoryStatusRow> gitRow
    ) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(buildRun, "buildRun must not be null");
        Objects.requireNonNull(gitRow, "gitRow must not be null");
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
        gitRow.ifPresent(g -> root.put("git", repositoryStatusRow(g)));
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

    public static JsonValue.JsonObject repositoryStatusRow(GitShell.RepositoryStatusRow row) {
        Objects.requireNonNull(row, "row must not be null");
        Map<String, JsonValue> obj = new LinkedHashMap<>();
        obj.put("repository", Json.str(row.repository().name()));
        obj.put("root", Json.str(row.repository().root().toString()));
        obj.put("remoteUrl", strOrNull(row.repository().remoteUrl()));
        List<JsonValue> svcNodes = new ArrayList<>(row.services().size());
        for (String svc : row.services()) {
            svcNodes.add(Json.str(svc));
        }
        obj.put("services", Json.arr(svcNodes));
        obj.put("compact", Json.str(row.compactDisplay()));
        obj.put("fetchAgeWarning", Json.bool(row.fetchAgeWarning()));
        switch (row.observation()) {
            case RepositoryObservation.NotCloned _ -> {
                obj.put("state", Json.str("NOT_CLONED"));
                obj.put("branch", Json.nil());
                obj.put("detachedSha", Json.nil());
                obj.put("upstream", Json.nil());
                obj.put("ahead", Json.num(0));
                obj.put("behind", Json.num(0));
                obj.put("dirty", Json.bool(false));
                obj.put("stagedCount", Json.num(0));
                obj.put("unstagedCount", Json.num(0));
                obj.put("untrackedCount", Json.num(0));
                obj.put("stashCount", Json.num(0));
                obj.put("lastFetchedAt", Json.nil());
            }
            case RepositoryObservation.NotAGitRepository nonGit -> {
                obj.put("state", Json.str("NOT_GIT"));
                obj.put("empty", Json.bool(nonGit.empty()));
                obj.put("branch", Json.nil());
                obj.put("detachedSha", Json.nil());
                obj.put("upstream", Json.nil());
                obj.put("ahead", Json.num(0));
                obj.put("behind", Json.num(0));
                obj.put("dirty", Json.bool(false));
                obj.put("stagedCount", Json.num(0));
                obj.put("unstagedCount", Json.num(0));
                obj.put("untrackedCount", Json.num(0));
                obj.put("stashCount", Json.num(0));
                obj.put("lastFetchedAt", Json.nil());
            }
            case RepositoryObservation.Cloned cloned -> {
                obj.put("state", Json.str("CLONED"));
                switch (cloned.head()) {
                    case RepositoryObservation.HeadState.Branch(String name) -> {
                        obj.put("branch", Json.str(name));
                        obj.put("detachedSha", Json.nil());
                    }
                    case RepositoryObservation.HeadState.Detached(String sha) -> {
                        obj.put("branch", Json.nil());
                        obj.put("detachedSha", Json.str(sha));
                    }
                }
                obj.put("upstream", strOrNull(cloned.upstream()));
                obj.put("ahead", Json.num(cloned.ahead()));
                obj.put("behind", Json.num(cloned.behind()));
                obj.put("dirty", Json.bool(cloned.isDirty()));
                obj.put("stagedCount", Json.num(cloned.stagedCount()));
                obj.put("unstagedCount", Json.num(cloned.unstagedCount()));
                obj.put("untrackedCount", Json.num(cloned.untrackedCount()));
                obj.put("stashCount", Json.num(cloned.stashCount()));
                obj.put("lastFetchedAt", strOrNull(cloned.lastFetchedAt().map(Instant::toString)));
            }
        }
        obj.put("latestRun", row.latestRun().<JsonValue>map(DomainJson::observedGitRun).orElse(Json.nil()));
        return Json.obj(obj);
    }

    public static JsonValue.JsonObject observedGitRun(ObservedGitRun run) {
        Objects.requireNonNull(run, "run must not be null");
        GitRunRecord rec = run.record();
        Map<String, JsonValue> obj = new LinkedHashMap<>();
        obj.put("repository", Json.str(rec.repositoryName()));
        obj.put("operation", Json.str(rec.operation().name()));
        obj.put("status", Json.str(run.effectiveStatus().name()));
        obj.put("pid", Json.num(rec.pid()));
        obj.put("startedAt", Json.str(rec.startedAt().toString()));
        obj.put("finishedAt", strOrNull(rec.finishedAt().map(Instant::toString)));
        obj.put("exitCode", numOrNull(rec.exitCode()));
        obj.put("reason", strOrNull(rec.reason()));
        obj.put("commandLine", Json.str(rec.commandLine()));
        obj.put("stdoutLog", Json.str(rec.stdoutLog().toString()));
        obj.put("stderrLog", Json.str(rec.stderrLog().toString()));
        return Json.obj(obj);
    }

    public static JsonValue.JsonObject gitStatusReport(GitShell.GitStatusReport report) {
        Objects.requireNonNull(report, "report must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(report.projectName()));
        root.put("observedAt", Json.str(report.observedAt().toString()));
        root.put("fetchWarnAfterHours", Json.num(report.fetchWarnAfterHours()));
        List<JsonValue> repos = new ArrayList<>(report.repositories().size());
        for (GitShell.RepositoryStatusRow row : report.repositories()) {
            repos.add(repositoryStatusRow(row));
        }
        root.put("repositories", Json.arr(repos));
        root.put("fetchReport", report.fetchReport().<JsonValue>map(DomainJson::gitBatchReport).orElse(Json.nil()));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject gitBranchesReport(GitShell.BranchesReport report) {
        Objects.requireNonNull(report, "report must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("repository", Json.str(report.repository().name()));
        root.put("root", Json.str(report.repository().root().toString()));
        List<JsonValue> svcs = new ArrayList<>(report.services().size());
        for (String s : report.services()) {
            svcs.add(Json.str(s));
        }
        root.put("services", Json.arr(svcs));
        boolean dirty = report.observation() instanceof RepositoryObservation.Cloned c && c.isDirty();
        root.put("dirty", Json.bool(dirty));
        if (report.observation() instanceof RepositoryObservation.Cloned c) {
            root.put("stagedCount", Json.num(c.stagedCount()));
            root.put("unstagedCount", Json.num(c.unstagedCount()));
            root.put("untrackedCount", Json.num(c.untrackedCount()));
            root.put("lastFetchedAt", strOrNull(c.lastFetchedAt().map(Instant::toString)));
        }
        List<JsonValue> list = new ArrayList<>(report.branches().size());
        for (BranchInfo b : report.branches()) {
            Map<String, JsonValue> bo = new LinkedHashMap<>();
            bo.put("name", Json.str(b.name()));
            bo.put("origin", Json.str(b.origin().name()));
            bo.put("lastCommitAt", Json.str(b.lastCommitAt().toString()));
            bo.put("shortSubject", Json.str(b.shortSubject()));
            bo.put("current", Json.bool(b.current()));
            list.add(Json.obj(bo));
        }
        root.put("branches", Json.arr(list));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject gitStashList(List<GitShell.RepositoryStashes> items) {
        List<JsonValue> repos = new ArrayList<>(items.size());
        for (GitShell.RepositoryStashes item : items) {
            Map<String, JsonValue> ro = new LinkedHashMap<>();
            ro.put("repository", Json.str(item.repository().name()));
            List<JsonValue> entries = new ArrayList<>(item.stashes().size());
            for (StashEntry e : item.stashes()) {
                Map<String, JsonValue> eo = new LinkedHashMap<>();
                eo.put("ref", Json.str(e.ref()));
                eo.put("message", Json.str(e.message()));
                entries.add(Json.obj(eo));
            }
            ro.put("stashes", Json.arr(entries));
            repos.add(Json.obj(ro));
        }
        return Json.obj(Map.of("repositories", Json.arr(repos)));
    }

    public static JsonValue.JsonObject gitBatchReport(GitShell.GitBatchReport report) {
        Objects.requireNonNull(report, "report must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("operation", Json.str(report.operation().name()));
        root.put("hasFailures", Json.bool(report.hasFailures()));
        List<JsonValue> outcomes = new ArrayList<>(report.outcomes().size());
        for (GitShell.GitRepoOutcome outcome : report.outcomes()) {
            Map<String, JsonValue> o = new LinkedHashMap<>();
            o.put("repository", Json.str(outcome.repository().name()));
            switch (outcome) {
                case GitShell.GitRepoOutcome.Succeeded(_, GitRunRecord rec) -> {
                    o.put("outcome", Json.str("SUCCEEDED"));
                    o.put("run", observedGitRun(new ObservedGitRun(rec.status(), rec)));
                }
                case GitShell.GitRepoOutcome.Skipped(_, String reason) -> {
                    o.put("outcome", Json.str("SKIPPED"));
                    o.put("reason", Json.str(reason));
                }
                case GitShell.GitRepoOutcome.Failed(_, DomainError err, Optional<GitRunRecord> rec) -> {
                    o.put("outcome", Json.str("FAILED"));
                    o.put("error", domainError(err));
                    o.put("run", rec.<JsonValue>map(r -> observedGitRun(new ObservedGitRun(r.status(), r))).orElse(Json.nil()));
                }
            }
            outcomes.add(Json.obj(o));
        }
        root.put("outcomes", Json.arr(outcomes));
        return Json.obj(root);
    }

    public static JsonValue.JsonObject gitCancelReport(GitShell.GitCancelReport report) {
        Objects.requireNonNull(report, "report must not be null");
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("hasFailures", Json.bool(report.hasFailures()));
        List<JsonValue> outcomes = new ArrayList<>(report.outcomes().size());
        for (GitShell.CancelOutcome outcome : report.outcomes()) {
            Map<String, JsonValue> o = new LinkedHashMap<>();
            o.put("repository", Json.str(outcome.repository().name()));
            switch (outcome) {
                case GitShell.CancelOutcome.Cancelled(_, GitRunRecord rec) -> {
                    o.put("outcome", Json.str("CANCELLED"));
                    o.put("run", observedGitRun(new ObservedGitRun(rec.status(), rec)));
                }
                case GitShell.CancelOutcome.NoActiveRun _ -> o.put("outcome", Json.str("NO_ACTIVE_RUN"));
                case GitShell.CancelOutcome.Failed(_, DomainError err) -> {
                    o.put("outcome", Json.str("FAILED"));
                    o.put("error", domainError(err));
                }
            }
            outcomes.add(Json.obj(o));
        }
        root.put("outcomes", Json.arr(outcomes));
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
