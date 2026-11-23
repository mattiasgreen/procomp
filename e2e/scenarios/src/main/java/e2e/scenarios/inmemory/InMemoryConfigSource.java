package e2e.scenarios.inmemory;

import e2e.scenarios.engine.EstateSpec;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.ConfigSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory implementation of {@link ConfigSource} synthesizing {@code .env},
 * {@code service-index.json}, and {@code procomp.json} for both the standard 4-service
 * topology and parameterized N-service {@link EstateSpec.LargeEstate}s.
 */
public final class InMemoryConfigSource implements ConfigSource {

    public static final String DEMO_SECRET_VALUE = "e2e-artemis-top-secret-9988";
    public static final int DEFAULT_GATEWAY_PORT = 18080;
    public static final int DEFAULT_REMOTE_PORT = 18090;

    public record ServiceSpec(
        String name,
        int port,
        String runtime,
        String subproject,
        boolean hasHttpHealth
    ) {}

    public record EstateLayout(
        String projectName,
        Path projectDir,
        int gatewayPort,
        int remotePort,
        Map<String, ServiceSpec> services
    ) {
        public Optional<ServiceSpec> serviceByPort(int port) {
            for (ServiceSpec spec : services.values()) {
                if (spec.port() == port) {
                    return Optional.of(spec);
                }
            }
            return Optional.empty();
        }
    }

    private final Path projectDir;
    private final Map<Path, String> files = new LinkedHashMap<>();
    private final Map<String, String> envVars = new LinkedHashMap<>();
    private EstateLayout layout;

    public InMemoryConfigSource() {
        this(Path.of("/in-memory-project").toAbsolutePath().normalize());
    }

    public InMemoryConfigSource(Path projectDir) {
        this.projectDir = Objects.requireNonNull(projectDir, "projectDir must not be null")
            .toAbsolutePath().normalize();
        this.layout = configure(EstateSpec.StandardTopology.INSTANCE);
    }

    public EstateLayout configure(EstateSpec estateSpec) {
        Objects.requireNonNull(estateSpec, "estateSpec must not be null");
        files.clear();
        envVars.clear();

        EstateLayout newLayout = switch (estateSpec) {
            case EstateSpec.StandardTopology _ -> buildStandardTopology();
            case EstateSpec.LargeEstate(int serviceCount) -> buildLargeEstate(serviceCount);
        };
        this.layout = newLayout;
        return newLayout;
    }

    public EstateLayout layout() {
        return layout;
    }

    private EstateLayout buildStandardTopology() {
        Map<String, ServiceSpec> services = new LinkedHashMap<>();
        services.put("broker", new ServiceSpec("broker", 16161, "artemis-broker", "e2e/broker", false));
        services.put("orders", new ServiceSpec("orders", 18101, "spring-boot", "e2e/orders", true));
        services.put("pricing", new ServiceSpec("pricing", 18102, "spring-boot", "e2e/pricing", true));
        services.put("notifications", new ServiceSpec("notifications", 18103, "spring-boot", "e2e/notifications", true));

        EstateLayout result = new EstateLayout(
            "procomp-e2e",
            projectDir,
            DEFAULT_GATEWAY_PORT,
            DEFAULT_REMOTE_PORT,
            Collections.unmodifiableMap(new LinkedHashMap<>(services))
        );
        populateFiles(result, Map.of("orders-local", List.of("broker", "orders", "notifications")));
        return result;
    }

    private EstateLayout buildLargeEstate(int serviceCount) {
        Map<String, ServiceSpec> services = new LinkedHashMap<>();
        List<String> checkoutSlice = new ArrayList<>();
        int localSliceSize = Math.min(8, serviceCount);
        for (int i = 1; i <= serviceCount; i++) {
            String name = String.format("svc-%02d", i);
            int port = 18200 + i;
            services.put(name, new ServiceSpec(name, port, "spring-boot", "services/" + name, true));
            if (i <= localSliceSize) {
                checkoutSlice.add(name);
            }
        }
        EstateLayout result = new EstateLayout(
            "large-estate",
            projectDir,
            DEFAULT_GATEWAY_PORT,
            DEFAULT_REMOTE_PORT,
            Collections.unmodifiableMap(new LinkedHashMap<>(services))
        );
        populateFiles(result, Map.of("checkout-slice", List.copyOf(checkoutSlice)));
        return result;
    }

    private void populateFiles(EstateLayout layout, Map<String, List<String>> profiles) {
        putFile(".env", "E2E_DEMO_SECRET=" + DEMO_SECRET_VALUE + "\n");

        StringBuilder catalog = new StringBuilder();
        catalog.append("{\n  \"apps\": [\n");
        List<ServiceSpec> specs = List.copyOf(layout.services().values());
        for (int i = 0; i < specs.size(); i++) {
            ServiceSpec svc = specs.get(i);
            catalog.append("    { \"servicename\": \"").append(svc.name())
                .append("\", \"localport\": ").append(svc.port())
                .append(", \"type\": \"").append(svc.runtime())
                .append("\", \"subproject\": \"").append(svc.subproject()).append("\" }");
            if (i + 1 < specs.size()) {
                catalog.append(',');
            }
            catalog.append('\n');
        }
        catalog.append("  ]\n}\n");
        putFile("service-index.json", catalog.toString());

        StringBuilder profilesJson = new StringBuilder();
        int pIdx = 0;
        for (Map.Entry<String, List<String>> entry : profiles.entrySet()) {
            if (pIdx++ > 0) {
                profilesJson.append(",\n");
            }
            profilesJson.append("    \"").append(entry.getKey()).append("\": { \"local\": [");
            List<String> locals = entry.getValue();
            for (int i = 0; i < locals.size(); i++) {
                if (i > 0) {
                    profilesJson.append(", ");
                }
                profilesJson.append('"').append(locals.get(i)).append('"');
            }
            profilesJson.append("] }");
        }

        String rootStr = escapeJson(projectDir.toString());
        int brokerPort = layout.services().containsKey("broker") ? layout.services().get("broker").port() : 16161;
        String configJson = """
            {
              "project": "%s",
              "repositoriesRoot": "%s",
              "envFiles": [".env"],
              "defaults": {
                "proxyPathTemplate": "/{{service}}",
                "remoteUrlTemplate": "http://127.0.0.1:%d/{{service}}",
                "workingDir": "{{repositoriesRoot}}",
                "vars": {
                  "jvmArgs": ["-Xmx256m"]
                },
                "env": {
                  "E2E_GATEWAY_URL": "http://127.0.0.1:%d",
                  "SPRING_ARTEMIS_MODE": "native",
                  "SPRING_ARTEMIS_BROKER_URL": "tcp://127.0.0.1:%d"
                }
              },
              "runtimes": {
                "spring-boot": {
                  "command": ["java", "{{jvmArgs}}", "-Dserver.port={{port}}", "-jar", "{{artifact}}"],
                  "healthPath": "/actuator/health",
                  "artifactGlob": "{{subproject}}/build/libs/*.jar",
                  "artifactExclude": "*-plain.jar"
                },
                "artemis-broker": {
                  "command": ["java", "{{jvmArgs}}", "-Dbroker.port={{port}}", "-jar", "{{artifact}}"],
                  "artifactGlob": "{{subproject}}/build/libs/*.jar",
                  "artifactExclude": "*-plain.jar"
                }
              },
              "gateway": {
                "type": "wiremock",
                "port": %d,
                "healthPath": "/__admin/health",
                "command": ["java", "-Xmx192m", "-jar", "wiremock-standalone.jar", "--port", "%d", "--bind-address", "127.0.0.1"],
                "workingDir": "%s"
              },
              "catalog": {
                "path": "service-index.json",
                "itemsPath": "apps",
                "map": {
                  "name": "servicename",
                  "port": "localport",
                  "runtime": "type",
                  "vars.subproject": "subproject"
                }
              },
              "profiles": {
            %s
              }
            }
            """.formatted(
            layout.projectName(),
            rootStr,
            layout.remotePort(),
            layout.gatewayPort(),
            brokerPort,
            layout.gatewayPort(),
            layout.gatewayPort(),
            rootStr,
            profilesJson.toString()
        );
        putFile("procomp.json", configJson);
    }

    private void putFile(String relativeName, String content) {
        files.put(projectDir.resolve(relativeName).toAbsolutePath().normalize(), content);
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public Path currentWorkingDirectory() {
        return projectDir;
    }

    @Override
    public Optional<String> envVar(String name) {
        return Optional.ofNullable(envVars.get(name));
    }

    @Override
    public Map<String, String> allEnvVars() {
        return Map.copyOf(envVars);
    }

    @Override
    public boolean isRegularFile(Path path) {
        return files.containsKey(path.toAbsolutePath().normalize());
    }

    @Override
    public Result<String, DomainError> readFile(Path path) {
        Path norm = path.toAbsolutePath().normalize();
        String content = files.get(norm);
        if (content == null) {
            return Result.err(DomainError.of(
                "CONFIG_FILE_NOT_FOUND",
                "Config file not found: " + norm,
                Map.of("path", norm.toString())
            ));
        }
        return Result.ok(content);
    }
}
