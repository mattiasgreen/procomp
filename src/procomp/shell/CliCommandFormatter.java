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

/**
 * Pure, zero-I/O formatter producing verbatim, copy-pasteable {@code procomp [--config <path>] ...}
 * CLI command strings for every v1 operation.
 */
public final class CliCommandFormatter {

    private static final CliCommandFormatter PLAIN = new CliCommandFormatter(List.of("procomp"));

    private final List<String> prefixTokens;

    private CliCommandFormatter(List<String> prefixTokens) {
        this.prefixTokens = prefixTokens;
    }

    public static CliCommandFormatter plain() {
        return PLAIN;
    }

    public static CliCommandFormatter withConfig(Path configOverride) {
        Objects.requireNonNull(configOverride, "configOverride must not be null");
        return new CliCommandFormatter(List.of("procomp", "--config", quoteArg(configOverride.toString())));
    }

    public String status(boolean json) {
        List<String> tokens = base("status");
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public String showRoutes(boolean json) {
        List<String> tokens = base("route");
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public String updateRoutes(List<String> services, Route route) {
        Objects.requireNonNull(services, "services must not be null");
        Objects.requireNonNull(route, "route must not be null");
        List<String> tokens = base("route");
        for (String svc : services) {
            tokens.add(quoteArg(svc));
        }
        tokens.add(route.name().toLowerCase(Locale.ROOT));
        return join(tokens);
    }

    public String listProfiles(boolean json) {
        List<String> tokens = base("profile");
        tokens.add("list");
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public String useProfile(String profileName) {
        Objects.requireNonNull(profileName, "profileName must not be null");
        List<String> tokens = base("profile");
        tokens.add("use");
        tokens.add(quoteArg(profileName));
        return join(tokens);
    }

    public String saveProfileSnapshot(String profileName) {
        Objects.requireNonNull(profileName, "profileName must not be null");
        List<String> tokens = base("profile");
        tokens.add("save");
        tokens.add(quoteArg(profileName));
        return join(tokens);
    }

    public String saveProfile(String profileName, List<String> localServices) {
        Objects.requireNonNull(profileName, "profileName must not be null");
        Objects.requireNonNull(localServices, "localServices must not be null");
        List<String> tokens = base("profile");
        tokens.add("save");
        tokens.add(quoteArg(profileName));
        if (localServices.isEmpty()) {
            tokens.add("--none");
        } else {
            for (String svc : localServices) {
                tokens.add(quoteArg(svc));
            }
        }
        return join(tokens);
    }

    public String deleteProfile(String profileName) {
        Objects.requireNonNull(profileName, "profileName must not be null");
        List<String> tokens = base("profile");
        tokens.add("delete");
        tokens.add(quoteArg(profileName));
        return join(tokens);
    }

    public String start(List<String> services, boolean detach, Duration timeout) {
        Objects.requireNonNull(services, "services must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        List<String> tokens = base("start");
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

    public String stop(List<String> services, boolean all, boolean killConflict) {
        Objects.requireNonNull(services, "services must not be null");
        List<String> tokens = base("stop");
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

    public String inspect(String service, boolean json) {
        Objects.requireNonNull(service, "service must not be null");
        List<String> tokens = base("inspect");
        tokens.add(quoteArg(service));
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public String build(List<String> services, boolean force) {
        Objects.requireNonNull(services, "services must not be null");
        List<String> tokens = base("build");
        for (String svc : services) {
            tokens.add(quoteArg(svc));
        }
        if (force) {
            tokens.add("--force");
        }
        return join(tokens);
    }

    public String buildCancel(String service) {
        Objects.requireNonNull(service, "service must not be null");
        List<String> tokens = base("build");
        tokens.add(quoteArg(service));
        tokens.add("--cancel");
        return join(tokens);
    }

    public String logs(
        String service,
        boolean stderr,
        boolean pathOnly,
        int lines,
        boolean follow
    ) {
        return logs(service, stderr, pathOnly, lines, follow, false);
    }

    public String logs(
        String service,
        boolean stderr,
        boolean pathOnly,
        int lines,
        boolean follow,
        boolean build
    ) {
        Objects.requireNonNull(service, "service must not be null");
        List<String> tokens = base("logs");
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
        if (build) {
            tokens.add("--build");
        }
        return join(tokens);
    }

    public String gatewayStart(boolean detach, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        List<String> tokens = base("gateway");
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

    public String gatewayStop() {
        List<String> tokens = base("gateway");
        tokens.add("stop");
        return join(tokens);
    }

    public String gatewayStatus(boolean json) {
        List<String> tokens = base("gateway");
        tokens.add("status");
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public String gatewayReconcile() {
        List<String> tokens = base("gateway");
        tokens.add("reconcile");
        return join(tokens);
    }

    public String gatewayLogs(boolean stderr, boolean pathOnly, int lines, boolean follow) {
        List<String> tokens = base("gateway");
        tokens.add("logs");
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

    public String gitHooksStatus(boolean json) {
        List<String> tokens = base("git");
        tokens.add("hooks");
        tokens.add("status");
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public String gitHooksInstall(List<String> repositories) {
        Objects.requireNonNull(repositories, "repositories must not be null");
        List<String> tokens = base("git");
        tokens.add("hooks");
        tokens.add("install");
        for (String repo : repositories) {
            tokens.add(quoteArg(repo));
        }
        return join(tokens);
    }

    public String gitStatus(boolean localOnly, boolean fetch, boolean all, boolean json) {
        List<String> tokens = base("git");
        tokens.add("status");
        if (localOnly) {
            tokens.add("--local");
        }
        if (fetch) {
            tokens.add("--fetch");
        }
        if (all) {
            tokens.add("--all");
        }
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public String gitBranches(String target, boolean json) {
        Objects.requireNonNull(target, "target must not be null");
        List<String> tokens = base("git");
        tokens.add("branches");
        tokens.add(quoteArg(target));
        if (json) {
            tokens.add("--json");
        }
        return join(tokens);
    }

    public String gitOp(String subcommand, List<String> targets, boolean all, boolean force) {
        Objects.requireNonNull(subcommand, "subcommand must not be null");
        Objects.requireNonNull(targets, "targets must not be null");
        List<String> tokens = base("git");
        tokens.add(subcommand);
        for (String target : targets) {
            tokens.add(quoteArg(target));
        }
        if (all) {
            tokens.add("--all");
        }
        if (force) {
            tokens.add("--force");
        }
        return join(tokens);
    }

    public String gitSwitch(String branch, boolean useDefault, List<String> targets, boolean all, boolean force) {
        Objects.requireNonNull(targets, "targets must not be null");
        List<String> tokens = base("git");
        tokens.add("switch");
        if (useDefault) {
            tokens.add("--default");
        } else {
            tokens.add(quoteArg(Objects.requireNonNull(branch, "branch must not be null")));
        }
        for (String target : targets) {
            tokens.add(quoteArg(target));
        }
        if (all) {
            tokens.add("--all");
        }
        if (force) {
            tokens.add("--force");
        }
        return join(tokens);
    }

    public String gitStashPush(List<String> targets, boolean all, String message, boolean includeUntracked, boolean force) {
        Objects.requireNonNull(targets, "targets must not be null");
        List<String> tokens = base("git");
        tokens.add("stash");
        tokens.add("push");
        for (String target : targets) {
            tokens.add(quoteArg(target));
        }
        if (all) {
            tokens.add("--all");
        }
        if (includeUntracked) {
            tokens.add("-u");
        }
        if (message != null && !message.isBlank()) {
            tokens.add("-m");
            tokens.add(quoteArg(message));
        }
        if (force) {
            tokens.add("--force");
        }
        return join(tokens);
    }

    public String gitCancel(List<String> targets, boolean all) {
        Objects.requireNonNull(targets, "targets must not be null");
        List<String> tokens = base("git");
        tokens.add("cancel");
        for (String target : targets) {
            tokens.add(quoteArg(target));
        }
        if (all) {
            tokens.add("--all");
        }
        return join(tokens);
    }

    public String gitLogs(String target, boolean stderr, boolean pathOnly, int lines, boolean follow) {
        Objects.requireNonNull(target, "target must not be null");
        List<String> tokens = base("git");
        tokens.add("logs");
        tokens.add(quoteArg(target));
        if (stderr) {
            tokens.add("--err");
        }
        if (pathOnly) {
            tokens.add("--path");
        }
        if (lines != LogsRequest.DEFAULT_LINES) {
            tokens.add("-n");
            tokens.add(Integer.toString(lines));
        }
        if (follow) {
            tokens.add("-f");
        }
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

    private List<String> base(String command) {
        List<String> tokens = new ArrayList<>(prefixTokens.size() + 1);
        tokens.addAll(prefixTokens);
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
