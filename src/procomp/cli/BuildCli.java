package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.shell.BuildShell.BuildRequest;
import procomp.shell.BuildShell.BuildResult;
import procomp.shell.BuildShell.BuildServiceOutcome;
import procomp.shell.BuildShell.CancelResult;
import procomp.shell.DomainJson;
import procomp.shell.ProcompRuntime;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.ProgressEvent;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * CLI argument parser and output renderer for {@code procomp build}.
 */
public final class BuildCli {

    private static final String USAGE =
        "Usage: procomp build [service...] [--force] [--cancel] [--timeout <seconds>] [--json]";

    public sealed interface Action permits Action.Build, Action.Cancel {
        record Build(List<String> services, boolean force, Optional<Duration> timeout, boolean json) implements Action {}
        record Cancel(List<String> services, boolean json) implements Action {}
    }

    private BuildCli() {}

    public static Result<Action, String> parse(List<String> args) {
        boolean force = false;
        boolean cancel = false;
        boolean json = false;
        Optional<Duration> timeout = Optional.empty();
        List<String> services = new ArrayList<>();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--force".equals(arg)) {
                force = true;
            } else if ("--cancel".equals(arg)) {
                cancel = true;
            } else if ("--json".equals(arg)) {
                json = true;
            } else if (CliFormat.isTimeoutOption(arg)) {
                Result<CliFormat.ParsedOption<Duration>, String> tRes = CliFormat.parseTimeoutOption(args, i);
                if (tRes instanceof Result.Err<CliFormat.ParsedOption<Duration>, String>(String msg)) {
                    return Result.err(msg);
                }
                var opt = ((Result.Ok<CliFormat.ParsedOption<Duration>, String>) tRes).value();
                timeout = Optional.of(opt.value());
                i = opt.nextIndex();
            } else if (arg.startsWith("-")) {
                return Result.err("Unknown option '" + arg + "' for 'build'. " + USAGE);
            } else {
                if (arg.isBlank()) {
                    return Result.err("Service name must not be blank.");
                }
                services.add(arg);
            }
        }

        if (force && cancel) {
            return Result.err("Options '--force' and '--cancel' are mutually exclusive. " + USAGE);
        }
        if (cancel && timeout.isPresent()) {
            return Result.err("Option '--timeout' cannot be combined with '--cancel'. " + USAGE);
        }
        if (cancel && services.isEmpty()) {
            return Result.err("Option '--cancel' requires one or more explicit <service> arguments. " + USAGE);
        }
        if (cancel) {
            return Result.ok(new Action.Cancel(List.copyOf(services), json));
        }
        return Result.ok(new Action.Build(List.copyOf(services), force, timeout, json));
    }

    static int execute(Action action, Project project, CliRuntime runtime) {
        return switch (action) {
            case Action.Build(List<String> services, boolean force, Optional<Duration> timeout, boolean json) ->
                executeBuild(services, force, timeout, json, project, runtime);
            case Action.Cancel(List<String> services, boolean json) ->
                executeCancel(services, json, project, runtime);
        };
    }

    private static int executeBuild(
        List<String> services,
        boolean force,
        Optional<Duration> timeout,
        boolean json,
        Project project,
        CliRuntime runtime
    ) {
        Thread callerThread = Thread.currentThread();
        CountDownLatch finishedLatch = new CountDownLatch(1);
        Thread shutdownHook = new Thread(() -> {
            callerThread.interrupt();
            try {
                finishedLatch.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "procomp-build-interrupt");
        boolean hookRegistered = false;
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            hookRegistered = true;
        } catch (IllegalStateException | SecurityException ignored) {
            // Shutdown already in progress or restricted environment
        }
        try {
            ProcompRuntime effective = json
                ? runtime.runtime()
                : runtime.runtime().withObserver(liveProgressObserver(runtime));
            return runtime.handleResult(
                effective.buildShell().flatMap(shell -> shell.build(project, new BuildRequest(services, force, timeout))),
                result -> {
                    if (json) {
                        runtime.out().println(Json.stringify(DomainJson.buildResult(project, result)));
                    } else {
                        renderBuildText(result, runtime.out());
                    }
                }
            );
        } finally {
            finishedLatch.countDown();
            if (hookRegistered) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM is shutting down
                }
            }
        }
    }

    private static ProgressObserver liveProgressObserver(CliRuntime runtime) {
        ProgressObserver delegate = runtime.runtime().observer();
        return new ProgressObserver() {
            @Override
            public boolean diagnosticsEnabled() {
                return delegate.diagnosticsEnabled();
            }

            @Override
            public void onEvent(ProgressEvent event) {
                if (event instanceof ProgressEvent.StatusMessage(_, String message)) {
                    runtime.out().println(message);
                    runtime.out().flush();
                }
                delegate.onEvent(event);
            }
        };
    }

    private static int executeCancel(List<String> services, boolean json, Project project, CliRuntime runtime) {
        Result<procomp.shell.BuildShell, DomainError> shellRes = runtime.buildShell();
        if (shellRes instanceof Result.Err<procomp.shell.BuildShell, DomainError>(DomainError err)) {
            runtime.err().println("Error: " + err.format());
            return 1;
        }
        procomp.shell.BuildShell shell = ((Result.Ok<procomp.shell.BuildShell, DomainError>) shellRes).value();

        List<CancelResult> results = new ArrayList<>(services.size());
        for (String svc : services) {
            Result<CancelResult, DomainError> res = shell.cancel(project, svc);
            if (res instanceof Result.Err<CancelResult, DomainError>(DomainError err)) {
                runtime.err().println("Error: " + err.format());
                return 1;
            }
            results.add(((Result.Ok<CancelResult, DomainError>) res).value());
        }

        if (json) {
            List<JsonValue> nodes = new ArrayList<>(results.size());
            for (CancelResult item : results) {
                nodes.add(DomainJson.cancelResult(project, item));
            }
            Map<String, JsonValue> root = new LinkedHashMap<>();
            root.put("project", Json.str(project.name()));
            root.put("results", Json.arr(nodes));
            runtime.out().println(Json.stringify(Json.obj(root)));
        } else {
            for (CancelResult item : results) {
                switch (item.disposition()) {
                    case CANCELLED -> runtime.out().println(item.service() + ": cancelled active Build Run");
                    case CLEARED_UNKNOWN -> runtime.out().println(item.service() + ": cleared UNKNOWN Build Run");
                    case NOT_RUNNING -> runtime.out().println(item.service() + ": no active Build Run to cancel");
                }
            }
        }
        return 0;
    }

    private static void renderBuildText(BuildResult result, PrintWriter out) {
        if (result.outcomes().isEmpty()) {
            out.println("No LOCAL-routed Services with a configured Builder — nothing to build.");
            return;
        }
        for (BuildServiceOutcome item : result.outcomes()) {
            String elapsed = CliFormat.formatDuration(item.duration());
            out.println(item.service() + ": built with '" + item.builder() + "' in " + elapsed);
        }
        out.println("Summary: " + result.outcomes().size() + " built.");
    }
}
