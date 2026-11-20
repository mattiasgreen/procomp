package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.Project;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingEngine;
import procomp.core.routing.RoutingTable;
import procomp.shell.ports.StateStore;

import java.util.List;
import java.util.Objects;

/**
 * Modality-agnostic orchestrator for Routing Table and Profile operations.
 * Injects {@link StateStore} only to structurally enforce UNIX separation from Process and Gateway ports.
 */
public final class RoutingShell {

    private final StateStore stateStore;

    public RoutingShell(StateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
    }

    /**
     * Loads and resolves the Project's current Routing Table without modifying persisted state.
     */
    public Result<RoutingTable, DomainError> showRoutes(Project project) {
        Objects.requireNonNull(project, "project must not be null");
        return stateStore.readRouting(project.name())
            .flatMap(persisted -> RoutingEngine.resolve(project, persisted));
    }

    /**
     * Validates all target Services, updates their Routes, and persists {@code routing.json}.
     */
    public Result<RoutingTable, DomainError> updateRoutes(Project project, List<String> serviceNames, Route route) {
        Objects.requireNonNull(project, "project must not be null");
        return showRoutes(project)
            .flatMap(current -> RoutingEngine.applyRoute(project, current, serviceNames, route))
            .flatMap(updated -> persistRouting(project, updated));
    }

    /**
     * Returns all Profiles configured on {@code project} in declaration order.
     */
    public Result<List<ProfileDefinition>, DomainError> listProfiles(Project project) {
        Objects.requireNonNull(project, "project must not be null");
        return Result.ok(List.copyOf(project.profiles().values()));
    }

    /**
     * Applies the named Profile to the Routing Table and persists {@code routing.json}.
     */
    public Result<RoutingTable, DomainError> useProfile(Project project, String profileName) {
        Objects.requireNonNull(project, "project must not be null");
        return RoutingEngine.applyProfile(project, profileName)
            .flatMap(updated -> persistRouting(project, updated));
    }

    private Result<RoutingTable, DomainError> persistRouting(Project project, RoutingTable updated) {
        return stateStore.writeRouting(project.name(), RoutingEngine.toPersistedMap(updated))
            .map(_ -> updated);
    }
}
