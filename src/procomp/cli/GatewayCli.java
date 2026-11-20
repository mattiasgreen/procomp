package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.gateway.GatewayDriftReport;
import procomp.core.gateway.RouteDrift;
import procomp.core.gateway.StaleManagedMapping;
import procomp.core.project.Project;
import procomp.shell.GatewayShell;
import procomp.shell.GatewayShell.GatewayReconcileResult;
import procomp.shell.GatewayShell.GatewayStartDisposition;
import procomp.shell.GatewayShell.GatewayStartRequest;
import procomp.shell.GatewayShell.GatewayStartResult;
import procomp.shell.GatewayShell.GatewayStatusReport;
import procomp.shell.GatewayShell.GatewayStopRequest;
import procomp.shell.GatewayShell.GatewayStopResult;
import procomp.shell.ports.Clock;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.StateStore;
import procomp.shell.ports.WorkspaceFiles;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CLI argument parser and text/JSON renderer for {@code procomp gateway <start|stop|status|reconcile>}.
 */
public final class GatewayCli {

    private static final String USAGE = "Usage: procomp gateway <start [-d|--detach] [--timeout <sec>] | stop | status [--json] | reconcile>";

    public sealed interface Action permits Action.Start, Action.Stop, Action.Status, Action.Reconcile {
        record Start(boolean detach, Duration timeout) implements Action {}
        record Stop() implements Action {}
        record Status(boolean json) implements Action {}
        record Reconcile() implements Action {}
    }

    private GatewayCli() {}

    public static Optional<Result<Action, String>> parse(String command, List<String> args) {
        if (!"gateway".equals(command)) {
            return Optional.empty();
        }
        return Optional.of(parseGateway(args));
    }

    private static Result<Action, String> parseGateway(List<String> args) {
        if (args.isEmpty()) {
            return Result.err("Expected subcommand 'start', 'stop', 'status', or 'reconcile' after 'gateway'. " + USAGE);
        }
        String sub = args.get(0);
        List<String> rest = args.subList(1, args.size());
        return switch (sub) {
            case "start" -> parseStart(rest);
            case "stop" -> rest.isEmpty()
                ? Result.ok(new Action.Stop())
                : Result.err("Unexpected argument '" + rest.get(0) + "' for 'gateway stop'. Usage: procomp gateway stop");
            case "status" -> parseStatus(rest);
            case "reconcile" -> rest.isEmpty()
                ? Result.ok(new Action.Reconcile())
                : Result.err("Unexpected argument '" + rest.get(0) + "' for 'gateway reconcile'. Usage: procomp gateway reconcile");
            default -> Result.err("Unknown 'gateway' subcommand '" + sub + "'. " + USAGE);
        };
    }

    private static Result<Action, String> parseStart(List<String> args) {
        boolean detach = false;
        Duration timeout = GatewayStartRequest.DEFAULT_TIMEOUT;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "-d", "--detach" -> detach = true;
                case "--timeout" -> {
                    if (i + 1 >= args.size()) {
                        return Result.err("Option '--timeout' requires a positive integer number of seconds.");
                    }
                    Result<Duration, String> tRes = ProcessCli.parsePositiveSeconds(args.get(++i));
                    if (tRes instanceof Result.Err<Duration, String>(String msg)) {
                        return Result.err(msg);
                    }
                    timeout = ((Result.Ok<Duration, String>) tRes).value();
                }
                default -> {
                    if (arg.startsWith("--timeout=")) {
                        Result<Duration, String> tRes = ProcessCli.parsePositiveSeconds(arg.substring("--timeout=".length()));
                        if (tRes instanceof Result.Err<Duration, String>(String msg)) {
                            return Result.err(msg);
                        }
                        timeout = ((Result.Ok<Duration, String>) tRes).value();
                    } else if (arg.startsWith("-")) {
                        return Result.err("Unknown option '" + arg + "' for 'gateway start'. Usage: procomp gateway start [-d|--detach] [--timeout <seconds>]");
                    } else {
                        return Result.err("Unexpected argument '" + arg + "' for 'gateway start'. Usage: procomp gateway start [-d|--detach] [--timeout <seconds>]");
                    }
                }
            }
        }
        return Result.ok(new Action.Start(detach, timeout));
    }

    private static Result<Action, String> parseStatus(List<String> args) {
        boolean json = false;
        for (String arg : args) {
            if ("--json".equals(arg)) {
                json = true;
            } else if (arg.startsWith("-")) {
                return Result.err("Unknown option '" + arg + "' for 'gateway status'. Usage: procomp gateway status [--json]");
            } else {
                return Result.err("Unexpected argument '" + arg + "' for 'gateway status'. Usage: procomp gateway status [--json]");
            }
        }
        return Result.ok(new Action.Status(json));
    }

    public static int execute(
        Action action,
        Project project,
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        WorkspaceFiles workspaceFiles,
        Clock clock,
        ProgressObserver observer,
        PrintWriter out,
        PrintWriter err
    ) {
        if (processRunner == null || portScanner == null || httpClient == null || workspaceFiles == null) {
            err.println("Error: Gateway ports are not configured.");
            return 1;
        }
        GatewayShell shell = new GatewayShell(
            stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, observer
        );
        return switch (action) {
            case Action.Start(boolean detach, Duration timeout) ->
                handleResult(shell.start(project, new GatewayStartRequest(detach, timeout)), err, res -> renderStart(res, out));
            case Action.Stop _ ->
                handleResult(shell.stop(project, new GatewayStopRequest(GatewayStopRequest.DEFAULT_GRACEFUL_TIMEOUT)), err, res -> renderStop(res, out));
            case Action.Status(boolean json) ->
                handleResult(shell.status(project), err, report -> {
                    if (json) {
                        renderStatusJson(report, out);
                    } else {
                        renderStatusText(report, out);
                    }
                });
            case Action.Reconcile _ ->
                handleResult(shell.reconcile(project), err, res -> renderReconcile(res, out));
        };
    }

    private static <T> int handleResult(
        Result<T, DomainError> res,
        PrintWriter err,
        java.util.function.Consumer<T> onSuccess
    ) {
        return switch (res) {
            case Result.Ok<T, DomainError>(T value) -> {
                onSuccess.accept(value);
                yield 0;
            }
            case Result.Err<T, DomainError>(DomainError error) -> {
                err.println("Error: " + error.format());
                yield 1;
            }
        };
    }

    private static void renderStart(GatewayStartResult result, PrintWriter out) {
        String verb = result.disposition() == GatewayStartDisposition.SPAWNED ? "spawned" : "reused";
        String ownerText = result.discovery().ownership().name()
            + result.discovery().provenance().map(p -> " (" + p + ")").orElse("");
        String pidText = result.discovery().pid().map(p -> "PID " + p).orElse("PID -");
        out.println("Gateway: " + verb + " " + ownerText + " [" + pidText + ", " + result.discovery().health().name()
            + "] -> " + result.gateway().adminUrl() + (result.detached() ? " (detached)" : ""));
    }

    private static void renderStop(GatewayStopResult result, PrintWriter out) {
        String pidText = result.pid().map(p -> " (PID " + p + ")").orElse("");
        switch (result.disposition()) {
            case STOPPED_MANAGED -> out.println("Gateway: stopped Managed Gateway" + pidText);
            case CLEANED_CRASHED -> out.println("Gateway: cleaned up crashed Managed Gateway record" + pidText);
            case ALREADY_STOPPED -> out.println("Gateway: already stopped");
        }
    }

    private static void renderReconcile(GatewayReconcileResult result, PrintWriter out) {
        StringBuilder sb = new StringBuilder();
        sb.append("Gateway: reconciled ")
            .append(result.appliedMappings())
            .append(" route mapping(s) on ")
            .append(result.gateway().adminUrl())
            .append(" (")
            .append(result.drift().syncStatus().name())
            .append(")");
        if (result.deletedPriorMappings() > 0) {
            sb.append(" [cleared ").append(result.deletedPriorMappings()).append(" prior managed mapping(s)]");
        }
        out.println(sb);
    }

    private static void renderStatusText(GatewayStatusReport report, PrintWriter out) {
        out.println("Gateway:         " + report.gateway().type() + " (" + report.gateway().adminUrl() + ")");
        out.println("Port:            " + report.gateway().port());
        out.println("Owner:           " + report.discovery().ownership().name()
            + report.discovery().provenance().map(p -> " (" + p + ")").orElse(""));
        out.println("Health:          " + report.discovery().health().name());
        out.println("PID:             " + report.discovery().pid().map(Object::toString).orElse("-"));
        out.println("Uptime:          " + report.uptime().map(ProcessCli::formatDuration).orElse("-"));
        out.println("Sync Status:     " + report.drift().syncStatus().name());

        GatewayDriftReport drift = report.drift();
        if (!drift.drifted().isEmpty()) {
            out.println("Drifted Routes:");
            List<List<String>> rows = new ArrayList<>(drift.drifted().size());
            for (RouteDrift item : drift.drifted()) {
                rows.add(List.of(item.service(), item.desired().name(), item.live()));
            }
            RoutingCli.renderTable(out, List.of("SERVICE", "DESIRED", "LIVE"), rows);
        }
        if (!drift.staleMappings().isEmpty()) {
            out.println("Stale Mappings:");
            for (StaleManagedMapping stale : drift.staleMappings()) {
                out.println("  - service=" + stale.service().orElse("(unknown)")
                    + ", id=" + stale.id().orElse("-")
                    + ", target=" + stale.targetUrl().orElse("-"));
            }
        }
    }

    private static void renderStatusJson(GatewayStatusReport report, PrintWriter out) {
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(report.projectName()));
        root.put("type", Json.str(report.gateway().type()));
        root.put("port", Json.num(report.gateway().port()));
        root.put("adminUrl", Json.str(report.gateway().adminUrl()));
        root.put("owner", Json.str(report.discovery().ownership().name()));
        root.put("provenance", report.discovery().provenance().<JsonValue>map(Json::str).orElse(Json.nil()));
        root.put("health", Json.str(report.discovery().health().name()));
        root.put("pid", report.discovery().pid().<JsonValue>map(Json::num).orElse(Json.nil()));
        root.put("uptimeSeconds", report.uptime().<JsonValue>map(d -> Json.num(d.toSeconds())).orElse(Json.nil()));
        root.put("healthEndpoint", report.discovery().healthEndpoint().<JsonValue>map(Json::str).orElse(Json.nil()));
        root.put("healthResponse", Json.str(report.discovery().healthResponse()));
        root.put("syncStatus", Json.str(report.drift().syncStatus().name()));

        List<JsonValue> driftedNodes = new ArrayList<>(report.drift().drifted().size());
        for (RouteDrift item : report.drift().drifted()) {
            Map<String, JsonValue> dObj = new LinkedHashMap<>();
            dObj.put("service", Json.str(item.service()));
            dObj.put("desired", Json.str(item.desired().name()));
            dObj.put("live", Json.str(item.live()));
            driftedNodes.add(Json.obj(dObj));
        }
        root.put("drifted", Json.arr(driftedNodes));

        List<JsonValue> staleNodes = new ArrayList<>(report.drift().staleMappings().size());
        for (StaleManagedMapping stale : report.drift().staleMappings()) {
            Map<String, JsonValue> sObj = new LinkedHashMap<>();
            sObj.put("id", stale.id().<JsonValue>map(Json::str).orElse(Json.nil()));
            sObj.put("service", stale.service().<JsonValue>map(Json::str).orElse(Json.nil()));
            sObj.put("targetUrl", stale.targetUrl().<JsonValue>map(Json::str).orElse(Json.nil()));
            staleNodes.add(Json.obj(sObj));
        }
        root.put("staleMappings", Json.arr(staleNodes));

        out.println(Json.stringify(Json.obj(root)));
    }
}
