package procomp.core.process;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure, stateless Discovery classifier implementing the exact v1 ownership, health, and stale rules.
 */
public final class DiscoveryEngine {

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

    public static DiscoveryResult discover(DiscoveryInput input) {
        Objects.requireNonNull(input, "input must not be null");
        ProcessTarget target = input.target();
        Optional<String> healthEndpoint = target.healthEndpoint();

        if (input.managedRecord().isPresent()) {
            ManagedRecordView rec = input.managedRecord().get();
            Optional<ObservedProcess> proc = input.process().filter(ObservedProcess::alive);
            if (proc.isPresent()) {
                ObservedProcess aliveProc = proc.get();
                boolean healthy = isHealthy(target, true, input.healthProbe());
                HealthState health = healthy ? HealthState.HEALTHY : HealthState.STARTING;
                Instant startedAt = aliveProc.startTime().orElse(rec.startedAt());
                boolean stale = isStale(rec, input.latestArtifactTimestamp());
                return new DiscoveryResult(
                    target.name(),
                    Ownership.MANAGED,
                    Optional.of("procomp"),
                    health,
                    Optional.of(rec.pid()),
                    Optional.of(startedAt),
                    stale,
                    healthEndpoint,
                    describeHealth(target, health, Optional.of(rec.pid()), input.healthProbe()),
                    Optional.of(aliveProc)
                );
            }
            return new DiscoveryResult(
                target.name(),
                Ownership.MANAGED,
                Optional.of("procomp"),
                HealthState.CRASHED,
                Optional.of(rec.pid()),
                Optional.of(rec.startedAt()),
                false,
                healthEndpoint,
                describeHealth(target, HealthState.CRASHED, Optional.of(rec.pid()), input.healthProbe()),
                Optional.empty()
            );
        }

        if (input.portListener().isPresent()) {
            PortBinding listener = input.portListener().get();
            Optional<ObservedProcess> listenerProc = input.process().filter(ObservedProcess::alive);
            Optional<Long> pid = listenerProc.map(ObservedProcess::pid).or(listener::pid);
            Optional<Instant> startedAt = listenerProc.flatMap(ObservedProcess::startTime);
            boolean ideAncestor = listenerProc.map(DiscoveryEngine::hasIdeAncestor).orElse(false);
            boolean healthy = isHealthy(target, true, input.healthProbe());

            if (healthy) {
                String provenance = ideAncestor ? "IDE" : "shell";
                return new DiscoveryResult(
                    target.name(),
                    Ownership.UNMANAGED,
                    Optional.of(provenance),
                    HealthState.HEALTHY,
                    pid,
                    startedAt,
                    false,
                    healthEndpoint,
                    describeHealth(target, HealthState.HEALTHY, pid, input.healthProbe()),
                    listenerProc
                );
            }
            if (ideAncestor) {
                return new DiscoveryResult(
                    target.name(),
                    Ownership.UNMANAGED,
                    Optional.of("IDE"),
                    HealthState.STARTING,
                    pid,
                    startedAt,
                    false,
                    healthEndpoint,
                    describeHealth(target, HealthState.STARTING, pid, input.healthProbe()),
                    listenerProc
                );
            }
            return new DiscoveryResult(
                target.name(),
                Ownership.CONFLICT,
                Optional.of("unknown"),
                HealthState.STARTING,
                pid,
                startedAt,
                false,
                healthEndpoint,
                describeHealth(target, HealthState.STARTING, pid, input.healthProbe()),
                listenerProc
            );
        }

        return new DiscoveryResult(
            target.name(),
            Ownership.NONE,
            Optional.empty(),
            HealthState.STOPPED,
            Optional.empty(),
            Optional.empty(),
            false,
            healthEndpoint,
            describeHealth(target, HealthState.STOPPED, Optional.empty(), input.healthProbe()),
            Optional.empty()
        );
    }

    public static InspectionReport inspect(
        ProcessTarget target,
        DiscoveryResult discovery,
        Optional<ManagedRecordView> managedRecord,
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
        String rawExe = executable.filter(s -> !s.isBlank())
            .or(() -> commandLine.flatMap(DiscoveryEngine::firstCommandToken))
            .orElse(null);
        if (rawExe == null || rawExe.isBlank()) {
            return Optional.empty();
        }
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

    static boolean isHealthy(ProcessTarget target, boolean processAlive, HealthProbe probe) {
        if (!processAlive) {
            return false;
        }
        if (target.healthEndpoint().isPresent()) {
            return probe instanceof HealthProbe.HttpResult http && http.is2xx();
        }
        if (target.port().isPresent()) {
            return probe instanceof HealthProbe.TcpResult tcp && tcp.listening();
        }
        return true;
    }

    static boolean isStale(ManagedRecordView record, Optional<Instant> latestArtifactTimestamp) {
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
        HealthProbe probe
    ) {
        if (health == HealthState.STOPPED) {
            return target.port()
                .map(p -> "No active listener on 127.0.0.1:" + p)
                .orElse("No active process");
        }
        if (health == HealthState.CRASHED) {
            return "Process exited unexpectedly" + pid.map(id -> " (PID " + id + ")").orElse("");
        }
        if (target.healthEndpoint().isPresent()) {
            String endpoint = target.healthEndpoint().get();
            return switch (probe) {
                case HealthProbe.HttpResult(int code, String summary) ->
                    "HTTP " + code + (summary.isBlank() ? "" : " " + summary) + " (GET " + endpoint + ")";
                case HealthProbe.HttpFailure(String detail) ->
                    "Probe failed: " + detail + " (GET " + endpoint + ")";
                default -> "Health probe pending (GET " + endpoint + ")";
            };
        }
        if (target.port().isPresent()) {
            int port = target.port().get();
            if (probe instanceof HealthProbe.TcpResult(boolean listening)) {
                return listening
                    ? "TCP listening on 127.0.0.1:" + port
                    : "TCP port 127.0.0.1:" + port + " not listening yet";
            }
            return "TCP probe pending on 127.0.0.1:" + port;
        }
        return "Process alive (no port or healthPath configured)";
    }
}
