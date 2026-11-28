package procomp.core.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure spawn-time size-threshold log rotation planner (zero I/O, zero nulls).
 * Generation {@code 0} denotes the active log file; generations {@code 1..maxBackups}
 * denote rotated backups ({@code .1} newest through {@code .maxBackups} oldest).
 */
public final class LogRotation {

    public static final long DEFAULT_MAX_FILE_BYTES = 10L * 1024L * 1024L;
    public static final int DEFAULT_MAX_BACKUPS = 3;

    private LogRotation() {}

    public static RotationPlan plan(
        long activeFileSize,
        long maxFileBytes,
        int maxBackups,
        Set<Integer> existingBackups
    ) {
        Objects.requireNonNull(existingBackups, "existingBackups must not be null");
        if (activeFileSize < 0) {
            throw new IllegalArgumentException("activeFileSize must be non-negative: " + activeFileSize);
        }
        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("maxFileBytes must be positive: " + maxFileBytes);
        }
        if (maxBackups < 0) {
            throw new IllegalArgumentException("maxBackups must be non-negative: " + maxBackups);
        }
        if (maxBackups == 0 || activeFileSize < maxFileBytes) {
            return RotationPlan.NoOp.INSTANCE;
        }

        List<Step> steps = new ArrayList<>(maxBackups + 1);
        if (existingBackups.contains(maxBackups)) {
            steps.add(new Step.Delete(maxBackups));
        }
        for (int gen = maxBackups - 1; gen >= 1; gen--) {
            if (existingBackups.contains(gen)) {
                steps.add(new Step.Rename(gen, gen + 1));
            }
        }
        steps.add(new Step.Rename(0, 1));
        return new RotationPlan.Rotate(List.copyOf(steps));
    }

    public sealed interface RotationPlan permits RotationPlan.NoOp, RotationPlan.Rotate {

        record NoOp() implements RotationPlan {
            public static final NoOp INSTANCE = new NoOp();
        }

        record Rotate(List<Step> steps) implements RotationPlan {
            public Rotate {
                Objects.requireNonNull(steps, "steps must not be null");
                if (steps.isEmpty()) {
                    throw new IllegalArgumentException("steps must not be empty");
                }
                steps = List.copyOf(steps);
            }
        }
    }

    public sealed interface Step permits Step.Delete, Step.Rename {

        record Delete(int generation) implements Step {
            public Delete {
                if (generation <= 0) {
                    throw new IllegalArgumentException("generation must be positive: " + generation);
                }
            }
        }

        record Rename(int fromGeneration, int toGeneration) implements Step {
            public Rename {
                if (fromGeneration < 0) {
                    throw new IllegalArgumentException("fromGeneration must be non-negative: " + fromGeneration);
                }
                if (toGeneration <= fromGeneration) {
                    throw new IllegalArgumentException(
                        "toGeneration must be > fromGeneration: " + fromGeneration + " -> " + toGeneration
                    );
                }
            }
        }
    }
}
