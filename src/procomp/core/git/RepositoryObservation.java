package procomp.core.git;

import procomp.core.project.RepositoryRef;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only, zero-network local observation of a {@code Repository} on disk.
 */
public sealed interface RepositoryObservation
    permits RepositoryObservation.NotCloned, RepositoryObservation.NotAGitRepository, RepositoryObservation.Cloned {

    RepositoryRef repository();

    record NotCloned(RepositoryRef repository) implements RepositoryObservation {
        public NotCloned {
            Objects.requireNonNull(repository, "repository must not be null");
        }
    }

    record NotAGitRepository(RepositoryRef repository, boolean empty) implements RepositoryObservation {
        public NotAGitRepository {
            Objects.requireNonNull(repository, "repository must not be null");
        }
    }

    record Cloned(
        RepositoryRef repository,
        HeadState head,
        Optional<String> upstream,
        int ahead,
        int behind,
        int stagedCount,
        int unstagedCount,
        int untrackedCount,
        int stashCount,
        Optional<Instant> lastFetchedAt
    ) implements RepositoryObservation {
        public Cloned {
            Objects.requireNonNull(repository, "repository must not be null");
            Objects.requireNonNull(head, "head must not be null");
            Objects.requireNonNull(upstream, "upstream must not be null");
            Objects.requireNonNull(lastFetchedAt, "lastFetchedAt must not be null");
            if (ahead < 0 || behind < 0 || stagedCount < 0 || unstagedCount < 0 || untrackedCount < 0 || stashCount < 0) {
                throw new IllegalArgumentException("Counts must be non-negative");
            }
        }

        public boolean isDirty() {
            return stagedCount > 0 || unstagedCount > 0 || untrackedCount > 0;
        }

        public int dirtyCount() {
            return stagedCount + unstagedCount + untrackedCount;
        }

        public Optional<String> branchName() {
            return head instanceof HeadState.Branch(String name) ? Optional.of(name) : Optional.empty();
        }
    }

    sealed interface HeadState permits HeadState.Branch, HeadState.Detached {

        record Branch(String name) implements HeadState {
            public Branch {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Branch name must be non-blank");
                }
            }
        }

        record Detached(String shortSha) implements HeadState {
            public Detached {
                if (shortSha == null || shortSha.isBlank()) {
                    throw new IllegalArgumentException("Detached shortSha must be non-blank");
                }
            }
        }
    }
}
