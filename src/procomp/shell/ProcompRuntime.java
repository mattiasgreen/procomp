package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.Clock;
import procomp.shell.ports.ConfigSource;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProjectFiles;
import procomp.shell.ports.StateStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Modality-neutral backend runtime holding configured {@code procomp.shell.ports}
 * and thread-safe lazy factories for each narrow {@code procomp.shell} use-case orchestrator.
 */
public final class ProcompRuntime {

    private final Optional<ConfigSource> configSource;
    private final Optional<StateStore> stateStore;
    private final Optional<ProcessRunner> processRunner;
    private final Optional<PortScanner> portScanner;
    private final Optional<HttpClient> httpClient;
    private final Optional<ProjectFiles> projectFiles;
    private final Optional<Clock> clock;
    private final ProgressObserver observer;

    private Optional<ProjectLoader> projectLoader = Optional.empty();
    private Optional<RoutingShell> routingShell = Optional.empty();
    private Optional<LogsShell> logsShell = Optional.empty();
    private Optional<ProcessShell> processShell = Optional.empty();
    private Optional<GatewayShell> gatewayShell = Optional.empty();
    private Optional<StatusShell> statusShell = Optional.empty();

    private ProcompRuntime(
        Optional<ConfigSource> configSource,
        Optional<StateStore> stateStore,
        Optional<ProcessRunner> processRunner,
        Optional<PortScanner> portScanner,
        Optional<HttpClient> httpClient,
        Optional<ProjectFiles> projectFiles,
        Optional<Clock> clock,
        ProgressObserver observer
    ) {
        this.configSource = configSource;
        this.stateStore = stateStore;
        this.processRunner = processRunner;
        this.portScanner = portScanner;
        this.httpClient = httpClient;
        this.projectFiles = projectFiles;
        this.clock = clock;
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a new {@code ProcompRuntime} sharing the same configured ports with a different
     * {@link ProgressObserver} (e.g., for per-request SSE streams or CLI stdout log streaming).
     */
    public ProcompRuntime withObserver(ProgressObserver newObserver) {
        Objects.requireNonNull(newObserver, "observer must not be null");
        return new ProcompRuntime(
            configSource,
            stateStore,
            processRunner,
            portScanner,
            httpClient,
            projectFiles,
            clock,
            newObserver
        );
    }

    public ProgressObserver observer() {
        return observer;
    }

    public synchronized Result<ProjectLoader, DomainError> projectLoader() {
        if (configSource.isEmpty()) {
            return Result.err(missingPorts("ProjectLoader", List.of("ConfigSource")));
        }
        if (projectLoader.isEmpty()) {
            projectLoader = Optional.of(new ProjectLoader(configSource.get()));
        }
        return Result.ok(projectLoader.get());
    }

    public synchronized Result<RoutingShell, DomainError> routingShell() {
        if (stateStore.isEmpty()) {
            return Result.err(missingPorts("RoutingShell", List.of("StateStore")));
        }
        if (routingShell.isEmpty()) {
            routingShell = Optional.of(new RoutingShell(stateStore.get()));
        }
        return Result.ok(routingShell.get());
    }

    public synchronized Result<LogsShell, DomainError> logsShell() {
        List<String> missing = new ArrayList<>();
        if (stateStore.isEmpty()) {
            missing.add("StateStore");
        }
        if (clock.isEmpty()) {
            missing.add("Clock");
        }
        if (!missing.isEmpty()) {
            return Result.err(missingPorts("LogsShell", missing));
        }
        if (logsShell.isEmpty()) {
            logsShell = Optional.of(new LogsShell(stateStore.get(), clock.get(), observer));
        }
        return Result.ok(logsShell.get());
    }

    public synchronized Result<ProcessShell, DomainError> processShell() {
        Optional<DomainError> missing = checkLifecyclePorts("ProcessShell");
        if (missing.isPresent()) {
            return Result.err(missing.get());
        }
        if (processShell.isEmpty()) {
            processShell = Optional.of(new ProcessShell(
                stateStore.get(),
                processRunner.get(),
                portScanner.get(),
                httpClient.get(),
                projectFiles.get(),
                clock.get(),
                observer
            ));
        }
        return Result.ok(processShell.get());
    }

    public synchronized Result<GatewayShell, DomainError> gatewayShell() {
        Optional<DomainError> missing = checkLifecyclePorts("GatewayShell");
        if (missing.isPresent()) {
            return Result.err(missing.get());
        }
        if (gatewayShell.isEmpty()) {
            gatewayShell = Optional.of(new GatewayShell(
                stateStore.get(),
                processRunner.get(),
                portScanner.get(),
                httpClient.get(),
                projectFiles.get(),
                clock.get(),
                observer
            ));
        }
        return Result.ok(gatewayShell.get());
    }

    public synchronized Result<StatusShell, DomainError> statusShell() {
        Optional<DomainError> missing = checkLifecyclePorts("StatusShell");
        if (missing.isPresent()) {
            return Result.err(missing.get());
        }
        if (statusShell.isEmpty()) {
            statusShell = Optional.of(new StatusShell(
                stateStore.get(),
                processRunner.get(),
                portScanner.get(),
                httpClient.get(),
                projectFiles.get(),
                clock.get(),
                observer
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
        if (clock.isEmpty()) {
            missing.add("Clock");
        }
        return missing.isEmpty() ? Optional.empty() : Optional.of(missingPorts(shellName, missing));
    }

    private static DomainError missingPorts(String shellName, List<String> missing) {
        return DomainError.of(
            "PORT_NOT_CONFIGURED",
            shellName + " requires configured port(s): " + String.join(", ", missing)
        );
    }

    public static final class Builder {

        private Optional<ConfigSource> configSource = Optional.empty();
        private Optional<StateStore> stateStore = Optional.empty();
        private Optional<ProcessRunner> processRunner = Optional.empty();
        private Optional<PortScanner> portScanner = Optional.empty();
        private Optional<HttpClient> httpClient = Optional.empty();
        private Optional<ProjectFiles> projectFiles = Optional.empty();
        private Optional<Clock> clock = Optional.empty();
        private ProgressObserver observer = ProgressObserver.noop();

        private Builder() {}

        public Builder configSource(ConfigSource configSource) {
            this.configSource = Optional.ofNullable(configSource);
            return this;
        }

        public Builder stateStore(StateStore stateStore) {
            this.stateStore = Optional.ofNullable(stateStore);
            return this;
        }

        public Builder processRunner(ProcessRunner processRunner) {
            this.processRunner = Optional.ofNullable(processRunner);
            return this;
        }

        public Builder portScanner(PortScanner portScanner) {
            this.portScanner = Optional.ofNullable(portScanner);
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Optional.ofNullable(httpClient);
            return this;
        }

        public Builder projectFiles(ProjectFiles projectFiles) {
            this.projectFiles = Optional.ofNullable(projectFiles);
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Optional.ofNullable(clock);
            return this;
        }

        public Builder observer(ProgressObserver observer) {
            this.observer = Objects.requireNonNull(observer, "observer must not be null");
            return this;
        }

        public ProcompRuntime build() {
            return new ProcompRuntime(
                configSource,
                stateStore,
                processRunner,
                portScanner,
                httpClient,
                projectFiles,
                clock,
                observer
            );
        }
    }
}
