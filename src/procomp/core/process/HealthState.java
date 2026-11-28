package procomp.core.process;

/**
 * Pure, stateless Health State values observed for a Service or Gateway Process.
 */
public enum HealthState {
    STOPPED,
    STARTING,
    HEALTHY,
    STOPPING,
    CRASHED;

    public boolean isLive() {
        return this == STARTING || this == HEALTHY || this == STOPPING;
    }
}
