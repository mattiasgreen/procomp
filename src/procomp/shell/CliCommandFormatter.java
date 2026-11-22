package procomp.shell;

import procomp.core.routing.Route;
import procomp.shell.GatewayShell.GatewayStartRequest;
import procomp.shell.LogsShell.LogsRequest;
import procomp.shell.ProcessShell.StartRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure, zero-I/O formatter producing verbatim, copy-pasteable {@code procomp [--config <path>] ...}
 * CLI command strings for every v1 operation.
 */
public final class CliCommandFormatter {

    private CliCommandFormatter() {}

    public static String status(Optional<Path> configOverride, boolean json) {
        List<String> tokens = base(configOverride, "status");
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public static String showRoutes(Optional<Path> configOverride, boolean json) {
        List<String> tokens = base(configOverride, "route");
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public static String updateRoutes(Optional<Path> configOverride, List<String> services, Route route) {
        Objects.requireNonNull(services, "services must not be null");
        Objects.requireNonNull(route, "route must not be null");
        List<String> tokens = base(configOverride, "route");
        for (String svc : services) {
            tokens.add(quoteArg(svc));
        }
        tokens.add(route.name().toLowerCase(Locale.ROOT));
        return join(tokens);
    }

    public static String listProfiles(Optional<Path> configOverride, boolean json) {
        List<String> tokens = base(configOverride, "profile");
        tokens.add("list");
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public static String useProfile(Optional<Path> configOverride, String profileName) {
        Objects.requireNonNull(profileName, "profileName must not be null");
        List<String> tokens = base(configOverride, "profile");
        tokens.add("use");
        tokens.add(quoteArg(profileName));
        return join(tokens);
    }

    public static String start(
        Optional<Path> configOverride,
        List<String> services,
        boolean detach,
        Duration timeout
    ) {
        Objects.requireNonNull(services, "services must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        List<String> tokens = base(configOverride, "start");
        for (String svc : services) {
            tokens.add(quoteArg(svc));
        }
        if (detach) {
            tokens.add("-d");
        }
        if (!timeout.equals(StartRequest.DEFAULT_TIMEOUT)) {
            tokens.add("--timeout");
            tokens.add(Long.toString(timeout.toSeconds()));
        }
        return join(tokens);
    }

    public static String stop(
        Optional<Path> configOverride,
        List<String> services,
        boolean all,
        boolean killConflict
    ) {
        Objects.requireNonNull(services, "services must not be null");
        List<String> tokens = base(configOverride, "stop");
        for (String svc : services) {
            tokens.add(quoteArg(svc));
        }
        if (all) {
            tokens.add("--all");
        }
        if (killConflict) {
            tokens.add("--kill-conflict");
        }
        return join(tokens);
    }

    public static String inspect(Optional<Path> configOverride, String service, boolean json) {
        Objects.requireNonNull(service, "service must not be null");
        List<String> tokens = base(configOverride, "inspect");
        tokens.add(quoteArg(service));
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public static String logs(
        Optional<Path> configOverride,
        String service,
        boolean stderr,
        boolean pathOnly,
        int lines,
        boolean follow
    ) {
        Objects.requireNonNull(service, "service must not be null");
        List<String> tokens = base(configOverride, "logs");
        tokens.add(quoteArg(service));
        if (!pathOnly && lines != LogsRequest.DEFAULT_LINES) {
            tokens.add("-n");
            tokens.add(Integer.toString(lines));
        }
        if (follow) {
            tokens.add("-f");
        }
        if (stderr) {
            tokens.add("--err");
        }
        if (pathOnly) {
            tokens.add("--path");
        }
        return join(tokens);
    }

    public static String gatewayStart(Optional<Path> configOverride, boolean detach, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        List<String> tokens = base(configOverride, "gateway");
        tokens.add("start");
        if (detach) {
            tokens.add("-d");
        }
        if (!timeout.equals(GatewayStartRequest.DEFAULT_TIMEOUT)) {
            tokens.add("--timeout");
            tokens.add(Long.toString(timeout.toSeconds()));
        }
        return join(tokens);
    }

    public static String gatewayStop(Optional<Path> configOverride) {
        List<String> tokens = base(configOverride, "gateway");
        tokens.add("stop");
        return join(tokens);
    }

    public static String gatewayStatus(Optional<Path> configOverride, boolean json) {
        List<String> tokens = base(configOverride, "gateway");
        tokens.add("status");
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public static String gatewayReconcile(Optional<Path> configOverride) {
        List<String> tokens = base(configOverride, "gateway");
        tokens.add("reconcile");
        return join(tokens);
    }

    public static String quoteArg(String arg) {
        Objects.requireNonNull(arg, "arg must not be null");
        if (arg.isEmpty()) {
            return "\"\"";
        }
        if (isSafeUnquoted(arg)) {
            return arg;
        }
        StringBuilder sb = new StringBuilder(arg.length() + 2);
        sb.append('"');
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static List<String> base(Optional<Path> configOverride, String command) {
        Objects.requireNonNull(configOverride, "configOverride must not be null");
        List<String> tokens = new ArrayList<>();
        tokens.add("procomp");
        if (configOverride.isPresent()) {
            tokens.add("--config");
            tokens.add(quoteArg(configOverride.get().toString()));
        }
        tokens.add(command);
        return tokens;
    }

    private static boolean isSafeUnquoted(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '/' || c == '\\'
                || c == ':' || c == '@' || c == '=' || c == '+';
            if (!safe) {
                return false;
            }
        }
        return true;
    }

    private static String join(List<String> tokens) {
        return String.join(" ", tokens);
    }
}
