package procomp.core;

import procomp.Assert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Mechanical architecture and domain-vocabulary guardrails enforced on every test run.
 */
public final class ArchitectureRulesTest {

    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern CAMEL_WORD_PATTERN = Pattern.compile("[A-Z][a-z0-9]*");

    private static final List<String> FORBIDDEN_CORE_IMPORT_PREFIXES = List.of(
        "procomp.shell.",
        "procomp.platform.",
        "procomp.cli.",
        "java.io.",
        "java.net.",
        "java.nio.file.Files",
        "java.lang.ProcessBuilder",
        "java.lang.ProcessHandle"
    );

    private static final Set<String> BANNED_GLOSSARY_TYPE_WORDS = Set.of(
        "Workspace",
        "Probe",
        "Probing",
        "External",
        "AppsFile",
        "StatusText"
    );

    public static void testCoreLayerHasZeroForbiddenImports() throws IOException {
        Path coreDir = Path.of("src/procomp/core");
        List<String> violations = new ArrayList<>();
        for (Path file : javaFilesUnder(coreDir)) {
            for (String imp : importsOf(file)) {
                for (String forbidden : FORBIDDEN_CORE_IMPORT_PREFIXES) {
                    if (imp.startsWith(forbidden)) {
                        violations.add(file + " imports forbidden " + imp);
                    }
                }
            }
        }
        Assert.assertTrue(violations.isEmpty(), "Core purity violations:\n" + String.join("\n", violations));
    }

    public static void testCoreDomainsRemainDecoupled() throws IOException {
        List<String> violations = new ArrayList<>();
        checkForbiddenImports(Path.of("src/procomp/core/routing"), List.of("procomp.core.process.", "procomp.core.gateway."), violations);
        checkForbiddenImports(Path.of("src/procomp/core/process"), List.of("procomp.core.routing.", "procomp.core.gateway."), violations);
        checkForbiddenImports(Path.of("src/procomp/core/gateway"), List.of("procomp.core.process."), violations);
        Assert.assertTrue(violations.isEmpty(), "Core domain decoupling violations:\n" + String.join("\n", violations));
    }

    public static void testPublicTypeNamesAvoidBannedGlossarySynonyms() throws IOException {
        Path srcDir = Path.of("src/procomp");
        List<String> violations = new ArrayList<>();
        for (Path file : javaFilesUnder(srcDir)) {
            String fileName = file.getFileName().toString();
            String typeName = fileName.substring(0, fileName.length() - ".java".length());
            Matcher m = CAMEL_WORD_PATTERN.matcher(typeName);
            while (m.find()) {
                String word = m.group();
                if (BANNED_GLOSSARY_TYPE_WORDS.contains(word)) {
                    violations.add(file + " uses avoided GLOSSARY.md term '" + word + "'");
                }
            }
        }
        Assert.assertTrue(violations.isEmpty(), "Glossary vocabulary violations:\n" + String.join("\n", violations));
    }

    private static void checkForbiddenImports(Path dir, List<String> forbiddenPrefixes, List<String> violations) throws IOException {
        for (Path file : javaFilesUnder(dir)) {
            for (String imp : importsOf(file)) {
                for (String prefix : forbiddenPrefixes) {
                    if (imp.startsWith(prefix)) {
                        violations.add(file + " imports forbidden " + imp);
                    }
                }
            }
        }
    }

    private static List<Path> javaFilesUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
        }
    }

    private static List<String> importsOf(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        List<String> imports = new ArrayList<>();
        Matcher m = IMPORT_PATTERN.matcher(content);
        while (m.find()) {
            imports.add(m.group(1));
        }
        return imports;
    }
}
