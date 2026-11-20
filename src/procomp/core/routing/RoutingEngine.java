package procomp.core.routing;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.Project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure domain functions for resolving and updating a Project's Routing Table and applying Profiles.
 */
public final class RoutingEngine {

    private RoutingEngine() {}

    /**
     * Resolves a RoutingTable for {@code project} from optional persisted routes,
     * failing fast on invalid route values and defaulting unpersisted Services to REMOTE.
     */
    public static Result<RoutingTable, DomainError> resolve(Project project, Optional<Map<String, String>> persisted) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(persisted, "persisted must not be null");

        Map<String, Route> parsedPersisted = new LinkedHashMap<>();
        if (persisted.isPresent()) {
            for (Map.Entry<String, String> entry : persisted.get().entrySet()) {
                String serviceName = entry.getKey();
                if (serviceName == null || serviceName.isBlank()) {
                    return Result.err(DomainError.of(
                        "ROUTE_INVALID_VALUE",
                        "Persisted routing table contains a blank service name"
                    ));
                }
                Result<Route, DomainError> parsed = Route.parse(entry.getValue());
                if (parsed instanceof Result.Err<Route, DomainError>(DomainError err)) {
                    Map<String, String> details = new LinkedHashMap<>(err.details());
                    details.put("service", serviceName);
                    return Result.err(DomainError.of(
                        err.code(),
                        "Invalid persisted route for service '" + serviceName + "': " + err.message(),
                        details
                    ));
                }
                parsedPersisted.put(serviceName, ((Result.Ok<Route, DomainError>) parsed).value());
            }
        }

        Map<String, Route> resolved = new LinkedHashMap<>();
        for (String serviceName : project.services().keySet()) {
            resolved.put(serviceName, parsedPersisted.getOrDefault(serviceName, Route.REMOTE));
        }
        return Result.ok(new RoutingTable(resolved));
    }

    /**
     * Validates all {@code serviceNames} exist in {@code project} and returns a new RoutingTable
     * with each listed Service set to {@code route}.
     */
    public static Result<RoutingTable, DomainError> applyRoute(
        Project project,
        RoutingTable current,
        List<String> serviceNames,
        Route route
    ) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(route, "route must not be null");

        if (serviceNames == null || serviceNames.isEmpty()) {
            return Result.err(DomainError.of(
                "ROUTE_MISSING_SERVICES",
                "At least one service name must be specified"
            ));
        }

        Set<String> targetServices = new LinkedHashSet<>();
        for (String serviceName : serviceNames) {
            if (serviceName == null || serviceName.isBlank() || project.service(serviceName).isEmpty()) {
                String display = serviceName == null ? "null" : serviceName;
                return Result.err(DomainError.of(
                    "UNKNOWN_SERVICE",
                    "Unknown service '" + display + "' in project '" + project.name() + "'",
                    Map.of("service", display, "project", project.name())
                ));
            }
            targetServices.add(serviceName);
        }

        Map<String, Route> updated = new LinkedHashMap<>();
        for (String serviceName : project.services().keySet()) {
            Route nextRoute = targetServices.contains(serviceName)
                ? route
                : current.routeOf(serviceName).orElse(Route.REMOTE);
            updated.put(serviceName, nextRoute);
        }
        return Result.ok(new RoutingTable(updated));
    }

    /**
     * Resolves a RoutingTable for the named Profile, setting listed local Services to LOCAL
     * and all remaining Services in {@code project} to REMOTE.
     */
    public static Result<RoutingTable, DomainError> applyProfile(Project project, String profileName) {
        Objects.requireNonNull(project, "project must not be null");
        if (profileName == null || profileName.isBlank()) {
            return Result.err(DomainError.of(
                "UNKNOWN_PROFILE",
                "Profile name must not be blank",
                Map.of("profile", profileName == null ? "null" : profileName, "project", project.name())
            ));
        }

        Optional<ProfileDefinition> maybeProfile = project.profile(profileName);
        if (maybeProfile.isEmpty()) {
            return Result.err(DomainError.of(
                "UNKNOWN_PROFILE",
                "Unknown profile '" + profileName + "' in project '" + project.name() + "'",
                Map.of("profile", profileName, "project", project.name())
            ));
        }

        ProfileDefinition profile = maybeProfile.get();
        for (String localService : profile.localServices()) {
            if (project.service(localService).isEmpty()) {
                return Result.err(DomainError.of(
                    "UNKNOWN_SERVICE",
                    "Profile '" + profileName + "' references unknown service '" + localService + "'",
                    Map.of("profile", profileName, "service", localService, "project", project.name())
                ));
            }
        }

        Set<String> localSet = Set.copyOf(profile.localServices());
        Map<String, Route> updated = new LinkedHashMap<>();
        for (String serviceName : project.services().keySet()) {
            updated.put(serviceName, localSet.contains(serviceName) ? Route.LOCAL : Route.REMOTE);
        }
        return Result.ok(new RoutingTable(updated));
    }

    /**
     * Converts a RoutingTable into an ordered map suitable for persisting in {@code routing.json}.
     */
    public static Map<String, String> toPersistedMap(RoutingTable table) {
        Objects.requireNonNull(table, "table must not be null");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Route> entry : table.routes().entrySet()) {
            out.put(entry.getKey(), entry.getValue().name());
        }
        return Collections.unmodifiableMap(out);
    }
}
