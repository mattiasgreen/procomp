package procomp.cli;

import procomp.core.common.Json;
import procomp.core.common.Result;
import procomp.core.gateway.GatewayDriftReport;
import procomp.core.gateway.RouteDrift;
import procomp.core.gateway.StaleManagedMapping;
import procomp.core.project.Project;
import procomp.shell.DomainJson;
import procomp.shell.GatewayShell.GatewayReconcileResult;
import procomp.shell.GatewayShell.GatewayStartDisposition;
import procomp.shell.GatewayShell.GatewayStartRequest;
import procomp.shell.GatewayShell.GatewayStartResult;
import procomp.shell.GatewayShell.GatewayStatusReport;
import procomp.shell.GatewayShell.GatewayStopRequest;
import procomp.shell.GatewayShell.GatewayStopResult;
import procomp.shell.LogsShell.GatewayLogsRequest;
import procomp.shell.LogsShell.LogsRequest;
import procomp.shell.LogsShell.LogsResult;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI argument parser and text/JSON renderer for {@code procomp gateway <start|stop|status|reconcile|logs>}.
 */
public final class GatewayCli {

    private static final String USAGE =
        "Usage: procomp gateway <start [-d|--detach] [--timeout <sec>] | stop | status [--json] | reconcile | logs [-n <lines>] [-f] [--err] [--path]>";

    public sealed interface Action permits Action.Start, Action.Stop, Action.Status, Action.Reconcile, Action.Logs {
        record Start(boolean detach, Duration timeout) implements Action {}
        record Stop() implements Action {}
        record Status(boolean json) implements Action {}
        record Reconcile() implements Action {}
        record Logs(GatewayLogsRequest request) implements Action {}
    }

    private GatewayCli() {}

    public static Result<Action, String> parse(List<String> args) {
        if (args.isEmpty()) {
            return Result.err("Expected subcommand 'start', 'stop', 'status', 'reconcile', or 'logs' after 'gateway'. " + USAGE);
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
            case "logs" -> parseLogs(rest);
            default -> Result.err("Unknown 'gateway' subcommand '" + sub + "'. " + USAGE);
        };
    }

    private static Result<Action, String> parseLogs(List<String> args) {
        boolean stderr = false;
        boolean pathOnly = false;
        int lines = LogsRequest.DEFAULT_LINES;
        boolean follow = false;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--err" -> stderr = true;
                case "--path" -> pathOnly = true;
                case "-f", "--follow" -> follow = true;
                case "-n", "--lines" -> {
                    if (i + 1 >= args.size()) {
                        return Result.err("Option '" + arg + "' requires a non-negative integer line count.");
                    }
                    Result<Integer, String> parsedLines = LogsCli.parseNonNegativeInt(args.get(++i));
                    if (parsedLines instanceof Result.Err<Integer, String>(String msg)) {
                        return Result.err(msg);
                    }
                    lines = ((Result.Ok<Integer, String>) parsedLines).value();
                }
                default -> {
                    if (arg.startsWith("--lines=")) {
                        Result<Integer, String> parsedLines = LogsCli.parseNonNegativeInt(arg.substring("--lines=".length()));
                        if (parsedLines instanceof Result.Err<Integer, String>(String msg)) {
                            return Result.err(msg);
                        }
                        lines = ((Result.Ok<Integer, String>) parsedLines).value();
                    } else if (arg.startsWith("-")) {
                        return Result.err(
                            "Unknown option '" + arg + "' for 'gateway logs'. Usage: procomp gateway logs [-n <lines>] [-f] [--err] [--path]"
                        );
                    } else {
                        return Result.err(
                            "Unexpected argument '" + arg + "' for 'gateway logs'. Usage: procomp gateway logs [-n <lines>] [-f] [--err] [--path]"
                        );
                    }
                }
            }
        }
        return Result.ok(new Action.Logs(new GatewayLogsRequest(stderr, pathOnly, lines, follow)));
    }

    private static Result<Action, String> parseStart(List<String> args) {
        boolean detach = false;
        Duration timeout = GatewayStartRequest.DEFAULT_TIMEOUT;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("-d".equals(arg) || "--detach".equals(arg)) {
                detach = true;
            } else if (CliFormat.isTimeoutOption(arg)) {
                Result<CliFormat.ParsedOption<Duration>, String> tRes = CliFormat.parseTimeoutOption(args, i);
                if (tRes instanceof Result.Err<CliFormat.ParsedOption<Duration>, String>(String msg)) {
                    return Result.err(msg);
                }
                var opt = ((Result.Ok<CliFormat.ParsedOption<Duration>, String>) tRes).value();
                timeout = opt.value();
                i = opt.nextIndex();
            } else if (arg.startsWith("-")) {
                return Result.err("Unknown option '" + arg + "' for 'gateway start'. Usage: procomp gateway start [-d|--detach] [--timeout <seconds>]");
            } else {
                return Result.err("Unexpected argument '" + arg + "' for 'gateway start'. Usage: procomp gateway start [-d|--detach] [--timeout <seconds>]");
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

    static int execute(Action action, Project project, CliRuntime runtime) {
        return switch (action) {
            case Action.Start(boolean detach, Duration timeout) -> runtime.handleResult(
                runtime.gatewayShell().flatMap(shell -> shell.start(project, new GatewayStartRequest(detach, timeout))),
                res -> renderStart(res, runtime.out())
            );
            case Action.Stop _ -> runtime.handleResult(
                runtime.gatewayShell().flatMap(shell -> shell.stop(project, new GatewayStopRequest(GatewayStopRequest.DEFAULT_GRACEFUL_TIMEOUT))),
                res -> renderStop(res, runtime.out())
            );
            case Action.Status(boolean json) -> runtime.handleResult(
                runtime.gatewayShell().flatMap(shell -> shell.status(project)),
                report -> {
                    if (json) {
                        renderStatusJson(report, runtime.out());
                    } else {
                        renderStatusText(report, runtime.out());
                    }
                }
            );
            case Action.Reconcile _ -> runtime.handleResult(
                runtime.gatewayShell().flatMap(shell -> shell.reconcile(project)),
                res -> renderReconcile(res, runtime.out())
            );
            case Action.Logs(GatewayLogsRequest request) -> runtime.handleResult(
                runtime.logsShell().flatMap(shell -> shell.runGateway(project, request)),
                result -> {
                    switch (result) {
                        case LogsResult.ResolvedPath(Path path) -> runtime.out().println(path);
                        case LogsResult.RawTail(_, String text) -> {
                            runtime.out().print(text);
                            runtime.out().flush();
                        }
                        case LogsResult.Followed _ -> runtime.out().flush();
                    }
                }
            );
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
        out.println("Uptime:          " + report.uptime().map(CliFormat::formatDuration).orElse("-"));
        out.println("Sync Status:     " + report.drift().syncStatus().name());

        GatewayDriftReport drift = report.drift();
        if (!drift.drifted().isEmpty()) {
            out.println("Drifted Routes:");
            List<List<String>> rows = new ArrayList<>(drift.drifted().size());
            for (RouteDrift item : drift.drifted()) {
                rows.add(List.of(item.service(), item.desired().name(), item.live()));
            }
            CliFormat.renderTable(out, List.of("SERVICE", "DESIRED", "LIVE"), rows);
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
        out.println(Json.stringify(DomainJson.gatewayStatus(report)));
    }
}
