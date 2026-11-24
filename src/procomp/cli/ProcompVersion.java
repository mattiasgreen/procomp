package procomp.cli;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the installed {@code procomp} version by walking up from this class's
 * {@link CodeSource} to find a sibling {@code VERSION} file, falling back to {@code "dev"}.
 */
public final class ProcompVersion {

    public static final String DEV_VERSION = "dev";
    private static final String VERSION_FILE = "VERSION";

    private ProcompVersion() {}

    public static String resolve() {
        return codeSourcePath()
            .map(ProcompVersion::resolveFrom)
            .orElse(DEV_VERSION);
    }

    public static String resolveFrom(Path searchRoot) {
        Objects.requireNonNull(searchRoot, "searchRoot must not be null");
        return findVersionWalkingUp(searchRoot).orElse(DEV_VERSION);
    }

    private static Optional<Path> codeSourcePath() {
        CodeSource codeSource = ProcompVersion.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> findVersionWalkingUp(Path start) {
        Path cursor = start.toAbsolutePath().normalize();
        if (Files.isRegularFile(cursor)) {
            cursor = cursor.getParent();
        }
        while (cursor != null) {
            Path candidate = cursor.resolve(VERSION_FILE);
            if (Files.isRegularFile(candidate)) {
                Optional<String> content = readTrimmedFirstLine(candidate);
                if (content.isPresent()) {
                    return content;
                }
            }
            cursor = cursor.getParent();
        }
        return Optional.empty();
    }

    private static Optional<String> readTrimmedFirstLine(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8)
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
