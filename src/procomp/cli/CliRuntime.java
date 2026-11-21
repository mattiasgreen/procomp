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

    private final ConfigSource configSource;
    private final StateStore stateStore;
    private final ProcessRunner processRunner;
    private final PortScanner portScanner;
    private final HttpClient httpClient;
    private final ProjectFiles projectFiles;
    private final Clock clock;
    private final ProgressObserver observer;
    private final PrintWriter out;
    private final PrintWriter err;

    private ProjectLoader projectLoader;
    private RoutingShell routingShell;
    private LogsShell logsShell;
    private ProcessShell processShell;
    private GatewayShell gatewayShell;
    private StatusShell statusShell;

    private CliRuntime(Builder builder) {
        this.configSource = builder.configSource;
        this.stateStore = builder.stateStore;
        this.processRunner = builder.processRunner;
        this.portScanner = builder.portScanner;
        this.httpClient = builder.httpClient;
        this.projectFiles = builder.projectFiles;
        this.clock = builder.clock != null ? builder.clock : new SystemClock();
        this.observer = builder.observer != null ? builder.observer : ProgressObserver.noop();
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
        if (configSource == null) {
            return Result.err(missingPorts("ProjectLoader", List.of("ConfigSource")));
        }
        if (projectLoader == null) {
            projectLoader = new ProjectLoader(configSource);
        }
        return Result.ok(projectLoader);
    }

    Result<RoutingShell, DomainError> routingShell() {
        if (stateStore == null) {
            return Result.err(missingPorts("RoutingShell", List.of("StateStore")));
        }
        if (routingShell == null) {
            routingShell = new RoutingShell(stateStore);
        }
        return Result.ok(routingShell);
    }

    Result<LogsShell, DomainError> logsShell() {
        if (stateStore == null) {
            return Result.err(missingPorts("LogsShell", List.of("StateStore")));
        }
        if (logsShell == null) {
            ProgressObserver cliObserver = event -> {
                if (event instanceof ProgressEvent.LogLine logLine) {
                    out.print(logLine.line());
                    out.flush();
                }
                observer.onEvent(event);
            };
            logsShell = new LogsShell(stateStore, clock, cliObserver);
        }
        return Result.ok(logsShell);
    }

    Result<ProcessShell, DomainError> processShell() {
        Optional<DomainError> missing = checkLifecyclePorts("ProcessShell");
        if (missing.isPresent()) {
            return Result.err(missing.get());
        }
        if (processShell == null) {
            processShell = new ProcessShell(
                stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer
            );
        }
        return Result.ok(processShell);
    }

    Result<GatewayShell, DomainError> gatewayShell() {
        Optional<DomainError> missing = checkLifecyclePorts("GatewayShell");
        if (missing.isPresent()) {
            return Result.err(missing.get());
        }
        if (gatewayShell == null) {
            gatewayShell = new GatewayShell(
                stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer
            );
        }
        return Result.ok(gatewayShell);
    }

    Result<StatusShell, DomainError> statusShell() {
        Optional<DomainError> missing = checkLifecyclePorts("StatusShell");
        if (missing.isPresent()) {
            return Result.err(missing.get());
        }
        if (statusShell == null) {
            statusShell = new StatusShell(
                stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer
            );
        }
        return Result.ok(statusShell);
    }

    private Optional<DomainError> checkLifecyclePorts(String shellName) {
        List<String> missing = new ArrayList<>();
        if (stateStore == null) {
            missing.add("StateStore");
        }
        if (processRunner == null) {
            missing.add("ProcessRunner");
        }
        if (portScanner == null) {
            missing.add("PortScanner");
        }
        if (httpClient == null) {
            missing.add("HttpClient");
        }
        if (projectFiles == null) {
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
        private ConfigSource configSource;
        private StateStore stateStore;
        private ProcessRunner processRunner;
        private PortScanner portScanner;
        private HttpClient httpClient;
        private ProjectFiles projectFiles;
        private Clock clock;
        private ProgressObserver observer;

        private Builder(PrintWriter out, PrintWriter err) {
            this.out = Objects.requireNonNull(out, "out");
            this.err = Objects.requireNonNull(err, "err");
        }

        Builder configSource(ConfigSource configSource) {
            this.configSource = configSource;
            return this;
        }

        Builder stateStore(StateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        Builder processRunner(ProcessRunner processRunner) {
            this.processRunner = processRunner;
            return this;
        }

        Builder portScanner(PortScanner portScanner) {
            this.portScanner = portScanner;
            return this;
        }

        Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        Builder projectFiles(ProjectFiles projectFiles) {
            this.projectFiles = projectFiles;
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        Builder observer(ProgressObserver observer) {
            this.observer = observer;
            return this;
        }

        CliRuntime build() {
            return new CliRuntime(this);
        }
    }
}
