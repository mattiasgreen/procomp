package e2e.scenarios.repl;

import e2e.scenarios.catalog.ScenarioCatalog;
import e2e.scenarios.engine.CliResult;
import e2e.scenarios.engine.ExternalActorEvent;
import e2e.scenarios.engine.LagProfile;
import e2e.scenarios.engine.Scenario;
import e2e.scenarios.engine.ScenarioRunner;
import e2e.scenarios.engine.ScenarioStep;
import e2e.scenarios.engine.ScenarioWorld;
import e2e.scenarios.inmemory.InMemoryWorld;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Interactive REPL driving real {@code procomp} CLI commands and Scenario navigation
 * meta-commands against a live {@link ScenarioWorld} (typically {@link InMemoryWorld}).
 */
public final class ScenarioRepl {

    private final BufferedReader in;
    private final PrintWriter out;
    private final Supplier<? extends ScenarioWorld> worldFactory;
    private final ScenarioWorld providedWorld;
    private final ScenarioRunner runner = new ScenarioRunner();

    private Scenario currentScenario;
    private int currentStepIndex;

    public ScenarioRepl(Reader in, PrintWriter out) {
        this(in, out, InMemoryWorld::new, null);
    }

    public ScenarioRepl(Reader in, PrintWriter out, Supplier<? extends ScenarioWorld> worldFactory) {
        this(in, out, Objects.requireNonNull(worldFactory, "worldFactory must not be null"), null);
    }

    public ScenarioRepl(Reader in, PrintWriter out, ScenarioWorld world) {
        this(in, out, null, Objects.requireNonNull(world, "world must not be null"));
    }

    private ScenarioRepl(
        Reader in,
        PrintWriter out,
        Supplier<? extends ScenarioWorld> worldFactory,
        ScenarioWorld providedWorld
    ) {
        Objects.requireNonNull(in, "in must not be null");
        Objects.requireNonNull(out, "out must not be null");
        this.in = (in instanceof BufferedReader br) ? br : new BufferedReader(in);
        this.out = out;
        this.worldFactory = worldFactory;
        this.providedWorld = providedWorld;
        this.currentScenario = ScenarioCatalog.hybridRouting();
        this.currentStepIndex = 0;
    }

    public void run() {
        run(LagProfile.NONE);
    }

    public void run(LagProfile initialLag) {
        Objects.requireNonNull(initialLag, "initialLag must not be null");
        executeWithWorld(world -> runLoop(world, initialLag, () -> initDefaultScenario(world)));
    }

    public void run(String initialTarget, LagProfile initialLag) {
        Objects.requireNonNull(initialTarget, "initialTarget must not be null");
        Objects.requireNonNull(initialLag, "initialLag must not be null");
        String trimmedTarget = initialTarget.trim();
        executeWithWorld(world -> runLoop(world, initialLag, () -> {
            if (trimmedTarget.isEmpty()) {
                initDefaultScenario(world);
            } else {
                handleJump(world, trimmedTarget);
            }
        }));
    }

    private void executeWithWorld(java.util.function.Consumer<ScenarioWorld> action) {
        if (providedWorld != null) {
            action.accept(providedWorld);
        } else {
            try (ScenarioWorld world = worldFactory.get()) {
                action.accept(world);
            }
        }
    }

    private void initDefaultScenario(ScenarioWorld world) {
        Scenario initial = ScenarioCatalog.hybridRouting();
        String firstStep = initial.steps().get(0).id();
        runner.jumpToStep(initial, world, firstStep);
        this.currentScenario = initial;
        this.currentStepIndex = 0;
        out.println("Ready at " + currentScenario.id() + "/" + firstStep
            + " (lag=" + world.lag().name().toLowerCase(Locale.ROOT) + ")");
    }

    private void runLoop(ScenarioWorld world, LagProfile initialLag, Runnable initializer) {
        world.setLag(initialLag);
        out.println("procomp Scenario REPL (type :help for meta-commands, :quit to exit)");
        initializer.run();

        try {
            while (true) {
                printPrompt(world);
                String line = in.readLine();
                if (line == null) {
                    break;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith(":")) {
                    if (!handleMetaCommand(world, trimmed)) {
                        break;
                    }
                } else {
                    handleCliCommand(world, trimmed);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading REPL input", e);
        } finally {
            out.flush();
        }
    }

    private void printPrompt(ScenarioWorld world) {
        String stepId = currentScenario.steps().get(currentStepIndex).id();
        out.print("procomp[" + currentScenario.id() + "/" + stepId
            + " lag=" + world.lag().name().toLowerCase(Locale.ROOT) + "]> ");
        out.flush();
    }

    private void handleCliCommand(ScenarioWorld world, String line) {
        List<String> args;
        try {
            args = splitArgs(line);
        } catch (IllegalArgumentException e) {
            out.println("Parse error: " + e.getMessage());
            return;
        }
        if (args.isEmpty()) {
            return;
        }
        if ("procomp".equals(args.get(0))) {
            args = args.subList(1, args.size());
        }
        if (args.isEmpty()) {
            return;
        }
        CliResult result = world.runProcomp(args);
        printCliResult(result);
    }

    private void printCliResult(CliResult result) {
        if (!result.stdout().isEmpty()) {
            out.print(result.stdout());
            if (!result.stdout().endsWith("\n")) {
                out.println();
            }
        }
        if (!result.stderr().isEmpty()) {
            out.print(result.stderr());
            if (!result.stderr().endsWith("\n")) {
                out.println();
            }
        }
        if (result.exitCode() != 0) {
            out.println("[exit " + result.exitCode() + "]");
        }
        out.flush();
    }

    /**
     * @return {@code true} to keep running, {@code false} to exit the REPL.
     */
    private boolean handleMetaCommand(ScenarioWorld world, String trimmed) {
        String withoutColon = trimmed.substring(1).trim();
        if (withoutColon.isEmpty()) {
            out.println("Unknown meta-command. Type :help for available commands.");
            return true;
        }

        String[] parts = withoutColon.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "quit", "exit", "q" -> {
                out.println("Bye.");
                return false;
            }
            case "help", "h" -> printHelp();
            case "scenarios" -> printScenarios();
            case "steps" -> printSteps();
            case "next" -> handleNext(world);
            case "jump" -> {
                if (rest.isEmpty()) {
                    out.println("Usage: :jump <scenario>[/<step>]");
                } else {
                    handleJump(world, rest);
                }
            }
            case "lag" -> handleLag(world, rest);
            case "actor" -> handleActor(world, rest);
            case "reset" -> handleReset(world);
            default -> out.println("Unknown meta-command ':" + cmd + "'. Type :help for available commands.");
        }
        out.flush();
        return true;
    }

    private void printScenarios() {
        out.println("Scenarios:");
        for (Scenario scenario : ScenarioCatalog.all()) {
            String marker = scenario.id().equals(currentScenario.id()) ? "*" : " ";
            String mode = scenario.dualWorld() ? "dual-world" : "in-memory";
            out.printf(" %s %-25s [%s, %d steps] %s%n",
                marker, scenario.id(), mode, scenario.steps().size(), scenario.title());
        }
    }

    private void printSteps() {
        out.println("Steps for " + currentScenario.id() + " (" + currentScenario.title() + "):");
        List<ScenarioStep> steps = currentScenario.steps();
        for (int i = 0; i < steps.size(); i++) {
            ScenarioStep step = steps.get(i);
            String marker = (i == currentStepIndex) ? "*" : " ";
            String showcaseTag = step.showcase().isPresent() ? " [showcase]" : "";
            out.printf(" %s %d. %-22s %s%s%n", marker, i + 1, step.id(), step.label(), showcaseTag);
        }
    }

    private void handleNext(ScenarioWorld world) {
        int nextIndex = currentStepIndex + 1;
        if (nextIndex >= currentScenario.steps().size()) {
            out.println("Already at final step (" + currentScenario.steps().get(currentStepIndex).id()
                + ") of scenario '" + currentScenario.id() + "'.");
            return;
        }
        ScenarioStep step = currentScenario.steps().get(nextIndex);
        out.println("==> Step " + (nextIndex + 1) + "/" + currentScenario.steps().size()
            + ": " + step.id() + " — " + step.label());
        try {
            ScenarioRunner.StepExecutionResult result = runner.advanceStep(currentScenario, world, nextIndex);
            this.currentStepIndex = nextIndex;
            for (CliResult cli : result.cliResults()) {
                printCliResult(cli);
            }
        } catch (AssertionError | RuntimeException e) {
            out.println("Step failed: " + e.getMessage());
        }
    }

    private void handleJump(ScenarioWorld world, String target) {
        Scenario targetScenario;
        String targetStepId;

        int slash = target.indexOf('/');
        if (slash >= 0) {
            String scenarioPart = target.substring(0, slash).trim();
            String stepPart = target.substring(slash + 1).trim();
            if (scenarioPart.isEmpty()) {
                targetScenario = currentScenario;
            } else {
                Optional<Scenario> found = ScenarioCatalog.byId(scenarioPart);
                if (found.isEmpty()) {
                    out.println("Unknown scenario '" + scenarioPart + "'. Type :scenarios to list available scenarios.");
                    return;
                }
                targetScenario = found.get();
            }
            targetStepId = stepPart.isEmpty() ? targetScenario.steps().get(0).id() : stepPart;
        } else {
            Optional<Scenario> foundScenario = ScenarioCatalog.byId(target);
            if (foundScenario.isPresent()) {
                targetScenario = foundScenario.get();
                targetStepId = targetScenario.steps().get(0).id();
            } else if (currentScenario.step(target).isPresent()) {
                targetScenario = currentScenario;
                targetStepId = target;
            } else {
                out.println("Unknown scenario or step '" + target + "'. Type :scenarios or :steps.");
                return;
            }
        }

        try {
            ScenarioRunner.ScenarioRunResult result = runner.jumpToStep(targetScenario, world, targetStepId);
            this.currentScenario = targetScenario;
            this.currentStepIndex = result.lastStepIndex();
            ScenarioStep landed = targetScenario.steps().get(currentStepIndex);
            out.println("Jumped to " + currentScenario.id() + "/" + landed.id()
                + " (" + landed.label() + ", lag=" + world.lag().name().toLowerCase(Locale.ROOT) + ")");
        } catch (AssertionError | RuntimeException e) {
            out.println("Jump failed: " + e.getMessage());
        }
    }

    private void handleLag(ScenarioWorld world, String arg) {
        if (arg.isEmpty()) {
            out.println("Current lag: " + world.lag().name() + " (" + world.lag().delayMillis() + "ms)");
            return;
        }
        Optional<LagProfile> parsed = parseLagProfile(arg);
        if (parsed.isEmpty()) {
            out.println("Invalid lag profile '" + arg + "'. Expected: none | fast | realistic");
            return;
        }
        world.setLag(parsed.get());
        out.println("Lag set to " + parsed.get().name() + " (" + parsed.get().delayMillis() + "ms)");
    }

    private void handleActor(ScenarioWorld world, String arg) {
        if (arg.isEmpty()) {
            out.println("Usage: :actor <event> (e.g. unmanaged-pricing, conflict-orders, unmanaged-gateway, "
                + "kill-orders, rebuild-pricing, bootstrap)");
            return;
        }
        Optional<ExternalActorEvent> event = parseActorEvent(arg);
        if (event.isEmpty()) {
            out.println("Unknown actor event '" + arg + "'. Try: unmanaged-<svc>, conflict-<svc>, "
                + "unmanaged-gateway, kill-<svc>, rebuild-<svc>, bootstrap");
            return;
        }
        try {
            world.triggerActor(event.get());
            out.println("Triggered actor: " + event.get());
        } catch (IllegalArgumentException e) {
            out.println("Actor error: " + e.getMessage());
        }
    }

    private void handleReset(ScenarioWorld world) {
        String firstStep = currentScenario.steps().get(0).id();
        runner.jumpToStep(currentScenario, world, firstStep);
        this.currentStepIndex = 0;
        out.println("Reset " + currentScenario.id() + " to " + firstStep
            + " (lag=" + world.lag().name().toLowerCase(Locale.ROOT) + ")");
    }

    private void printHelp() {
        out.println("""
            procomp Scenario REPL commands:
              <procomp args...>             Run any real procomp CLI command (e.g. status, route orders local, start)
              :scenarios                    List all Scenarios in ScenarioCatalog
              :steps                        List Steps in the current Scenario
              :next                         Execute the next Step in the current Scenario (with active lag)
              :jump <scenario>[/<step>]     Reset & fast-forward replay to Scenario/Step (lag disabled during replay)
              :lag none|fast|realistic      Set simulated lag profile (or show current lag)
              :actor <event>                Trigger external world event:
                                              unmanaged-<service>   (e.g. unmanaged-pricing)
                                              conflict-<service>    (e.g. conflict-orders)
                                              unmanaged-gateway
                                              kill-<service>        (e.g. kill-orders)
                                              rebuild-<service>     (e.g. rebuild-pricing)
                                              bootstrap
              :reset                        Reset current Scenario to step 1 (preserving lag)
              :help                         Show this help message
              :quit | :exit                 Exit the REPL""");
    }

    public static Optional<LagProfile> parseLagProfile(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> Optional.of(LagProfile.NONE);
            case "fast" -> Optional.of(LagProfile.FAST);
            case "realistic" -> Optional.of(LagProfile.REALISTIC);
            default -> Optional.empty();
        };
    }

    static Optional<ExternalActorEvent> parseActorEvent(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if ("bootstrap".equals(lower) || "bootstrap-clean".equals(lower)) {
            return Optional.of(new ExternalActorEvent.BootstrapClean());
        }
        if ("unmanaged-gateway".equals(lower)) {
            return Optional.of(new ExternalActorEvent.SpawnUnmanagedGateway());
        }
        if ("seed-git".equals(lower)) {
            return Optional.of(new ExternalActorEvent.SeedGitRepositories());
        }

        // Dash-separated shorthand: unmanaged-pricing, conflict-orders, kill-orders, rebuild-pricing
        if (lower.startsWith("unmanaged-") && trimmed.length() > "unmanaged-".length()) {
            return Optional.of(new ExternalActorEvent.SpawnUnmanagedService(
                trimmed.substring("unmanaged-".length()), true));
        }
        if (lower.startsWith("conflict-") && trimmed.length() > "conflict-".length()) {
            return Optional.of(new ExternalActorEvent.SpawnConflict(
                trimmed.substring("conflict-".length())));
        }
        if (lower.startsWith("kill-") && trimmed.length() > "kill-".length()) {
            return Optional.of(new ExternalActorEvent.KillManagedService(
                trimmed.substring("kill-".length())));
        }
        if (lower.startsWith("rebuild-") && trimmed.length() > "rebuild-".length()) {
            return Optional.of(new ExternalActorEvent.RebuildArtifact(
                trimmed.substring("rebuild-".length())));
        }

        // Space-separated forms: "unmanaged pricing", "conflict orders", "log orders hello", etc.
        String[] tokens = trimmed.split("\\s+", 3);
        if (tokens.length >= 2) {
            String kind = tokens[0].toLowerCase(Locale.ROOT);
            String target = tokens[1];
            return switch (kind) {
                case "unmanaged" -> Optional.of(new ExternalActorEvent.SpawnUnmanagedService(target, true));
                case "conflict" -> Optional.of(new ExternalActorEvent.SpawnConflict(target));
                case "kill" -> Optional.of(new ExternalActorEvent.KillManagedService(target));
                case "rebuild" -> Optional.of(new ExternalActorEvent.RebuildArtifact(target));
                case "log" -> tokens.length == 3
                    ? Optional.of(new ExternalActorEvent.AppendServiceLog(target, tokens[2], false))
                    : Optional.empty();
                case "errlog" -> tokens.length == 3
                    ? Optional.of(new ExternalActorEvent.AppendServiceLog(target, tokens[2], true))
                    : Optional.empty();
                default -> Optional.empty();
            };
        }

        return Optional.empty();
    }

    static List<String> splitArgs(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean hasToken = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    current.append(c);
                }
            } else if (inDouble) {
                if (c == '\\' && i + 1 < line.length()) {
                    char next = line.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                    } else {
                        current.append(c);
                    }
                } else if (c == '"') {
                    inDouble = false;
                } else {
                    current.append(c);
                }
            } else {
                if (Character.isWhitespace(c)) {
                    if (hasToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                } else if (c == '\'') {
                    inSingle = true;
                    hasToken = true;
                } else if (c == '"') {
                    inDouble = true;
                    hasToken = true;
                } else {
                    current.append(c);
                    hasToken = true;
                }
            }
        }
        if (inSingle || inDouble) {
            throw new IllegalArgumentException("Unclosed quote in command: " + line);
        }
        if (hasToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
