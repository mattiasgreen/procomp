package procomp.shell;

import procomp.core.build.BuildDisplayState;
import procomp.core.build.ObservedBuildRun;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.process.DiscoveryResult;
import procomp.core.process.HealthState;
import procomp.core.process.Ownership;
import procomp.core.process.PortBinding;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Modality-agnostic orchestrator for read-only unified estate status.
 * Composes {@link RoutingShell}, {@link ProcessShell}, and {@link GatewayShell} strictly inside {@code shell/}.
 */
public final class StatusShell {

    private final RoutingShell routingShell;
    private final ProcessShell processShell;
    private final GatewayShell gatewayShell;
    private final Optional<BuildShell> buildShell;
    private final Optional<GitShell> gitShell;
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
        this(stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer, Optional.empty());
    }

    public StatusShell(
        StateStore stateStore,
        ProcessRunner processRunner,
        PortScanner portScanner,
        HttpClient httpClient,
        ProjectFiles projectFiles,
        Clock clock,
        ProgressObserver observer,
        Optional<GitShell> gitShell
    ) {
        this(
            new RoutingShell(stateStore),
            new ProcessLifecycle(stateStore, processRunner, portScanner, httpClient, projectFiles, clock, observer),
            new BuildShell(stateStore, processRunner, projectFiles, clock, observer),
            gitShell,
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
        Optional<GitShell> gitShell,
        HttpClient httpClient,
        ProjectFiles projectFiles,
        Clock clock,
        ProgressObserver observer
    ) {
        this(
            routingShell,
            new GatewayShell(httpClient, projectFiles, clock, observer, routingShell, lifecycle),
            buildShell,
            gitShell,
            projectFiles,
            lifecycle,
            clock
        );
    }

    private StatusShell(
        RoutingShell routingShell,
        GatewayShell gatewayShell,
        BuildShell buildShell,
        Optional<GitShell> gitShell,
        ProjectFiles projectFiles,
        ProcessLifecycle lifecycle,
        Clock clock
    ) {
        this(
            routingShell,
            new ProcessShell(projectFiles, routingShell, gatewayShell, lifecycle),
            gatewayShell,
            Optional.of(buildShell),
            gitShell,
            clock
        );
    }

    public StatusShell(
        RoutingShell routingShell,
        ProcessShell processShell,
        GatewayShell gatewayShell,
        Clock clock
    ) {
        this(routingShell, processShell, gatewayShell, Optional.empty(), Optional.empty(), clock);
    }

    private StatusShell(
        RoutingShell routingShell,
        ProcessShell processShell,
        GatewayShell gatewayShell,
        Optional<BuildShell> buildShell,
        Optional<GitShell> gitShell,
        Clock clock
    ) {
        this.routingShell = Objects.requireNonNull(routingShell, "routingShell must not be null");
        this.processShell = Objects.requireNonNull(processShell, "processShell must not be null");
        this.gatewayShell = Objects.requireNonNull(gatewayShell, "gatewayShell must not be null");
        this.buildShell = Objects.requireNonNull(buildShell, "buildShell must not be null");
        this.gitShell = Objects.requireNonNull(gitShell, "gitShell must not be null");
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

        Result<Map<Integer, PortBinding>, DomainError> portSnapRes = processShell.snapshotServicePorts(project);
        if (portSnapRes.isErr()) {
            return portSnapRes.castError();
        }
        Map<Integer, PortBinding> portSnapshot = ((Result.Ok<Map<Integer, PortBinding>, DomainError>) portSnapRes).value();
        Map<Long, Long> rssSnapshot = processShell.snapshotRssBytes();

        Map<java.nio.file.Path, GitShell.RepositoryStatusRow> repoSnapshot = Map.of();
        if (gitShell.isPresent()) {
            Result<Map<java.nio.file.Path, GitShell.RepositoryStatusRow>, DomainError> repoSnapRes =
                gitShell.get().snapshotRepositories(project);
            if (repoSnapRes.isErr()) {
                return repoSnapRes.castError();
            }
            repoSnapshot = ((Result.Ok<Map<java.nio.file.Path, GitShell.RepositoryStatusRow>, DomainError>) repoSnapRes).value();
        }
        final Map<java.nio.file.Path, GitShell.RepositoryStatusRow> finalRepoSnapshot = repoSnapshot;

        Instant now = clock.now();
        List<ServiceDefinition> serviceList = List.copyOf(project.services().values());
        List<ServiceStatusRow> rows = new ArrayList<>(serviceList.size());
        if (serviceList.size() <= 1) {
            for (ServiceDefinition service : serviceList) {
                Result<ServiceStatusRow, DomainError> rowRes =
                    discoverServiceRow(project, service, routing, portSnapshot, rssSnapshot, finalRepoSnapshot, now);
                if (rowRes.isErr()) {
                    return rowRes.castError();
                }
                rows.add(((Result.Ok<ServiceStatusRow, DomainError>) rowRes).value());
            }
        } else {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Result<ServiceStatusRow, DomainError>>> futures = new ArrayList<>(serviceList.size());
                for (ServiceDefinition service : serviceList) {
                    futures.add(executor.submit(() ->
                        discoverServiceRow(project, service, routing, portSnapshot, rssSnapshot, finalRepoSnapshot, now)
                    ));
                }
                for (Future<Result<ServiceStatusRow, DomainError>> future : futures) {
                    Result<ServiceStatusRow, DomainError> rowRes = future.get();
                    if (rowRes.isErr()) {
                        return rowRes.castError();
                    }
                    rows.add(((Result.Ok<ServiceStatusRow, DomainError>) rowRes).value());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.err(DomainError.of("STATUS_INTERRUPTED", "Interrupted while collecting service status"));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                return Result.err(DomainError.of(
                    "STATUS_ERROR",
                    "Failed to collect service status: " + (cause != null ? cause.getMessage() : e.getMessage())
                ));
            }
        }

        GatewaySummary gatewaySummary = GatewaySummary.notConfigured();
        if (project.gateway().isPresent()) {
            Result<GatewayStatusReport, DomainError> gwRes = gatewayShell.status(project, routing, rssSnapshot);
            if (gwRes instanceof Result.Err<GatewayStatusReport, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            gatewaySummary = GatewaySummary.of(((Result.Ok<GatewayStatusReport, DomainError>) gwRes).value());
        }

        return Result.ok(new EstateStatus(project.name(), gatewaySummary, rows));
    }

    private Result<ServiceStatusRow, DomainError> discoverServiceRow(
        Project project,
        ServiceDefinition service,
        RoutingTable routing,
        Map<Integer, PortBinding> portSnapshot,
        Map<Long, Long> rssSnapshot,
        Map<java.nio.file.Path, GitShell.RepositoryStatusRow> repoSnapshot,
        Instant now
    ) {
        Result<DiscoveryResult, DomainError> discRes = processShell.discoverService(project, service.name(), portSnapshot);
        if (discRes.isErr()) {
            return discRes.castError();
        }
        DiscoveryResult disc = ((Result.Ok<DiscoveryResult, DomainError>) discRes).value();
        Route route = routing.routeOf(service.name()).orElse(Route.REMOTE);
        Optional<String> healthNote = disc.healthResponse().isBlank()
            ? Optional.empty()
            : Optional.of(disc.healthResponse());

        ObservedBuildRun buildRun;
        if (buildShell.isPresent()) {
            Result<ObservedBuildRun, DomainError> bRes = buildShell.get().inspectBuild(project, service);
            if (bRes.isErr()) {
                return bRes.castError();
            }
            buildRun = ((Result.Ok<ObservedBuildRun, DomainError>) bRes).value();
        } else {
            BuildDisplayState fallback = service.buildConfig().isPresent()
                ? BuildDisplayState.NOT_BUILT
                : BuildDisplayState.NO_BUILDER;
            buildRun = new ObservedBuildRun(fallback, Optional.empty(), Optional.empty());
        }

        Optional<Long> rssBytes = processShell.resolveRssBytes(disc, rssSnapshot);
        Optional<GitShell.RepositoryStatusRow> gitRow = service.repository()
            .map(ref -> repoSnapshot.get(ref.root()));

        return Result.ok(new ServiceStatusRow(
            service.name(),
            route,
            disc.ownership(),
            disc.provenance(),
            disc.health(),
            service.port(),
            disc.pid(),
            disc.startedAt(),
            disc.uptime(now),
            rssBytes,
            disc.stale(),
            disc.healthEndpoint(),
            healthNote,
            buildRun,
            gitRow
        ));
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

        /**
         * Sum of observed RSS bytes across all running Services ({@code Managed}, {@code Unmanaged},
         * {@code Conflict}) and the {@code Gateway}, or empty when no running Process reports RSS.
         */
        public Optional<Long> totalRssBytes() {
            long total = 0L;
            boolean any = false;
            for (ServiceStatusRow row : services) {
                if (row.rssBytes().isPresent()) {
                    total += row.rssBytes().get();
                    any = true;
                }
            }
            if (gateway.report().isPresent() && gateway.report().get().rssBytes().isPresent()) {
                total += gateway.report().get().rssBytes().get();
                any = true;
            }
            return any ? Optional.of(total) : Optional.empty();
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
        Optional<Long> rssBytes,
        boolean stale,
        Optional<String> healthEndpoint,
        Optional<String> healthNote,
        ObservedBuildRun build,
        Optional<GitShell.RepositoryStatusRow> git
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
                service, route, ownership, provenance, health, port, pid, startedAt, uptime, Optional.empty(), stale,
                healthEndpoint, healthNote,
                new ObservedBuildRun(BuildDisplayState.NO_BUILDER, Optional.empty(), Optional.empty()),
                Optional.empty()
            );
        }

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
            Optional<String> healthNote,
            ObservedBuildRun build
        ) {
            this(
                service, route, ownership, provenance, health, port, pid, startedAt, uptime, Optional.empty(), stale,
                healthEndpoint, healthNote, build, Optional.empty()
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
            Objects.requireNonNull(rssBytes, "rssBytes must not be null");
            Objects.requireNonNull(healthEndpoint, "healthEndpoint must not be null");
            Objects.requireNonNull(healthNote, "healthNote must not be null");
            Objects.requireNonNull(build, "build must not be null");
            Objects.requireNonNull(git, "git must not be null");
        }
    }
}
