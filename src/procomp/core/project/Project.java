package procomp.core.project;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Fully validated microservice estate configuration loaded from procomp.json and optional Catalog.
 */
public record Project(
    String name,
    Path configPath,
    Path configDir,
    Optional<Path> repositoriesRoot,
    Map<String, RuntimeDefinition> runtimes,
    Optional<GatewayDefinition> gateway,
    Map<String, ServiceDefinition> services,
    Map<String, ProfileDefinition> profiles,
    List<RepositoryRef> repositories
) {
    public Project {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project name must be non-blank");
        }
        Objects.requireNonNull(configPath);
        Objects.requireNonNull(configDir);
        Objects.requireNonNull(repositoriesRoot);
        runtimes = Collections.unmodifiableMap(new LinkedHashMap<>(runtimes));
        Objects.requireNonNull(gateway);
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        repositories = List.copyOf(repositories);
    }

    public Optional<ServiceDefinition> service(String serviceName) {
        return Optional.ofNullable(services.get(serviceName));
    }

    public Optional<ProfileDefinition> profile(String profileName) {
        return Optional.ofNullable(profiles.get(profileName));
    }
}
