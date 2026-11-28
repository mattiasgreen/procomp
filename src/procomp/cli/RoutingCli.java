package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.Result;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.Project;
import procomp.core.project.ServiceDefinition;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.DomainJson;
import procomp.shell.ProfileConfigShell;
import procomp.shell.ProjectLoader.LoadedProject;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CLI argument parser and table/JSON renderer for {@code procomp route} and {@code procomp profile}.
 */
public final class RoutingCli {

    public sealed interface Action permits
        Action.ShowRoutes,
        Action.UpdateRoutes,
        Action.ListProfiles,
        Action.UseProfile,
        Action.SaveProfile,
        Action.DeleteProfile {
        record ShowRoutes(boolean json) implements Action {}
        record UpdateRoutes(List<String> services, Route route) implements Action {}
        record ListProfiles(boolean json) implements Action {}
        record UseProfile(String profileName) implements Action {}
        record SaveProfile(String profileName, Optional<List<String>> explicitLocalServices) implements Action {}
        record DeleteProfile(String profileName) implements Action {}
    }

    private RoutingCli() {}

    /**
     * Parses a routing/profile subcommand.
     */
    public static Result<Action, String> parse(String command, List<String> args) {
        return switch (command) {
            case "route" -> parseRoute(args);
            case "profile" -> parseProfile(args);
            default -> Result.err("Unknown routing command '" + command + "'.");
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
            return Result.err("Expected subcommand 'list', 'use <name>', 'save <name> [service... | --none]', or 'delete <name>' after 'profile'.");
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
            case "save" -> parseProfileSave(rest);
            case "delete" -> {
                if (rest.size() != 1 || rest.get(0).startsWith("-") || rest.get(0).isBlank()) {
                    yield Result.err("Expected '<name>' after 'profile delete'. Usage: procomp profile delete <name>");
                }
                yield Result.ok(new Action.DeleteProfile(rest.get(0)));
            }
            default -> Result.err("Unknown 'profile' subcommand '" + subcommand + "'. Usage: procomp profile <list [--json] | use <name> | save <name> [service... | --none] | delete <name>>");
        };
    }

    private static Result<Action, String> parseProfileSave(List<String> rest) {
        if (rest.isEmpty() || rest.get(0).startsWith("-") || rest.get(0).isBlank()) {
            return Result.err("Expected '<name>' after 'profile save'. Usage: procomp profile save <name> [service... | --none]");
        }
        String profileName = rest.get(0);
        if (rest.size() == 1) {
            return Result.ok(new Action.SaveProfile(profileName, Optional.empty()));
        }
        List<String> serviceArgs = rest.subList(1, rest.size());
        if (serviceArgs.size() == 1 && "--none".equals(serviceArgs.get(0))) {
            return Result.ok(new Action.SaveProfile(profileName, Optional.of(List.of())));
        }
        for (String arg : serviceArgs) {
            if ("--none".equals(arg)) {
                return Result.err("Option '--none' cannot be combined with explicit service names. Usage: procomp profile save <name> [service... | --none]");
            }
            if (arg.startsWith("-")) {
                return Result.err("Unknown option '" + arg + "' for 'profile save'. Usage: procomp profile save <name> [service... | --none]");
            }
            if (arg.isBlank()) {
                return Result.err("Service name must not be blank. Usage: procomp profile save <name> [service... | --none]");
            }
        }
        return Result.ok(new Action.SaveProfile(profileName, Optional.of(List.copyOf(serviceArgs))));
    }

    /**
     * Executes a parsed routing/profile action against {@link CliRuntime} and writes rendered output or errors.
     */
    static int execute(Action action, LoadedProject loaded, CliRuntime runtime) {
        Project project = loaded.project();
        return switch (action) {
            case Action.ShowRoutes(boolean json) -> runtime.handleResult(
                runtime.routingShell().flatMap(shell -> shell.showRoutes(project)),
                table -> {
                    if (json) {
                        renderRoutesJson(project, table, runtime.out());
                    } else {
                        renderRoutesTable(project, table, runtime.out());
                    }
                }
            );
            case Action.UpdateRoutes(List<String> services, Route route) -> runtime.handleResult(
                runtime.routingShell().flatMap(shell -> shell.updateRoutes(project, services, route)),
                _ -> {
                    for (String svc : new LinkedHashSet<>(services)) {
                        runtime.out().println("Updated route: " + svc + " -> " + route.name());
                    }
                }
            );
            case Action.ListProfiles(boolean json) -> runtime.handleResult(
                runtime.routingShell().flatMap(shell -> shell.listProfiles(project)),
                profiles -> {
                    if (json) {
                        renderProfilesJson(project, profiles, runtime.out());
                    } else {
                        renderProfilesTable(project, profiles, runtime.out());
                    }
                }
            );
            case Action.UseProfile(String profileName) -> runtime.handleResult(
                runtime.routingShell().flatMap(shell -> shell.useProfile(project, profileName)),
                table -> {
                    int localCount = table.localServices().size();
                    int remoteCount = table.remoteServices().size();
                    runtime.out().println("Applied profile '" + profileName + "' (" + localCount + " LOCAL, " + remoteCount + " REMOTE)");
                }
            );
            case Action.SaveProfile(String profileName, Optional<List<String>> explicitLocalServices) -> {
                Result<List<String>, DomainError> localRes = explicitLocalServices.isPresent()
                    ? Result.ok(explicitLocalServices.get())
                    : runtime.routingShell().flatMap(shell -> shell.showRoutes(project)).map(RoutingTable::localServices);
                Result<ProfileConfigShell.SaveProfileResult, DomainError> saveRes = localRes.flatMap(
                    locals -> runtime.profileConfigShell().flatMap(shell -> shell.saveProfile(loaded, profileName, locals))
                );
                yield runtime.handleResult(
                    saveRes,
                    saved -> {
                        String verb = saved.overwrote() ? "Updated" : "Saved";
                        int localCount = saved.profile().localServices().size();
                        runtime.out().println(verb + " profile '" + saved.profile().name() + "' (" + localCount + " LOCAL) in " + saved.loaded().configPath());
                    }
                );
            }
            case Action.DeleteProfile(String profileName) -> runtime.handleResult(
                runtime.profileConfigShell().flatMap(shell -> shell.deleteProfile(loaded, profileName)),
                deleted -> runtime.out().println("Deleted profile '" + deleted.deletedName() + "' from " + deleted.loaded().configPath())
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
        out.println(Json.stringify(DomainJson.routingTable(project, table)));
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
        out.println(Json.stringify(DomainJson.profiles(project, profiles)));
    }
}
