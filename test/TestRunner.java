import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class TestRunner {

    private TestRunner() {}

    public static void main(String[] args) throws Exception {
        RunOptions options = parseOptions(args);
        if (options == null) {
            System.exit(2);
        }
        Path root = Path.of("").toAbsolutePath().normalize();
        Path srcDir = root.resolve("src");
        Path testDir = root.resolve("test");

        List<Path> sourceFiles = new ArrayList<>();
        collectJavaFiles(srcDir, sourceFiles, null);
        collectJavaFiles(testDir, sourceFiles, testDir.resolve("TestRunner.java"));

        if (sourceFiles.isEmpty()) {
            System.err.println("No Java files found under src/ or test/.");
            System.exit(1);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("No system Java compiler available (JDK required).");
            System.exit(1);
        }

        Path classesDir = Files.createTempDirectory("procomp-test-classes");
        try {
            if (!compileAll(compiler, sourceFiles, classesDir)) {
                System.exit(1);
            }

            List<String> testClassNames = discoverTestClasses(testDir.resolve("procomp"));
            if (testClassNames.isEmpty()) {
                System.err.println("No *Test classes discovered under test/procomp/.");
                System.exit(1);
            }

            Summary summary = runAllTests(classesDir, testClassNames, options);
            printSummary(summary, options);
            if (summary.totalRun() == 0 || summary.failed() > 0) {
                System.exit(1);
            }
        } finally {
            deleteRecursively(classesDir);
        }
    }

    private static RunOptions parseOptions(String[] args) {
        boolean fast = false;
        int onlyLevel = 0;
        String filter = "";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i].trim();
            if ("--fast".equals(arg)) {
                fast = true;
            } else if ("--level".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Option '--level' requires 1, 2, or 3.");
                    return null;
                }
                onlyLevel = parseLevelNumber(args[++i]);
                if (onlyLevel == 0) {
                    return null;
                }
            } else if (arg.startsWith("--level=")) {
                onlyLevel = parseLevelNumber(arg.substring("--level=".length()));
                if (onlyLevel == 0) {
                    return null;
                }
            } else if (arg.startsWith("-")) {
                System.err.println("Unknown option '" + arg + "'. Usage: TestRunner [--fast | --level <1|2|3>] [filter]");
                return null;
            } else if (filter.isEmpty()) {
                filter = arg;
            } else {
                System.err.println("Unexpected argument '" + arg + "'. Usage: TestRunner [--fast | --level <1|2|3>] [filter]");
                return null;
            }
        }
        return new RunOptions(fast, onlyLevel, filter);
    }

    private static int parseLevelNumber(String raw) {
        return switch (raw.trim()) {
            case "1" -> 1;
            case "2" -> 2;
            case "3" -> 3;
            default -> {
                System.err.println("Invalid --level value '" + raw + "': expected 1, 2, or 3.");
                yield 0;
            }
        };
    }

    private static void collectJavaFiles(Path dir, List<Path> out, Path exclude) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> exclude == null || !p.toAbsolutePath().normalize().equals(exclude.toAbsolutePath().normalize()))
                .sorted()
                .forEach(out::add);
        }
    }

    private static boolean compileAll(JavaCompiler compiler, List<Path> sourceFiles, Path classesDir) throws IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromPaths(sourceFiles);
            List<String> options = List.of("-d", classesDir.toString(), "-encoding", "UTF-8");
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);
            boolean success = Boolean.TRUE.equals(task.call());
            if (!success) {
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        System.err.println(d);
                    }
                }
            }
            return success;
        }
    }

    private static List<String> discoverTestClasses(Path procompTestDir) throws IOException {
        if (!Files.isDirectory(procompTestDir)) {
            return List.of();
        }
        Path testRoot = procompTestDir.getParent();
        List<String> classNames = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(procompTestDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith("Test.java"))
                .sorted()
                .forEach(p -> {
                    String rel = testRoot.relativize(p).toString();
                    String className = rel.substring(0, rel.length() - ".java".length())
                        .replace('/', '.')
                        .replace('\\', '.');
                    classNames.add(className);
                });
        }
        return classNames;
    }

    private static Summary runAllTests(Path classesDir, List<String> testClassNames, RunOptions options) throws Exception {
        String lowerFilter = options.filter().toLowerCase(Locale.ROOT);
        Map<String, LevelCounts> levelCounts = new LinkedHashMap<>();
        int totalPassed = 0;
        int totalFailed = 0;

        try (URLClassLoader loader = new URLClassLoader(new URL[]{classesDir.toUri().toURL()}, ClassLoader.getSystemClassLoader())) {
            for (String className : testClassNames) {
                int levelNum = classifyLevelNumber(className);
                if (options.fast() && levelNum == 3) {
                    continue;
                }
                if (options.onlyLevel() != 0 && levelNum != options.onlyLevel()) {
                    continue;
                }
                Class<?> clazz = Class.forName(className, true, loader);
                List<Method> testMethods = findTestMethods(clazz);
                boolean classMatches = lowerFilter.isEmpty() || className.toLowerCase(Locale.ROOT).contains(lowerFilter);

                List<Method> toRun = new ArrayList<>();
                for (Method m : testMethods) {
                    String fullName = (className + "." + m.getName()).toLowerCase(Locale.ROOT);
                    if (classMatches || fullName.contains(lowerFilter)) {
                        toRun.add(m);
                    }
                }
                if (toRun.isEmpty()) {
                    continue;
                }

                String level = classifyLevel(className);
                LevelCounts counts = levelCounts.computeIfAbsent(level, _ -> new LevelCounts());
                int classPassed = 0;
                int classFailed = 0;
                System.out.println("[" + level + "] " + className);

                for (Method m : toRun) {
                    try {
                        m.invoke(null);
                        classPassed++;
                        totalPassed++;
                        counts.passed++;
                        System.out.println("  PASS " + m.getName());
                    } catch (InvocationTargetException e) {
                        classFailed++;
                        totalFailed++;
                        counts.failed++;
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        System.err.println("  FAIL " + m.getName() + ": " + cause.getMessage());
                        cause.printStackTrace(System.err);
                    } catch (Throwable t) {
                        classFailed++;
                        totalFailed++;
                        counts.failed++;
                        System.err.println("  FAIL " + m.getName() + ": " + t.getMessage());
                        t.printStackTrace(System.err);
                    }
                }
                System.out.println("  -> " + classPassed + " passed, " + classFailed + " failed");
            }
        }
        return new Summary(totalPassed, totalFailed, levelCounts);
    }

    private static List<Method> findTestMethods(Class<?> clazz) {
        List<Method> methods = new ArrayList<>();
        for (Method m : clazz.getDeclaredMethods()) {
            int mods = m.getModifiers();
            if (Modifier.isPublic(mods)
                && Modifier.isStatic(mods)
                && m.getReturnType() == void.class
                && m.getParameterCount() == 0
                && m.getName().startsWith("test")) {
                methods.add(m);
            }
        }
        methods.sort(Comparator.comparing(Method::getName));
        return methods;
    }

    private static int classifyLevelNumber(String className) {
        if (className.startsWith("procomp.core.")) {
            return 1;
        }
        if (className.startsWith("procomp.shell.")
            || className.startsWith("procomp.cli.")
            || className.startsWith("procomp.web.")) {
            return 2;
        }
        if (className.startsWith("procomp.platform.") || className.startsWith("procomp.smoke.")) {
            return 3;
        }
        return 0;
    }

    private static String classifyLevel(String className) {
        return switch (classifyLevelNumber(className)) {
            case 1 -> "Level 1 (core)";
            case 2 -> "Level 2 (shell/cli/web)";
            case 3 -> "Level 3 (platform/e2e)";
            default -> "Other";
        };
    }

    private static void printSummary(Summary summary, RunOptions options) {
        String label = options.describe();
        System.out.println();
        System.out.println("=== Test Summary" + (label.isEmpty() ? "" : " (" + label + ")") + " ===");
        for (Map.Entry<String, LevelCounts> entry : summary.byLevel().entrySet()) {
            LevelCounts c = entry.getValue();
            System.out.println("  " + entry.getKey() + ": " + c.passed + " passed, " + c.failed + " failed");
        }
        System.out.println("Total: " + summary.passed() + " passed, " + summary.failed() + " failed");
        if (summary.totalRun() == 0) {
            System.err.println("ERROR: 0 tests matched.");
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static final class LevelCounts {
        int passed;
        int failed;
    }

    private record RunOptions(boolean fast, int onlyLevel, String filter) {
        String describe() {
            List<String> parts = new ArrayList<>();
            if (fast) {
                parts.add("fast: Levels 1-2");
            }
            if (onlyLevel != 0) {
                parts.add("level: " + onlyLevel);
            }
            if (!filter.isEmpty()) {
                parts.add("filter: " + filter);
            }
            return String.join(", ", parts);
        }
    }

    private record Summary(int passed, int failed, Map<String, LevelCounts> byLevel) {
        int totalRun() {
            return passed + failed;
        }
    }
}
