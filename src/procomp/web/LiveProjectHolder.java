package procomp.web;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ProjectLoader;
import procomp.shell.ProjectLoader.LoadedProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe single-{@code Project} cache backed by {@link ProjectLoader#loadWithPath(Optional)}.
 * Automatically reloads {@code procomp.json} when modified on disk and surfaces invalid config as
 * a {@link DomainError} without crashing, recovering cleanly once fixed.
 */
public final class LiveProjectHolder {

    private final ProjectLoader projectLoader;
    private final Optional<Path> configOverride;
    private final ReentrantLock lock = new ReentrantLock();

    private Optional<CachedProject> cached = Optional.empty();

    public LiveProjectHolder(ProjectLoader projectLoader, Optional<Path> configOverride) {
        this.projectLoader = Objects.requireNonNull(projectLoader, "projectLoader must not be null");
        this.configOverride = Objects.requireNonNull(configOverride, "configOverride must not be null");
    }

    public Optional<Path> configOverride() {
        return configOverride;
    }

    /**
     * Returns the current {@link LoadedProject}, reloading automatically if {@code procomp.json}
     * changed on disk (or when backed by an in-memory {@code ConfigSource}).
     */
    public Result<LoadedProject, DomainError> current() {
        lock.lock();
        try {
            if (cached.isPresent()) {
                CachedProject entry = cached.get();
                if (entry.diskStamps().isPresent()) {
                    Optional<Map<Path, Optional<DiskStamp>>> currentStamps = readDiskStamps(entry.loaded().watchedPaths());
                    if (currentStamps.isPresent() && currentStamps.get().equals(entry.diskStamps().get())) {
                        return Result.ok(entry.loaded());
                    }
                }
            }

            Result<LoadedProject, DomainError> loadRes = projectLoader.loadWithPath(configOverride);
            if (loadRes instanceof Result.Ok<LoadedProject, DomainError>(LoadedProject loaded)) {
                Optional<Map<Path, Optional<DiskStamp>>> stamps = readDiskStamps(loaded.watchedPaths());
                cached = Optional.of(new CachedProject(loaded, stamps));
                return Result.ok(loaded);
            }
            cached = Optional.empty();
            return loadRes;
        } finally {
            lock.unlock();
        }
    }

    public void invalidate() {
        lock.lock();
        try {
            cached = Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    private static Optional<Map<Path, Optional<DiskStamp>>> readDiskStamps(List<Path> paths) {
        if (paths.isEmpty() || !Files.isRegularFile(paths.getFirst())) {
            // Backed by an in-memory ConfigSource (configPath does not exist on real disk)
            return Optional.empty();
        }
        Map<Path, Optional<DiskStamp>> stamps = new LinkedHashMap<>();
        for (Path p : paths) {
            stamps.put(p, readDiskStamp(p));
        }
        return Optional.of(stamps);
    }

    private static Optional<DiskStamp> readDiskStamp(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            FileTime mtime = Files.getLastModifiedTime(path);
            long size = Files.size(path);
            return Optional.of(new DiskStamp(mtime, size));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private record DiskStamp(FileTime mtime, long size) {}

    private record CachedProject(LoadedProject loaded, Optional<Map<Path, Optional<DiskStamp>>> diskStamps) {}
}
