package e2e.scenarios.demo;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * CLI entrypoint for deterministic Markdown demo generation and freshness verification.
 *
 * <p>Usage:
 * <pre>
 *   DemoCaptureMain --generate &lt;outputDir&gt;
 *   DemoCaptureMain --check &lt;outputDir&gt;
 * </pre>
 */
public final class DemoCaptureMain {

    private DemoCaptureMain() {}

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        int exitCode = run(args, out, err);
        out.flush();
        err.flush();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args, PrintWriter out, PrintWriter err) {
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(out, "out must not be null");
        Objects.requireNonNull(err, "err must not be null");

        if (args.length != 2) {
            err.println("Usage: DemoCaptureMain --generate <outputDir> | --check <outputDir>");
            return 2;
        }

        String mode = args[0];
        Path outputDir = Path.of(args[1]);
        DemoCapture capture = new DemoCapture();

        return switch (mode) {
            case "--generate" -> {
                List<Path> written = capture.generateAll(outputDir);
                for (Path file : written) {
                    out.println("Generated " + file);
                }
                yield 0;
            }
            case "--check" -> {
                DemoCapture.CheckResult result = capture.checkAll(outputDir);
                if (result.isFresh()) {
                    out.println(result.formatSummary());
                    yield 0;
                } else {
                    err.println(result.formatSummary());
                    yield 1;
                }
            }
            default -> {
                err.println("Unknown mode '" + mode + "'. Expected --generate or --check.");
                yield 2;
            }
        };
    }
}
