package procomp.core.process;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Sealed observed lifecycle state for a local Service or Gateway Process.
 * Makes illegal {@link Ownership} x {@link HealthState} combinations unrepresentable.
 */
public sealed interface ProcessState
    permits ProcessState.Stopped, ProcessState.Managed, ProcessState.Unmanaged, ProcessState.Conflict {

    Ownership ownership();

    HealthState health();

    Optional<String> provenance();

    Optional<Long> pid();

    Optional<Instant> startedAt();

    boolean stale();

    Optional<ObservedProcess> process();

    /**
     * No active process and no live managed record.
     */
    record Stopped() implements ProcessState {
        public static final Stopped INSTANCE = new Stopped();

        @Override
        public Ownership ownership() {
            return Ownership.NONE;
        }

        @Override
        public HealthState health() {
            return HealthState.STOPPED;
        }

        @Override
        public Optional<String> provenance() {
            return Optional.empty();
        }

        @Override
        public Optional<Long> pid() {
            return Optional.empty();
        }

        @Override
        public Optional<Instant> startedAt() {
            return Optional.empty();
        }

        @Override
        public boolean stale() {
            return false;
        }

        @Override
        public Optional<ObservedProcess> process() {
            return Optional.empty();
        }
    }

    /**
     * Process owned and tracked by procomp.
     */
    sealed interface Managed extends ProcessState
        permits Managed.Starting, Managed.Healthy, Managed.Stopping, Managed.Crashed {

        long managedPid();

        Instant managedStartedAt();

        @Override
        default Ownership ownership() {
            return Ownership.MANAGED;
        }

        @Override
        default Optional<String> provenance() {
            return Optional.of("procomp");
        }

        @Override
        default Optional<Long> pid() {
            return Optional.of(managedPid());
        }

        @Override
        default Optional<Instant> startedAt() {
            return Optional.of(managedStartedAt());
        }

        record Starting(
            long managedPid,
            Instant managedStartedAt,
            boolean stale,
            ObservedProcess observedProcess
        ) implements Managed {
            public Starting {
                if (managedPid <= 0) {
                    throw new IllegalArgumentException("managedPid must be positive: " + managedPid);
                }
                Objects.requireNonNull(managedStartedAt, "managedStartedAt must not be null");
                Objects.requireNonNull(observedProcess, "observedProcess must not be null");
            }

            @Override
            public HealthState health() {
                return HealthState.STARTING;
            }

            @Override
            public Optional<ObservedProcess> process() {
                return Optional.of(observedProcess);
            }
        }

        record Healthy(
            long managedPid,
            Instant managedStartedAt,
            boolean stale,
            ObservedProcess observedProcess
        ) implements Managed {
            public Healthy {
                if (managedPid <= 0) {
                    throw new IllegalArgumentException("managedPid must be positive: " + managedPid);
                }
                Objects.requireNonNull(managedStartedAt, "managedStartedAt must not be null");
                Objects.requireNonNull(observedProcess, "observedProcess must not be null");
            }

            @Override
            public HealthState health() {
                return HealthState.HEALTHY;
            }

            @Override
            public Optional<ObservedProcess> process() {
                return Optional.of(observedProcess);
            }
        }

        record Stopping(
            long managedPid,
            Instant managedStartedAt,
            Instant stopRequestedAt,
            ObservedProcess observedProcess
        ) implements Managed {
            public Stopping {
                if (managedPid <= 0) {
                    throw new IllegalArgumentException("managedPid must be positive: " + managedPid);
                }
                Objects.requireNonNull(managedStartedAt, "managedStartedAt must not be null");
                Objects.requireNonNull(stopRequestedAt, "stopRequestedAt must not be null");
                Objects.requireNonNull(observedProcess, "observedProcess must not be null");
            }

            @Override
            public HealthState health() {
                return HealthState.STOPPING;
            }

            @Override
            public boolean stale() {
                return false;
            }

            @Override
            public Optional<ObservedProcess> process() {
                return Optional.of(observedProcess);
            }
        }

        record Crashed(
            long managedPid,
            Instant managedStartedAt
        ) implements Managed {
            public Crashed {
                if (managedPid <= 0) {
                    throw new IllegalArgumentException("managedPid must be positive: " + managedPid);
                }
                Objects.requireNonNull(managedStartedAt, "managedStartedAt must not be null");
            }

            @Override
            public HealthState health() {
                return HealthState.CRASHED;
            }

            @Override
            public boolean stale() {
                return false;
            }

            @Override
            public Optional<ObservedProcess> process() {
                return Optional.empty();
            }
        }
    }

    /**
     * External process recognized as the Service or Gateway (IDE or shell); never killed by procomp.
     */
    sealed interface Unmanaged extends ProcessState
        permits Unmanaged.Starting, Unmanaged.Healthy {

        String source();

        @Override
        default Ownership ownership() {
            return Ownership.UNMANAGED;
        }

        @Override
        default Optional<String> provenance() {
            return Optional.of(source());
        }

        @Override
        default boolean stale() {
            return false;
        }

        record Starting(
            ObservedProcess observedProcess
        ) implements Unmanaged {
            public Starting {
                Objects.requireNonNull(observedProcess, "observedProcess must not be null");
            }

            @Override
            public String source() {
                return "IDE";
            }

            @Override
            public HealthState health() {
                return HealthState.STARTING;
            }

            @Override
            public Optional<Long> pid() {
                return Optional.of(observedProcess.pid());
            }

            @Override
            public Optional<Instant> startedAt() {
                return observedProcess.startTime();
            }

            @Override
            public Optional<ObservedProcess> process() {
                return Optional.of(observedProcess);
            }
        }

        record Healthy(
            String source,
            Optional<Long> pid,
            Optional<Instant> startedAt,
            Optional<ObservedProcess> process
        ) implements Unmanaged {
            public Healthy {
                if (source == null || source.isBlank()) {
                    throw new IllegalArgumentException("source must be non-blank");
                }
                Objects.requireNonNull(pid, "pid must not be null");
                Objects.requireNonNull(startedAt, "startedAt must not be null");
                Objects.requireNonNull(process, "process must not be null");
            }

            @Override
            public HealthState health() {
                return HealthState.HEALTHY;
            }
        }
    }

    /**
     * Unrelated process occupying the target port (non-IDE, unhealthy, not Managed).
     */
    record Conflict(
        Optional<Long> pid,
        Optional<Instant> startedAt,
        Optional<ObservedProcess> process
    ) implements ProcessState {
        public Conflict {
            Objects.requireNonNull(pid, "pid must not be null");
            Objects.requireNonNull(startedAt, "startedAt must not be null");
            Objects.requireNonNull(process, "process must not be null");
        }

        @Override
        public Ownership ownership() {
            return Ownership.CONFLICT;
        }

        @Override
        public HealthState health() {
            return HealthState.STARTING;
        }

        @Override
        public Optional<String> provenance() {
            return Optional.of("unknown");
        }

        @Override
        public boolean stale() {
            return false;
        }
    }
}
