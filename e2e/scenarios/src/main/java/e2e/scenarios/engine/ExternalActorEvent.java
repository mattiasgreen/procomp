package e2e.scenarios.engine;

import java.util.Objects;

/**
 * World-mutating events performed strictly outside {@code procomp} (never mutating {@code procomp} internal state).
 */
public sealed interface ExternalActorEvent permits
    ExternalActorEvent.BootstrapClean,
    ExternalActorEvent.SpawnUnmanagedService,
    ExternalActorEvent.SpawnPortConflict,
    ExternalActorEvent.SpawnUnmanagedGateway,
    ExternalActorEvent.KillManagedService,
    ExternalActorEvent.RebuildArtifact,
    ExternalActorEvent.AppendServiceLog,
    ExternalActorEvent.SeedGitRepositories,
    ExternalActorEvent.AdvanceGitRemote,
    ExternalActorEvent.DirtyGitRepository {

    record BootstrapClean() implements ExternalActorEvent {}

    record SpawnUnmanagedService(String service, boolean ideAncestor) implements ExternalActorEvent {
        public SpawnUnmanagedService {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("service must be non-blank");
            }
        }
    }

    record SpawnPortConflict(String service) implements ExternalActorEvent {
        public SpawnPortConflict {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("service must be non-blank");
            }
        }
    }

    record SpawnUnmanagedGateway() implements ExternalActorEvent {}

    record KillManagedService(String service) implements ExternalActorEvent {
        public KillManagedService {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("service must be non-blank");
            }
        }
    }

    record RebuildArtifact(String service) implements ExternalActorEvent {
        public RebuildArtifact {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("service must be non-blank");
            }
        }
    }

    record AppendServiceLog(String service, String line, boolean stderr) implements ExternalActorEvent {
        public AppendServiceLog {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("service must be non-blank");
            }
            Objects.requireNonNull(line, "line must not be null");
        }
    }

    // Reserved Git Repository fixture seam for upcoming repo-sync Scenarios
    record SeedGitRepositories() implements ExternalActorEvent {}

    record AdvanceGitRemote(String repository) implements ExternalActorEvent {
        public AdvanceGitRemote {
            if (repository == null || repository.isBlank()) {
                throw new IllegalArgumentException("repository must be non-blank");
            }
        }
    }

    record DirtyGitRepository(String repository) implements ExternalActorEvent {
        public DirtyGitRepository {
            if (repository == null || repository.isBlank()) {
                throw new IllegalArgumentException("repository must be non-blank");
            }
        }
    }
}
