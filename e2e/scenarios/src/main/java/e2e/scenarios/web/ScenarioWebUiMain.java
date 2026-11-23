package e2e.scenarios.web;

import e2e.scenarios.catalog.ScenarioCatalog;
import e2e.scenarios.engine.LagProfile;
import e2e.scenarios.engine.Scenario;
import e2e.scenarios.engine.ScenarioRunner;
import e2e.scenarios.inmemory.InMemoryWorld;
import procomp.web.WebUi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interactive launcher serving the real Vanilla ES6 Web UI ({@code procomp ui}) directly
 * over an {@link InMemoryWorld} fast-forwarded to any Scenario/Step with optional simulated lag.
 */
public final class ScenarioWebUiMain {

    private ScenarioWebUiMain() {}

    public static void main(String[] args) throws Exception {
        Options opts = parseArgs(args);
        Scenario scenario = ScenarioCatalog.byId(opts.scenarioId())
            .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + opts.scenarioId()));
        String stepId = opts.stepId() != null
            ? opts.stepId()
            : scenario.steps().get(scenario.steps().size() - 1).id();

        InMemoryWorld world = new InMemoryWorld();
        world.setLag(opts.lag());
        new ScenarioRunner().jumpToStep(scenario, world, stepId);

        ScenarioWebUiHost host = ScenarioWebUiHost.start(world, opts.webDir(), opts.port());
        AtomicBoolean closed = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                host.close();
                world.close();
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(cleanup, "scenario-webui-shutdown"));

        try {
            System.out.println("============================================================");
            System.out.println("procomp Web UI Sandbox (InMemoryWorld)");
            System.out.println("  Scenario: " + scenario.id() + " @ " + stepId);
            System.out.println("  Lag:      " + opts.lag());
            System.out.println("  URL:      " + host.baseUrl());
            System.out.println("============================================================");
            System.out.println("Press Enter (or Ctrl+C) to stop Web UI and exit.");
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            reader.readLine();
        } finally {
            cleanup.run();
        }
    }

    static Options parseArgs(String[] args) {
        String scenarioId = "hybrid-routing";
        String stepId = null;
        LagProfile lag = LagProfile.NONE;
        int port = WebUi.DEFAULT_PORT;
        Path webDir = Path.of("web");

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--scenario" -> scenarioId = nextArg(args, ++i, "--scenario");
                case "--step" -> stepId = nextArg(args, ++i, "--step");
                case "--lag" -> lag = LagProfile.valueOf(nextArg(args, ++i, "--lag").toUpperCase(Locale.ROOT));
                case "--port" -> port = Integer.parseInt(nextArg(args, ++i, "--port"));
                case "--web-dir" -> webDir = Path.of(nextArg(args, ++i, "--web-dir"));
                default -> {
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    int slash = arg.indexOf('/');
                    if (slash > 0) {
                        scenarioId = arg.substring(0, slash);
                        stepId = arg.substring(slash + 1);
                    } else {
                        scenarioId = arg;
                    }
                }
            }
        }
        return new Options(scenarioId, stepId, lag, port, webDir);
    }

    private static String nextArg(String[] args, int idx, String flag) {
        if (idx >= args.length || args[idx].isBlank()) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[idx];
    }

    record Options(String scenarioId, String stepId, LagProfile lag, int port, Path webDir) {}
}
