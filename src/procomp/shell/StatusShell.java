package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.gateway.GatewayDriftReport;
import procomp.core.gateway.GatewaySyncStatus;
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
import procomp.shell.ports.StateStore;
import procomp.shell.ports.WorkspaceFiles;

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
    private final Clock clock;

    public StatusShell(
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        WorkspaceFiles workspaceFiles,
        Clock clock
    ) {
        this(stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, ProgressObserver.noop());
    }

    public StatusShell(
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        WorkspaceFiles workspaceFiles,
        Clock clock,
        ProgressObserver observer
    ) {
        this(
            new RoutingShell(stateStore),
            new ProcessShell(stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, observer),
            new GatewayShell(stateStore, processRunner, portScanner, httpClient, workspaceFiles, clock, observer),
            clock
        );
    }

    public StatusShell(
        RoutingShell routingShell,
        ProcessShell processShell,
        GatewayShell gatewayShell,
        Clock clock
    ) {
        this.routingShell = Objects.requireNonNull(routingShell, "routingShell must not be null");
        this.processShell = Objects.requireNonNull(processShell, "processShell must not be null");
        this.gatewayShell = Objects.requireNonNull(gatewayShell, "gatewayShell must not be null");
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
                healthNote
            ));
        }

        GatewaySummary gatewaySummary = GatewaySummary.notConfigured();
        if (project.gateway().isPresent()) {
            Result<GatewayStatusReport, DomainError> gwRes = gatewayShell.status(project);
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

    public record GatewaySummary(
        boolean configured,
        Optional<GatewayStatusReport> report
    ) {
        public GatewaySummary {
            Objects.requireNonNull(report, "report must not be null");
            if (configured != report.isPresent()) {
                throw new IllegalArgumentException("configured must match report presence");
            }
        }

        public static GatewaySummary notConfigured() {
            return new GatewaySummary(false, Optional.empty());
        }

        public static GatewaySummary of(GatewayStatusReport report) {
            return new GatewaySummary(true, Optional.of(Objects.requireNonNull(report, "report must not be null")));
        }

        public Optional<String> type() {
            return report.map(r -> r.gateway().type());
        }

        public Optional<Integer> port() {
            return report.map(r -> r.gateway().port());
        }

        public Optional<String> adminUrl() {
            return report.map(r -> r.gateway().adminUrl());
        }

        public Optional<Ownership> ownership() {
            return report.map(r -> r.discovery().ownership());
        }

        public Optional<String> provenance() {
            return report.flatMap(r -> r.discovery().provenance());
        }

        public Optional<HealthState> health() {
            return report.map(r -> r.discovery().health());
        }

        public Optional<Long> pid() {
            return report.flatMap(r -> r.discovery().pid());
        }

        public Optional<Duration> uptime() {
            return report.flatMap(GatewayStatusReport::uptime);
        }

        public Optional<GatewayDriftReport> drift() {
            return report.map(GatewayStatusReport::drift);
        }

        public Optional<GatewaySyncStatus> syncStatus() {
            return report.map(r -> r.drift().syncStatus());
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
        Optional<String> healthNote
    ) {
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
        }
    }
}
