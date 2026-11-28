package procomp.cli;

import procomp.core.common.Json;
import procomp.core.common.Result;
import procomp.core.git.GitHooksInstallResult;
import procomp.core.git.GitHooksReport;
import procomp.core.git.HookCheckResult;
import procomp.core.git.RepositoryHookStatus;
import procomp.core.git.RepositoryInstallOutcome;
import procomp.core.project.Project;
import procomp.shell.DomainJson;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * CLI argument parser and text/JSON renderer for {@code procomp git hooks <status|install|upgrade>}.
 */
public final class GitHooksCli {

    private static final String USAGE =
        "Usage: procomp git hooks [status [--json] | install [repository...] [--json] | upgrade [repository...] [--json]]";

    public sealed interface Action permits Action.HooksStatus, Action.HooksInstall {
        record HooksStatus(boolean json) implements Action {}

        record HooksInstall(List<String> repositories, boolean json) implements Action {
            public HooksInstall {
                repositories = List.copyOf(repositories);
            }
        }
    }

    private GitHooksCli() {}

    public static Result<Action, String> parse(List<String> args) {
        if (args.isEmpty()) {
            return Result.ok(new Action.HooksStatus(false));
        }

        String first = args.get(0);
        if ("--json".equals(first) && args.size() == 1) {
            return Result.ok(new Action.HooksStatus(true));
        }
        if ("status".equals(first)) {
            return parseStatusArgs(args.subList(1, args.size()));
        }
        if ("install".equals(first) || "upgrade".equals(first)) {
            return parseInstallArgs(first, args.subList(1, args.size()));
        }
        return Result.err("Unknown 'git hooks' action '" + first + "'. " + USAGE);
    }

    private static Result<Action, String> parseStatusArgs(List<String> args) {
        boolean json = false;
        for (String arg : args) {
            if ("--json".equals(arg)) {
                json = true;
            } else if (arg.startsWith("-")) {
                return Result.err("Unknown option '" + arg + "' for 'git hooks status'. " + USAGE);
            } else {
                return Result.err("Unexpected argument '" + arg + "' for 'git hooks status'. " + USAGE);
            }
        }
        return Result.ok(new Action.HooksStatus(json));
    }

    private static Result<Action, String> parseInstallArgs(String verb, List<String> args) {
        boolean json = false;
        List<String> repos = new ArrayList<>();
        for (String arg : args) {
            if ("--json".equals(arg)) {
                json = true;
            } else if (arg.startsWith("-")) {
                return Result.err("Unknown option '" + arg + "' for 'git hooks " + verb + "'. " + USAGE);
            } else if (arg.isBlank()) {
                return Result.err("Repository name must not be blank. " + USAGE);
            } else {
                repos.add(arg);
            }
        }
        return Result.ok(new Action.HooksInstall(repos, json));
    }

    static int execute(Action action, Project project, CliRuntime runtime) {
        return switch (action) {
            case Action.HooksStatus(boolean json) -> runtime.handleResult(
                runtime.gitHooksShell().flatMap(shell -> shell.status(project)),
                report -> {
                    if (json) {
                        runtime.out().println(Json.stringify(DomainJson.gitHooksReport(report)));
                    } else {
                        renderStatusText(report, runtime.out());
                    }
                }
            );
            case Action.HooksInstall(List<String> repos, boolean json) -> runtime.handleResult(
                runtime.gitHooksShell().flatMap(shell -> shell.install(project, repos)),
                result -> {
                    if (json) {
                        runtime.out().println(Json.stringify(DomainJson.gitHooksInstallResult(result)));
                    } else {
                        renderInstallText(result, runtime.out());
                    }
                }
            );
        };
    }

    private static void renderStatusText(GitHooksReport report, PrintWriter out) {
        out.println("Project: " + report.project());
        switch (report) {
            case GitHooksReport.NotConfigured _ -> out.println("Mandatory Git hooks: not configured.");
            case GitHooksReport.SourceUnavailable unavailable -> out.println(formatWarningLine(unavailable));
            case GitHooksReport.Configured configured -> {
                out.println(
                    "Mandatory Git hooks (" + configured.mode().name().toLowerCase(Locale.ROOT) + "): "
                        + String.join(", ", configured.requiredHooks())
                );
                if (configured.repositories().isEmpty()) {
                    out.println("No Repositories configured in project '" + configured.project() + "'.");
                    return;
                }
                List<List<String>> rows = new ArrayList<>(configured.repositories().size());
                for (RepositoryHookStatus repo : configured.repositories()) {
                    switch (repo) {
                        case RepositoryHookStatus.NotCloned nc -> rows.add(List.of(
                            nc.displayName(),
                            "NOT_CLONED",
                            "-",
                            "-",
                            nc.repositoryRoot().toString()
                        ));
                        case RepositoryHookStatus.NotGitRepository ngr -> rows.add(List.of(
                            ngr.displayName(),
                            "NOT_GIT_REPOSITORY",
                            "-",
                            "-",
                            ngr.repositoryRoot().toString()
                        ));
                        case RepositoryHookStatus.Checked checked -> {
                            List<HookCheckResult> missing = checked.missingHooks();
                            List<HookCheckResult> outdated = checked.outdatedHooks();
                            String statusLabel;
                            if (checked.allInstalled()) {
                                statusLabel = "OK";
                            } else if (!missing.isEmpty() && !outdated.isEmpty()) {
                                statusLabel = "MISSING/OUTDATED";
                            } else if (!missing.isEmpty()) {
                                statusLabel = "MISSING (" + missing.size() + ")";
                            } else {
                                statusLabel = "OUTDATED (" + outdated.size() + ")";
                            }
                            String missingNames = missing.isEmpty()
                                ? "-"
                                : missing.stream().map(HookCheckResult::hookName).collect(Collectors.joining(", "));
                            String outdatedNames = outdated.isEmpty()
                                ? "-"
                                : outdated.stream().map(HookCheckResult::hookName).collect(Collectors.joining(", "));
                            rows.add(List.of(
                                checked.displayName(),
                                statusLabel,
                                missingNames,
                                outdatedNames,
                                checked.repositoryRoot().toString()
                            ));
                        }
                    }
                }
                CliFormat.renderTable(out, List.of("REPOSITORY", "STATUS", "MISSING", "OUTDATED", "PATH"), rows);
                if (configured.hasWarnings()) {
                    out.println(formatWarningLine(configured));
                } else {
                    out.println("All cloned Repositories have mandatory Git hooks installed.");
                }
            }
        }
    }

    private static void renderInstallText(GitHooksInstallResult result, PrintWriter out) {
        out.println(
            "Project: " + result.project()
                + " (mode: " + result.mode().name().toLowerCase(Locale.ROOT) + ")"
        );
        if (result.repositories().isEmpty()) {
            out.println("No Repositories configured in project '" + result.project() + "'.");
            return;
        }
        List<List<String>> rows = new ArrayList<>(result.repositories().size());
        for (RepositoryInstallOutcome repo : result.repositories()) {
            switch (repo) {
                case RepositoryInstallOutcome.SkippedNotCloned s -> rows.add(List.of(
                    s.displayName(),
                    "SKIPPED (not cloned)",
                    "0",
                    "0",
                    "0",
                    s.repositoryRoot().toString()
                ));
                case RepositoryInstallOutcome.SkippedNotGitRepository s -> rows.add(List.of(
                    s.displayName(),
                    "SKIPPED (not a Git repository)",
                    "0",
                    "0",
                    "0",
                    s.repositoryRoot().toString()
                ));
                case RepositoryInstallOutcome.Applied a -> rows.add(List.of(
                    a.displayName(),
                    "APPLIED",
                    Integer.toString(a.installedCount()),
                    Integer.toString(a.upgradedCount()),
                    Integer.toString(a.unchangedCount()),
                    a.repositoryRoot().toString()
                ));
            }
        }
        CliFormat.renderTable(out, List.of("REPOSITORY", "OUTCOME", "INSTALLED", "UPGRADED", "UNCHANGED", "PATH"), rows);
        out.println(
            "Summary: " + result.totalInstalled() + " installed, "
                + result.totalUpgraded() + " upgraded, "
                + result.totalUnchanged() + " unchanged."
        );
    }

    static String formatWarningLine(GitHooksReport report) {
        return switch (report) {
            case GitHooksReport.NotConfigured _ -> "";
            case GitHooksReport.SourceUnavailable unavailable ->
                "Warning: Mandatory Git hooks could not be checked (" + unavailable.reason() + ").";
            case GitHooksReport.Configured configured -> {
                List<String> parts = new ArrayList<>(2);
                if (configured.missingHookCount() > 0) {
                    parts.add(configured.missingHookCount() + " missing");
                }
                if (configured.outdatedHookCount() > 0) {
                    parts.add(configured.outdatedHookCount() + " outdated");
                }
                String detail = parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
                yield "Warning: Mandatory Git hooks need attention in "
                    + configured.repositoriesWithIssuesCount() + "/" + configured.checkedRepositoryCount()
                    + " Repositories" + detail
                    + ". Run 'procomp git hooks install' to install/upgrade.";
            }
        };
    }
}
