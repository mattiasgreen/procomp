package e2e.scenarios.engine;

import java.util.Objects;

/**
 * Captured result of invoking the {@code procomp} CLI.
 */
public record CliResult(int exitCode, String stdout, String stderr) {

    public CliResult {
        Objects.requireNonNull(stdout, "stdout must not be null");
        Objects.requireNonNull(stderr, "stderr must not be null");
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }
}
