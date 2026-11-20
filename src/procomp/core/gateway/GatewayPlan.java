package procomp.core.gateway;

import java.util.List;

/**
 * Desired set of WireMock proxy mappings derived from a Project and its Routing Table.
 */
public record GatewayPlan(
    String projectName,
    List<WireMockMapping> mappings
) {
    public GatewayPlan {
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException("GatewayPlan projectName must be non-blank");
        }
        mappings = List.copyOf(mappings);
    }
}
