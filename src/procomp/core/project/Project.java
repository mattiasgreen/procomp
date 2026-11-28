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
    RepositorySettings repositorySettings,
    Map<String, BuilderDefinition> builders,
    Map<String, RuntimeDefinition> runtimes,
    Optional<GatewayDefinition> gateway,
    Map<String, ServiceDefinition> services,
    Map<String, ProfileDefinition> profiles,
    List<RepositoryRef> repositories,
    Optional<GitConfig> git
) {
    public Project(
        String name,
        Path configPath,
        Path configDir,
        Optional<Path> repositoriesRoot,
        Map<String, BuilderDefinition> builders,
        Map<String, RuntimeDefinition> runtimes,
        Optional<GatewayDefinition> gateway,
        Map<String, ServiceDefinition> services,
        Map<String, ProfileDefinition> profiles,
        List<RepositoryRef> repositories
    ) {
        this(
            name, configPath, configDir, repositoriesRoot, RepositorySettings.DEFAULT,
            builders, runtimes, gateway, services, profiles, repositories, Optional.empty()
        );
    }

    public Project(
        String name,
        Path configPath,
        Path configDir,
        Optional<Path> repositoriesRoot,
        RepositorySettings repositorySettings,
        Map<String, BuilderDefinition> builders,
        Map<String, RuntimeDefinition> runtimes,
        Optional<GatewayDefinition> gateway,
        Map<String, ServiceDefinition> services,
        Map<String, ProfileDefinition> profiles,
        List<RepositoryRef> repositories
    ) {
        this(
            name, configPath, configDir, repositoriesRoot, repositorySettings,
            builders, runtimes, gateway, services, profiles, repositories, Optional.empty()
        );
    }

    public Project {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project name must be non-blank");
        }
        Objects.requireNonNull(configPath);
        Objects.requireNonNull(configDir);
        Objects.requireNonNull(repositoriesRoot);
        Objects.requireNonNull(repositorySettings);
        builders = Collections.unmodifiableMap(new LinkedHashMap<>(builders));
        runtimes = Collections.unmodifiableMap(new LinkedHashMap<>(runtimes));
        Objects.requireNonNull(gateway);
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        repositories = List.copyOf(repositories);
        Objects.requireNonNull(git);
    }

    public Optional<RepositoryRef> repository(String repositoryName) {
        for (RepositoryRef ref : repositories) {
            if (ref.name().equals(repositoryName)) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }

    public Optional<BuilderDefinition> builder(String builderName) {
        return Optional.ofNullable(builders.get(builderName));
    }

    public Optional<ServiceDefinition> service(String serviceName) {
        return Optional.ofNullable(services.get(serviceName));
    }

    public Optional<ProfileDefinition> profile(String profileName) {
        return Optional.ofNullable(profiles.get(profileName));
    }
}
