package procomp.core.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unresolved, schema-validated representation of procomp.json prior to catalog/env/template resolution.
 */
public record RawProjectConfig(
    String project,
    Optional<String> repositoriesRoot,
    List<EnvFileSpec> envFiles,
    RawDefaultsConfig defaults,
    Map<String, RawRuntimeConfig> runtimes,
    Optional<RawGatewayConfig> gateway,
    Optional<CatalogMapping> catalog,
    Map<String, RawServiceConfig> services,
    Map<String, ProfileDefinition> profiles
) {
    public RawProjectConfig {
        envFiles = List.copyOf(envFiles);
        runtimes = Collections.unmodifiableMap(new LinkedHashMap<>(runtimes));
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    public record RawDefaultsConfig(
        Optional<String> proxyPathTemplate,
        Optional<String> remoteUrlTemplate,
        Optional<String> workingDir,
        Map<String, VarValue> vars,
        Map<String, String> env
    ) {
        public static final RawDefaultsConfig EMPTY = new RawDefaultsConfig(
            Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Map.of()
        );

        public RawDefaultsConfig {
            vars = Collections.unmodifiableMap(new LinkedHashMap<>(vars));
            env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }
    }

    public record RawRuntimeConfig(
        List<String> command,
        Optional<String> healthPath,
        Optional<String> artifactGlob,
        Optional<String> artifactExclude,
        Optional<String> workingDir,
        Map<String, String> env
    ) {
        public RawRuntimeConfig {
            command = List.copyOf(command);
            env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }
    }

    public record RawGatewayConfig(
        String type,
        int port,
        Optional<String> healthPath,
        List<String> command,
        Optional<String> workingDir,
        Map<String, String> env
    ) {
        public RawGatewayConfig {
            command = List.copyOf(command);
            env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }
    }

    public record RawServiceConfig(
        Optional<Integer> port,
        Optional<String> runtime,
        Optional<String> repository,
        Optional<String> repositoryUrl,
        Optional<String> workingDir,
        Optional<String> healthPath,
        Optional<String> proxyPath,
        Optional<String> remoteUrl,
        Map<String, VarValue> vars,
        Map<String, String> env,
        List<EnvFileSpec> envFiles
    ) {
        public RawServiceConfig {
            vars = Collections.unmodifiableMap(new LinkedHashMap<>(vars));
            env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
            envFiles = List.copyOf(envFiles);
        }
    }
}
