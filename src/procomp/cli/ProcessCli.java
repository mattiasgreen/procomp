package procomp.cli;

import procomp.core.build.BuildRunRecord;
import procomp.core.build.ObservedBuildRun;
import procomp.core.common.Json;
import procomp.core.common.Result;
import procomp.core.process.InspectionReport;
import procomp.core.process.ParentProcess;
import procomp.core.project.Project;
import procomp.core.routing.Route;
import procomp.shell.DomainJson;
import procomp.shell.ProcessShell.RestartRequest;
import procomp.shell.ProcessShell.RestartResult;
import procomp.shell.ProcessShell.RestartServiceOutcome;
import procomp.shell.ProcessShell.StartDisposition;
import procomp.shell.ProcessShell.StartRequest;
import procomp.shell.ProcessShell.StartResult;
import procomp.shell.ProcessShell.StartServiceOutcome;
import procomp.shell.ProcessShell.StopRequest;
import procomp.shell.ProcessShell.StopResult;
import procomp.shell.ProcessShell.StopServiceOutcome;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI argument parser and output renderer for {@code procomp inspect}, {@code start}, and {@code stop}.
 */
public final class ProcessCli {

    public sealed interface Action permits Action.Inspect, Action.Start, Action.Stop, Action.Restart {
        record Inspect(String serviceName, boolean json) implements Action {}
        record Start(List<String> services, boolean detach, Duration timeout) implements Action {}
        record Stop(List<String> services, boolean all, boolean killConflict) implements Action {}
        record Restart(List<String> services, boolean detach, Duration timeout) implements Action {}
    }

    private ProcessCli() {}

    public static Result<Action, String> parse(String command, List<String> args) {
        return switch (command) {
            case "inspect" -> parseInspect(args);
            case "start" -> parseStart(args);
            case "stop" -> parseStop(args);
            case "restart" -> parseRestart(args);
            default -> Result.err("Unknown process command '" + command + "'.");
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
        return Result.ok(new Action.Start(services, detach, timeout));
    }

    private static Result<Action, String> parseRestart(List<String> args) {
        boolean detach = false;
        Duration timeout = RestartRequest.DEFAULT_TIMEOUT;
        List<String> services = new ArrayList<>();

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
                return Result.err(
                    "Unknown option '" + arg + "' for 'restart'. Usage: procomp restart [service...] [-d|--detach] [--timeout <seconds>]"
                );
            } else {
                if (arg.isBlank()) {
                    return Result.err("Service name must not be blank.");
                }
                services.add(arg);
            }
        }
        return Result.ok(new Action.Restart(services, detach, timeout));
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

    static int execute(Action action, Project project, CliRuntime runtime) {
        return switch (action) {
            case Action.Start(List<String> services, boolean detach, Duration timeout) -> runtime.handleResult(
                runtime.processShell().flatMap(shell -> shell.start(project, new StartRequest(services, detach, timeout))),
                result -> renderStartResult(result, runtime.out())
            );
            case Action.Stop(List<String> services, boolean all, boolean killConflict) -> runtime.handleResult(
                runtime.processShell().flatMap(shell -> shell.stop(
                    project,
                    new StopRequest(services, all, killConflict, StopRequest.DEFAULT_GRACEFUL_TIMEOUT)
                )),
                result -> renderStopResult(result, runtime.out())
            );
            case Action.Restart(List<String> services, boolean detach, Duration timeout) -> runtime.handleResult(
                runtime.processShell().flatMap(shell -> shell.restart(project, new RestartRequest(services, detach, timeout))),
                result -> renderRestartResult(result, runtime.out())
            );
            case Action.Inspect(String serviceName, boolean json) -> runtime.handleResult(
                runtime.processShell().flatMap(shell -> shell.inspect(project, serviceName)),
                report -> {
                    Result<ObservedBuildRun, procomp.core.common.DomainError> buildRes =
                        runtime.buildShell().flatMap(b -> b.inspectBuild(project, serviceName));
                    java.util.Optional<ObservedBuildRun> buildRun =
                        buildRes instanceof Result.Ok<ObservedBuildRun, procomp.core.common.DomainError>(ObservedBuildRun obs)
                            ? java.util.Optional.of(obs)
                            : java.util.Optional.empty();
                    if (json) {
                        renderInspectJson(project, report, buildRun, runtime.out());
                    } else {
                        renderInspectText(report, buildRun, runtime.out());
                    }
                }
            );
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

    private static void renderRestartResult(RestartResult result, PrintWriter out) {
        if (result.outcomes().isEmpty()) {
            out.println("No running Managed Services — nothing to restart.");
            return;
        }
        int restarted = 0;
        int spawned = 0;
        int sparedUnmanaged = 0;
        for (RestartServiceOutcome item : result.outcomes()) {
            String pidText = item.discovery().pid().map(p -> "PID " + p).orElse("PID -");
            String ownerText = item.discovery().ownership().name()
                + item.discovery().provenance().map(p -> " (" + p + ")").orElse("");
            switch (item.disposition()) {
                case RESTARTED -> {
                    restarted++;
                    out.println(item.service() + ": restarted " + ownerText + " [" + pidText + ", " + item.discovery().health().name() + "]");
                }
                case SPAWNED -> {
                    spawned++;
                    out.println(item.service() + ": spawned " + ownerText + " [" + pidText + ", " + item.discovery().health().name() + "]");
                }
                case SPARED_UNMANAGED -> {
                    sparedUnmanaged++;
                    out.println(item.service() + ": spared Unmanaged Process [" + pidText + "] (never killed by procomp)");
                }
            }
        }
        StringBuilder summary = new StringBuilder();
        summary.append("Summary: ").append(restarted).append(" restarted");
        if (spawned > 0) {
            summary.append(", ").append(spawned).append(" started");
        }
        if (sparedUnmanaged > 0) {
            summary.append(", ").append(sparedUnmanaged).append(" Unmanaged spared");
        }
        summary.append(result.detached() ? " (detached)." : ".");
        out.println(summary);
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
        renderInspectText(report, java.util.Optional.empty(), out);
    }

    static void renderInspectText(
        InspectionReport report,
        java.util.Optional<ObservedBuildRun> buildRun,
        PrintWriter out
    ) {
        out.println("Service:         " + report.service());
        out.println("Port:            " + report.port().map(Object::toString).orElse("-"));
        out.println("Desired Route:   " + report.desiredRoute().orElse("-"));
        out.println("Live Route:      " + report.liveGatewayRoute().orElse("none (no Gateway configured)"));
        out.println("Owner:           " + report.ownership().name()
            + report.provenance().map(p -> " (" + p + ")").orElse(""));
        out.println("Health:          " + report.health().name());
        out.println("PID:             " + report.pid().map(Object::toString).orElse("-"));
        out.println("Uptime:          " + report.uptime().map(CliFormat::formatDuration).orElse("-"));
        out.println("Stale:           " + (report.stale() ? "YES" : "no"));
        out.println("RAM:             " + report.rssBytes().map(ProcessCli::formatBytes).orElse("-"));
        out.println("Command Line:    " + report.commandLine().orElse("-"));
        if (!report.arguments().isEmpty()) {
            out.println("Arguments:       " + String.join(" ", report.arguments()));
        }
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
        if (buildRun.isPresent()) {
            ObservedBuildRun build = buildRun.get();
            out.println("Build Run:       " + build.displayState().name());
            if (build.record().isPresent()) {
                BuildRunRecord rec = build.record().get();
                if (rec instanceof BuildRunRecord.Running) {
                    out.println("Build PID:       " + rec.pid());
                }
                out.println("Build Started:   " + rec.startedAt());
                switch (rec) {
                    case BuildRunRecord.Running _ -> { }
                    case BuildRunRecord.Succeeded s -> {
                        out.println("Build Finished:  " + s.finishedAt());
                        out.println("Build Exit Code: 0");
                    }
                    case BuildRunRecord.Failed f -> {
                        out.println("Build Finished:  " + f.finishedAt());
                        f.exitCode().ifPresent(c -> out.println("Build Exit Code: " + c));
                        out.println("Build Reason:    " + f.reason());
                    }
                }
                out.println("Build Stdout:    " + rec.stdoutLog());
                out.println("Build Stderr:    " + rec.stderrLog());
            }
        }
    }

    private static void renderInspectJson(
        Project project,
        InspectionReport report,
        java.util.Optional<ObservedBuildRun> buildRun,
        PrintWriter out
    ) {
        out.println(Json.stringify(DomainJson.inspectionReport(project, report, buildRun)));
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
