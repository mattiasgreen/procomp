package procomp.cli;

import procomp.core.common.Json;
import procomp.core.common.Result;
import procomp.core.git.BranchInfo;
import procomp.core.git.GitEngine;
import procomp.core.git.GitRunRecord;
import procomp.core.git.RepositoryObservation;
import procomp.core.git.StashEntry;
import procomp.core.project.Project;
import procomp.shell.DomainJson;
import procomp.shell.GitShell;
import procomp.shell.LogsShell.GitLogsRequest;
import procomp.shell.LogsShell.LogsRequest;
import procomp.shell.LogsShell.LogsResult;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CLI argument parser and renderer for {@code procomp git <status|branches|clone|fetch|pull|switch|stash|cancel|logs>}.
 */
public final class GitCli {

    private static final DateTimeFormatter COMMIT_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneOffset.UTC);

    public sealed interface Action permits
        Action.Status,
        Action.Branches,
        Action.Clone,
        Action.Fetch,
        Action.Pull,
        Action.Switch,
        Action.StashPush,
        Action.StashList,
        Action.Cancel,
        Action.Logs {

        record Status(List<String> targets, boolean localOnly, boolean fetch, boolean all, boolean force, Optional<Duration> timeout, boolean json) implements Action {}
        record Branches(String target, boolean json) implements Action {}
        record Clone(List<String> targets, boolean all, boolean force, Optional<Duration> timeout, boolean json) implements Action {}
        record Fetch(List<String> targets, boolean all, boolean force, Optional<Duration> timeout, boolean json) implements Action {}
        record Pull(List<String> targets, boolean all, boolean force, Optional<Duration> timeout, boolean json) implements Action {}
        record Switch(Optional<String> branch, boolean useDefault, List<String> targets, boolean all, boolean force, Optional<Duration> timeout, boolean json) implements Action {}
        record StashPush(List<String> targets, boolean all, Optional<String> message, boolean includeUntracked, boolean force, Optional<Duration> timeout, boolean json) implements Action {}
        record StashList(List<String> targets, boolean all, boolean json) implements Action {}
        record Cancel(List<String> targets, boolean all, boolean json) implements Action {}
        record Logs(GitLogsRequest request) implements Action {}
    }

    private GitCli() {}

    public static Result<Action, String> parse(List<String> args) {
        if (args.isEmpty()) {
            return Result.err("Missing 'git' subcommand. Usage: procomp git <status|branches|clone|fetch|pull|switch|stash|cancel|logs> [options]");
        }
        String sub = args.get(0);
        List<String> rest = args.subList(1, args.size());
        return switch (sub) {
            case "status" -> parseStatus(rest);
            case "branches" -> parseBranches(rest);
            case "clone" -> parseBatchOp("clone", rest).map(o -> new Action.Clone(o.targets, o.all, o.force, o.timeout, o.json));
            case "fetch" -> parseBatchOp("fetch", rest).map(o -> new Action.Fetch(o.targets, o.all, o.force, o.timeout, o.json));
            case "pull" -> parseBatchOp("pull", rest).map(o -> new Action.Pull(o.targets, o.all, o.force, o.timeout, o.json));
            case "switch" -> parseSwitch(rest);
            case "stash" -> parseStash(rest);
            case "cancel" -> parseCancel(rest);
            case "logs" -> parseLogs(rest);
            default -> Result.err("Unknown 'git' subcommand '" + sub + "'. Supported: status, branches, clone, fetch, pull, switch, stash, cancel, logs.");
        };
    }

    static int execute(Action action, Project project, CliRuntime runtime) {
        return switch (action) {
            case Action.Status s -> {
                Result<GitShell.GitStatusReport, procomp.core.common.DomainError> res =
                    runtime.gitShell().flatMap(shell -> shell.status(project, s.targets(), s.localOnly(), s.fetch(), s.all(), s.force(), s.timeout()));
                if (res instanceof Result.Err<GitShell.GitStatusReport, procomp.core.common.DomainError>(var err)) {
                    runtime.err().println("Error: " + err.format());
                    yield 1;
                }
                GitShell.GitStatusReport report = ((Result.Ok<GitShell.GitStatusReport, procomp.core.common.DomainError>) res).value();
                if (s.json()) {
                    runtime.out().println(Json.stringify(DomainJson.gitStatusReport(report)));
                } else {
                    if (report.fetchReport().isPresent()) {
                        renderBatchText(report.fetchReport().get(), runtime.out());
                    }
                    renderStatusText(report, runtime.out());
                }
                yield report.fetchReport().map(GitShell.GitBatchReport::hasFailures).orElse(false) ? 1 : 0;
            }
            case Action.Branches b -> runtime.handleResult(
                runtime.gitShell().flatMap(shell -> shell.branches(project, b.target())),
                report -> {
                    if (b.json()) {
                        runtime.out().println(Json.stringify(DomainJson.gitBranchesReport(report)));
                    } else {
                        renderBranchesText(report, runtime.out());
                    }
                }
            );
            case Action.Clone c -> executeBatch(
                runtime.gitShell().flatMap(shell -> shell.cloneRepositories(project, c.targets(), c.all(), c.force(), c.timeout())),
                c.json(), runtime
            );
            case Action.Fetch f -> executeBatch(
                runtime.gitShell().flatMap(shell -> shell.fetch(project, f.targets(), f.all(), f.force(), f.timeout())),
                f.json(), runtime
            );
            case Action.Pull p -> executeBatch(
                runtime.gitShell().flatMap(shell -> shell.pull(project, p.targets(), p.all(), p.force(), p.timeout())),
                p.json(), runtime
            );
            case Action.Switch sw -> executeBatch(
                runtime.gitShell().flatMap(shell -> shell.switchBranch(project, sw.branch(), sw.useDefault(), sw.targets(), sw.all(), sw.force(), sw.timeout())),
                sw.json(), runtime
            );
            case Action.StashPush sp -> executeBatch(
                runtime.gitShell().flatMap(shell -> shell.stashPush(project, sp.targets(), sp.all(), sp.message(), sp.includeUntracked(), sp.force(), sp.timeout())),
                sp.json(), runtime
            );
            case Action.StashList sl -> runtime.handleResult(
                runtime.gitShell().flatMap(shell -> shell.stashList(project, sl.targets(), sl.all())),
                items -> {
                    if (sl.json()) {
                        runtime.out().println(Json.stringify(DomainJson.gitStashList(items)));
                    } else {
                        renderStashListText(items, runtime.out());
                    }
                }
            );
            case Action.Cancel c -> {
                Result<GitShell.GitCancelReport, procomp.core.common.DomainError> res =
                    runtime.gitShell().flatMap(shell -> shell.cancel(project, c.targets(), c.all()));
                if (res instanceof Result.Err<GitShell.GitCancelReport, procomp.core.common.DomainError>(var err)) {
                    runtime.err().println("Error: " + err.format());
                    yield 1;
                }
                GitShell.GitCancelReport report = ((Result.Ok<GitShell.GitCancelReport, procomp.core.common.DomainError>) res).value();
                if (c.json()) {
                    runtime.out().println(Json.stringify(DomainJson.gitCancelReport(report)));
                } else {
                    renderCancelText(report, runtime.out());
                }
                yield report.hasFailures() ? 1 : 0;
            }
            case Action.Logs l -> runtime.handleResult(
                runtime.logsShell().flatMap(shell -> shell.runGit(project, l.request())),
                result -> {
                    switch (result) {
                        case LogsResult.ResolvedPath(Path path) -> runtime.out().println(path);
                        case LogsResult.RawTail(_, String text) -> {
                            runtime.out().print(text);
                            runtime.out().flush();
                        }
                        case LogsResult.Followed _ -> runtime.out().flush();
                    }
                }
            );
        };
    }

    private static int executeBatch(
        Result<GitShell.GitBatchReport, procomp.core.common.DomainError> res,
        boolean json,
        CliRuntime runtime
    ) {
        if (res instanceof Result.Err<GitShell.GitBatchReport, procomp.core.common.DomainError>(var err)) {
            runtime.err().println("Error: " + err.format());
            return 1;
        }
        GitShell.GitBatchReport report = ((Result.Ok<GitShell.GitBatchReport, procomp.core.common.DomainError>) res).value();
        if (json) {
            runtime.out().println(Json.stringify(DomainJson.gitBatchReport(report)));
        } else {
            renderBatchText(report, runtime.out());
        }
        return report.hasFailures() ? 1 : 0;
    }

    private record BatchOpts(List<String> targets, boolean all, boolean force, Optional<Duration> timeout, boolean json) {}

    private static Result<BatchOpts, String> parseBatchOp(String op, List<String> args) {
        List<String> targets = new ArrayList<>();
        boolean all = false;
        boolean force = false;
        boolean json = false;
        Optional<Duration> timeout = Optional.empty();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--all" -> all = true;
                case "--force" -> force = true;
                case "--json" -> json = true;
                case "--timeout" -> {
                    if (i + 1 >= args.size()) {
                        return Result.err("Option '--timeout' requires a positive integer number of seconds.");
                    }
                    Result<Duration, String> tRes = parsePositiveSeconds(args.get(++i));
                    if (tRes.isErr()) return tRes.castError();
                    timeout = Optional.of(((Result.Ok<Duration, String>) tRes).value());
                }
                default -> {
                    if (arg.startsWith("--timeout=")) {
                        Result<Duration, String> tRes = parsePositiveSeconds(arg.substring("--timeout=".length()));
                        if (tRes.isErr()) return tRes.castError();
                        timeout = Optional.of(((Result.Ok<Duration, String>) tRes).value());
                    } else if (arg.startsWith("-")) {
                        return Result.err("Unknown option '" + arg + "' for 'git " + op + "'.");
                    } else {
                        targets.add(arg);
                    }
                }
            }
        }
        return Result.ok(new BatchOpts(targets, all, force, timeout, json));
    }

    private static Result<Action, String> parseStatus(List<String> args) {
        List<String> targets = new ArrayList<>();
        boolean localOnly = false;
        boolean fetch = false;
        boolean all = false;
        boolean force = false;
        boolean json = false;
        Optional<Duration> timeout = Optional.empty();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--local" -> localOnly = true;
                case "--fetch" -> fetch = true;
                case "--all" -> all = true;
                case "--force" -> force = true;
                case "--json" -> json = true;
                case "--timeout" -> {
                    if (i + 1 >= args.size()) {
                        return Result.err("Option '--timeout' requires a positive integer number of seconds.");
                    }
                    Result<Duration, String> tRes = parsePositiveSeconds(args.get(++i));
                    if (tRes.isErr()) return tRes.castError();
                    timeout = Optional.of(((Result.Ok<Duration, String>) tRes).value());
                }
                default -> {
                    if (arg.startsWith("--timeout=")) {
                        Result<Duration, String> tRes = parsePositiveSeconds(arg.substring("--timeout=".length()));
                        if (tRes.isErr()) return tRes.castError();
                        timeout = Optional.of(((Result.Ok<Duration, String>) tRes).value());
                    } else if (arg.startsWith("-")) {
                        return Result.err("Unknown option '" + arg + "' for 'git status'.");
                    } else {
                        targets.add(arg);
                    }
                }
            }
        }
        return Result.ok(new Action.Status(targets, localOnly, fetch, all, force, timeout, json));
    }

    private static Result<Action, String> parseBranches(List<String> args) {
        String target = null;
        boolean json = false;
        for (String arg : args) {
            if ("--json".equals(arg)) {
                json = true;
            } else if (arg.startsWith("-")) {
                return Result.err("Unknown option '" + arg + "' for 'git branches'. Usage: procomp git branches <target> [--json]");
            } else if (target == null) {
                target = arg;
            } else {
                return Result.err("Unexpected extra argument '" + arg + "'. Usage: procomp git branches <target> [--json]");
            }
        }
        if (target == null || target.isBlank()) {
            return Result.err("Missing required <target>. Usage: procomp git branches <target> [--json]");
        }
        return Result.ok(new Action.Branches(target, json));
    }

    private static Result<Action, String> parseSwitch(List<String> args) {
        Optional<String> branch = Optional.empty();
        boolean useDefault = false;
        List<String> targets = new ArrayList<>();
        boolean all = false;
        boolean force = false;
        boolean json = false;
        Optional<Duration> timeout = Optional.empty();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--default" -> useDefault = true;
                case "--all" -> all = true;
                case "--force" -> force = true;
                case "--json" -> json = true;
                case "--timeout" -> {
                    if (i + 1 >= args.size()) {
                        return Result.err("Option '--timeout' requires a positive integer number of seconds.");
                    }
                    Result<Duration, String> tRes = parsePositiveSeconds(args.get(++i));
                    if (tRes.isErr()) return tRes.castError();
                    timeout = Optional.of(((Result.Ok<Duration, String>) tRes).value());
                }
                default -> {
                    if (arg.startsWith("--timeout=")) {
                        Result<Duration, String> tRes = parsePositiveSeconds(arg.substring("--timeout=".length()));
                        if (tRes.isErr()) return tRes.castError();
                        timeout = Optional.of(((Result.Ok<Duration, String>) tRes).value());
                    } else if (arg.startsWith("-")) {
                        return Result.err("Unknown option '" + arg + "' for 'git switch'.");
                    } else if (!useDefault && branch.isEmpty()) {
                        branch = Optional.of(arg);
                    } else {
                        targets.add(arg);
                    }
                }
            }
        }
        if (useDefault && branch.isPresent()) {
            return Result.err("Cannot specify both <branch> and --default for 'git switch'.");
        }
        if (!useDefault && branch.isEmpty()) {
            return Result.err("Missing required <branch> or --default. Usage: procomp git switch <branch|--default> [target...]");
        }
        return Result.ok(new Action.Switch(branch, useDefault, targets, all, force, timeout, json));
    }

    private static Result<Action, String> parseStash(List<String> args) {
        if (!args.isEmpty() && "list".equals(args.get(0))) {
            List<String> targets = new ArrayList<>();
            boolean all = false;
            boolean json = false;
            for (int i = 1; i < args.size(); i++) {
                String arg = args.get(i);
                switch (arg) {
                    case "--all" -> all = true;
                    case "--json" -> json = true;
                    default -> {
                        if (arg.startsWith("-")) {
                            return Result.err("Unknown option '" + arg + "' for 'git stash list'.");
                        }
                        targets.add(arg);
                    }
                }
            }
            return Result.ok(new Action.StashList(targets, all, json));
        }

        int startIdx = (!args.isEmpty() && "push".equals(args.get(0))) ? 1 : 0;
        List<String> targets = new ArrayList<>();
        boolean all = false;
        boolean includeUntracked = false;
        boolean force = false;
        boolean json = false;
        Optional<String> message = Optional.empty();
        Optional<Duration> timeout = Optional.empty();

        for (int i = startIdx; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--all" -> all = true;
                case "-u", "--include-untracked" -> includeUntracked = true;
                case "--force" -> force = true;
                case "--json" -> json = true;
                case "-m", "--message" -> {
                    if (i + 1 >= args.size()) {
                        return Result.err("Option '" + arg + "' requires a message.");
                    }
                    message = Optional.of(args.get(++i));
                }
                case "--timeout" -> {
                    if (i + 1 >= args.size()) {
                        return Result.err("Option '--timeout' requires a positive integer number of seconds.");
                    }
                    Result<Duration, String> tRes = parsePositiveSeconds(args.get(++i));
                    if (tRes.isErr()) return tRes.castError();
                    timeout = Optional.of(((Result.Ok<Duration, String>) tRes).value());
                }
                default -> {
                    if (arg.startsWith("--timeout=")) {
                        Result<Duration, String> tRes = parsePositiveSeconds(arg.substring("--timeout=".length()));
                        if (tRes.isErr()) return tRes.castError();
                        timeout = Optional.of(((Result.Ok<Duration, String>) tRes).value());
                    } else if (arg.startsWith("-")) {
                        return Result.err("Unknown option '" + arg + "' for 'git stash'.");
                    } else {
                        targets.add(arg);
                    }
                }
            }
        }
        return Result.ok(new Action.StashPush(targets, all, message, includeUntracked, force, timeout, json));
    }

    private static Result<Action, String> parseCancel(List<String> args) {
        List<String> targets = new ArrayList<>();
        boolean all = false;
        boolean json = false;
        for (String arg : args) {
            switch (arg) {
                case "--all" -> all = true;
                case "--json" -> json = true;
                default -> {
                    if (arg.startsWith("-")) {
                        return Result.err("Unknown option '" + arg + "' for 'git cancel'.");
                    }
                    targets.add(arg);
                }
            }
        }
        return Result.ok(new Action.Cancel(targets, all, json));
    }

    private static Result<Action, String> parseLogs(List<String> args) {
        String target = null;
        boolean stderr = false;
        boolean pathOnly = false;
        int lines = LogsRequest.DEFAULT_LINES;
        boolean follow = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--err" -> stderr = true;
                case "--path" -> pathOnly = true;
                case "-f", "--follow" -> follow = true;
                case "-n", "--lines" -> {
                    if (i + 1 >= args.size()) {
                        return Result.err("Option '" + arg + "' requires a non-negative integer line count.");
                    }
                    Result<Integer, String> parsed = LogsCli.parseNonNegativeInt(args.get(++i));
                    if (parsed.isErr()) return parsed.castError();
                    lines = ((Result.Ok<Integer, String>) parsed).value();
                }
                default -> {
                    if (arg.startsWith("-")) {
                        return Result.err("Unknown option '" + arg + "' for 'git logs'.");
                    }
                    if (target == null) {
                        target = arg;
                    } else {
                        return Result.err("Unexpected extra argument '" + arg + "' for 'git logs'.");
                    }
                }
            }
        }
        if (target == null || target.isBlank()) {
            return Result.err("Missing required <target>. Usage: procomp git logs <target> [-n <lines>] [-f] [--err] [--path]");
        }
        return Result.ok(new Action.Logs(new GitLogsRequest(target, stderr, pathOnly, lines, follow)));
    }

    private static Result<Duration, String> parsePositiveSeconds(String raw) {
        try {
            long secs = Long.parseLong(raw.trim());
            if (secs <= 0) {
                return Result.err("Timeout must be a positive integer number of seconds, got '" + raw + "'.");
            }
            return Result.ok(Duration.ofSeconds(secs));
        } catch (NumberFormatException e) {
            return Result.err("Invalid timeout '" + raw + "': must be a positive integer number of seconds.");
        }
    }

    private static void renderStatusText(GitShell.GitStatusReport report, PrintWriter out) {
        out.println("Project: " + report.projectName());
        if (report.repositories().isEmpty()) {
            out.println("No repositories matched.");
            return;
        }
        List<List<String>> rows = new ArrayList<>(report.repositories().size());
        for (GitShell.RepositoryStatusRow row : report.repositories()) {
            String state;
            String branch = "-";
            String upstream = "-";
            String dirty = "-";
            String stashes = "-";
            String fetched = "-";
            switch (row.observation()) {
                case RepositoryObservation.NotCloned _ -> state = "NOT_CLONED";
                case RepositoryObservation.NotAGitRepository _ -> state = "NOT_GIT";
                case RepositoryObservation.Cloned c -> {
                    state = "CLONED";
                    branch = switch (c.head()) {
                        case RepositoryObservation.HeadState.Branch(String n) -> n;
                        case RepositoryObservation.HeadState.Detached(String sha) -> "DETACHED@" + sha;
                    };
                    String ab = (c.ahead() > 0 || c.behind() > 0)
                        ? " (↑" + c.ahead() + " ↓" + c.behind() + ")"
                        : "";
                    upstream = c.upstream().map(u -> u + ab).orElse("-");
                    dirty = c.isDirty() ? (c.dirtyCount() + " (" + c.stagedCount() + "s/" + c.unstagedCount() + "m/" + c.untrackedCount() + "u)") : "clean";
                    stashes = Integer.toString(c.stashCount());
                    fetched = c.lastFetchedAt()
                        .map(t -> GitEngine.formatShortAge(Duration.between(t, report.observedAt())) + " ago" + (row.fetchAgeWarning() ? " (!)" : ""))
                        .orElse("never");
                }
            }
            String lastRun = row.latestRun()
                .map(r -> r.effectiveStatus().name() + "(" + r.record().operation().cliName() + ")")
                .orElse("-");
            rows.add(List.of(
                row.repository().name(),
                String.join(", ", row.services()),
                state,
                branch,
                upstream,
                dirty,
                stashes,
                fetched,
                lastRun
            ));
        }
        CliFormat.renderTable(
            out,
            List.of("REPOSITORY", "SERVICES", "STATE", "BRANCH", "UPSTREAM", "DIRTY", "STASHES", "FETCHED", "LAST_RUN"),
            rows
        );
    }

    private static void renderBranchesText(GitShell.BranchesReport report, PrintWriter out) {
        out.println("Repository: " + report.repository().name() + " (services: " + String.join(", ", report.services()) + ")");
        if (report.branches().isEmpty()) {
            out.println("No branches found.");
            return;
        }
        List<List<String>> rows = new ArrayList<>(report.branches().size());
        for (BranchInfo b : report.branches()) {
            rows.add(List.of(
                (b.current() ? "* " : "  ") + b.name(),
                b.origin().name(),
                COMMIT_TIME_FMT.format(b.lastCommitAt()) + "Z",
                b.shortSubject()
            ));
        }
        CliFormat.renderTable(out, List.of("BRANCH", "ORIGIN", "LAST_COMMIT", "SUBJECT"), rows);
    }

    private static void renderStashListText(List<GitShell.RepositoryStashes> items, PrintWriter out) {
        if (items.isEmpty()) {
            out.println("No repositories matched.");
            return;
        }
        for (GitShell.RepositoryStashes item : items) {
            out.println(item.repository().name() + ":");
            if (item.stashes().isEmpty()) {
                out.println("  (no stashes)");
            } else {
                for (StashEntry e : item.stashes()) {
                    out.println("  " + e.ref() + ": " + e.message());
                }
            }
        }
    }

    private static void renderBatchText(GitShell.GitBatchReport report, PrintWriter out) {
        if (report.outcomes().isEmpty()) {
            out.println("No repositories matched for 'git " + report.operation().cliName() + "'.");
            return;
        }
        for (GitShell.GitRepoOutcome outcome : report.outcomes()) {
            switch (outcome) {
                case GitShell.GitRepoOutcome.Succeeded(var repo, GitRunRecord rec) ->
                    out.println("OK      " + repo.name() + " (" + rec.operation().cliName() + ")");
                case GitShell.GitRepoOutcome.Skipped(var repo, String reason) ->
                    out.println("SKIPPED " + repo.name() + " (" + reason + ")");
                case GitShell.GitRepoOutcome.Failed(var repo, var err, _) ->
                    out.println("FAILED  " + repo.name() + ": " + err.format());
            }
        }
    }

    private static void renderCancelText(GitShell.GitCancelReport report, PrintWriter out) {
        if (report.outcomes().isEmpty()) {
            out.println("No repositories matched for 'git cancel'.");
            return;
        }
        for (GitShell.CancelOutcome outcome : report.outcomes()) {
            switch (outcome) {
                case GitShell.CancelOutcome.Cancelled(var repo, GitRunRecord rec) ->
                    out.println("CANCELLED " + repo.name() + " (PID " + rec.pid() + ")");
                case GitShell.CancelOutcome.NoActiveRun(var repo, _) ->
                    out.println("IDLE      " + repo.name() + " (no active Git Run)");
                case GitShell.CancelOutcome.Failed(var repo, var err) ->
                    out.println("FAILED    " + repo.name() + ": " + err.format());
            }
        }
    }
}
