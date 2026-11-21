package procomp;

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Bridges existing zero-dependency {@code test/procomp/** /*Test.java} suites into JUnit 5
 * so Gradle can execute, cache, and report them without modifying the zero-dep test files.
 */
final class JUnit5BridgeTest {

    @TestFactory
    Stream<DynamicContainer> allProcompTests() throws IOException {
        boolean fast = Boolean.parseBoolean(System.getProperty("procomp.fast", "false"));
        int onlyLevel = Integer.parseInt(System.getProperty("procomp.level", "0"));

        Path testRoot = Path.of("test").toAbsolutePath().normalize();
        Path procompTestDir = testRoot.resolve("procomp");
        List<String> classNames = discoverTestClasses(testRoot, procompTestDir);

        List<DynamicContainer> containers = new ArrayList<>();
        for (String className : classNames) {
            int level = classifyLevel(className);
            if (fast && level == 3) {
                continue;
            }
            if (onlyLevel != 0 && level != onlyLevel) {
                continue;
            }
            Class<?> clazz;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Failed to load test class " + className, e);
            }
            List<Method> methods = findTestMethods(clazz);
            if (methods.isEmpty()) {
                continue;
            }
            List<DynamicTest> tests = new ArrayList<>();
            for (Method m : methods) {
                tests.add(DynamicTest.dynamicTest(m.getName(), () -> invokeStatic(m)));
            }
            containers.add(DynamicContainer.dynamicContainer(className, tests));
        }
        return containers.stream();
    }

    private static void invokeStatic(Method m) throws Throwable {
        try {
            m.invoke(null);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private static List<String> discoverTestClasses(Path testRoot, Path procompTestDir) throws IOException {
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

    private static int classifyLevel(String className) {
        if (className.startsWith("procomp.core.")) {
            return 1;
        }
        if (className.startsWith("procomp.shell.") || className.startsWith("procomp.cli.")) {
            return 2;
        }
        if (className.startsWith("procomp.platform.") || className.startsWith("procomp.smoke.")) {
            return 3;
        }
        return 0;
    }
}
