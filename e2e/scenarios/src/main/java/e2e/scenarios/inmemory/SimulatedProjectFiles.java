package e2e.scenarios.inmemory;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.ProjectFiles;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory implementation of {@link ProjectFiles} supporting directory checks,
 * artifact glob matching, and timestamp updates for {@code Stale} detection.
 */
public final class SimulatedProjectFiles implements ProjectFiles {

    private final Set<Path> directories = new HashSet<>();
    private final Map<Path, Instant> artifacts = new LinkedHashMap<>();
    private final Map<String, Path> primaryArtifactByService = new LinkedHashMap<>();

    public void clear() {
        directories.clear();
        artifacts.clear();
        primaryArtifactByService.clear();
    }

    public void addDirectory(Path dir) {
        directories.add(normalize(dir));
    }

    public void registerServiceArtifact(String serviceName, Path artifactPath, Instant lastModified) {
        Path norm = normalize(artifactPath);
        artifacts.put(norm, Objects.requireNonNull(lastModified, "lastModified must not be null"));
        primaryArtifactByService.put(serviceName, norm);
    }

    public void addArtifactFile(Path artifactPath, Instant lastModified) {
        artifacts.put(normalize(artifactPath), Objects.requireNonNull(lastModified, "lastModified must not be null"));
    }

    public void rebuildServiceArtifact(String serviceName, Instant newTimestamp) {
        Path artifactPath = primaryArtifactByService.get(serviceName);
        if (artifactPath == null) {
            throw new IllegalArgumentException("No registered artifact for service '" + serviceName + "'");
        }
        artifacts.put(artifactPath, Objects.requireNonNull(newTimestamp, "newTimestamp must not be null"));
    }

    @Override
    public boolean directoryExists(Path dir) {
        return directories.contains(normalize(dir));
    }

    @Override
    public Result<Optional<ArtifactInfo>, DomainError> findNewestArtifact(
        Path baseDir,
        String glob,
        Optional<String> excludeGlob
    ) {
        Path normBase = normalize(baseDir);
        Optional<ArtifactInfo> newest = artifacts.entrySet().stream()
            .filter(entry -> ProjectFiles.matchesGlob(normBase, entry.getKey(), glob, excludeGlob))
            .map(entry -> new ArtifactInfo(entry.getKey(), entry.getValue()))
            .max(Comparator.comparing(ArtifactInfo::lastModified));
        return Result.ok(newest);
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path must not be null").toAbsolutePath().normalize();
    }
}
