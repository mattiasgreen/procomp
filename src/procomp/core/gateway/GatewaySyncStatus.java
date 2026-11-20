package procomp.core.gateway;

/**
 * Synchronization state between the Project's Routing Table and the live Gateway mappings.
 */
public enum GatewaySyncStatus {
    OFFLINE,
    IN_SYNC,
    DRIFTED
}
