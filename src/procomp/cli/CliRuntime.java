package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.platform.FsConfigSource;
import procomp.platform.FsProjectFiles;
import procomp.platform.FsStateStore;
import procomp.platform.JdkHttpClient;
import procomp.platform.OsPortScanner;
import procomp.platform.OsProcessRunner;
import procomp.platform.SystemClock;
import procomp.shell.GatewayShell;
import procomp.shell.LogsShell;
import procomp.shell.ProcessShell;
import procomp.shell.ProjectLoader;
import procomp.shell.RoutingShell;
import procomp.shell.StatusShell;
import procomp.shell.ports.Clock;
import procomp.shell.ports.ConfigSource;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.ProgressEvent;
import procomp.shell.ports.ProjectFiles;
import procomp.shell.ports.StateStore;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Package-private CLI runtime context holding configured I/O ports, output streams,
 * and lazy factories for each narrow {@code procomp.shell} use-case orchestrator.
 */
final class CliRuntime {

    private final Optional<ConfigSource> configSource;
    private final Optional<StateStore> stateStore;
    private final Optional<ProcessRunner> processRunner;
    private final Optional<PortScanner> portScanner;
    private final Optional<HttpClient> httpClient;
    private final Optional<ProjectFiles> projectFiles;
    private final Clock clock;
    private final ProgressObserver observer;
    private final PrintWriter out;
    private final PrintWriter err;

    private Optional<ProjectLoader> projectLoader = Optional.empty();
    private Optional<RoutingShell> routingShell = Optional.empty();
    private Optional<LogsShell> logsShell = Optional.empty();
    private Optional<ProcessShell> processShell = Optional.empty();
    private Optional<GatewayShell> gatewayShell = Optional.empty();
    private Optional<StatusShell> statusShell = Optional.empty();

    private CliRuntime(Builder builder) {
        this.configSource = builder.configSource;
        this.stateStore = builder.stateStore;
        this.processRunner = builder.processRunner;
        this.portScanner = builder.portScanner;
        this.httpClient = builder.httpClient;
        this.projectFiles = builder.projectFiles;
        this.clock = builder.clock;
        this.observer = builder.observer;
        this.out = builder.out;
        this.err = builder.err;
    }

    static CliRuntime system(PrintWriter out, PrintWriter err) {
        return builder(out, err)
            .configSource(new FsConfigSource())
            .stateStore(new FsStateStore())
            .processRunner(new OsProcessRunner())
            .portScanner(new OsPortScanner())
            .httpClient(new JdkHttpClient())
            .projectFiles(new FsProjectFiles())
            .clock(new SystemClock())
            .observer(ProgressObserver.noop())
            .build();
    }

    static Builder builder(PrintWriter out, PrintWriter err) {
        return new Builder(out, err);
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
        if (configSource.isEmpty()) {
            return Result.err(missingPorts("ProjectLoader", List.of("ConfigSource")));
        }
        if (projectLoader.isEmpty()) {
            projectLoader = Optional.of(new ProjectLoader(configSource.get()));
        }
        return Result.ok(projectLoader.get());
    }

    Result<RoutingShell, DomainError> routingShell() {
        if (stateStore.isEmpty()) {
            return Result.err(missingPorts("RoutingShell", List.of("StateStore")));
        }
        if (routingShell.isEmpty()) {
            routingShell = Optional.of(new RoutingShell(stateStore.get()));
        }
        return Result.ok(routingShell.get());
    }

    Result<LogsShell, DomainError> logsShell() {
        if (stateStore.isEmpty()) {
            return Result.err(missingPorts("LogsShell", List.of("StateStore")));
        }
        if (logsShell.isEmpty()) {
            ProgressObserver cliObserver = event -> {
                if (event instanceof ProgressEvent.LogLine logLine) {
                    out.print(logLine.line());
                    out.flush();
                }
                observer.onEvent(event);
            };
            logsShell = Optional.of(new LogsShell(stateStore.get(), clock, cliObserver));
        }
        return Result.ok(logsShell.get());
    }

    Result<ProcessShell, DomainError> processShell() {
        Optional<DomainError> missing = checkLifecyclePorts("ProcessShell");
        if (missing.isPresent()) {
            return Result.err(missing.get());
        }
        if (processShell.isEmpty()) {
            processShell = Optional.of(new ProcessShell(
                stateStore.get(), processRunner.get(), portScanner.get(), httpClient.get(), projectFiles.get(), clock, observer
            ));
        }
        return Result.ok(processShell.get());
    }

    Result<GatewayShell, DomainError> gatewayShell() {
        Optional<DomainError> missing = checkLifecyclePorts("GatewayShell");
        if (missing.isPresent()) {
            return Result.err(missing.get());
        }
        if (gatewayShell.isEmpty()) {
            gatewayShell = Optional.of(new GatewayShell(
                stateStore.get(), processRunner.get(), portScanner.get(), httpClient.get(), projectFiles.get(), clock, observer
            ));
        }
        return Result.ok(gatewayShell.get());
    }

    Result<StatusShell, DomainError> statusShell() {
        Optional<DomainError> missing = checkLifecyclePorts("StatusShell");
        if (missing.isPresent()) {
            return Result.err(missing.get());
        }
        if (statusShell.isEmpty()) {
            statusShell = Optional.of(new StatusShell(
                stateStore.get(), processRunner.get(), portScanner.get(), httpClient.get(), projectFiles.get(), clock, observer
            ));
        }
        return Result.ok(statusShell.get());
    }

    private Optional<DomainError> checkLifecyclePorts(String shellName) {
        List<String> missing = new ArrayList<>();
        if (stateStore.isEmpty()) {
            missing.add("StateStore");
        }
        if (processRunner.isEmpty()) {
            missing.add("ProcessRunner");
        }
        if (portScanner.isEmpty()) {
            missing.add("PortScanner");
        }
        if (httpClient.isEmpty()) {
            missing.add("HttpClient");
        }
        if (projectFiles.isEmpty()) {
            missing.add("ProjectFiles");
        }
        return missing.isEmpty() ? Optional.empty() : Optional.of(missingPorts(shellName, missing));
    }

    private static DomainError missingPorts(String shellName, List<String> missing) {
        return DomainError.of(
            "PORT_NOT_CONFIGURED",
            shellName + " requires configured port(s): " + String.join(", ", missing)
        );
    }

    static final class Builder {

        private final PrintWriter out;
        private final PrintWriter err;
        private Optional<ConfigSource> configSource = Optional.empty();
        private Optional<StateStore> stateStore = Optional.empty();
        private Optional<ProcessRunner> processRunner = Optional.empty();
        private Optional<PortScanner> portScanner = Optional.empty();
        private Optional<HttpClient> httpClient = Optional.empty();
        private Optional<ProjectFiles> projectFiles = Optional.empty();
        private Clock clock = new SystemClock();
        private ProgressObserver observer = ProgressObserver.noop();

        private Builder(PrintWriter out, PrintWriter err) {
            this.out = Objects.requireNonNull(out, "out");
            this.err = Objects.requireNonNull(err, "err");
        }

        Builder configSource(ConfigSource configSource) {
            this.configSource = Optional.ofNullable(configSource);
            return this;
        }

        Builder stateStore(StateStore stateStore) {
            this.stateStore = Optional.ofNullable(stateStore);
            return this;
        }

        Builder processRunner(ProcessRunner processRunner) {
            this.processRunner = Optional.ofNullable(processRunner);
            return this;
        }

        Builder portScanner(PortScanner portScanner) {
            this.portScanner = Optional.ofNullable(portScanner);
            return this;
        }

        Builder httpClient(HttpClient httpClient) {
            this.httpClient = Optional.ofNullable(httpClient);
            return this;
        }

        Builder projectFiles(ProjectFiles projectFiles) {
            this.projectFiles = Optional.ofNullable(projectFiles);
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        Builder observer(ProgressObserver observer) {
            this.observer = Objects.requireNonNull(observer, "observer");
            return this;
        }

        CliRuntime build() {
            return new CliRuntime(this);
        }
    }
}
