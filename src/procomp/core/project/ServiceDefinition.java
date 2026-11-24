package procomp.core.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fully resolved logical Service definition inside a Project.
 */
public record ServiceDefinition(
    String name,
    Optional<Integer> port,
    String runtime,
    Optional<ServiceBuildConfig> buildConfig,
    Optional<RepositoryRef> repository,
    Path workingDir,
    Optional<String> healthPath,
    String proxyPath,
    Optional<String> remoteUrl,
    Optional<String> artifactGlob,
    Optional<String> artifactExclude,
    List<String> command,
    SecretEnv env,
    List<EnvFileSpec> envFiles
) {
    public ServiceDefinition {
        if (!ProjectConfigParser.isPathSafeIdentifier(name)) {
            throw new IllegalArgumentException("ServiceDefinition name must be a single path-safe segment: " + name);
        }
        Objects.requireNonNull(port);
        port.ifPresent(p -> {
            if (p < 1 || p > 65535) {
                throw new IllegalArgumentException("ServiceDefinition port out of range: " + p);
            }
        });
        if (runtime == null || runtime.isBlank()) {
            throw new IllegalArgumentException("ServiceDefinition runtime must be non-blank");
        }
        Objects.requireNonNull(buildConfig);
        Objects.requireNonNull(repository);
        Objects.requireNonNull(workingDir);
        Objects.requireNonNull(healthPath);
        if (proxyPath == null || proxyPath.isBlank()) {
            throw new IllegalArgumentException("ServiceDefinition proxyPath must be non-blank");
        }
        Objects.requireNonNull(remoteUrl);
        Objects.requireNonNull(artifactGlob);
        Objects.requireNonNull(artifactExclude);
        command = List.copyOf(command);
        Objects.requireNonNull(env);
        envFiles = List.copyOf(envFiles);
    }
}
