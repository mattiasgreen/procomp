package procomp.fakes;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.ProjectFiles;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FakeProjectFiles implements ProjectFiles {

    private final Set<Path> directories = new LinkedHashSet<>();
    private final Map<Path, Instant> files = new LinkedHashMap<>();

    public FakeProjectFiles withDirectory(Path dir) {
        Path cur = dir.toAbsolutePath().normalize();
        while (cur != null) {
            directories.add(cur);
            cur = cur.getParent();
        }
        return this;
    }

    public FakeProjectFiles removeDirectory(Path dir) {
        directories.remove(dir.toAbsolutePath().normalize());
        return this;
    }

    public FakeProjectFiles withFile(Path file, Instant lastModified) {
        Path norm = file.toAbsolutePath().normalize();
        files.put(norm, lastModified);
        if (norm.getParent() != null) {
            withDirectory(norm.getParent());
        }
        return this;
    }

    @Override
    public boolean directoryExists(Path dir) {
        return directories.contains(dir.toAbsolutePath().normalize());
    }

    @Override
    public Result<Optional<ArtifactInfo>, DomainError> findNewestArtifact(
        Path baseDir,
        String glob,
        Optional<String> excludeGlob
    ) {
        Path normBase = baseDir.toAbsolutePath().normalize();
        if (!directories.contains(normBase)) {
            return Result.ok(Optional.empty());
        }
        Optional<ArtifactInfo> newest = files.entrySet().stream()
            .filter(e -> ProjectFiles.matchesGlob(normBase, e.getKey(), glob, excludeGlob))
            .map(e -> new ArtifactInfo(e.getKey(), e.getValue()))
            .max(Comparator.comparing(ArtifactInfo::lastModified)
                .thenComparing(a -> a.path().toString()));
        return Result.ok(newest);
    }
}
