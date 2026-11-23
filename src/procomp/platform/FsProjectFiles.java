package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.ProjectFiles;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/**
 * Real filesystem adapter for {@link ProjectFiles}.
 */
public final class FsProjectFiles implements ProjectFiles {

    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "node_modules");

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
        PathMatcher includeMatcher;
        Optional<PathMatcher> excludeMatcher;
        boolean excludeHasSlash = false;
        try {
            includeMatcher = compileGlob(glob);
            if (excludeGlob.isPresent() && !excludeGlob.get().isBlank()) {
                String normEx = normalizeGlob(excludeGlob.get());
                excludeHasSlash = normEx.contains("/");
                excludeMatcher = Optional.of(FileSystems.getDefault().getPathMatcher("glob:" + normEx));
            } else {
                excludeMatcher = Optional.empty();
            }
        } catch (PatternSyntaxException e) {
            return Result.err(DomainError.of(
                "INVALID_GLOB",
                "Invalid artifact glob pattern: " + e.getMessage(),
                Map.of("baseDir", normBase.toString(), "glob", glob)
            ));
        }

        final boolean matchExcludeAgainstRelative = excludeHasSlash;
        Comparator<ArtifactInfo> comparator = Comparator.comparing(ArtifactInfo::lastModified)
            .thenComparing(a -> a.path().toString());
        ArtifactInfo[] newestHolder = new ArtifactInfo[1];
        try {
            Files.walkFileTree(normBase, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(normBase)) {
                        Path fileName = dir.getFileName();
                        if (fileName != null && SKIPPED_DIRS.contains(fileName.toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path normFile = file.toAbsolutePath().normalize();
                    Path rel = normBase.relativize(normFile);
                    if (!includeMatcher.matches(rel)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (excludeMatcher.isPresent()) {
                        Path target = matchExcludeAgainstRelative ? rel : normFile.getFileName();
                        if (target != null && excludeMatcher.get().matches(target)) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    Instant mtime = attrs.lastModifiedTime().toInstant();
                    ArtifactInfo candidate = new ArtifactInfo(normFile, mtime);
                    if (newestHolder[0] == null || comparator.compare(candidate, newestHolder[0]) > 0) {
                        newestHolder[0] = candidate;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Skip unreadable files/subdirectories cleanly rather than aborting scan.
                    return FileVisitResult.CONTINUE;
                }
            });
            return Result.ok(Optional.ofNullable(newestHolder[0]));
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "PROJECT_IO_ERROR",
                "Failed scanning artifacts under '" + normBase + "': " + e.getMessage(),
                Map.of("baseDir", normBase.toString(), "glob", glob)
            ));
        }
    }

    private static PathMatcher compileGlob(String glob) {
        return FileSystems.getDefault().getPathMatcher("glob:" + normalizeGlob(glob));
    }

    private static String normalizeGlob(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        return s;
    }
}
