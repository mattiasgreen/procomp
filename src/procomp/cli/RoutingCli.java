package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.Project;
import procomp.core.project.ServiceDefinition;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.RoutingShell;
import procomp.shell.ports.StateStore;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CLI argument parser and table/JSON renderer for {@code procomp route} and {@code procomp profile}.
 */
public final class RoutingCli {

    public sealed interface Action permits Action.ShowRoutes, Action.UpdateRoutes, Action.ListProfiles, Action.UseProfile {
        record ShowRoutes(boolean json) implements Action {}
        record UpdateRoutes(List<String> services, Route route) implements Action {}
        record ListProfiles(boolean json) implements Action {}
        record UseProfile(String profileName) implements Action {}
    }

    private RoutingCli() {}

    /**
     * Parses a routing/profile subcommand if {@code command} is handled by this CLI module.
     */
    public static Optional<Result<Action, String>> parse(String command, List<String> args) {
        return switch (command) {
            case "route" -> Optional.of(parseRoute(args));
            case "profile" -> Optional.of(parseProfile(args));
            default -> Optional.empty();
        };
    }

    /**
     * Parses arguments for {@code procomp route}, failing fast with usage/syntax messages on invalid input.
     */
    public static Result<Action, String> parseRoute(List<String> args) {
        if (args.isEmpty()) {
            return Result.ok(new Action.ShowRoutes(false));
        }
        if (args.size() == 1 && "--json".equals(args.get(0))) {
            return Result.ok(new Action.ShowRoutes(true));
        }

        for (String arg : args) {
            if (arg.startsWith("-")) {
                return Result.err("Unknown or misplaced option '" + arg + "' for 'route'. Usage: procomp route [--json] | procomp route <service...> <local|remote>");
            }
            if (arg.isBlank()) {
                return Result.err("Service name must not be blank. Usage: procomp route <service...> <local|remote>");
            }
        }

        if (args.size() < 2) {
            return Result.err("Expected '<service...> <local|remote>' after 'route'. Usage: procomp route [--json] | procomp route <service...> <local|remote>");
        }

        String rawRoute = args.get(args.size() - 1);
        Result<Route, DomainError> parsedRoute = Route.parse(rawRoute);
        if (parsedRoute instanceof Result.Err<Route, DomainError>(DomainError err)) {
            return Result.err(err.format());
        }

        Route route = ((Result.Ok<Route, DomainError>) parsedRoute).value();
        List<String> services = List.copyOf(args.subList(0, args.size() - 1));
        return Result.ok(new Action.UpdateRoutes(services, route));
    }

    /**
     * Parses arguments for {@code procomp profile}, failing fast with usage/syntax messages on invalid input.
     */
    public static Result<Action, String> parseProfile(List<String> args) {
        if (args.isEmpty()) {
            return Result.err("Expected subcommand 'list' or 'use <name>' after 'profile'. Usage: procomp profile <list [--json] | use <name>>");
        }
        String subcommand = args.get(0);
        List<String> rest = args.subList(1, args.size());
        return switch (subcommand) {
            case "list" -> {
                if (rest.isEmpty()) {
                    yield Result.ok(new Action.ListProfiles(false));
                }
                if (rest.size() == 1 && "--json".equals(rest.get(0))) {
                    yield Result.ok(new Action.ListProfiles(true));
                }
                yield Result.err("Invalid arguments for 'profile list'. Usage: procomp profile list [--json]");
            }
            case "use" -> {
                if (rest.size() != 1 || rest.get(0).startsWith("-") || rest.get(0).isBlank()) {
                    yield Result.err("Expected '<name>' after 'profile use'. Usage: procomp profile use <name>");
                }
                yield Result.ok(new Action.UseProfile(rest.get(0)));
            }
            default -> Result.err("Unknown 'profile' subcommand '" + subcommand + "'. Usage: procomp profile <list [--json] | use <name>>");
        };
    }

    /**
     * Executes a parsed routing/profile action against {@link RoutingShell} and writes rendered output or errors.
     */
    public static int execute(
        Action action,
        Project project,
        StateStore stateStore,
        PrintWriter out,
        PrintWriter err
    ) {
        RoutingShell shell = new RoutingShell(stateStore);
        return switch (action) {
            case Action.ShowRoutes(boolean json) -> CliFormat.handleResult(shell.showRoutes(project), err, table -> {
                if (json) {
                    renderRoutesJson(project, table, out);
                } else {
                    renderRoutesTable(project, table, out);
                }
            });
            case Action.UpdateRoutes(List<String> services, Route route) -> CliFormat.handleResult(
                shell.updateRoutes(project, services, route),
                err,
                _ -> {
                    for (String svc : new LinkedHashSet<>(services)) {
                        out.println("Updated route: " + svc + " -> " + route.name());
                    }
                }
            );
            case Action.ListProfiles(boolean json) -> CliFormat.handleResult(shell.listProfiles(project), err, profiles -> {
                if (json) {
                    renderProfilesJson(project, profiles, out);
                } else {
                    renderProfilesTable(project, profiles, out);
                }
            });
            case Action.UseProfile(String profileName) -> CliFormat.handleResult(
                shell.useProfile(project, profileName),
                err,
                table -> {
                    int localCount = table.localServices().size();
                    int remoteCount = table.remoteServices().size();
                    out.println("Applied profile '" + profileName + "' (" + localCount + " LOCAL, " + remoteCount + " REMOTE)");
                }
            );
        };
    }

    private static void renderRoutesTable(Project project, RoutingTable table, PrintWriter out) {
        if (table.isEmpty()) {
            out.println("No services configured in project '" + project.name() + "'.");
            return;
        }
        List<List<String>> rows = new ArrayList<>(table.size());
        for (Map.Entry<String, Route> entry : table.routes().entrySet()) {
            String svcName = entry.getKey();
            Route route = entry.getValue();
            ServiceDefinition svc = project.service(svcName).orElseThrow();
            String target = switch (route) {
                case LOCAL -> svc.port().map(p -> "http://127.0.0.1:" + p).orElse("(no local port)");
                case REMOTE -> svc.remoteUrl().orElse("(no remoteUrl)");
            };
            rows.add(List.of(svcName, route.name(), svc.proxyPath(), target));
        }
        CliFormat.renderTable(out, List.of("SERVICE", "ROUTE", "PROXY_PATH", "TARGET"), rows);
    }

    private static void renderRoutesJson(Project project, RoutingTable table, PrintWriter out) {
        List<JsonValue> items = new ArrayList<>(table.size());
        for (Map.Entry<String, Route> entry : table.routes().entrySet()) {
            String svcName = entry.getKey();
            Route route = entry.getValue();
            ServiceDefinition svc = project.service(svcName).orElseThrow();

            Map<String, JsonValue> obj = new LinkedHashMap<>();
            obj.put("service", Json.str(svcName));
            obj.put("route", Json.str(route.name()));
            obj.put("proxyPath", Json.str(svc.proxyPath()));
            obj.put("port", svc.port().<JsonValue>map(Json::num).orElse(Json.nil()));
            obj.put("remoteUrl", svc.remoteUrl().<JsonValue>map(Json::str).orElse(Json.nil()));
            items.add(Json.obj(obj));
        }
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(project.name()));
        root.put("routes", Json.arr(items));
        out.println(Json.stringify(Json.obj(root)));
    }

    private static void renderProfilesTable(Project project, List<ProfileDefinition> profiles, PrintWriter out) {
        if (profiles.isEmpty()) {
            out.println("No profiles configured in project '" + project.name() + "'.");
            return;
        }
        List<List<String>> rows = new ArrayList<>(profiles.size());
        for (ProfileDefinition profile : profiles) {
            String localCell = profile.localServices().isEmpty()
                ? "(none)"
                : String.join(", ", profile.localServices());
            rows.add(List.of(profile.name(), localCell));
        }
        CliFormat.renderTable(out, List.of("PROFILE", "LOCAL"), rows);
    }

    private static void renderProfilesJson(Project project, List<ProfileDefinition> profiles, PrintWriter out) {
        List<JsonValue> items = new ArrayList<>(profiles.size());
        for (ProfileDefinition profile : profiles) {
            List<JsonValue> localNodes = new ArrayList<>(profile.localServices().size());
            for (String svc : profile.localServices()) {
                localNodes.add(Json.str(svc));
            }
            Map<String, JsonValue> obj = new LinkedHashMap<>();
            obj.put("name", Json.str(profile.name()));
            obj.put("local", Json.arr(localNodes));
            items.add(Json.obj(obj));
        }
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("project", Json.str(project.name()));
        root.put("profiles", Json.arr(items));
        out.println(Json.stringify(Json.obj(root)));
    }
}
