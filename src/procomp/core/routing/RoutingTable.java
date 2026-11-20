package procomp.core.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable persisted set of desired Routes for all Services in a Project, ordered by Project.services().
 */
public record RoutingTable(Map<String, Route> routes) {

    public RoutingTable {
        Objects.requireNonNull(routes, "routes must not be null");
        Map<String, Route> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Route> entry : routes.entrySet()) {
            String service = entry.getKey();
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("Service name in RoutingTable must be non-blank");
            }
            Route route = Objects.requireNonNull(entry.getValue(), "Route must not be null for service: " + service);
            copy.put(service, route);
        }
        routes = Collections.unmodifiableMap(copy);
    }

    public Optional<Route> routeOf(String serviceName) {
        return Optional.ofNullable(routes.get(serviceName));
    }

    public List<String> localServices() {
        return servicesWithRoute(Route.LOCAL);
    }

    public List<String> remoteServices() {
        return servicesWithRoute(Route.REMOTE);
    }

    public int size() {
        return routes.size();
    }

    public boolean isEmpty() {
        return routes.isEmpty();
    }

    private List<String> servicesWithRoute(Route target) {
        List<String> matching = new ArrayList<>();
        for (Map.Entry<String, Route> entry : routes.entrySet()) {
            if (entry.getValue() == target) {
                matching.add(entry.getKey());
            }
        }
        return List.copyOf(matching);
    }
}
