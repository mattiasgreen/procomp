package procomp.cli;

import procomp.core.common.Json;
import procomp.core.common.Result;
import procomp.core.gateway.GatewayDriftReport;
import procomp.core.process.Ownership;
import procomp.core.project.Project;
import procomp.shell.DomainJson;
import procomp.shell.GatewayShell.GatewayStatusReport;
import procomp.shell.StatusShell.EstateStatus;
import procomp.shell.StatusShell.GatewaySummary;
import procomp.shell.StatusShell.ServiceStatusRow;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CLI argument parser and table/JSON renderer for {@code procomp status [--json]}.
 */
public final class StatusCli {

    private static final String USAGE = "Usage: procomp status [--json]";

    public sealed interface Action permits Action.Show {
        record Show(boolean json) implements Action {}
    }

    private StatusCli() {}

    public static Result<Action, String> parse(List<String> args) {
        boolean json = false;
        for (String arg : args) {
            if ("--json".equals(arg)) {
                json = true;
            } else if (arg.startsWith("-")) {
                return Result.err("Unknown option '" + arg + "' for 'status'. " + USAGE);
            } else {
                return Result.err("Unexpected argument '" + arg + "' for 'status'. " + USAGE);
            }
        }
        return Result.ok(new Action.Show(json));
    }

    static int execute(Action action, Project project, CliRuntime runtime) {
        return runtime.handleResult(
            runtime.statusShell().flatMap(shell -> shell.status(project)),
            status -> {
                if (action instanceof Action.Show(boolean json) && json) {
                    renderJson(status, runtime.out());
                } else {
                    renderText(status, runtime.out());
                }
            }
        );
    }

    private static void renderText(EstateStatus status, PrintWriter out) {
        out.println("Project: " + status.project());
        if (status.services().isEmpty()) {
            out.println("No services configured in project '" + status.project() + "'.");
        } else {
            List<List<String>> rows = new ArrayList<>(status.services().size());
            for (ServiceStatusRow row : status.services()) {
                rows.add(List.of(
                    row.service(),
                    row.route().name(),
                    formatOwner(row.ownership(), row.provenance()),
                    row.health().name(),
                    row.build().displayState().name(),
                    row.git().map(g -> g.compactDisplay()).orElse("NO_REPO"),
                    row.port().map(Object::toString).orElse("-"),
                    row.pid().map(Object::toString).orElse("-"),
                    row.uptime().map(CliFormat::formatDuration).orElse("-"),
                    row.rssBytes().map(CliFormat::formatBytes).orElse("-"),
                    row.stale() ? "YES" : "no"
                ));
            }
            CliFormat.renderTable(
                out,
                List.of("SERVICE", "ROUTE", "OWNER", "HEALTH", "BUILD", "GIT", "PORT", "PID", "UPTIME", "RSS", "STALE"),
                rows
            );
        }
        out.println(formatGatewayLine(status.gateway()));
        status.totalRssBytes().ifPresent(total -> out.println(formatTotalRssLine(status, total)));
        if (status.gitHooks().isPresent() && status.gitHooks().get().hasWarnings()) {
            out.println(GitHooksCli.formatWarningLine(status.gitHooks().get()));
        }
    }

    private static String formatTotalRssLine(EstateStatus status, long totalBytes) {
        int serviceCount = 0;
        for (ServiceStatusRow row : status.services()) {
            if (row.rssBytes().isPresent()) {
                serviceCount++;
            }
        }
        boolean hasGateway = status.gateway().report().isPresent()
            && status.gateway().report().get().rssBytes().isPresent();
        String breakdown;
        if (serviceCount > 0 && hasGateway) {
            breakdown = serviceCount + (serviceCount == 1 ? " service" : " services") + " + Gateway";
        } else if (serviceCount > 0) {
            breakdown = serviceCount + (serviceCount == 1 ? " service" : " services");
        } else {
            breakdown = "Gateway";
        }
        return "Total RSS: " + CliFormat.formatBytes(totalBytes) + " (" + breakdown + ")";
    }

    private static void renderJson(EstateStatus status, PrintWriter out) {
        out.println(Json.stringify(DomainJson.estateStatus(status)));
    }

    private static String formatGatewayLine(GatewaySummary summary) {
        if (summary.report().isEmpty()) {
            return "Gateway: not configured";
        }
        GatewayStatusReport report = summary.report().get();
        String owner = formatOwner(report.discovery().ownership(), report.discovery().provenance());
        String health = report.discovery().health().name();
        String pidPart = report.discovery().pid().map(p -> ", PID " + p).orElse("");
        String rssPart = report.rssBytes().map(b -> ", " + CliFormat.formatBytes(b) + " RSS").orElse("");
        GatewayDriftReport drift = report.drift();
        String syncLabel = switch (drift.syncStatus()) {
            case IN_SYNC -> "IN SYNC";
            case OFFLINE -> "OFFLINE";
            case DRIFTED -> "DRIFTED (" + (drift.drifted().size() + drift.staleMappings().size()) + ")";
        };
        return "Gateway: port " + report.gateway().port() + ", " + owner + ", " + health + pidPart + rssPart + ", " + syncLabel;
    }

    private static String formatOwner(Ownership ownership, Optional<String> provenance) {
        if (ownership == Ownership.UNMANAGED && provenance.isPresent()) {
            return ownership.name() + " (" + provenance.get() + ")";
        }
        return ownership.name();
    }
}
