package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.shell.LogsShell.LogsRequest;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JEP 458 CLI entry point for procomp.
 */
public final class Procomp {

    private Procomp() {}

    private sealed interface CliCommand permits
        CliCommand.Routing, CliCommand.ProcessCommand, CliCommand.Gateway, CliCommand.Status, CliCommand.Logs, CliCommand.Ui {
        record Routing(RoutingCli.Action action) implements CliCommand {}
        record ProcessCommand(ProcessCli.Action action) implements CliCommand {}
        record Gateway(GatewayCli.Action action) implements CliCommand {}
        record Status(StatusCli.Action action) implements CliCommand {}
        record Logs(LogsRequest request) implements CliCommand {}
        record Ui(UiCli.Action action) implements CliCommand {}
    }

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        int exitCode = run(args, CliRuntime.system(out, err));
        out.flush();
        err.flush();
        System.exit(exitCode);
    }

    static int run(String[] args, CliRuntime runtime) {
        Result<ParsedArgs, String> parsedRes = parseArgs(args);
        if (parsedRes instanceof Result.Err<ParsedArgs, String>(String msg)) {
            runtime.err().println("Error: " + msg);
            return 2;
        }
        ParsedArgs parsed = ((Result.Ok<ParsedArgs, String>) parsedRes).value();

        if (parsed.help() || parsed.commandArgs().isEmpty()) {
            printUsage(runtime.out());
            return 0;
        }

        String command = parsed.commandArgs().get(0);
        List<String> subArgs = parsed.commandArgs().subList(1, parsed.commandArgs().size());
        Result<CliCommand, String> commandRes = parseCommand(command, subArgs);
        if (commandRes instanceof Result.Err<CliCommand, String>(String msg)) {
            runtime.err().println("Error: " + msg);
            return 2;
        }
        CliCommand cliCommand = ((Result.Ok<CliCommand, String>) commandRes).value();

        if (cliCommand instanceof CliCommand.Ui(UiCli.Action action)) {
            return UiCli.execute(action, parsed.configOverride(), runtime);
        }

        Result<Project, DomainError> projRes = runtime.projectLoader()
            .flatMap(loader -> loader.load(parsed.configOverride()));
        if (projRes instanceof Result.Err<Project, DomainError>(DomainError error)) {
            runtime.err().println("Error: " + error.format());
            return 1;
        }
        Project project = ((Result.Ok<Project, DomainError>) projRes).value();

        return executeCommand(cliCommand, project, runtime);
    }

    private static Result<CliCommand, String> parseCommand(String command, List<String> subArgs) {
        return switch (command) {
            case "route", "profile" -> RoutingCli.parse(command, subArgs).map(CliCommand.Routing::new);
            case "start", "stop", "inspect" -> ProcessCli.parse(command, subArgs).map(CliCommand.ProcessCommand::new);
            case "gateway" -> GatewayCli.parse(subArgs).map(CliCommand.Gateway::new);
            case "status" -> StatusCli.parse(subArgs).map(CliCommand.Status::new);
            case "logs" -> LogsCli.parse(subArgs).map(CliCommand.Logs::new);
            case "ui" -> UiCli.parse(subArgs).map(CliCommand.Ui::new);
            default -> Result.err("Unknown command '" + command + "'. Run 'procomp --help' for usage.");
        };
    }

    private static int executeCommand(CliCommand cliCommand, Project project, CliRuntime runtime) {
        return switch (cliCommand) {
            case CliCommand.Routing(RoutingCli.Action action) -> RoutingCli.execute(action, project, runtime);
            case CliCommand.ProcessCommand(ProcessCli.Action action) -> ProcessCli.execute(action, project, runtime);
            case CliCommand.Gateway(GatewayCli.Action action) -> GatewayCli.execute(action, project, runtime);
            case CliCommand.Status(StatusCli.Action action) -> StatusCli.execute(action, project, runtime);
            case CliCommand.Logs(LogsRequest request) -> LogsCli.execute(request, project, runtime);
            case CliCommand.Ui _ -> throw new IllegalStateException("Ui command must be dispatched via UiCli.execute");
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
              status [--json]                     Show estate Routing Table, Process ownership, Health, and Gateway
              inspect <service>                   Deep read-only Inspection of a Service's local Process (--json)
              logs <service>                      Read raw stdout/stderr logs (-n, -f, --err, --path)
              route [service... <local|remote>]   Show or update the Project's Routing Table
              profile <list|use <name>>           List or apply named routing Profiles
              gateway <start|stop|status|reconcile>
                                                  Manage the local Gateway and reconcile mappings
              ui [--port <n>] [--open]            Start the localhost Web UI (default 127.0.0.1:9090)""");
    }

    private record ParsedArgs(boolean help, Optional<Path> configOverride, List<String> commandArgs) {}
}
