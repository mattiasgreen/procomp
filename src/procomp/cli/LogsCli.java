package procomp.cli;

import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.shell.LogsShell.LogsRequest;
import procomp.shell.LogsShell.LogsResult;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI argument parser and output renderer for {@code procomp logs}.
 */
final class LogsCli {

    private LogsCli() {}

    static Result<LogsRequest, String> parse(List<String> args) {
        return parseArgs(args);
    }

    static int execute(LogsRequest request, Project project, CliRuntime runtime) {
        return runtime.handleResult(
            runtime.logsShell().flatMap(shell -> shell.run(project, request)),
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
    }

    private static Result<LogsRequest, String> parseArgs(List<String> args) {
        String serviceName = null;
        boolean stderr = false;
        boolean pathOnly = false;
        int lines = LogsRequest.DEFAULT_LINES;
        boolean follow = false;
        boolean build = false;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--err" -> stderr = true;
                case "--path" -> pathOnly = true;
                case "--build" -> build = true;
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
                            "Unknown option '" + arg + "' for 'logs'. Supported options: -n <lines>, --lines <lines>, -f, --follow, --err, --path, --build."
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
            return Result.err("Missing required <service> argument. Usage: procomp logs <service> [-n <lines>] [-f] [--err] [--path] [--build]");
        }
        return Result.ok(new LogsRequest(serviceName, stderr, pathOnly, lines, follow, build));
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
