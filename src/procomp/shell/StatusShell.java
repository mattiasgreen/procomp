package procomp.shell;

import procomp.core.build.BuildDisplayState;
import procomp.core.build.ObservedBuildRun;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.process.DiscoveryResult;
import procomp.core.process.HealthState;
import procomp.core.process.Ownership;
import procomp.core.project.Project;
import procomp.core.project.ServiceDefinition;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.GatewayShell.GatewayStatusReport;
import procomp.shell.ports.Clock;
import procomp.shell.ports.HttpClient;
import procomp.shell.ports.PortScanner;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProjectFiles;
import procomp.shell.ports.StateStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Modality-agnostic orchestrator for read-only unified estate status.
 * Composes {@link RoutingShell}, {@link ProcessShell}, and {@link GatewayShell} strictly inside {@code shell/}.
 */
public final class StatusShell {

    private final RoutingShell routingShell;
    private final ProcessShell processShell;
    private final GatewayShell gatewayShell;
    private final Optional<BuildShell> buildShell;
    private final Clock clock;

    public StatusShell(
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        ProjectFiles projectFiles,
        Clock clock
    ) {
        this(stateStore, processRunner, portScanner, httpClient, projectFiles, clock, ProgressObserver.noop());
    }

    public StatusShell(
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        ProjectFiles projectFiles,
        Clock clock,
        ProgressObserver observer
    ) {
        this(
            new RoutingShell(stateStore),
            new ProcessLifecycle(stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer),
            new BuildShell(stateStore, processRunner, projectFiles, clock, observer),
            httpClient,
            projectFiles,
            clock,
            observer
        );
    }

    private StatusShell(
        RoutingShell routingShell,
        ProcessLifecycle lifecycle,
        BuildShell buildShell,
        HttpClient httpClient,
        ProjectFiles projectFiles,
        Clock clock,
        ProgressObserver observer
    ) {
        this(
            routingShell,
            new GatewayShell(httpClient, projectFiles, clock, observer, routingShell, lifecycle),
            buildShell,
            projectFiles,
            lifecycle,
            clock
        );
    }

    private StatusShell(
        RoutingShell routingShell,
        GatewayShell gatewayShell,
        BuildShell buildShell,
        ProjectFiles projectFiles,
        ProcessLifecycle lifecycle,
        Clock clock
    ) {
        this(
            routingShell,
            new ProcessShell(projectFiles, routingShell, gatewayShell, lifecycle),
            gatewayShell,
            Optional.of(buildShell),
            clock
        );
    }

    public StatusShell(
        RoutingShell routingShell,
        ProcessShell processShell,
        GatewayShell gatewayShell,
        Clock clock
    ) {
        this(routingShell, processShell, gatewayShell, Optional.empty(), clock);
    }

    private StatusShell(
        RoutingShell routingShell,
        ProcessShell processShell,
        GatewayShell gatewayShell,
        Optional<BuildShell> buildShell,
        Clock clock
    ) {
        this.routingShell = Objects.requireNonNull(routingShell, "routingShell must not be null");
        this.processShell = Objects.requireNonNull(processShell, "processShell must not be null");
        this.gatewayShell = Objects.requireNonNull(gatewayShell, "gatewayShell must not be null");
        this.buildShell = Objects.requireNonNull(buildShell, "buildShell must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Collects a read-only snapshot of the Project's Routing Table, per-Service Process Discovery,
     * and Gateway status/drift (cleanly representing an unconfigured Gateway without error).
     */
    public Result<EstateStatus, DomainError> status(Project project) {
        Objects.requireNonNull(project, "project must not be null");

        Result<RoutingTable, DomainError> routingRes = routingShell.showRoutes(project);
        if (routingRes instanceof Result.Err<RoutingTable, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        RoutingTable routing = ((Result.Ok<RoutingTable, DomainError>) routingRes).value();

        Instant now = clock.now();
        List<ServiceStatusRow> rows = new ArrayList<>(project.services().size());
        for (ServiceDefinition service : project.services().values()) {
            Result<DiscoveryResult, DomainError> discRes = processShell.discoverService(project, service.name());
            if (discRes instanceof Result.Err<DiscoveryResult, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            DiscoveryResult disc = ((Result.Ok<DiscoveryResult, DomainError>) discRes).value();
            Route route = routing.routeOf(service.name()).orElse(Route.REMOTE);
            Optional<String> healthNote = disc.healthResponse().isBlank()
                ? Optional.empty()
                : Optional.of(disc.healthResponse());

            ObservedBuildRun buildRun;
            if (buildShell.isPresent()) {
                Result<ObservedBuildRun, DomainError> bRes = buildShell.get().inspectBuild(project, service);
                if (bRes instanceof Result.Err<ObservedBuildRun, DomainError>(DomainError err)) {
                    return Result.err(err);
                }
                buildRun = ((Result.Ok<ObservedBuildRun, DomainError>) bRes).value();
            } else {
                BuildDisplayState fallback = service.buildConfig().isPresent()
                    ? BuildDisplayState.NOT_BUILT
                    : BuildDisplayState.NO_BUILDER;
                buildRun = new ObservedBuildRun(fallback, Optional.empty(), Optional.empty());
            }

            rows.add(new ServiceStatusRow(
                service.name(),
                route,
                disc.ownership(),
                disc.provenance(),
                disc.health(),
                service.port(),
                disc.pid(),
                disc.startedAt(),
                disc.uptime(now),
                disc.stale(),
                disc.healthEndpoint(),
                healthNote,
                buildRun
            ));
        }

        GatewaySummary gatewaySummary = GatewaySummary.notConfigured();
        if (project.gateway().isPresent()) {
            Result<GatewayStatusReport, DomainError> gwRes = gatewayShell.status(project, routing);
            if (gwRes instanceof Result.Err<GatewayStatusReport, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            gatewaySummary = GatewaySummary.of(((Result.Ok<GatewayStatusReport, DomainError>) gwRes).value());
        }

        return Result.ok(new EstateStatus(project.name(), gatewaySummary, rows));
    }

    public record EstateStatus(
        String project,
        GatewaySummary gateway,
        List<ServiceStatusRow> services
    ) {
        public EstateStatus {
            if (project == null || project.isBlank()) {
                throw new IllegalArgumentException("project must be non-blank");
            }
            Objects.requireNonNull(gateway, "gateway must not be null");
            services = List.copyOf(services);
        }
    }

    public record GatewaySummary(Optional<GatewayStatusReport> report) {
        public GatewaySummary {
            Objects.requireNonNull(report, "report must not be null");
        }

        public static GatewaySummary notConfigured() {
            return new GatewaySummary(Optional.empty());
        }

        public static GatewaySummary of(GatewayStatusReport report) {
            return new GatewaySummary(Optional.of(Objects.requireNonNull(report, "report must not be null")));
        }

        public boolean configured() {
            return report.isPresent();
        }
    }

    public record ServiceStatusRow(
        String service,
        Route route,
        Ownership ownership,
        Optional<String> provenance,
        HealthState health,
        Optional<Integer> port,
        Optional<Long> pid,
        Optional<Instant> startedAt,
        Optional<Duration> uptime,
        boolean stale,
        Optional<String> healthEndpoint,
        Optional<String> healthNote,
        ObservedBuildRun build
    ) {
        public ServiceStatusRow(
            String service,
            Route route,
            Ownership ownership,
            Optional<String> provenance,
            HealthState health,
            Optional<Integer> port,
            Optional<Long> pid,
            Optional<Instant> startedAt,
            Optional<Duration> uptime,
            boolean stale,
            Optional<String> healthEndpoint,
            Optional<String> healthNote
        ) {
            this(
                service, route, ownership, provenance, health, port, pid, startedAt, uptime, stale,
                healthEndpoint, healthNote,
                new ObservedBuildRun(BuildDisplayState.NO_BUILDER, Optional.empty(), Optional.empty())
            );
        }

        public ServiceStatusRow {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("service must be non-blank");
            }
            Objects.requireNonNull(route, "route must not be null");
            Objects.requireNonNull(ownership, "ownership must not be null");
            Objects.requireNonNull(provenance, "provenance must not be null");
            Objects.requireNonNull(health, "health must not be null");
            Objects.requireNonNull(port, "port must not be null");
            Objects.requireNonNull(pid, "pid must not be null");
            Objects.requireNonNull(startedAt, "startedAt must not be null");
            Objects.requireNonNull(uptime, "uptime must not be null");
            Objects.requireNonNull(healthEndpoint, "healthEndpoint must not be null");
            Objects.requireNonNull(healthNote, "healthNote must not be null");
            Objects.requireNonNull(build, "build must not be null");
        }
    }
}
