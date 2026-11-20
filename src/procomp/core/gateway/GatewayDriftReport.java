package procomp.core.gateway;

import procomp.core.project.Project;
import procomp.core.routing.RoutingTable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure Gateway drift comparison result against the Project's Routing Table.
 */
public record GatewayDriftReport(
    GatewaySyncStatus syncStatus,
    List<RouteDrift> drifted,
    List<StaleManagedMapping> staleMappings,
    Map<String, String> liveRoutes
) {
    public GatewayDriftReport {
        Objects.requireNonNull(syncStatus, "syncStatus must not be null");
        drifted = List.copyOf(drifted);
        staleMappings = List.copyOf(staleMappings);
        liveRoutes = Collections.unmodifiableMap(new LinkedHashMap<>(liveRoutes));
    }

    public static GatewayDriftReport offline(Project project, RoutingTable routing) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(routing, "routing must not be null");
        Map<String, String> live = new LinkedHashMap<>();
        for (String svc : project.services().keySet()) {
            live.put(svc, "OFFLINE");
        }
        return new GatewayDriftReport(GatewaySyncStatus.OFFLINE, List.of(), List.of(), live);
    }
}
