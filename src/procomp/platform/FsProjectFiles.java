package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.ProjectFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Real filesystem adapter for {@link ProjectFiles}.
 */
public final class FsProjectFiles implements ProjectFiles {

    @Override
    public boolean directoryExists(Path dir) {
        return dir != null && Files.isDirectory(dir);
    }

    @Override
    public Result<Optional<ArtifactInfo>, DomainError> findNewestArtifact(
        Path baseDir,
        String glob,
        Optional<String> excludeGlob
    ) {
        if (baseDir == null || !Files.isDirectory(baseDir)) {
            return Result.ok(Optional.empty());
        }
        Path normBase = baseDir.toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(normBase)) {
            Optional<ArtifactInfo> newest = walk
                .filter(Files::isRegularFile)
                .filter(p -> ProjectFiles.matchesGlob(normBase, p, glob, excludeGlob))
                .map(p -> toArtifactInfo(p))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .max(Comparator.comparing(ArtifactInfo::lastModified)
                    .thenComparing(a -> a.path().toString()));
            return Result.ok(newest);
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "PROJECT_IO_ERROR",
                "Failed scanning artifacts under '" + normBase + "': " + e.getMessage(),
                Map.of("baseDir", normBase.toString(), "glob", glob)
            ));
        }
    }

    private static Optional<ArtifactInfo> toArtifactInfo(Path path) {
        try {
            Instant mtime = Files.getLastModifiedTime(path).toInstant();
            return Optional.of(new ArtifactInfo(path.toAbsolutePath().normalize(), mtime));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
