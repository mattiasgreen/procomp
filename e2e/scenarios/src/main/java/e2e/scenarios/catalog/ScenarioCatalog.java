package e2e.scenarios.catalog;

import e2e.scenarios.engine.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical catalog of reusable end-to-end {@link Scenario}s driving automated E2E suites,
 * interactive exploration, and deterministic Markdown demo capture.
 */
public final class ScenarioCatalog {

    public static final String HYBRID_ROUTING = "hybrid-routing";
    public static final String OWNERSHIP_AND_CONFLICTS = "ownership-and-conflicts";
    public static final String FAILURES_LOGS_AND_STALE = "failures-logs-and-stale";
    public static final String LARGE_ESTATE = "large-estate";

    private static final List<Scenario> ALL;
    private static final Map<String, Scenario> BY_ID;

    static {
        List<Scenario> list = List.of(
            HybridRoutingScenario.build(),
            OwnershipAndConflictsScenario.build(),
            FailuresLogsAndStaleScenario.build(),
            LargeEstateScenario.build()
        );
        Map<String, Scenario> map = new LinkedHashMap<>();
        for (Scenario scenario : list) {
            map.put(scenario.id(), scenario);
        }
        ALL = list;
        BY_ID = Collections.unmodifiableMap(map);
    }

    private ScenarioCatalog() {}

    public static List<Scenario> all() {
        return ALL;
    }

    public static Optional<Scenario> byId(String id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static Scenario hybridRouting() {
        return BY_ID.get(HYBRID_ROUTING);
    }

    public static Scenario ownershipAndConflicts() {
        return BY_ID.get(OWNERSHIP_AND_CONFLICTS);
    }

    public static Scenario failuresLogsAndStale() {
        return BY_ID.get(FAILURES_LOGS_AND_STALE);
    }

    public static Scenario largeEstate() {
        return BY_ID.get(LARGE_ESTATE);
    }
}
