package procomp.core.process;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure, stateless Discovery classifier implementing ownership, health, stopping, and stale rules.
 */
public final class DiscoveryEngine {

    /**
     * Default graceful termination timeout (10s) plus force-kill wait (5s).
     */
    public static final Duration DEFAULT_GRACEFUL_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_FORCE_KILL_WAIT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_TERMINATION_WINDOW = DEFAULT_GRACEFUL_TIMEOUT.plus(DEFAULT_FORCE_KILL_WAIT);

    private static final Set<String> KNOWN_IDE_EXECUTABLES = Set.of(
        "idea", "idea64",
        "code", "code - insiders", "code-insiders",
        "rider", "rider64",
        "eclipse",
        "webstorm", "webstorm64",
        "pycharm", "pycharm64",
        "goland", "goland64",
        "clion", "clion64",
        "studio", "studio64",
        "cursor",
        "rubymine", "rubymine64",
        "phpstorm", "phpstorm64",
        "datagrip", "datagrip64",
        "fleet",
        "windsurf",
        "antigravity"
    );

    private DiscoveryEngine() {}

    public static boolean isActivelyStopping(ManagedProcessRecord record, Instant now) {
        return isActivelyStopping(record, now, DEFAULT_TERMINATION_WINDOW);
    }

    public static boolean isActivelyStopping(
        ManagedProcessRecord record,
        Instant now,
        Duration terminationWindow
    ) {
        Objects.requireNonNull(record, "record must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(terminationWindow, "terminationWindow must not be null");
        return record instanceof ManagedProcessRecord.Stopping stopping
            && now.isBefore(stopping.stopRequestedAt().plus(terminationWindow));
    }

    public static DiscoveryResult discover(DiscoveryInput input) {
        return discover(input, DEFAULT_TERMINATION_WINDOW);
    }

    public static DiscoveryResult discover(DiscoveryInput input, Duration terminationWindow) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(terminationWindow, "terminationWindow must not be null");
        if (terminationWindow.isNegative()) {
            throw new IllegalArgumentException("terminationWindow must not be negative");
        }
        ProcessTarget target = input.target();
        Optional<String> healthEndpoint = target.healthEndpoint();

        if (input.managedRecord().isPresent()) {
            ManagedProcessRecord rec = input.managedRecord().get();
            Optional<ObservedProcess> proc = input.process()
                .filter(ObservedProcess::alive)
                .filter(p -> p.pid() == rec.pid())
                .filter(p -> p.startTime().map(st -> !st.isAfter(rec.startedAt().plusSeconds(60))).orElse(true));

            if (proc.isPresent()) {
                ObservedProcess aliveProc = proc.get();
                Instant startedAt = aliveProc.startTime().orElse(rec.startedAt());

                if (rec instanceof ManagedProcessRecord.Stopping stopping
                    && isActivelyStopping(rec, input.now(), terminationWindow)) {
                    ProcessState state = new ProcessState.Managed.Stopping(
                        rec.pid(),
                        startedAt,
                        stopping.stopRequestedAt(),
                        aliveProc
                    );
                    return new DiscoveryResult(
                        target.name(),
                        state,
                        healthEndpoint,
                        describeHealth(target, HealthState.STOPPING, Optional.of(rec.pid()), input.healthCheck())
                    );
                }

                boolean healthy = isHealthy(target, true, input.healthCheck());
                boolean stale = isStale(rec, input.latestArtifactTimestamp());
                ProcessState state = healthy
                    ? new ProcessState.Managed.Healthy(rec.pid(), startedAt, stale, aliveProc)
                    : new ProcessState.Managed.Starting(rec.pid(), startedAt, stale, aliveProc);
                return new DiscoveryResult(
                    target.name(),
                    state,
                    healthEndpoint,
                    describeHealth(target, state.health(), Optional.of(rec.pid()), input.healthCheck())
                );
            }

            if (rec instanceof ManagedProcessRecord.Stopping) {
                return new DiscoveryResult(
                    target.name(),
                    ProcessState.Stopped.INSTANCE,
                    healthEndpoint,
                    describeHealth(target, HealthState.STOPPED, Optional.empty(), input.healthCheck())
                );
            }

            ProcessState crashed = new ProcessState.Managed.Crashed(rec.pid(), rec.startedAt());
            return new DiscoveryResult(
                target.name(),
                crashed,
                healthEndpoint,
                describeHealth(target, HealthState.CRASHED, Optional.of(rec.pid()), input.healthCheck())
            );
        }

        if (input.portListener().isPresent()) {
            PortBinding listener = input.portListener().get();
            Optional<ObservedProcess> listenerProc = input.process().filter(ObservedProcess::alive);
            Optional<Long> pid = listenerProc.map(ObservedProcess::pid).or(listener::pid);
            Optional<Instant> startedAt = listenerProc.flatMap(ObservedProcess::startTime);
            boolean ideAncestor = listenerProc.map(DiscoveryEngine::hasIdeAncestor).orElse(false);
            boolean healthy = isHealthy(target, true, input.healthCheck());

            if (healthy) {
                String provenance = ideAncestor ? "IDE" : "shell";
                ProcessState state = new ProcessState.Unmanaged.Healthy(provenance, pid, startedAt, listenerProc);
                return new DiscoveryResult(
                    target.name(),
                    state,
                    healthEndpoint,
                    describeHealth(target, HealthState.HEALTHY, pid, input.healthCheck())
                );
            }
            if (ideAncestor && listenerProc.isPresent()) {
                ProcessState state = new ProcessState.Unmanaged.Starting(listenerProc.get());
                return new DiscoveryResult(
                    target.name(),
                    state,
                    healthEndpoint,
                    describeHealth(target, HealthState.STARTING, pid, input.healthCheck())
                );
            }
            ProcessState conflict = new ProcessState.Conflict(pid, startedAt, listenerProc);
            return new DiscoveryResult(
                target.name(),
                conflict,
                healthEndpoint,
                describeHealth(target, HealthState.STARTING, pid, input.healthCheck())
            );
        }

        return new DiscoveryResult(
            target.name(),
            ProcessState.Stopped.INSTANCE,
            healthEndpoint,
            describeHealth(target, HealthState.STOPPED, Optional.empty(), input.healthCheck())
        );
    }

    public static InspectionReport inspect(
        ProcessTarget target,
        DiscoveryResult discovery,
        Optional<ManagedProcessRecord> managedRecord,
        Optional<String> desiredRoute,
        Optional<String> liveGatewayRoute,
        Instant now
    ) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(discovery, "discovery must not be null");
        Objects.requireNonNull(managedRecord, "managedRecord must not be null");
        Objects.requireNonNull(desiredRoute, "desiredRoute must not be null");
        Objects.requireNonNull(liveGatewayRoute, "liveGatewayRoute must not be null");
        Objects.requireNonNull(now, "now must not be null");

        boolean inactive = discovery.health() == HealthState.STOPPED || discovery.health() == HealthState.CRASHED;
        Optional<Duration> uptime = discovery.uptime(now);
        Optional<String> commandLine = discovery.process()
            .flatMap(ObservedProcess::commandLine)
            .or(() -> discovery.health() == HealthState.CRASHED
                ? managedRecord.map(r -> String.join(" ", r.commandSummary()))
                : Optional.empty());
        List<String> arguments = discovery.process()
            .map(ObservedProcess::arguments)
            .orElse(List.of());
        List<ParentProcess> parentChain = discovery.process()
            .map(ObservedProcess::parentChain)
            .orElse(List.of());
        Optional<Long> rssBytes = inactive
            ? Optional.empty()
            : discovery.process().flatMap(ObservedProcess::rssBytes);

        return new InspectionReport(
            target.name(),
            target.port(),
            desiredRoute,
            liveGatewayRoute,
            discovery.ownership(),
            discovery.provenance(),
            discovery.health(),
            discovery.pid(),
            uptime,
            commandLine,
            arguments,
            parentChain,
            rssBytes,
            discovery.healthEndpoint(),
            discovery.healthResponse(),
            target.stdoutLog(),
            target.stderrLog(),
            discovery.stale()
        );
    }

    public static boolean hasIdeAncestor(ObservedProcess process) {
        Objects.requireNonNull(process, "process must not be null");
        for (ParentProcess ancestor : process.parentChain()) {
            Optional<String> baseName = extractExecutableBaseName(ancestor.executable(), ancestor.commandLine());
            if (baseName.isPresent() && KNOWN_IDE_EXECUTABLES.contains(baseName.get())) {
                return true;
            }
        }
        return false;
    }

    static Optional<String> extractExecutableBaseName(Optional<String> executable, Optional<String> commandLine) {
        Optional<String> rawExeOpt = executable.filter(s -> !s.isBlank())
            .or(() -> commandLine.flatMap(DiscoveryEngine::firstCommandToken))
            .filter(s -> !s.isBlank());
        if (rawExeOpt.isEmpty()) {
            return Optional.empty();
        }
        String rawExe = rawExeOpt.get();
        int lastSlash = Math.max(rawExe.lastIndexOf('/'), rawExe.lastIndexOf('\\'));
        String fileName = lastSlash >= 0 ? rawExe.substring(lastSlash + 1) : rawExe;
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        for (String ext : List.of(".exe", ".sh", ".cmd", ".bat")) {
            if (lower.endsWith(ext)) {
                lower = lower.substring(0, lower.length() - ext.length());
                break;
            }
        }
        return lower.isEmpty() ? Optional.empty() : Optional.of(lower);
    }

    private static Optional<String> firstCommandToken(String commandLine) {
        String trimmed = commandLine.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if (trimmed.startsWith("\"")) {
            int closing = trimmed.indexOf('"', 1);
            if (closing > 1) {
                return Optional.of(trimmed.substring(1, closing));
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String ext : List.of(".exe", ".cmd", ".bat", ".sh")) {
            int idx = lower.indexOf(ext);
            if (idx > 0) {
                int end = idx + ext.length();
                if (end == trimmed.length() || Character.isWhitespace(trimmed.charAt(end))) {
                    return Optional.of(trimmed.substring(0, end));
                }
            }
        }
        int ws = 0;
        while (ws < trimmed.length() && !Character.isWhitespace(trimmed.charAt(ws))) {
            ws++;
        }
        return Optional.of(trimmed.substring(0, ws));
    }

    static boolean isHealthy(ProcessTarget target, boolean processAlive, HealthCheck check) {
        if (!processAlive) {
            return false;
        }
        if (target.healthEndpoint().isPresent()) {
            return check instanceof HealthCheck.HttpResult http && http.is2xx();
        }
        if (target.port().isPresent()) {
            return check instanceof HealthCheck.TcpResult tcp && tcp.listening();
        }
        return true;
    }

    static boolean isStale(ManagedProcessRecord record, Optional<Instant> latestArtifactTimestamp) {
        if (latestArtifactTimestamp.isEmpty()) {
            return false;
        }
        Instant baseline = record.artifactTimestamp().orElse(record.startedAt());
        return latestArtifactTimestamp.get().isAfter(baseline);
    }

    static String describeHealth(
        ProcessTarget target,
        HealthState health,
        Optional<Long> pid,
        HealthCheck check
    ) {
        if (health == HealthState.STOPPED) {
            return target.port()
                .map(p -> "No active listener on 127.0.0.1:" + p)
                .orElse("No active process");
        }
        if (health == HealthState.STOPPING) {
            return "Process stopping" + pid.map(id -> " (PID " + id + ")").orElse("");
        }
        if (health == HealthState.CRASHED) {
            return "Process exited unexpectedly" + pid.map(id -> " (PID " + id + ")").orElse("");
        }
        if (target.healthEndpoint().isPresent()) {
            String endpoint = target.healthEndpoint().get();
            return switch (check) {
                case HealthCheck.HttpResult(int code, String summary) ->
                    "HTTP " + code + (summary.isBlank() ? "" : " " + summary) + " (GET " + endpoint + ")";
                case HealthCheck.HttpFailure(String detail) ->
                    "Health check failed: " + detail + " (GET " + endpoint + ")";
                default -> "Health check pending (GET " + endpoint + ")";
            };
        }
        if (target.port().isPresent()) {
            int port = target.port().get();
            if (check instanceof HealthCheck.TcpResult(boolean listening)) {
                return listening
                    ? "TCP listening on 127.0.0.1:" + port
                    : "TCP port 127.0.0.1:" + port + " not listening yet";
            }
            return "TCP health check pending on 127.0.0.1:" + port;
        }
        return "Process alive (no port or healthPath configured)";
    }
}
