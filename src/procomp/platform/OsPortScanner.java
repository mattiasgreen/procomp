package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.PortScanner;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
        "ESTABLISHED", "TIME_WAIT", "CLOSE_WAIT", "FIN_WAIT_1", "FIN_WAIT_2", "SYN_SENT", "SYN_RECV", "LAST_ACK", "CLOSING"
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
    public Result<Optional<PortListener>, DomainError> findListener(int port) {
        validatePort(port);
        String os = osName.toLowerCase(Locale.ROOT);
        Optional<PortListener> found = Optional.empty();

        if (os.contains("win")) {
            Optional<String> tcp4 = runCommandQuiet(List.of("netstat", "-ano", "-p", "tcp"));
            if (tcp4.isPresent()) {
                found = parseWindowsNetstat(tcp4.get(), port);
            }
            if (found.isEmpty()) {
                Optional<String> tcp6 = runCommandQuiet(List.of("netstat", "-ano", "-p", "tcpv6"));
                if (tcp6.isPresent()) {
                    found = parseWindowsNetstat(tcp6.get(), port);
                }
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            Optional<String> lsof = runCommandQuiet(List.of("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN"));
            if (lsof.isPresent()) {
                found = parsePosixLsof(lsof.get(), port);
            }
            if (found.isEmpty()) {
                Optional<String> ss = runCommandQuiet(List.of("ss", "-tlnp"));
                if (ss.isPresent()) {
                    found = parseLinuxSs(ss.get(), port);
                }
            }
        } else {
            Optional<String> ss = runCommandQuiet(List.of("ss", "-tlnp"));
            if (ss.isPresent()) {
                found = parseLinuxSs(ss.get(), port);
            }
            if (found.isEmpty()) {
                Optional<String> lsof = runCommandQuiet(List.of("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN"));
                if (lsof.isPresent()) {
                    found = parsePosixLsof(lsof.get(), port);
                }
            }
        }

        if (found.isPresent()) {
            return Result.ok(found);
        }
        if (isTcpListening(port, Duration.ofMillis(200))) {
            return Result.ok(Optional.of(new PortListener(port, Optional.empty(), "127.0.0.1:" + port)));
        }
        return Result.ok(Optional.empty());
    }

    public static Optional<PortListener> parseWindowsNetstat(String output, int targetPort) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        PortListener best = null;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("TCP")) {
                continue;
            }
            String[] cols = trimmed.split("\\s+");
            if (cols.length < 5) {
                continue;
            }
            String localAddr = cols[1];
            String foreignAddr = cols[2];
            String state = cols[3].toUpperCase(Locale.ROOT);
            boolean isListening = "LISTENING".equals(state)
                || (("0.0.0.0:0".equals(foreignAddr) || "[::]:0".equals(foreignAddr)) && !NON_LISTEN_STATES.contains(state));
            if (!isListening || !matchesLoopbackOrWildcardPort(localAddr, targetPort)) {
                continue;
            }
            best = pickBetterListener(best, new PortListener(targetPort, parsePositivePid(cols[4]), localAddr));
        }
        return Optional.ofNullable(best);
    }

    public static Optional<PortListener> parseLinuxSs(String output, int targetPort) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        PortListener best = null;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("State") || trimmed.startsWith("Netid")) {
                continue;
            }
            String[] cols = trimmed.split("\\s+");
            if (cols.length < 5) {
                continue;
            }
            int localIdx = cols[0].equalsIgnoreCase("LISTEN") ? 3 : 4;
            if (cols.length <= localIdx) {
                continue;
            }
            String localAddr = cols[localIdx];
            if (!matchesLoopbackOrWildcardPort(localAddr, targetPort)) {
                continue;
            }
            Optional<Long> pid = Optional.empty();
            Matcher m = SS_PID_PATTERN.matcher(trimmed);
            if (m.find()) {
                pid = parsePositivePid(m.group(1));
            }
            best = pickBetterListener(best, new PortListener(targetPort, pid, localAddr));
        }
        return Optional.ofNullable(best);
    }

    public static Optional<PortListener> parsePosixLsof(String output, int targetPort) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        PortListener best = null;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("COMMAND")) {
                continue;
            }
            String[] cols = trimmed.split("\\s+");
            if (cols.length < 9) {
                continue;
            }
            Optional<Long> pid = parsePositivePid(cols[1]);
            for (int i = 7; i < cols.length; i++) {
                if (matchesLoopbackOrWildcardPort(cols[i], targetPort)) {
                    best = pickBetterListener(best, new PortListener(targetPort, pid, cols[i]));
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static PortListener pickBetterListener(PortListener current, PortListener candidate) {
        if (current == null) {
            return candidate;
        }
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
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            return p.exitValue() == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static boolean matchesLoopbackOrWildcardPort(String localAddr, int targetPort) {
        int colon = localAddr.lastIndexOf(':');
        if (colon <= 0 || colon >= localAddr.length() - 1) {
            return false;
        }
        int parsedPort;
        try {
            parsedPort = Integer.parseInt(localAddr.substring(colon + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        if (parsedPort != targetPort) {
            return false;
        }
        String host = stripInterfaceScope(localAddr.substring(0, colon)).toLowerCase(Locale.ROOT);
        return switch (host) {
            case "127.0.0.1", "0.0.0.0", "[::1]", "[::]", "::1", "::", "*", "[::ffff:127.0.0.1]", "::ffff:127.0.0.1" -> true;
            default -> false;
        };
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
