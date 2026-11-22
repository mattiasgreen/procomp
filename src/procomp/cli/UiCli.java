package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ProjectLoader;
import procomp.shell.ProjectLoader.LoadedProject;
import procomp.web.LiveProjectHolder;
import procomp.web.StaticAssets;
import procomp.web.WebUi;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * CLI argument parser and foreground lifecycle runner for {@code procomp ui [--port <n>] [--open]}.
 */
public final class UiCli {

    public static final int DEFAULT_PORT = WebUi.DEFAULT_PORT;
    private static final String USAGE = "Usage: procomp ui [--port <n>] [--open]";

    public sealed interface Action permits Action.Serve {
        record Serve(int port, boolean openBrowser) implements Action {}
    }

    private UiCli() {}

    public static Result<Action, String> parse(List<String> args) {
        int port = DEFAULT_PORT;
        boolean openBrowser = false;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--open".equals(arg)) {
                openBrowser = true;
            } else if ("--port".equals(arg)) {
                if (i + 1 >= args.size()) {
                    return Result.err("Option '--port' requires a port number (0-65535). " + USAGE);
                }
                Result<Integer, String> parsedPort = parsePort(args.get(++i));
                if (parsedPort instanceof Result.Err<Integer, String>(String msg)) {
                    return Result.err(msg);
                }
                port = ((Result.Ok<Integer, String>) parsedPort).value();
            } else if (arg.startsWith("--port=")) {
                Result<Integer, String> parsedPort = parsePort(arg.substring("--port=".length()));
                if (parsedPort instanceof Result.Err<Integer, String>(String msg)) {
                    return Result.err(msg);
                }
                port = ((Result.Ok<Integer, String>) parsedPort).value();
            } else if (arg.startsWith("-")) {
                return Result.err("Unknown option '" + arg + "' for 'ui'. " + USAGE);
            } else {
                return Result.err("Unexpected argument '" + arg + "' for 'ui'. " + USAGE);
            }
        }

        return Result.ok(new Action.Serve(port, openBrowser));
    }

    static int execute(Action action, Optional<Path> configOverride, CliRuntime runtime) {
        Action.Serve serve = (Action.Serve) action;

        Result<ProjectLoader, DomainError> loaderRes = runtime.projectLoader();
        if (loaderRes instanceof Result.Err<ProjectLoader, DomainError>(DomainError error)) {
            runtime.err().println("Error: " + error.format());
            return 1;
        }
        ProjectLoader loader = ((Result.Ok<ProjectLoader, DomainError>) loaderRes).value();

        LiveProjectHolder projectHolder = new LiveProjectHolder(loader, configOverride);
        Result<LoadedProject, DomainError> initialLoad = projectHolder.current();
        if (initialLoad instanceof Result.Err<LoadedProject, DomainError>(DomainError error)) {
            runtime.err().println("Error: " + error.format());
            return 1;
        }

        Result<StaticAssets, DomainError> assetsRes = StaticAssets.discoverDefault();
        if (assetsRes instanceof Result.Err<StaticAssets, DomainError>(DomainError error)) {
            runtime.err().println("Error: " + error.format());
            return 1;
        }
        StaticAssets assets = ((Result.Ok<StaticAssets, DomainError>) assetsRes).value();

        WebUi webUi = new WebUi(runtime.runtime(), projectHolder, assets, serve.port());
        Result<Integer, DomainError> startRes = webUi.start();
        if (startRes instanceof Result.Err<Integer, DomainError>(DomainError error)) {
            runtime.err().println("Error: " + error.format());
            return 1;
        }

        String url = webUi.baseUrl();
        runtime.out().println(url);
        runtime.out().flush();

        if (serve.openBrowser()) {
            WebUi.openBrowser(url);
        }

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Thread shutdownHook = new Thread(() -> {
            webUi.stop();
            shutdownLatch.countDown();
        }, "procomp-ui-shutdown");

        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            webUi.stop();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown already in progress.
            }
        }
        return 0;
    }

    private static Result<Integer, String> parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.err("Invalid port '': must be an integer between 0 and 65535. " + USAGE);
        }
        String trimmed = raw.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') {
                return Result.err("Invalid port '" + raw + "': must be an integer between 0 and 65535. " + USAGE);
            }
        }
        try {
            int port = Integer.parseInt(trimmed);
            if (port < 0 || port > 65535) {
                return Result.err("Invalid port '" + raw + "': must be an integer between 0 and 65535. " + USAGE);
            }
            return Result.ok(port);
        } catch (NumberFormatException e) {
            return Result.err("Invalid port '" + raw + "': must be an integer between 0 and 65535. " + USAGE);
        }
    }
}
