package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.process.PortBinding;
import procomp.shell.ports.PortScanner;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-platform (Windows, Linux, macOS) TCP port listener discovery and connect-check adapter.
 */
public final class OsPortScanner implements PortScanner {

    private static final Pattern SS_PID_PATTERN = Pattern.compile("pid=(\\d+)");
    private static final Set<String> NON_LISTEN_STATES = Set.of(
        "ESTABLISHED", "TIME_WAIT", "CLOSE_WAIT", "FIN_WAIT_1", "FIN_WAIT_2", "SYN_SENT", "SYN_RECV", "LAST_ACK", "CLOSING", "BOUND"
    );

    private final String osName;

    public OsPortScanner() {
        this(System.getProperty("os.name", ""));
    }

    public OsPortScanner(String osName) {
        this.osName = osName == null ? "" : osName;
    }

    @Override
    public boolean isTcpListening(int port, Duration timeout) {
        validatePort(port);
        Objects.requireNonNull(timeout, "timeout must not be null");
        int timeoutMs = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        try {
            return canConnect("127.0.0.1", port, timeoutMs);
        } catch (ConnectException refusedV4) {
            try {
                return canConnect("::1", port, Math.min(timeoutMs, 200));
            } catch (IOException ignored) {
                return false;
            }
        } catch (IOException ignored) {
            return false;
        }
    }

    @Override
    public Result<Optional<PortBinding>, DomainError> findListener(int port) {
        validatePort(port);
        return findListeners(Set.of(port)).map(map -> Optional.ofNullable(map.get(port)));
    }

    @Override
    public Result<Map<Integer, PortBinding>, DomainError> findListeners(Set<Integer> ports) {
        Objects.requireNonNull(ports, "ports must not be null");
        if (ports.isEmpty()) {
            return Result.ok(Map.of());
        }
        for (int port : ports) {
            validatePort(port);
        }

        String os = osName.toLowerCase(Locale.ROOT);
        Map<Integer, PortBinding> found = new LinkedHashMap<>();
        boolean osSnapshotSucceeded = false;

        if (os.contains("win")) {
            java.util.concurrent.CompletableFuture<Optional<String>> tcp4Future =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> runCommandQuiet(List.of("netstat", "-ano", "-p", "tcp")));
            java.util.concurrent.CompletableFuture<Optional<String>> tcp6Future =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> runCommandQuiet(List.of("netstat", "-ano", "-p", "tcpv6")));
            Optional<String> tcp4 = tcp4Future.join();
            Optional<String> tcp6 = tcp6Future.join();
            if (tcp4.isPresent()) {
                osSnapshotSucceeded = true;
                parseWindowsNetstatBatch(tcp4.get(), ports, found);
            }
            if (tcp6.isPresent()) {
                osSnapshotSucceeded = true;
                parseWindowsNetstatBatch(tcp6.get(), ports, found);
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            Optional<String> lsof = runCommandQuiet(List.of("lsof", "-nP", "-iTCP", "-sTCP:LISTEN"));
            if (lsof.isPresent()) {
                osSnapshotSucceeded = true;
                parsePosixLsofBatch(lsof.get(), ports, found);
            } else {
                Optional<String> ss = runCommandQuiet(List.of("ss", "-tlnp"));
                if (ss.isPresent()) {
                    osSnapshotSucceeded = true;
                    parseLinuxSsBatch(ss.get(), ports, found);
                }
            }
        } else {
            Optional<String> ss = runCommandQuiet(List.of("ss", "-tlnp"));
            if (ss.isPresent()) {
                osSnapshotSucceeded = true;
                parseLinuxSsBatch(ss.get(), ports, found);
            } else {
                Optional<String> lsof = runCommandQuiet(List.of("lsof", "-nP", "-iTCP", "-sTCP:LISTEN"));
                if (lsof.isPresent()) {
                    osSnapshotSucceeded = true;
                    parsePosixLsofBatch(lsof.get(), ports, found);
                }
            }
        }

        // Only fall back to TCP connect probes when the OS port-listing command failed/was unavailable.
        if (!osSnapshotSucceeded) {
            for (int port : ports) {
                if (!found.containsKey(port) && isTcpListening(port, Duration.ofMillis(200))) {
                    found.put(port, new PortBinding(port, Optional.empty(), "127.0.0.1:" + port));
                }
            }
        }
        return Result.ok(Collections.unmodifiableMap(found));
    }

    public static Optional<PortBinding> parseWindowsNetstat(String output, int targetPort) {
        Map<Integer, PortBinding> out = new LinkedHashMap<>();
        parseWindowsNetstatBatch(output, Set.of(targetPort), out);
        return Optional.ofNullable(out.get(targetPort));
    }

    private static void parseWindowsNetstatBatch(String output, Set<Integer> targetPorts, Map<Integer, PortBinding> out) {
        if (output == null || output.isBlank()) {
            return;
        }
        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("TCP")) {
                continue;
            }
            String[] cols = trimmed.split("\\s+", -1);
            if (cols.length < 5) {
                continue;
            }
            String localAddr = cols[1];
            String foreignAddr = cols[2];
            String state = cols[3].toUpperCase(Locale.ROOT);
            boolean isListening = "LISTENING".equals(state)
                || (("0.0.0.0:0".equals(foreignAddr) || "[::]:0".equals(foreignAddr)) && !NON_LISTEN_STATES.contains(state));
            if (!isListening) {
                continue;
            }
            int parsedPort = extractLoopbackOrWildcardPort(localAddr);
            if (parsedPort < 0 || !targetPorts.contains(parsedPort)) {
                continue;
            }
            PortBinding candidate = new PortBinding(parsedPort, parsePositivePid(cols[4]), localAddr);
            out.put(parsedPort, pickBetterListener(Optional.ofNullable(out.get(parsedPort)), candidate));
        }
    }

    public static Optional<PortBinding> parseLinuxSs(String output, int targetPort) {
        Map<Integer, PortBinding> out = new LinkedHashMap<>();
        parseLinuxSsBatch(output, Set.of(targetPort), out);
        return Optional.ofNullable(out.get(targetPort));
    }

    private static void parseLinuxSsBatch(String output, Set<Integer> targetPorts, Map<Integer, PortBinding> out) {
        if (output == null || output.isBlank()) {
            return;
        }
        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("State") || trimmed.startsWith("Netid")) {
                continue;
            }
            String[] cols = trimmed.split("\\s+", -1);
            if (cols.length < 5) {
                continue;
            }
            int localIdx = cols[0].equalsIgnoreCase("LISTEN") ? 3 : 4;
            if (cols.length <= localIdx) {
                continue;
            }
            String localAddr = cols[localIdx];
            int parsedPort = extractLoopbackOrWildcardPort(localAddr);
            if (parsedPort < 0 || !targetPorts.contains(parsedPort)) {
                continue;
            }
            Optional<Long> pid = Optional.empty();
            Matcher m = SS_PID_PATTERN.matcher(trimmed);
            if (m.find()) {
                pid = parsePositivePid(m.group(1));
            }
            PortBinding candidate = new PortBinding(parsedPort, pid, localAddr);
            out.put(parsedPort, pickBetterListener(Optional.ofNullable(out.get(parsedPort)), candidate));
        }
    }

    public static Optional<PortBinding> parsePosixLsof(String output, int targetPort) {
        Map<Integer, PortBinding> out = new LinkedHashMap<>();
        parsePosixLsofBatch(output, Set.of(targetPort), out);
        return Optional.ofNullable(out.get(targetPort));
    }

    private static void parsePosixLsofBatch(String output, Set<Integer> targetPorts, Map<Integer, PortBinding> out) {
        if (output == null || output.isBlank()) {
            return;
        }
        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("COMMAND")) {
                continue;
            }
            String[] cols = trimmed.split("\\s+", -1);
            if (cols.length < 9) {
                continue;
            }
            Optional<Long> pid = parsePositivePid(cols[1]);
            for (int i = 7; i < cols.length; i++) {
                int parsedPort = extractLoopbackOrWildcardPort(cols[i]);
                if (parsedPort > 0 && targetPorts.contains(parsedPort)) {
                    PortBinding candidate = new PortBinding(parsedPort, pid, cols[i]);
                    out.put(parsedPort, pickBetterListener(Optional.ofNullable(out.get(parsedPort)), candidate));
                }
            }
        }
    }

    private static PortBinding pickBetterListener(Optional<PortBinding> currentOpt, PortBinding candidate) {
        if (currentOpt.isEmpty()) {
            return candidate;
        }
        PortBinding current = currentOpt.get();
        if (current.pid().isEmpty() && candidate.pid().isPresent()) {
            return candidate;
        }
        if (candidate.pid().isPresent() && !isExplicitLoopback(current.localAddress()) && isExplicitLoopback(candidate.localAddress())) {
            return candidate;
        }
        return current;
    }

    private static boolean canConnect(String host, int port, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), timeoutMs);
            return true;
        }
    }

    private static Optional<String> runCommandQuiet(List<String> command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process proc = p;
            java.util.concurrent.CompletableFuture<byte[]> stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (java.io.InputStream in = proc.getInputStream()) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                stdoutFuture.cancel(true);
                return Optional.empty();
            }
            byte[] bytes = stdoutFuture.get(1, TimeUnit.SECONDS);
            return p.exitValue() == 0 ? Optional.of(new String(bytes, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            if (p != null) {
                p.destroyForcibly();
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
            return Optional.empty();
        }
    }

    private static int extractLoopbackOrWildcardPort(String localAddr) {
        int colon = localAddr.lastIndexOf(':');
        if (colon <= 0 || colon >= localAddr.length() - 1) {
            return -1;
        }
        int parsedPort;
        try {
            parsedPort = Integer.parseInt(localAddr.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
        if (parsedPort < 1 || parsedPort > 65535) {
            return -1;
        }
        String host = stripInterfaceScope(localAddr.substring(0, colon)).toLowerCase(Locale.ROOT);
        boolean ok = switch (host) {
            case "127.0.0.1", "0.0.0.0", "[::1]", "[::]", "::1", "::", "*", "[::ffff:127.0.0.1]", "::ffff:127.0.0.1" -> true;
            default -> false;
        };
        return ok ? parsedPort : -1;
    }

    private static boolean isExplicitLoopback(String localAddr) {
        int colon = localAddr.lastIndexOf(':');
        if (colon <= 0) {
            return false;
        }
        String host = stripInterfaceScope(localAddr.substring(0, colon)).toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }

    private static String stripInterfaceScope(String host) {
        int pct = host.indexOf('%');
        if (pct < 0) {
            return host;
        }
        return host.startsWith("[") && host.endsWith("]")
            ? host.substring(0, pct) + "]"
            : host.substring(0, pct);
    }

    private static Optional<Long> parsePositivePid(String raw) {
        try {
            long pid = Long.parseLong(raw.trim());
            return pid > 0 ? Optional.of(pid) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static void validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }
    }
}
