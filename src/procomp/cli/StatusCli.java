package procomp.cli;

import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.gateway.GatewayDriftReport;
import procomp.core.process.Ownership;
import procomp.core.project.Project;
import procomp.shell.GatewayShell.GatewayStatusReport;
import procomp.shell.StatusShell;
import procomp.shell.StatusShell.EstateStatus;
import procomp.shell.StatusShell.GatewaySummary;
import procomp.shell.StatusShell.ServiceStatusRow;
import procomp.shell.ports.Clock;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProjectFiles;
import procomp.shell.ports.StateStore;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CLI argument parser and table/JSON renderer for {@code procomp status [--json]}.
 */
public final class StatusCli {

    private static final String USAGE = "Usage: procomp status [--json]";

    public sealed interface Action permits Action.Show {
        record Show(boolean json) implements Action {}
    }

    private StatusCli() {}

    public static Optional<Result<Action, String>> parse(String command, List<String> args) {
        if (!"status".equals(command)) {
            return Optional.empty();
        }
        boolean json = false;
        for (String arg : args) {
            if ("--json".equals(arg)) {
                json = true;
            } else if (arg.startsWith("-")) {
                return Optional.of(Result.err("Unknown option '" + arg + "' for 'status'. " + USAGE));
            } else {
                return Optional.of(Result.err("Unexpected argument '" + arg + "' for 'status'. " + USAGE));
            }
        }
        return Optional.of(Result.ok(new Action.Show(json)));
    }

    public static int execute(
        Action action,
        Project project,
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        ProjectFiles projectFiles,
        Clock clock,
        ProgressObserver observer,
        PrintWriter out,
        PrintWriter err
    ) {
        if (processRunner == null || portScanner == null || httpClient == null || projectFiles == null) {
            err.println("Error: Status ports are not configured.");
            return 1;
        }
        StatusShell shell = new StatusShell(
            stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer
        );
        return CliFormat.handleResult(shell.status(project), err, status -> {
            if (action instanceof Action.Show(boolean json) && json) {
                renderJson(status, out);
            } else {
                renderText(status, out);
            }
        });
    }

    private static void renderText(EstateStatus status, PrintWriter out) {
        out.println("Project: " + status.project());
        if (status.services().isEmpty()) {
            out.println("No services configured in project '" + status.project() + "'.");
        } else {
            List<List<String>> rows = new ArrayList<>(status.services().size());
            for (ServiceStatusRow row : status.services()) {
                rows.add(List.of(
                    row.service(),
                    row.route().name(),
                    formatOwner(row.ownership(), row.provenance()),
                    row.health().name(),
                    row.port().map(Object::toString).orElse("-"),
                    row.pid().map(Object::toString).orElse("-"),
                    row.uptime().map(CliFormat::formatDuration).orElse("-"),
                    row.stale() ? "YES" : "no"
                ));
            }
            CliFormat.renderTable(
                out,
                List.of("SERVICE", "ROUTE", "OWNER", "HEALTH", "PORT", "PID", "UPTIME", "STALE"),
                rows
            );
        }
        out.println(formatGatewayLine(status.gateway()));
    }

    private static void renderJson(EstateStatus status, PrintWriter out) {
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(status.project()));
        root.put("gateway", buildGatewayJson(status.gateway()));

        List<JsonValue> serviceNodes = new ArrayList<>(status.services().size());
        for (ServiceStatusRow row : status.services()) {
            Map<String, JsonValue> sObj = new LinkedHashMap<>();
            sObj.put("service", Json.str(row.service()));
            sObj.put("route", Json.str(row.route().name()));
            sObj.put("owner", Json.str(row.ownership().name()));
            sObj.put("provenance", CliFormat.jsonStrOrNull(row.provenance()));
            sObj.put("health", Json.str(row.health().name()));
            sObj.put("port", CliFormat.jsonNumOrNull(row.port()));
            sObj.put("pid", CliFormat.jsonNumOrNull(row.pid()));
            sObj.put("startedAt", CliFormat.jsonStrOrNull(row.startedAt().map(Instant::toString)));
            sObj.put("uptimeSeconds", CliFormat.jsonUptimeOrNull(row.uptime()));
            sObj.put("stale", Json.bool(row.stale()));
            sObj.put("healthEndpoint", CliFormat.jsonStrOrNull(row.healthEndpoint()));
            sObj.put("healthNote", CliFormat.jsonStrOrNull(row.healthNote()));
            serviceNodes.add(Json.obj(sObj));
        }
        root.put("services", Json.arr(serviceNodes));

        out.println(Json.stringify(Json.obj(root)));
    }

    private static JsonValue.JsonObject buildGatewayJson(GatewaySummary summary) {
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
        GatewayCli.appendGatewayStatusFields(gw, summary.report().get());
        return Json.obj(gw);
    }

    private static String formatGatewayLine(GatewaySummary summary) {
        if (summary.report().isEmpty()) {
            return "Gateway: not configured";
        }
        GatewayStatusReport report = summary.report().get();
        String owner = formatOwner(report.discovery().ownership(), report.discovery().provenance());
        String health = report.discovery().health().name();
        String pidPart = report.discovery().pid().map(p -> ", PID " + p).orElse("");
        GatewayDriftReport drift = report.drift();
        String syncLabel = switch (drift.syncStatus()) {
            case IN_SYNC -> "IN SYNC";
            case OFFLINE -> "OFFLINE";
            case DRIFTED -> "DRIFTED (" + (drift.drifted().size() + drift.staleMappings().size()) + ")";
        };
        return "Gateway: port " + report.gateway().port() + ", " + owner + ", " + health + pidPart + ", " + syncLabel;
    }

    private static String formatOwner(Ownership ownership, Optional<String> provenance) {
        if (ownership == Ownership.UNMANAGED && provenance.isPresent()) {
            return ownership.name() + " (" + provenance.get() + ")";
        }
        return ownership.name();
    }
}
