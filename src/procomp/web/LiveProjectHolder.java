package procomp.web;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ProjectLoader;
import procomp.shell.ProjectLoader.LoadedProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
                if (entry.diskStamp().isPresent()) {
                    Optional<DiskStamp> currentStamp = readDiskStamp(entry.loaded().configPath());
                    if (currentStamp.isPresent() && currentStamp.get().equals(entry.diskStamp().get())) {
                        return Result.ok(entry.loaded());
                    }
                }
            }

            Result<LoadedProject, DomainError> loadRes = projectLoader.loadWithPath(configOverride);
            if (loadRes instanceof Result.Ok<LoadedProject, DomainError>(LoadedProject loaded)) {
                Optional<DiskStamp> stamp = readDiskStamp(loaded.configPath());
                cached = Optional.of(new CachedProject(loaded, stamp));
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

    private record CachedProject(LoadedProject loaded, Optional<DiskStamp> diskStamp) {}
}
