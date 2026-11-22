package e2e.scenarios.engine;

import java.util.List;

/**
 * Pluggable world seam implemented by {@code InMemoryWorld} and {@code RealTopologyWorld}.
 */
public interface ScenarioWorld extends AutoCloseable {

    WorldKind worldKind();

    void reset(EstateSpec estateSpec);

    void setLag(LagProfile lag);

    LagProfile lag();

    CliResult runProcomp(String... args);

    default CliResult runProcomp(List<String> args) {
        return runProcomp(args.toArray(String[]::new));
    }

    void triggerActor(ExternalActorEvent event);

    HttpProbeResult httpGet(String path);

    HttpProbeResult httpPost(String path);

    String gatewayBaseUrl();

    void advanceClockSeconds(long seconds);

    @Override
    void close();
}
