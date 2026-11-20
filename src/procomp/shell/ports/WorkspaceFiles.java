package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Narrow read-only port for lazy Repository/workingDir checks and {{artifact}} glob resolution.
 */
public interface WorkspaceFiles {

    boolean directoryExists(Path dir);

    Result<Optional<ArtifactInfo>, DomainError> findNewestArtifact(
        Path baseDir,
        String glob,
        Optional<String> excludeGlob
    );

    record ArtifactInfo(Path path, Instant lastModified) {
        public ArtifactInfo {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(lastModified, "lastModified must not be null");
        }
    }

    /**
     * Pure shared glob + exclude matching relative to {@code baseDir}.
     */
    static boolean matchesGlob(Path baseDir, Path candidate, String glob, Optional<String> excludeGlob) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(glob, "glob must not be null");
        Objects.requireNonNull(excludeGlob, "excludeGlob must not be null");

        Path normBase = baseDir.toAbsolutePath().normalize();
        Path normCandidate = candidate.toAbsolutePath().normalize();
        if (!normCandidate.startsWith(normBase) || normCandidate.equals(normBase)) {
            return false;
        }
        Path relative = normBase.relativize(normCandidate);
        PathMatcher includeMatcher = FileSystems.getDefault().getPathMatcher("glob:" + normalizeGlob(glob));
        if (!includeMatcher.matches(relative)) {
            return false;
        }
        if (excludeGlob.isPresent() && !excludeGlob.get().isBlank()) {
            String ex = normalizeGlob(excludeGlob.get());
            PathMatcher excludeMatcher = FileSystems.getDefault().getPathMatcher("glob:" + ex);
            Path target = ex.contains("/") ? relative : normCandidate.getFileName();
            if (target != null && excludeMatcher.matches(target)) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeGlob(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        return s;
    }
}
