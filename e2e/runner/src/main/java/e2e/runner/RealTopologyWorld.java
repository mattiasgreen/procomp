package e2e.runner;

import e2e.scenarios.engine.CliResult;
import e2e.scenarios.engine.EstateSpec;
import e2e.scenarios.engine.ExternalActorEvent;
import e2e.scenarios.engine.HttpProbeResult;
import e2e.scenarios.engine.LagProfile;
import e2e.scenarios.engine.ScenarioWorld;
import e2e.scenarios.engine.WorldKind;
import e2e.scenarios.inmemory.InMemoryConfigSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Real-OS {@link ScenarioWorld} executing the actual JEP 458 {@code Procomp.java} CLI against
 * real Spring Boot 4 JARs, embedded ActiveMQ Artemis, real WireMock standalone, and real OS
 * processes, TCP ports, and state files.
 */
public final class RealTopologyWorld implements ScenarioWorld {

    public static final String PROJECT_NAME = "real-e2e";
    private static final String DEFAULT_START_TIMEOUT_SECONDS = "60";

    private static final String CONFLICT_LISTENER_SOURCE = """
        import java.io.OutputStream;
        import java.net.InetAddress;
        import java.net.ServerSocket;
        import java.net.Socket;
        import java.nio.charset.StandardCharsets;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.util.List;

        public final class ConflictListener {
            public static void main(String[] args) throws Exception {
                if (args.length == 5 && "--spawn".equals(args[0])) {
                    ProcessBuilder pb = new ProcessBuilder(
                        List.of(args[1], args[2], "--serve", args[3], args[4])
                    );
                    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                    pb.start();
                    return;
                }
                if (args.length == 3 && "--serve".equals(args[0])) {
                    int port = Integer.parseInt(args[1]);
                    Path pidFile = Path.of(args[2]);
                    try (ServerSocket server = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))) {
                        Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
                        byte[] resp = (
                            "HTTP/1.1 404 Not Found\\r\\n"
                            + "Content-Length: 0\\r\\n"
                            + "Connection: close\\r\\n\\r\\n"
                        ).getBytes(StandardCharsets.UTF_8);
                        while (true) {
                            try (Socket client = server.accept()) {
                                OutputStream out = client.getOutputStream();
                                out.write(resp);
                                out.flush();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                System.exit(2);
            }
        }
        """;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private final Path repoRoot;
    private final Path wiremockJar;
    private final String javaHome;
    private final String javaExe;

    private LagProfile lag = LagProfile.NONE;
    private boolean initialized;
    private boolean dirty;

    private Path sandboxDir;
    private Path stateDir;
    private Path configFile;
    private Path artifactsDir;
    private int gwPort;
    private int remotePort;
    private Map<String, Integer> servicePorts = Map.of();

    private Process remoteWireMock;
    private final List<Process> externalProcesses = new ArrayList<>();
    private final List<Long> externalPids = new ArrayList<>();

    public RealTopologyWorld(Path repoRoot, Path wiremockJar, String javaHome) {
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot must not be null").toAbsolutePath().normalize();
        this.wiremockJar = Objects.requireNonNull(wiremockJar, "wiremockJar must not be null").toAbsolutePath().normalize();
        if (javaHome == null || javaHome.isBlank()) {
            throw new IllegalArgumentException("javaHome must be non-blank");
        }
        this.javaHome = javaHome;
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        this.javaExe = Path.of(javaHome, "bin", isWindows ? "java.exe" : "java").toString();
    }

    public static RealTopologyWorld fromSystemProperties() {
        Path repoRoot = Path.of(requireProp("e2e.repoRoot"));
        Path wiremockJar = Path.of(requireProp("e2e.wiremockJar"));
        String javaHome = requireProp("e2e.javaHome");
        return new RealTopologyWorld(repoRoot, wiremockJar, javaHome);
    }

    @Override
    public WorldKind worldKind() {
        return WorldKind.REAL_TOPOLOGY;
    }

    @Override
    public void reset(EstateSpec estateSpec) {
        Objects.requireNonNull(estateSpec, "estateSpec must not be null");
        if (estateSpec instanceof EstateSpec.LargeEstate) {
            throw new UnsupportedOperationException(
                "RealTopologyWorld does not support LargeEstate (" + estateSpec + "); use InMemoryWorld instead."
            );
        }

        cleanupCurrentTopology();

        if (!Files.isRegularFile(wiremockJar)) {
            throw new IllegalStateException("WireMock standalone JAR does not exist: " + wiremockJar);
        }

        try {
            int[] allocated = allocateDistinctPorts(6);
            this.gwPort = allocated[0];
            this.remotePort = allocated[1];
            int brokerPort = allocated[2];
            int ordersPort = allocated[3];
            int pricingPort = allocated[4];
            int notificationsPort = allocated[5];

            Map<String, Integer> ports = new LinkedHashMap<>();
            ports.put("broker", brokerPort);
            ports.put("orders", ordersPort);
            ports.put("pricing", pricingPort);
            ports.put("notifications", notificationsPort);
            this.servicePorts = Collections.unmodifiableMap(ports);

            this.sandboxDir = Files.createTempDirectory("procomp-real-e2e-");
            this.stateDir = sandboxDir.resolve("state");
            this.configFile = sandboxDir.resolve("procomp.json");
            this.artifactsDir = sandboxDir.resolve("artifacts");
            Files.createDirectories(stateDir);
            Files.createDirectories(artifactsDir);

            // Copy built boot JARs into sandbox-local artifacts/ so RebuildArtifact never mutates build/libs/
            for (String svc : ports.keySet()) {
                copyServiceArtifact(svc);
            }

            writeTopologyFiles(brokerPort, ordersPort, pricingPort, notificationsPort);

            // Start external Simulated Remote (real WireMock standalone, not managed by procomp)
            this.remoteWireMock = startWireMock(remotePort, "remote-wiremock");
            waitForHttp200("http://127.0.0.1:" + remotePort + "/__admin/health", Duration.ofSeconds(30));
            registerRemoteStub(remotePort, "/pricing/quote", """
                {"price": 99, "servedBy": "remote-wiremock-pricing"}
                """);

            this.initialized = true;
            this.dirty = false;
        } catch (IOException e) {
            cleanupCurrentTopology();
            throw new UncheckedIOException("Failed to bootstrap RealTopologyWorld", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanupCurrentTopology();
            throw new IllegalStateException("Interrupted while bootstrapping RealTopologyWorld", e);
        } catch (RuntimeException e) {
            cleanupCurrentTopology();
            throw e;
        }
    }

    @Override
    public void setLag(LagProfile lag) {
        this.lag = Objects.requireNonNull(lag, "lag must not be null");
    }

    @Override
    public LagProfile lag() {
        return lag;
    }

    @Override
    public CliResult runProcomp(String... args) {
        requireInitialized();
        Objects.requireNonNull(args, "args must not be null");
        this.dirty = true;
        return executeProcompProcess(withDefaultTimeoutIfNeeded(List.of(args)));
    }

    @Override
    public void triggerActor(ExternalActorEvent event) {
        requireInitialized();
        Objects.requireNonNull(event, "event must not be null");
        switch (event) {
            case ExternalActorEvent.BootstrapClean _ -> {
                if (dirty) {
                    reset(EstateSpec.StandardTopology.INSTANCE);
                }
            }
            case ExternalActorEvent.SpawnUnmanagedService(String service, boolean ideAncestor) -> {
                this.dirty = true;
                spawnUnmanagedService(service);
            }
            case ExternalActorEvent.SpawnPortConflict(String service) -> {
                this.dirty = true;
                spawnPortConflict(service);
            }
            case ExternalActorEvent.SpawnUnmanagedGateway _ -> {
                this.dirty = true;
                spawnUnmanagedGateway();
            }
            case ExternalActorEvent.KillManagedService(String service) -> {
                this.dirty = true;
                killManagedService(service);
            }
            case ExternalActorEvent.RebuildArtifact(String service) -> {
                this.dirty = true;
                rebuildArtifact(service);
            }
            case ExternalActorEvent.AppendServiceLog(String service, String line, boolean stderr) -> {
                this.dirty = true;
                appendServiceLog(service, line, stderr);
            }
            case ExternalActorEvent.SeedGitRepositories _,
                 ExternalActorEvent.AdvanceGitRemote _,
                 ExternalActorEvent.DirtyGitRepository _ -> throw new UnsupportedOperationException(
                "Git repository fixtures are not yet implemented in RealTopologyWorld: " + event
            );
        }
    }

    @Override
    public HttpProbeResult httpGet(String path) {
        requireInitialized();
        return sendHttp("GET", gatewayBaseUrl() + normalizePath(path));
    }

    @Override
    public HttpProbeResult httpPost(String path) {
        requireInitialized();
        return sendHttp("POST", gatewayBaseUrl() + normalizePath(path));
    }

    public HttpProbeResult pollHttpGet200(String path, Duration timeout) {
        requireInitialized();
        String url = gatewayBaseUrl() + normalizePath(path);
        Instant deadline = Instant.now().plus(timeout);
        HttpProbeResult last = new HttpProbeResult(0, "no attempt");
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpProbeResult resp = sendHttp("GET", url);
                if (resp.statusCode() == 200) {
                    return resp;
                }
                last = resp;
            } catch (RuntimeException e) {
                last = new HttpProbeResult(0, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            sleepMillis(250);
        }
        throw new AssertionError("Timed out waiting for HTTP 200 from " + url + " (last: " + last + ")");
    }

    public HttpProbeResult remoteHttpGet(String path) {
        requireInitialized();
        return sendHttp("GET", remoteBaseUrl() + normalizePath(path));
    }

    @Override
    public String gatewayBaseUrl() {
        requireInitialized();
        return "http://127.0.0.1:" + gwPort;
    }

    public String remoteBaseUrl() {
        requireInitialized();
        return "http://127.0.0.1:" + remotePort;
    }

    public Path sandboxDir() {
        requireInitialized();
        return sandboxDir;
    }

    public Path configFile() {
        requireInitialized();
        return configFile;
    }

    public Path stateDir() {
        requireInitialized();
        return stateDir;
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public String javaExe() {
        return javaExe;
    }

    public int gatewayPort() {
        requireInitialized();
        return gwPort;
    }

    public int remotePort() {
        requireInitialized();
        return remotePort;
    }

    public Map<String, Integer> servicePorts() {
        requireInitialized();
        return servicePorts;
    }

    @Override
    public void advanceClockSeconds(long seconds) {
        // Real OS clock advances naturally.
    }

    @Override
    public void close() {
        cleanupCurrentTopology();
    }

    static List<String> withDefaultTimeoutIfNeeded(List<String> args) {
        if (args.isEmpty()) {
            return args;
        }
        boolean isStart = "start".equals(args.get(0))
            || (args.size() >= 2 && "gateway".equals(args.get(0)) && "start".equals(args.get(1)));
        if (!isStart || args.contains("--timeout")) {
            return args;
        }
        List<String> augmented = new ArrayList<>(args.size() + 2);
        augmented.addAll(args);
        augmented.add("--timeout");
        augmented.add(DEFAULT_START_TIMEOUT_SECONDS);
        return augmented;
    }

    private void cleanupCurrentTopology() {
        if (configFile != null && Files.isRegularFile(configFile)) {
            try {
                executeProcompProcess(List.of("stop", "--all"));
            } catch (Exception ignored) {
            }
        }
        for (Process p : externalProcesses) {
            try {
                destroyTree(p.toHandle());
            } catch (Exception ignored) {
            }
        }
        externalProcesses.clear();

        for (Long pid : externalPids) {
            try {
                ProcessHandle.of(pid).ifPresent(RealTopologyWorld::destroyTree);
            } catch (Exception ignored) {
            }
        }
        externalPids.clear();

        if (remoteWireMock != null) {
            try {
                destroyTree(remoteWireMock.toHandle());
            } catch (Exception ignored) {
            }
            remoteWireMock = null;
        }

        if (sandboxDir != null) {
            deleteRecursively(sandboxDir);
            sandboxDir = null;
        }
        stateDir = null;
        configFile = null;
        artifactsDir = null;
        servicePorts = Map.of();
        initialized = false;
        dirty = false;
    }

    private void copyServiceArtifact(String service) throws IOException {
        Path libsDir = repoRoot.resolve("e2e").resolve(service).resolve("build").resolve("libs");
        if (!Files.isDirectory(libsDir)) {
            throw new IllegalStateException(
                "Missing built libs directory for service '" + service + "': " + libsDir
                    + " (run :e2e:" + service + ":bootJar first)"
            );
        }
        Path sourceJar;
        try (Stream<Path> files = Files.list(libsDir)) {
            sourceJar = files
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".jar") && !name.endsWith("-plain.jar");
                })
                .max(Comparator.comparingLong(RealTopologyWorld::lastModifiedMillis))
                .orElseThrow(() -> new IllegalStateException("No executable JAR found in " + libsDir));
        }
        Path targetDir = artifactsDir.resolve(service);
        Files.createDirectories(targetDir);
        Files.copy(
            sourceJar,
            targetDir.resolve(sourceJar.getFileName()),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES
        );
    }

    private Path findSandboxArtifact(String service) {
        Path dir = artifactsDir.resolve(service);
        try (Stream<Path> files = Files.list(dir)) {
            return files
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".jar") && !name.endsWith("-plain.jar");
                })
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No sandbox JAR found for service '" + service + "' in " + dir));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to locate sandbox JAR for " + service, e);
        }
    }

    private void writeTopologyFiles(int brokerPort, int ordersPort, int pricingPort, int notificationsPort) throws IOException {
        Files.writeString(
            sandboxDir.resolve(".env"),
            "E2E_DEMO_SECRET=" + InMemoryConfigSource.DEMO_SECRET_VALUE + "\n",
            StandardCharsets.UTF_8
        );

        Files.writeString(sandboxDir.resolve("service-index.json"), """
            {
              "apps": [
                { "servicename": "broker",        "localport": %d, "type": "artemis-broker", "subproject": "artifacts/broker" },
                { "servicename": "orders",        "localport": %d, "type": "spring-boot",    "subproject": "artifacts/orders" },
                { "servicename": "pricing",       "localport": %d, "type": "spring-boot",    "subproject": "artifacts/pricing" },
                { "servicename": "notifications", "localport": %d, "type": "spring-boot",    "subproject": "artifacts/notifications" }
              ]
            }
            """.formatted(brokerPort, ordersPort, pricingPort, notificationsPort), StandardCharsets.UTF_8);

        Files.writeString(configFile, """
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
                  "command": ["%s", "{{jvmArgs}}", "-Dserver.port={{port}}", "-jar", "{{artifact}}"],
                  "healthPath": "/actuator/health",
                  "artifactGlob": "{{subproject}}/*.jar",
                  "artifactExclude": "*-plain.jar"
                },
                "artemis-broker": {
                  "command": ["%s", "{{jvmArgs}}", "-Dbroker.port={{port}}", "-jar", "{{artifact}}"],
                  "artifactGlob": "{{subproject}}/*.jar",
                  "artifactExclude": "*-plain.jar"
                }
              },
              "gateway": {
                "type": "wiremock",
                "port": %d,
                "healthPath": "/__admin/health",
                "command": ["%s", "-Xmx192m", "-jar", "%s", "--port", "%d", "--bind-address", "127.0.0.1", "--enable-stub-cors"],
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
                "orders-local": {
                  "local": ["broker", "orders", "notifications"]
                }
              }
            }
            """.formatted(
            PROJECT_NAME,
            escapeJson(sandboxDir.toString()),
            remotePort,
            gwPort,
            brokerPort,
            escapeJson(javaExe),
            escapeJson(javaExe),
            gwPort,
            escapeJson(javaExe),
            escapeJson(wiremockJar.toString()),
            gwPort,
            escapeJson(sandboxDir.toString())
        ), StandardCharsets.UTF_8);
    }

    private Process startWireMock(int port, String logPrefix) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
            javaExe, "-Xmx192m", "-jar", wiremockJar.toString(),
            "--port", Integer.toString(port),
            "--bind-address", "127.0.0.1",
            "--enable-stub-cors"
        );
        pb.directory(sandboxDir.toFile());
        pb.redirectOutput(sandboxDir.resolve(logPrefix + ".out.log").toFile());
        pb.redirectError(sandboxDir.resolve(logPrefix + ".err.log").toFile());
        return pb.start();
    }

    private void registerRemoteStub(int port, String urlPath, String responseJson) throws IOException, InterruptedException {
        String mapping = """
            {
              "request": { "method": "GET", "urlPath": "%s" },
              "response": {
                "status": 200,
                "headers": { "Content-Type": "application/json" },
                "body": %s
              }
            }
            """.formatted(urlPath, quoteJsonString(responseJson.trim()));

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__admin/mappings"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapping, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 201) {
            throw new IllegalStateException("Failed to register remote WireMock stub (" + resp.statusCode() + "): " + resp.body());
        }
    }

    private void spawnUnmanagedService(String service) {
        int port = requireServicePort(service);
        Path jar = findSandboxArtifact(service);
        boolean isBroker = "broker".equals(service);
        String portProp = isBroker ? "-Dbroker.port=" + port : "-Dserver.port=" + port;
        try {
            ProcessBuilder pb = new ProcessBuilder(javaExe, "-Xmx256m", portProp, "-jar", jar.toString());
            pb.directory(sandboxDir.toFile());
            pb.environment().put("E2E_GATEWAY_URL", "http://127.0.0.1:" + gwPort);
            pb.environment().put("SPRING_ARTEMIS_MODE", "native");
            pb.environment().put("SPRING_ARTEMIS_BROKER_URL", "tcp://127.0.0.1:" + requireServicePort("broker"));
            pb.environment().put("E2E_DEMO_SECRET", InMemoryConfigSource.DEMO_SECRET_VALUE);
            pb.redirectOutput(sandboxDir.resolve("unmanaged-" + service + ".out.log").toFile());
            pb.redirectError(sandboxDir.resolve("unmanaged-" + service + ".err.log").toFile());
            Process proc = pb.start();
            externalProcesses.add(proc);

            if (isBroker) {
                waitForTcpListening(port, Duration.ofSeconds(60));
            } else {
                waitForHttp200("http://127.0.0.1:" + port + "/actuator/health", Duration.ofSeconds(60));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to spawn unmanaged service " + service, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for unmanaged service " + service, e);
        }
    }

    private void spawnPortConflict(String service) {
        int port = requireServicePort(service);
        try {
            Path listenerSource = sandboxDir.resolve("ConflictListener.java");
            if (!Files.isRegularFile(listenerSource)) {
                Files.writeString(listenerSource, CONFLICT_LISTENER_SOURCE, StandardCharsets.UTF_8);
            }
            Path pidFile = sandboxDir.resolve("conflict-" + service + ".pid");
            Files.deleteIfExists(pidFile);

            // Spawn via short-lived launcher JVM that exits immediately so the listener has no IDE ancestor.
            ProcessBuilder launcher = new ProcessBuilder(
                javaExe,
                listenerSource.toString(),
                "--spawn",
                javaExe,
                listenerSource.toString(),
                Integer.toString(port),
                pidFile.toString()
            );
            launcher.directory(sandboxDir.toFile());
            launcher.redirectErrorStream(true);
            Process launcherProc = launcher.start();
            String launcherOutput = new String(launcherProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!launcherProc.waitFor(30, TimeUnit.SECONDS) || launcherProc.exitValue() != 0) {
                destroyTree(launcherProc.toHandle());
                throw new IllegalStateException(
                    "ConflictListener launcher failed for " + service + " on port " + port + ": " + launcherOutput
                );
            }

            long pid = waitForPidFile(pidFile, Duration.ofSeconds(20));
            externalPids.add(pid);
            waitForTcpListening(port, Duration.ofSeconds(15));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to spawn port conflict for " + service, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while spawning port conflict for " + service, e);
        }
    }

    private void spawnUnmanagedGateway() {
        try {
            Process gw = startWireMock(gwPort, "unmanaged-gateway");
            externalProcesses.add(gw);
            waitForHttp200("http://127.0.0.1:" + gwPort + "/__admin/health", Duration.ofSeconds(30));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to spawn unmanaged gateway", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for unmanaged gateway", e);
        }
    }

    private static final Pattern PID_JSON_PATTERN = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");

    private void killManagedService(String service) {
        Path procFile = stateDir.resolve(PROJECT_NAME).resolve("processes").resolve(service + ".json");
        if (!Files.isRegularFile(procFile)) {
            throw new IllegalStateException("Managed process record not found for '" + service + "': " + procFile);
        }
        try {
            String raw = Files.readString(procFile, StandardCharsets.UTF_8);
            Matcher matcher = PID_JSON_PATTERN.matcher(raw);
            if (!matcher.find()) {
                throw new IllegalStateException("Missing pid in managed process JSON for '" + service + "': " + raw);
            }
            long pid = Long.parseLong(matcher.group(1));
            ProcessHandle.of(pid).ifPresent(RealTopologyWorld::destroyTree);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to kill managed service " + service, e);
        }
    }

    private void rebuildArtifact(String service) {
        Path jar = findSandboxArtifact(service);
        try {
            Instant future = Instant.now().plusSeconds(10);
            Files.setLastModifiedTime(jar, FileTime.from(future));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to update artifact timestamp for " + service, e);
        }
    }

    private void appendServiceLog(String service, String line, boolean stderr) {
        Path logFile = stateDir.resolve(PROJECT_NAME)
            .resolve("logs")
            .resolve(service + (stderr ? ".err.log" : ".out.log"));
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(
                logFile,
                line + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append service log for " + service, e);
        }
    }

    private CliResult executeProcompProcess(List<String> args) {
        List<String> cmd = new ArrayList<>(args.size() + 4);
        cmd.add(javaExe);
        cmd.add(repoRoot.resolve("src/procomp/cli/Procomp.java").toString());
        cmd.add("--config");
        cmd.add(configFile.toString());
        cmd.addAll(args);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(repoRoot.toFile());
            pb.environment().put("JAVA_HOME", javaHome);
            pb.environment().put("PROCOMP_STATE_DIR", stateDir.toString());
            Process p = pb.start();

            CompletableFuture<String> outFuture = CompletableFuture.supplyAsync(() -> readAllQuiet(p.getInputStream()));
            CompletableFuture<String> errFuture = CompletableFuture.supplyAsync(() -> readAllQuiet(p.getErrorStream()));

            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                destroyTree(p.toHandle());
                throw new AssertionError("CLI timed out: " + cmd);
            }
            String stdout = outFuture.get(10, TimeUnit.SECONDS);
            String stderr = errFuture.get(10, TimeUnit.SECONDS);
            return new CliResult(p.exitValue(), stdout, stderr);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to execute procomp CLI: " + cmd, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted executing procomp CLI: " + cmd, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed collecting output from procomp CLI: " + cmd, e);
        }
    }

    private static HttpProbeResult sendHttp(String method, String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15));
            if ("POST".equals(method)) {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                builder.GET();
            }
            HttpResponse<String> resp = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpProbeResult(resp.statusCode(), resp.body());
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP " + method + " " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during HTTP " + method + " " + url, e);
        }
    }

    private static void waitForHttp200(String url, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String lastError = "no attempt";
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200) {
                    return;
                }
                lastError = "HTTP " + resp.statusCode() + ": " + resp.body();
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Timed out waiting for HTTP 200 from " + url + " (last: " + lastError + ")");
    }

    private static void waitForTcpListening(int port, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException ignored) {
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Timed out waiting for TCP port 127.0.0.1:" + port);
    }

    private static long waitForPidFile(Path pidFile, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(pidFile)) {
                try {
                    String content = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
                    if (!content.isEmpty()) {
                        return Long.parseLong(content);
                    }
                } catch (Exception ignored) {
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Timed out waiting for PID file: " + pidFile);
    }

    private int requireServicePort(String service) {
        Integer port = servicePorts.get(service);
        if (port == null) {
            throw new IllegalArgumentException("Unknown service '" + service + "' (known: " + servicePorts.keySet() + ")");
        }
        return port;
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("RealTopologyWorld.reset(...) must be called first");
        }
    }

    private static String normalizePath(String path) {
        Objects.requireNonNull(path, "path must not be null");
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String readAllQuiet(java.io.InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String quoteJsonString(String raw) {
        return "\"" + escapeJson(raw) + "\"";
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static int[] allocateDistinctPorts(int count) throws IOException {
        List<ServerSocket> held = new ArrayList<>(count);
        int[] ports = new int[count];
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try {
            for (int i = 0; i < count; i++) {
                ServerSocket bound = null;
                // Prefer non-ephemeral ports (20000..45000) so OS dynamic outbound sockets never collide.
                for (int attempt = 0; attempt < 64; attempt++) {
                    int candidate = ThreadLocalRandom.current().nextInt(20000, 45000);
                    try {
                        bound = new ServerSocket(candidate, 1, loopback);
                        break;
                    } catch (IOException ignored) {
                    }
                }
                if (bound == null) {
                    bound = new ServerSocket(0, 1, loopback);
                }
                held.add(bound);
                ports[i] = bound.getLocalPort();
            }
            return ports;
        } finally {
            for (ServerSocket s : held) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String requireProp(String key) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + key);
        }
        return val;
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling", e);
        }
    }

    private static void destroyTree(ProcessHandle handle) {
        List<ProcessHandle> all = new ArrayList<>(handle.descendants().toList());
        all.add(handle);
        for (ProcessHandle h : all) {
            if (h.isAlive()) {
                h.destroyForcibly();
            }
        }
        for (ProcessHandle h : all) {
            try {
                h.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
