package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.platform.FsConfigSource;
import procomp.platform.FsProjectFiles;
import procomp.platform.FsStateStore;
import procomp.platform.JdkHttpClient;
import procomp.platform.OsGitRepositoryReader;
import procomp.platform.OsPortScanner;
import procomp.platform.OsProcessRunner;
import procomp.platform.SystemClock;
import procomp.shell.BuildShell;
import procomp.shell.GatewayShell;
import procomp.shell.GitShell;
import procomp.shell.LogsShell;
import procomp.shell.ProcessShell;
import procomp.shell.ProcompRuntime;
import procomp.shell.ProjectLoader;
import procomp.shell.RoutingShell;
import procomp.shell.StatusShell;
import procomp.shell.ports.Clock;
import procomp.shell.ports.ConfigSource;
import procomp.shell.ports.GitRepositoryReader;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.ProgressEvent;
import procomp.shell.ports.ProjectFiles;
import procomp.shell.ports.StateStore;

import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * CLI runtime context wrapping {@link ProcompRuntime} with CLI output streams,
 * exit-code handling, and CLI stdout log streaming.
 */
public final class CliRuntime {

    private final ProcompRuntime runtime;
    private final ProcompRuntime logsRuntime;
    private final PrintWriter out;
    private final PrintWriter err;

    private CliRuntime(ProcompRuntime runtime, PrintWriter out, PrintWriter err) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        ProgressObserver delegateObserver = runtime.observer();
        ProgressObserver cliLogsObserver = new ProgressObserver() {
            @Override
            public boolean diagnosticsEnabled() {
                return delegateObserver.diagnosticsEnabled();
            }

            @Override
            public void onEvent(ProgressEvent event) {
                if (event instanceof ProgressEvent.LogLine logLine) {
                    out.print(logLine.line());
                    out.flush();
                }
                delegateObserver.onEvent(event);
            }
        };
        this.logsRuntime = runtime.withObserver(cliLogsObserver);
    }

    static CliRuntime system(PrintWriter out, PrintWriter err) {
        return builder(out, err)
            .configSource(new FsConfigSource())
            .stateStore(new FsStateStore())
            .processRunner(new OsProcessRunner())
            .portScanner(new OsPortScanner())
            .httpClient(new JdkHttpClient())
            .projectFiles(new FsProjectFiles())
            .gitRepositoryReader(new OsGitRepositoryReader())
            .clock(new SystemClock())
            .observer(ProgressObserver.noop())
            .build();
    }

    public static CliRuntime of(ProcompRuntime runtime, PrintWriter out, PrintWriter err) {
        return new CliRuntime(runtime, out, err);
    }

    static Builder builder(PrintWriter out, PrintWriter err) {
        return new Builder(out, err);
    }

    public ProcompRuntime runtime() {
        return runtime;
    }

    CliRuntime withVerbose() {
        Instant startedAt = runtime.clock().map(Clock::now).orElseGet(Instant::now);
        ProgressObserver delegate = runtime.observer();
        ProgressObserver verboseObserver = new ProgressObserver() {
            @Override
            public boolean diagnosticsEnabled() {
                return true;
            }

            @Override
            public void onEvent(ProgressEvent event) {
                switch (event) {
                    case ProgressEvent.Diagnostic(ProgressObserver.DiagnosticCategory category, String message) -> {
                        printVerboseLine(startedAt, category.label(), message);
                        if (delegate.diagnosticsEnabled()) {
                            delegate.onEvent(event);
                        }
                    }
                    case ProgressEvent.StatusMessage(String target, String message) -> {
                        printVerboseLine(startedAt, target, message);
                        delegate.onEvent(event);
                    }
                    case ProgressEvent.LogLine _ -> delegate.onEvent(event);
                }
            }
        };
        return new CliRuntime(runtime.withObserver(verboseObserver), out, err);
    }

    private void printVerboseLine(Instant startedAt, String category, String message) {
        Instant now = runtime.clock().map(Clock::now).orElseGet(Instant::now);
        long elapsedMs = Math.max(0L, Duration.between(startedAt, now).toMillis());
        String formatted = String.format(Locale.ROOT, "[+%.3fs] [%s] %s", elapsedMs / 1000.0, category, message);
        synchronized (err) {
            err.println(formatted);
            err.flush();
        }
    }

    PrintWriter out() {
        return out;
    }

    PrintWriter err() {
        return err;
    }

    <T> int handleResult(Result<T, DomainError> res, Consumer<T> onSuccess) {
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

    Result<ProjectLoader, DomainError> projectLoader() {
        return runtime.projectLoader();
    }

    Result<RoutingShell, DomainError> routingShell() {
        return runtime.routingShell();
    }

    Result<LogsShell, DomainError> logsShell() {
        return logsRuntime.logsShell();
    }

    Result<BuildShell, DomainError> buildShell() {
        return runtime.buildShell();
    }

    Result<GitShell, DomainError> gitShell() {
        return runtime.gitShell();
    }

    Result<ProcessShell, DomainError> processShell() {
        return runtime.processShell();
    }

    Result<GatewayShell, DomainError> gatewayShell() {
        return runtime.gatewayShell();
    }

    Result<StatusShell, DomainError> statusShell() {
        return runtime.statusShell();
    }

    static final class Builder {

        private final PrintWriter out;
        private final PrintWriter err;
        private final ProcompRuntime.Builder runtimeBuilder = ProcompRuntime.builder()
            .clock(new SystemClock())
            .observer(ProgressObserver.noop());

        private Builder(PrintWriter out, PrintWriter err) {
            this.out = Objects.requireNonNull(out, "out");
            this.err = Objects.requireNonNull(err, "err");
        }

        Builder configSource(ConfigSource configSource) {
            runtimeBuilder.configSource(configSource);
            return this;
        }

        Builder stateStore(StateStore stateStore) {
            runtimeBuilder.stateStore(stateStore);
            return this;
        }

        Builder processRunner(ProcessRunner processRunner) {
            runtimeBuilder.processRunner(processRunner);
            return this;
        }

        Builder portScanner(PortScanner portScanner) {
            runtimeBuilder.portScanner(portScanner);
            return this;
        }

        Builder httpClient(HttpClient httpClient) {
            runtimeBuilder.httpClient(httpClient);
            return this;
        }

        Builder projectFiles(ProjectFiles projectFiles) {
            runtimeBuilder.projectFiles(projectFiles);
            return this;
        }

        Builder gitRepositoryReader(GitRepositoryReader gitRepositoryReader) {
            runtimeBuilder.gitRepositoryReader(gitRepositoryReader);
            return this;
        }

        Builder clock(Clock clock) {
            runtimeBuilder.clock(Objects.requireNonNull(clock, "clock"));
            return this;
        }

        Builder observer(ProgressObserver observer) {
            runtimeBuilder.observer(Objects.requireNonNull(observer, "observer"));
            return this;
        }

        CliRuntime build() {
            return new CliRuntime(runtimeBuilder.build(), out, err);
        }
    }
}
