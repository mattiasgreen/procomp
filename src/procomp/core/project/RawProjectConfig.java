package procomp.core.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unresolved, schema-validated representation of procomp.json prior to catalog/env/template resolution.
 * For {@code healthPath}, {@link Optional#empty()} indicates omitted/inherited whereas {@code Optional.of("")}
 * preserves an explicit empty override that disables HTTP health checks.
 */
record RawProjectConfig(
    String project,
    Optional<String> repositoriesRoot,
    List<EnvFileSpec> envFiles,
    RawDefaultsConfig defaults,
    Map<String, RawBuilderConfig> builders,
    Map<String, RawRuntimeConfig> runtimes,
    Optional<RawGatewayConfig> gateway,
    Optional<CatalogMapping> catalog,
    Map<String, RawServiceConfig> services,
    Map<String, ProfileDefinition> profiles
) {
    RawProjectConfig {
        envFiles = List.copyOf(envFiles);
        builders = Collections.unmodifiableMap(new LinkedHashMap<>(builders));
        runtimes = Collections.unmodifiableMap(new LinkedHashMap<>(runtimes));
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    record RawDefaultsConfig(
        Optional<String> builder,
        Optional<String> proxyPathTemplate,
        Optional<String> remoteUrlTemplate,
        Optional<String> workingDir,
        Map<String, VarValue> vars,
        Map<String, String> env
    ) {
        static final RawDefaultsConfig EMPTY = new RawDefaultsConfig(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Map.of()
        );

        RawDefaultsConfig {
            vars = Collections.unmodifiableMap(new LinkedHashMap<>(vars));
            env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }
    }

    record RawBuilderConfig(
        List<String> command,
        Optional<String> workingDir,
        Map<String, String> env
    ) {
        RawBuilderConfig {
            command = List.copyOf(command);
            env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }
    }

    record RawRuntimeConfig(
        List<String> command,
        Optional<String> healthPath,
        Optional<String> artifactGlob,
        Optional<String> artifactExclude,
        Optional<String> workingDir,
        Map<String, String> env
    ) {
        RawRuntimeConfig {
            command = List.copyOf(command);
            env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }
    }

    record RawGatewayConfig(
        String type,
        int port,
        Optional<String> healthPath,
        List<String> command,
        Optional<String> workingDir,
        Map<String, String> env,
        Map<String, String> proxyHeaders
    ) {
        RawGatewayConfig {
            command = List.copyOf(command);
            env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
            proxyHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(proxyHeaders));
        }
    }

    record RawServiceConfig(
        Optional<Integer> port,
        Optional<String> runtime,
        Optional<String> builder,
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
        RawServiceConfig {
            vars = Collections.unmodifiableMap(new LinkedHashMap<>(vars));
            env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
            envFiles = List.copyOf(envFiles);
        }
    }
}
