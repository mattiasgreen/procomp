package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.shell.LogsShell;
import procomp.shell.LogsShell.LogsRequest;
import procomp.shell.LogsShell.LogsResult;
import procomp.shell.ports.Clock;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.ProgressEvent;
import procomp.shell.ports.StateStore;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI argument parser and output renderer for {@code procomp logs}.
 */
final class LogsCli {

    private LogsCli() {}

    static int run(
        Project project,
        List<String> args,
        StateStore stateStore,
        Clock clock,
        ProgressObserver observer,
        PrintWriter out,
        PrintWriter err
    ) {
        Result<LogsRequest, String> reqRes = parseArgs(args);
        if (reqRes instanceof Result.Err<LogsRequest, String>(String msg)) {
            err.println("Error: " + msg);
            return 2;
        }
        LogsRequest request = ((Result.Ok<LogsRequest, String>) reqRes).value();

        ProgressObserver cliObserver = event -> {
            if (event instanceof ProgressEvent.LogLine logLine) {
                out.print(logLine.line());
                out.flush();
            }
            observer.onEvent(event);
        };

        Result<LogsResult, DomainError> res = new LogsShell(stateStore, clock, cliObserver).run(project, request);
        return switch (res) {
            case Result.Ok<LogsResult, DomainError>(LogsResult.ResolvedPath(Path path)) -> {
                out.println(path);
                yield 0;
            }
            case Result.Ok<LogsResult, DomainError>(LogsResult.RawTail(_, String text)) -> {
                out.print(text);
                out.flush();
                yield 0;
            }
            case Result.Ok<LogsResult, DomainError>(LogsResult.Followed _) -> {
                out.flush();
                yield 0;
            }
            case Result.Err<LogsResult, DomainError>(DomainError error) -> {
                err.println("Error: " + error.format());
                yield 1;
            }
        };
    }

    private static Result<LogsRequest, String> parseArgs(List<String> args) {
        String serviceName = null;
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
                    Result<Integer, String> parsedLines = parseNonNegativeInt(args.get(++i));
                    if (parsedLines instanceof Result.Err<Integer, String>(String msg)) {
                        return Result.err(msg);
                    }
                    lines = ((Result.Ok<Integer, String>) parsedLines).value();
                }
                default -> {
                    if (arg.startsWith("--lines=")) {
                        Result<Integer, String> parsedLines = parseNonNegativeInt(arg.substring("--lines=".length()));
                        if (parsedLines instanceof Result.Err<Integer, String>(String msg)) {
                            return Result.err(msg);
                        }
                        lines = ((Result.Ok<Integer, String>) parsedLines).value();
                    } else if (arg.startsWith("-")) {
                        return Result.err(
                            "Unknown option '" + arg + "' for 'logs'. Supported options: -n <lines>, --lines <lines>, -f, --follow, --err, --path."
                        );
                    } else if (serviceName == null) {
                        if (arg.isBlank()) {
                            return Result.err("Service name must not be blank.");
                        }
                        serviceName = arg;
                    } else {
                        return Result.err(
                            "Unexpected extra argument '" + arg + "'. 'logs' accepts a single <service>."
                        );
                    }
                }
            }
        }

        if (serviceName == null) {
            return Result.err("Missing required <service> argument. Usage: procomp logs <service> [-n <lines>] [-f] [--err] [--path]");
        }
        return Result.ok(new LogsRequest(serviceName, stderr, pathOnly, lines, follow));
    }

    private static Result<Integer, String> parseNonNegativeInt(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Result.err("Invalid line count: value must be a non-negative integer.");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return Result.err("Invalid line count '" + raw + "': must be a non-negative integer.");
            }
        }
        try {
            return Result.ok(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return Result.err("Invalid line count '" + raw + "': integer overflow.");
        }
    }
}
