package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.process.InspectionReport;
import procomp.core.process.ParentProcess;
import procomp.core.project.Project;
import procomp.core.routing.Route;
import procomp.shell.ProcessShell;
import procomp.shell.ProcessShell.StartDisposition;
import procomp.shell.ProcessShell.StartRequest;
import procomp.shell.ProcessShell.StartResult;
import procomp.shell.ProcessShell.StartServiceOutcome;
import procomp.shell.ProcessShell.StopDisposition;
import procomp.shell.ProcessShell.StopRequest;
import procomp.shell.ProcessShell.StopResult;
import procomp.shell.ProcessShell.StopServiceOutcome;
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
 * CLI argument parser and output renderer for {@code procomp inspect}, {@code start}, and {@code stop}.
 */
public final class ProcessCli {

    public sealed interface Action permits Action.Inspect, Action.Start, Action.Stop {
        record Inspect(String serviceName, boolean json) implements Action {}
        record Start(List<String> services, boolean detach, Duration timeout) implements Action {}
        record Stop(List<String> services, boolean all, boolean killConflict) implements Action {}
    }

    private ProcessCli() {}

    public static Optional<Result<Action, String>> parse(String command, List<String> args) {
        return switch (command) {
            case "inspect" -> Optional.of(parseInspect(args));
            case "start" -> Optional.of(parseStart(args));
            case "stop" -> Optional.of(parseStop(args));
            default -> Optional.empty();
        };
    }

    private static Result<Action, String> parseStop(List<String> args) {
        boolean all = false;
        boolean killConflict = false;
        List<String> services = new ArrayList<>();

        for (String arg : args) {
            switch (arg) {
                case "--all" -> all = true;
                case "--kill-conflict" -> killConflict = true;
                default -> {
                    if (arg.startsWith("-")) {
                        return Result.err(
                            "Unknown option '" + arg + "' for 'stop'. Usage: procomp stop [service...] [--all] [--kill-conflict]"
                        );
                    }
                    if (arg.isBlank()) {
                        return Result.err("Service name must not be blank.");
                    }
                    services.add(arg);
                }
            }
        }
        if (killConflict && services.isEmpty()) {
            return Result.err("Option '--kill-conflict' requires one or more explicit <service> arguments.");
        }
        return Result.ok(new Action.Stop(services, all, killConflict));
    }

    private static Result<Action, String> parseStart(List<String> args) {
        boolean detach = false;
        Duration timeout = StartRequest.DEFAULT_TIMEOUT;
        List<String> services = new ArrayList<>();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "-d", "--detach" -> detach = true;
                case "--timeout" -> {
                    if (i + 1 >= args.size()) {
                        return Result.err("Option '--timeout' requires a positive integer number of seconds.");
                    }
                    Result<Duration, String> tRes = parsePositiveSeconds(args.get(++i));
                    if (tRes instanceof Result.Err<Duration, String>(String msg)) {
                        return Result.err(msg);
                    }
                    timeout = ((Result.Ok<Duration, String>) tRes).value();
                }
                default -> {
                    if (arg.startsWith("--timeout=")) {
                        Result<Duration, String> tRes = parsePositiveSeconds(arg.substring("--timeout=".length()));
                        if (tRes instanceof Result.Err<Duration, String>(String msg)) {
                            return Result.err(msg);
                        }
                        timeout = ((Result.Ok<Duration, String>) tRes).value();
                    } else if (arg.startsWith("-")) {
                        return Result.err(
                            "Unknown option '" + arg + "' for 'start'. Usage: procomp start [service...] [-d|--detach] [--timeout <seconds>]"
                        );
                    } else {
                        if (arg.isBlank()) {
                            return Result.err("Service name must not be blank.");
                        }
                        services.add(arg);
                    }
                }
            }
        }
        return Result.ok(new Action.Start(services, detach, timeout));
    }

    static Result<Duration, String> parsePositiveSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.err("Invalid --timeout value: must be a positive integer number of seconds.");
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds <= 0) {
                return Result.err("Invalid --timeout value '" + raw + "': must be greater than 0 seconds.");
            }
            return Result.ok(Duration.ofSeconds(seconds));
        } catch (NumberFormatException e) {
            return Result.err("Invalid --timeout value '" + raw + "': must be a positive integer number of seconds.");
        }
    }

    private static Result<Action, String> parseInspect(List<String> args) {
        String serviceName = null;
        boolean json = false;
        for (String arg : args) {
            if ("--json".equals(arg)) {
                json = true;
            } else if (arg.startsWith("-")) {
                return Result.err("Unknown option '" + arg + "' for 'inspect'. Usage: procomp inspect <service> [--json]");
            } else if (serviceName == null) {
                if (arg.isBlank()) {
                    return Result.err("Service name must not be blank.");
                }
                serviceName = arg;
            } else {
                return Result.err("Unexpected extra argument '" + arg + "'. Usage: procomp inspect <service> [--json]");
            }
        }
        if (serviceName == null) {
            return Result.err("Missing required <service> argument. Usage: procomp inspect <service> [--json]");
        }
        return Result.ok(new Action.Inspect(serviceName, json));
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
            err.println("Error: Process ports are not configured.");
            return 1;
        }
        ProcessShell shell = new ProcessShell(
            stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, observer
        );
        return switch (action) {
            case Action.Start(List<String> services, boolean detach, Duration timeout) -> {
                Result<StartResult, DomainError> res =
                    shell.start(project, new StartRequest(services, detach, timeout));
                yield switch (res) {
                    case Result.Ok<StartResult, DomainError>(StartResult result) -> {
                        renderStartResult(result, out);
                        yield 0;
                    }
                    case Result.Err<StartResult, DomainError>(DomainError error) -> {
                        err.println("Error: " + error.format());
                        yield 1;
                    }
                };
            }
            case Action.Stop(List<String> services, boolean all, boolean killConflict) -> {
                Result<StopResult, DomainError> res = shell.stop(
                    project,
                    new StopRequest(services, all, killConflict, StopRequest.DEFAULT_GRACEFUL_TIMEOUT)
                );
                yield switch (res) {
                    case Result.Ok<StopResult, DomainError>(StopResult result) -> {
                        renderStopResult(result, out);
                        yield 0;
                    }
                    case Result.Err<StopResult, DomainError>(DomainError error) -> {
                        err.println("Error: " + error.format());
                        yield 1;
                    }
                };
            }
            case Action.Inspect(String serviceName, boolean json) -> {
                Result<InspectionReport, DomainError> res = shell.inspect(project, serviceName);
                yield switch (res) {
                    case Result.Ok<InspectionReport, DomainError>(InspectionReport report) -> {
                        if (json) {
                            renderInspectJson(project, report, out);
                        } else {
                            renderInspectText(report, out);
                        }
                        yield 0;
                    }
                    case Result.Err<InspectionReport, DomainError>(DomainError error) -> {
                        err.println("Error: " + error.format());
                        yield 1;
                    }
                };
            }
        };
    }

    private static void renderStartResult(StartResult result, PrintWriter out) {
        if (result.outcomes().isEmpty()) {
            out.println("No Services routed LOCAL in the Routing Table — nothing to start.");
            out.println("Use 'procomp route <service> local' or 'procomp profile use <name>' to select a LOCAL slice.");
            return;
        }
        int spawned = 0;
        int reused = 0;
        for (StartServiceOutcome item : result.outcomes()) {
            if (item.disposition() == StartDisposition.SPAWNED) {
                spawned++;
            } else {
                reused++;
            }
            String pidText = item.discovery().pid().map(p -> "PID " + p).orElse("PID -");
            String ownerText = item.discovery().ownership().name()
                + item.discovery().provenance().map(p -> " (" + p + ")").orElse("");
            String actionWord = item.disposition() == StartDisposition.SPAWNED ? "spawned" : "reused";
            out.println(item.service() + ": " + actionWord + " " + ownerText + " [" + pidText + ", " + item.discovery().health().name() + "]");
            if (item.desiredRoute() == Route.REMOTE) {
                out.println("  note: route remains REMOTE (run 'procomp route " + item.service() + " local' to route locally)");
            }
        }
        out.println("Summary: " + spawned + " started, " + reused + " reused" + (result.detached() ? " (detached)." : "."));
    }

    private static void renderStopResult(StopResult result, PrintWriter out) {
        int stopped = 0;
        int sparedUnmanaged = 0;
        for (StopServiceOutcome item : result.outcomes()) {
            String pidText = item.pid().map(p -> " (PID " + p + ")").orElse("");
            switch (item.disposition()) {
                case STOPPED_MANAGED -> {
                    stopped++;
                    out.println(item.service() + ": stopped Managed Process" + pidText);
                }
                case CLEANED_CRASHED ->
                    out.println(item.service() + ": cleaned up crashed Managed record" + pidText);
                case SPARED_UNMANAGED -> {
                    sparedUnmanaged++;
                    out.println(item.service() + ": spared Unmanaged"
                        + item.provenance().map(p -> " (" + p + ")").orElse("") + " Process" + pidText);
                }
                case SPARED_CONFLICT ->
                    out.println(item.service() + ": spared Conflict Process" + pidText + " (pass --kill-conflict with explicit service to stop)");
                case KILLED_CONFLICT -> {
                    stopped++;
                    out.println(item.service() + ": killed Conflict Process" + pidText + " with explicit consent");
                }
                case ALREADY_STOPPED -> { /* omit noise for already stopped services unless needed */ }
            }
        }
        String gwPidText = result.gatewayPid().map(p -> " (PID " + p + ")").orElse("");
        switch (result.gatewayOutcome()) {
            case LEFT_RUNNING ->
                out.println("Gateway: left running" + gwPidText + " (pass --all or run 'procomp gateway stop' to stop it)");
            case STOPPED_MANAGED ->
                out.println("Gateway: stopped Managed Gateway" + gwPidText);
            case CLEANED_CRASHED ->
                out.println("Gateway: cleaned up crashed Managed Gateway record" + gwPidText);
            case SPARED_NON_MANAGED ->
                out.println("Gateway: spared non-Managed Gateway" + gwPidText);
            case NOT_RUNNING -> { }
        }
        out.println("Summary: " + stopped + " stopped" + (sparedUnmanaged > 0 ? ", " + sparedUnmanaged + " Unmanaged spared." : "."));
    }

    static void renderInspectText(InspectionReport report, PrintWriter out) {
        out.println("Service:         " + report.service());
        out.println("Port:            " + report.port().map(Object::toString).orElse("-"));
        out.println("Desired Route:   " + report.desiredRoute().orElse("-"));
        out.println("Owner:           " + report.ownership().name()
            + report.provenance().map(p -> " (" + p + ")").orElse(""));
        out.println("Health:          " + report.health().name());
        out.println("PID:             " + report.pid().map(Object::toString).orElse("-"));
        out.println("Uptime:          " + report.uptime().map(ProcessCli::formatDuration).orElse("-"));
        out.println("Stale:           " + (report.stale() ? "YES" : "no"));
        out.println("RAM:             " + report.rssBytes().map(ProcessCli::formatBytes).orElse("-"));
        out.println("Command Line:    " + report.commandLine().orElse("-"));
        if (report.parentChain().isEmpty()) {
            out.println("Parent Chain:    -");
        } else {
            out.println("Parent Chain:");
            for (ParentProcess parent : report.parentChain()) {
                String label = parent.commandLine()
                    .or(parent::executable)
                    .orElse("(unknown)");
                out.println("  - PID " + parent.pid() + ": " + label);
            }
        }
        out.println("Health Endpoint: " + report.healthEndpoint().orElse("-"));
        out.println("Health Response: " + report.healthResponse());
        out.println("Stdout Log:      " + report.outLogPath());
        out.println("Stderr Log:      " + report.errLogPath());
    }

    private static void renderInspectJson(Project project, InspectionReport report, PrintWriter out) {
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(project.name()));
        root.put("service", Json.str(report.service()));
        root.put("port", report.port().<JsonValue>map(Json::num).orElse(Json.nil()));
        root.put("desiredRoute", report.desiredRoute().<JsonValue>map(Json::str).orElse(Json.nil()));
        root.put("liveGatewayRoute", report.liveGatewayRoute().<JsonValue>map(Json::str).orElse(Json.nil()));
        root.put("owner", Json.str(report.ownership().name()));
        root.put("provenance", report.provenance().<JsonValue>map(Json::str).orElse(Json.nil()));
        root.put("health", Json.str(report.health().name()));
        root.put("pid", report.pid().<JsonValue>map(Json::num).orElse(Json.nil()));
        root.put("uptimeSeconds", report.uptime().<JsonValue>map(d -> Json.num(d.toSeconds())).orElse(Json.nil()));
        root.put("commandLine", report.commandLine().<JsonValue>map(Json::str).orElse(Json.nil()));

        List<JsonValue> argNodes = new ArrayList<>(report.arguments().size());
        for (String arg : report.arguments()) {
            argNodes.add(Json.str(arg));
        }
        root.put("arguments", Json.arr(argNodes));

        List<JsonValue> parentNodes = new ArrayList<>(report.parentChain().size());
        for (ParentProcess parent : report.parentChain()) {
            Map<String, JsonValue> pObj = new LinkedHashMap<>();
            pObj.put("pid", Json.num(parent.pid()));
            pObj.put("commandLine", parent.commandLine().<JsonValue>map(Json::str).orElse(Json.nil()));
            pObj.put("executable", parent.executable().<JsonValue>map(Json::str).orElse(Json.nil()));
            parentNodes.add(Json.obj(pObj));
        }
        root.put("parentChain", Json.arr(parentNodes));

        root.put("rssBytes", report.rssBytes().<JsonValue>map(Json::num).orElse(Json.nil()));
        root.put("healthEndpoint", report.healthEndpoint().<JsonValue>map(Json::str).orElse(Json.nil()));
        root.put("healthResponse", Json.str(report.healthResponse()));
        root.put("outLogPath", Json.str(report.outLogPath().toString()));
        root.put("errLogPath", Json.str(report.errLogPath().toString()));
        root.put("stale", Json.bool(report.stale()));

        out.println(Json.stringify(Json.obj(root)));
    }

    static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0L, duration.toSeconds());
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + "h" + minutes + "m" + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m" + seconds + "s";
        }
        return seconds + "s";
    }

    private static String formatBytes(long bytes) {
        long mb = bytes / (1024L * 1024L);
        if (mb > 0) {
            return mb + " MB";
        }
        long kb = bytes / 1024L;
        if (kb > 0) {
            return kb + " KB";
        }
        return bytes + " B";
    }
}
