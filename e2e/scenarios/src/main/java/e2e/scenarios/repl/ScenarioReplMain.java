package e2e.scenarios.repl;

import e2e.scenarios.engine.LagProfile;
import e2e.scenarios.inmemory.InMemoryWorld;

import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * CLI entrypoint for the interactive {@link ScenarioRepl} over {@link InMemoryWorld}.
 *
 * <p>Usage:
 * <pre>
 *   ScenarioReplMain [--scenario &lt;id&gt;] [--step &lt;step&gt;] [--lag none|fast|realistic] [&lt;scenario&gt;[/&lt;step&gt;]]
 * </pre>
 */
public final class ScenarioReplMain {

    private ScenarioReplMain() {}

    public static void main(String[] args) {
        String scenario = null;
        String step = null;
        LagProfile lag = LagProfile.NONE;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--scenario" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("Missing value for --scenario");
                        System.exit(2);
                    }
                    scenario = args[++i];
                }
                case "--step" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("Missing value for --step");
                        System.exit(2);
                    }
                    step = args[++i];
                }
                case "--lag" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("Missing value for --lag");
                        System.exit(2);
                    }
                    String rawLag = args[++i];
                    Optional<LagProfile> parsed = ScenarioRepl.parseLagProfile(rawLag);
                    if (parsed.isEmpty()) {
                        System.err.println("Invalid --lag value '" + rawLag + "'. Expected: none | fast | realistic");
                        System.exit(2);
                    }
                    lag = parsed.get();
                }
                case "--help", "-h" -> {
                    System.out.println(
                        "Usage: ScenarioReplMain [--scenario <id>] [--step <step>] [--lag none|fast|realistic] [<scenario>[/<step>]]"
                    );
                    return;
                }
                default -> {
                    if (arg.startsWith("-")) {
                        System.err.println("Unknown option: " + arg);
                        System.exit(2);
                    }
                    if (scenario == null) {
                        scenario = arg;
                    } else if (step == null) {
                        step = arg;
                    } else {
                        System.err.println("Unexpected argument: " + arg);
                        System.exit(2);
                    }
                }
            }
        }

        String initialTarget;
        if (scenario != null && !scenario.isBlank()) {
            if (step != null && !step.isBlank() && !scenario.contains("/")) {
                initialTarget = scenario.trim() + "/" + step.trim();
            } else {
                initialTarget = scenario.trim();
            }
        } else if (step != null && !step.isBlank()) {
            initialTarget = "/" + step.trim();
        } else {
            initialTarget = "";
        }

        ScenarioRepl repl = new ScenarioRepl(
            new InputStreamReader(System.in, StandardCharsets.UTF_8),
            new PrintWriter(System.out, true, StandardCharsets.UTF_8),
            InMemoryWorld::new
        );
        if (initialTarget.isEmpty()) {
            repl.run(lag);
        } else {
            repl.run(initialTarget, lag);
        }
    }
}
