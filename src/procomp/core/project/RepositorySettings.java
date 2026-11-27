package procomp.core.project;

/**
 * Project-level Git settings for {@code Repository} observation and {@code Git Run} execution.
 */
public record RepositorySettings(
    int fetchWarnAfterHours,
    int timeoutSeconds
) {
    public static final int DEFAULT_FETCH_WARN_AFTER_HOURS = 24;
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    public static final RepositorySettings DEFAULT = new RepositorySettings(
        DEFAULT_FETCH_WARN_AFTER_HOURS,
        DEFAULT_TIMEOUT_SECONDS
    );

    public RepositorySettings {
        if (fetchWarnAfterHours < 0) {
            throw new IllegalArgumentException("fetchWarnAfterHours must be >= 0, got " + fetchWarnAfterHours);
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be > 0, got " + timeoutSeconds);
        }
    }
}
