package e2e.scenarios.inmemory;

import e2e.scenarios.engine.CliResult;
import e2e.scenarios.engine.EstateSpec;
import e2e.scenarios.engine.ExternalActorEvent;
import e2e.scenarios.engine.HttpProbeResult;
import e2e.scenarios.engine.LagProfile;
import e2e.scenarios.engine.ScenarioWorld;
import e2e.scenarios.engine.WorldKind;
import e2e.scenarios.inmemory.InMemoryConfigSource.EstateLayout;
import e2e.scenarios.inmemory.InMemoryConfigSource.ServiceSpec;
import procomp.cli.CliRuntime;
import procomp.cli.Procomp;
import procomp.shell.ProcompRuntime;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProgressObserver;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Faithful stateful in-memory implementation of {@link ScenarioWorld} wiring the shared
 * modality-neutral {@link ProcompRuntime} and {@link Procomp#run(String[], CliRuntime)}
 * to deterministic virtual OS, port, HTTP/WireMock, filesystem, and clock fakes.
 */
public final class InMemoryWorld implements ScenarioWorld {

    public enum GitRepoState {
        CLEAN,
        UNCLONED,
        BEHIND_REMOTE,
        DIRTY
    }

    private final FakeClock clock;
    private final InMemoryConfigSource configSource;
    private final InMemoryStateStore stateStore;
    private final SimulatedProjectFiles projectFiles;
    private final VirtualOsState os;
    private final SimulatedProcessRunner processRunner;
    private final SimulatedPortScanner portScanner;
    private final SimulatedHttpClient httpClient;
    private final Map<String, GitRepoState> gitRepositories = new LinkedHashMap<>();

    private EstateSpec currentEstateSpec;
    private ProcompRuntime procompRuntime;

    public InMemoryWorld() {
        this.clock = new FakeClock();
        this.configSource = new InMemoryConfigSource();
        this.stateStore = new InMemoryStateStore();
        this.projectFiles = new SimulatedProjectFiles();
        this.os = new VirtualOsState(clock, stateStore);
        this.processRunner = new SimulatedProcessRunner(os);
        this.portScanner = new SimulatedPortScanner(os);
        this.httpClient = new SimulatedHttpClient(os, configSource::layout);
        reset(EstateSpec.StandardTopology.INSTANCE);
    }

    public ProcompRuntime procompRuntime() {
        return procompRuntime;
    }

    public Map<String, GitRepoState> gitRepositories() {
        return Collections.unmodifiableMap(gitRepositories);
    }

    @Override
    public WorldKind worldKind() {
        return WorldKind.IN_MEMORY;
    }

    @Override
    public void reset(EstateSpec estateSpec) {
        Objects.requireNonNull(estateSpec, "estateSpec must not be null");
        this.currentEstateSpec = estateSpec;

        clock.reset();
        stateStore.clear();
        projectFiles.clear();
        os.reset();
        httpClient.reset();
        gitRepositories.clear();

        EstateLayout layout = configSource.configure(estateSpec);
        projectFiles.addDirectory(layout.projectDir());

        Instant initialArtifactTime = clock.now().minusSeconds(60);
        for (ServiceSpec svc : layout.services().values()) {
            Path libsDir = layout.projectDir().resolve(svc.subproject()).resolve("build/libs");
            Path jarPath = libsDir.resolve(svc.name() + "-1.0.0.jar");
            Path plainJarPath = libsDir.resolve(svc.name() + "-1.0.0-plain.jar");
            projectFiles.registerServiceArtifact(svc.name(), jarPath, initialArtifactTime);
            projectFiles.addArtifactFile(plainJarPath, initialArtifactTime.plusSeconds(1));
        }

        this.procompRuntime = ProcompRuntime.builder()
            .configSource(configSource)
            .stateStore(stateStore)
            .processRunner(processRunner)
            .portScanner(portScanner)
            .httpClient(httpClient)
            .projectFiles(projectFiles)
            .clock(clock)
            .observer(ProgressObserver.noop())
            .build();
    }

    @Override
    public void setLag(LagProfile lag) {
        os.setLag(Objects.requireNonNull(lag, "lag must not be null"));
    }

    @Override
    public LagProfile lag() {
        return os.lag();
    }

    @Override
    public CliResult runProcomp(String... args) {
        Objects.requireNonNull(args, "args must not be null");
        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        PrintWriter out = new PrintWriter(outBuf, true);
        PrintWriter err = new PrintWriter(errBuf, true);

        int exitCode = Procomp.run(args, CliRuntime.of(procompRuntime, out, err));
        out.flush();
        err.flush();
        return new CliResult(exitCode, outBuf.toString(), errBuf.toString());
    }

    @Override
    public void triggerActor(ExternalActorEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        EstateLayout layout = configSource.layout();
        switch (event) {
            case ExternalActorEvent.BootstrapClean _ -> reset(currentEstateSpec);
            case ExternalActorEvent.SpawnUnmanagedService(String service, boolean ideAncestor) -> {
                ServiceSpec spec = requireService(layout, service);
                os.spawnUnmanagedService(service, spec.port(), ideAncestor);
            }
            case ExternalActorEvent.SpawnPortConflict(String service) -> {
                ServiceSpec spec = requireService(layout, service);
                os.spawnPortConflict(service, spec.port());
            }
            case ExternalActorEvent.SpawnUnmanagedGateway _ -> os.spawnUnmanagedGateway(layout.gatewayPort());
            case ExternalActorEvent.KillManagedService(String service) -> {
                requireService(layout, service);
                os.killManagedService(service);
            }
            case ExternalActorEvent.RebuildArtifact(String service) -> {
                requireService(layout, service);
                clock.advanceSeconds(1);
                projectFiles.rebuildServiceArtifact(service, clock.now());
            }
            case ExternalActorEvent.AppendServiceLog(String service, String line, boolean stderr) -> {
                requireService(layout, service);
                Path logPath = stateStore.logPath(layout.projectName(), ProcessKey.service(service), stderr);
                stateStore.appendLog(logPath, line);
            }
            case ExternalActorEvent.SeedGitRepositories _ -> {
                gitRepositories.clear();
                for (String serviceName : layout.services().keySet()) {
                    gitRepositories.put(serviceName + "-repo",
                        "pricing".equals(serviceName) ? GitRepoState.UNCLONED : GitRepoState.CLEAN);
                }
            }
            case ExternalActorEvent.AdvanceGitRemote(String repository) ->
                gitRepositories.put(repository, GitRepoState.BEHIND_REMOTE);
            case ExternalActorEvent.DirtyGitRepository(String repository) ->
                gitRepositories.put(repository, GitRepoState.DIRTY);
        }
    }

    @Override
    public HttpProbeResult httpGet(String path) {
        Objects.requireNonNull(path, "path must not be null");
        return httpClient.probeGateway("GET", path);
    }

    @Override
    public HttpProbeResult httpPost(String path) {
        Objects.requireNonNull(path, "path must not be null");
        return httpClient.probeGateway("POST", path);
    }

    @Override
    public String gatewayBaseUrl() {
        return "http://127.0.0.1:" + configSource.layout().gatewayPort();
    }

    @Override
    public void advanceClockSeconds(long seconds) {
        clock.advanceSeconds(seconds);
    }

    @Override
    public void close() {
        os.reset();
        httpClient.reset();
    }

    private static ServiceSpec requireService(EstateLayout layout, String service) {
        ServiceSpec spec = layout.services().get(service);
        if (spec == null) {
            throw new IllegalArgumentException(
                "Unknown service '" + service + "' in estate '" + layout.projectName() + "'"
            );
        }
        return spec;
    }
}
