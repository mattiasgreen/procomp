package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.platform.FsConfigSource;
import procomp.platform.FsStateStore;
import procomp.platform.FsWorkspaceFiles;
import procomp.platform.JdkHttpClient;
import procomp.platform.OsPortScanner;
import procomp.platform.OsProcessRunner;
import procomp.platform.SystemClock;
import procomp.shell.ProjectLoader;
import procomp.shell.ports.Clock;
import procomp.shell.ports.ConfigSource;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.StateStore;
import procomp.shell.ports.WorkspaceFiles;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JEP 458 CLI entry point for procomp.
 */
public final class Procomp {

    private static final Set<String> KNOWN_COMMANDS = Set.of(
        "start", "stop", "status", "inspect", "logs", "route", "profile", "gateway"
    );

    private Procomp() {}

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        int exitCode = run(
            args,
            new FsConfigSource(),
            new FsStateStore(),
            new OsProcessRunner(),
            new OsPortScanner(),
            new JdkHttpClient(),
            new FsWorkspaceFiles(),
            new SystemClock(),
            ProgressObserver.noop(),
            out,
            err
        );
        out.flush();
        err.flush();
        System.exit(exitCode);
    }

    public static int run(
        String[] args,
        ConfigSource configSource,
        StateStore stateStore,
        PrintWriter out,
        PrintWriter err
    ) {
        return run(args, configSource, stateStore, new SystemClock(), ProgressObserver.noop(), out, err);
    }

    public static int run(
        String[] args,
        ConfigSource configSource,
        StateStore stateStore,
        Clock clock,
        ProgressObserver observer,
        PrintWriter out,
        PrintWriter err
    ) {
        return run(
            args,
            configSource,
            stateStore,
            new OsProcessRunner(),
            new OsPortScanner(),
            new JdkHttpClient(),
            new FsWorkspaceFiles(),
            clock,
            observer,
            out,
            err
        );
    }

    public static int run(
        String[] args,
        ConfigSource configSource,
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
        Clock effectiveClock = clock != null ? clock : new SystemClock();
        ProgressObserver effectiveObserver = observer != null ? observer : ProgressObserver.noop();
        Result<ParsedArgs, String> parsedRes = parseArgs(args);
        if (parsedRes instanceof Result.Err<ParsedArgs, String>(String msg)) {
            err.println("Error: " + msg);
            return 2;
        }
        ParsedArgs parsed = ((Result.Ok<ParsedArgs, String>) parsedRes).value();

        if (parsed.help()) {
            printUsage(out);
            return 0;
        }

        if (parsed.commandArgs().isEmpty()) {
            if (parsed.configOverride().isPresent()) {
                Result<Project, DomainError> projRes = new ProjectLoader(configSource).load(parsed.configOverride());
                return switch (projRes) {
                    case Result.Ok<Project, DomainError>(Project p) -> {
                        out.println("Config OK: project '" + p.name() + "' (" + p.services().size() + " services)");
                        yield 0;
                    }
                    case Result.Err<Project, DomainError>(DomainError e) -> {
                        err.println("Error: " + e.format());
                        yield 1;
                    }
                };
            }
            printUsage(out);
            return 0;
        }

        String command = parsed.commandArgs().get(0);
        if (!KNOWN_COMMANDS.contains(command)) {
            err.println("Error: Unknown command '" + command + "'. Run 'procomp --help' for usage.");
            return 2;
        }

        List<String> subArgs = parsed.commandArgs().subList(1, parsed.commandArgs().size());
        Optional<Result<RoutingCli.Action, String>> routingParse = RoutingCli.parse(command, subArgs);
        RoutingCli.Action routingAction = null;
        if (routingParse.isPresent()) {
            Result<RoutingCli.Action, String> actionRes = routingParse.get();
            if (actionRes instanceof Result.Err<RoutingCli.Action, String>(String msg)) {
                err.println("Error: " + msg);
                return 2;
            }
            routingAction = ((Result.Ok<RoutingCli.Action, String>) actionRes).value();
        }

        Optional<Result<ProcessCli.Action, String>> processParse = ProcessCli.parse(command, subArgs);
        ProcessCli.Action processAction = null;
        if (processParse.isPresent()) {
            Result<ProcessCli.Action, String> actionRes = processParse.get();
            if (actionRes instanceof Result.Err<ProcessCli.Action, String>(String msg)) {
                err.println("Error: " + msg);
                return 2;
            }
            processAction = ((Result.Ok<ProcessCli.Action, String>) actionRes).value();
        }

        Result<Project, DomainError> projRes = new ProjectLoader(configSource).load(parsed.configOverride());
        if (projRes instanceof Result.Err<Project, DomainError>(DomainError error)) {
            err.println("Error: " + error.format());
            return 1;
        }
        Project project = ((Result.Ok<Project, DomainError>) projRes).value();

        // StateStore is wired cleanly here for downstream commands in Tickets 02-06.
        if (stateStore == null) {
            err.println("Error: StateStore is not configured.");
            return 1;
        }
        if (routingAction != null) {
            return RoutingCli.execute(routingAction, project, stateStore, out, err);
        }
        if (processAction != null) {
            return ProcessCli.execute(
                processAction,
                project,
                stateStore,
                processRunner,
                portScanner,
                httpClient,
                workspaceFiles,
                effectiveClock,
                effectiveObserver,
                out,
                err
            );
        }
        return switch (command) {
            case "logs" -> LogsCli.run(project, subArgs, stateStore, effectiveClock, effectiveObserver, out, err);
            default -> {
                err.println("Error: Command '" + command + "' is not yet implemented.");
                yield 2;
            }
        };
    }

    private static Result<ParsedArgs, String> parseArgs(String[] args) {
        boolean help = false;
        Optional<Path> configOverride = Optional.empty();
        List<String> commandArgs = new ArrayList<>();
        boolean seenCommand = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                help = true;
                continue;
            }
            if ("--config".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].isBlank()) {
                    return Result.err("Option '--config' requires a file path.");
                }
                configOverride = Optional.of(Path.of(args[++i]));
                continue;
            }
            if (arg.startsWith("--config=")) {
                String pathVal = arg.substring("--config=".length());
                if (pathVal.isBlank()) {
                    return Result.err("Option '--config=' requires a non-empty file path.");
                }
                configOverride = Optional.of(Path.of(pathVal));
                continue;
            }
            if (!seenCommand && arg.startsWith("-")) {
                return Result.err("Unknown option '" + arg + "'. Run 'procomp --help' for usage.");
            }
            seenCommand = true;
            commandArgs.add(arg);
        }

        return Result.ok(new ParsedArgs(help, configOverride, commandArgs));
    }

    private static void printUsage(PrintWriter out) {
        out.println("""
            Usage: procomp [--config <path>] <command> [args...]

            Global options:
              -h, --help          Show this help message
                  --config <path> Use explicit procomp.json path (overrides PROCOMP_CONFIG and walk-up)

            Commands:
              start [service...]                  Start local Service Process(es) (-d|--detach, --timeout <sec>)
              stop [service...]                   Stop Managed Service Process(es) (--all, --kill-conflict)
              status                              Show estate Routing Table, Process ownership, Health, and Gateway
              inspect <service>                   Deep read-only Inspection of a Service's local Process (--json)
              logs <service>                      Read raw stdout/stderr logs (-n, -f, --err, --path)
              route [service... <local|remote>]   Show or update the Project's Routing Table
              profile <list|use <name>>           List or apply named routing Profiles
              gateway <start|stop|status|reconcile>
                                                  Manage the local Gateway and reconcile mappings""");
    }

    private record ParsedArgs(boolean help, Optional<Path> configOverride, List<String> commandArgs) {}
}
