package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.project.ProfileConfigEditor;
import procomp.core.project.ProfileDefinition;
import procomp.core.project.ProjectResolver;
import procomp.shell.ProjectLoader.LoadedProject;
import procomp.shell.ports.ConfigSource;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.DiagnosticCategory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Modality-agnostic orchestrator for creating, updating, and deleting Profiles in {@code procomp.json}.
 * Injects {@link ConfigSource} only to structurally enforce UNIX separation from Routing Table,
 * Process, and Gateway state.
 */
public final class ProfileConfigShell {

    public record SaveProfileResult(LoadedProject loaded, ProfileDefinition profile, boolean overwrote) {
        public SaveProfileResult {
            Objects.requireNonNull(loaded, "loaded must not be null");
            Objects.requireNonNull(profile, "profile must not be null");
        }
    }

    public record DeleteProfileResult(LoadedProject loaded, String deletedName) {
        public DeleteProfileResult {
            Objects.requireNonNull(loaded, "loaded must not be null");
            Objects.requireNonNull(deletedName, "deletedName must not be null");
        }
    }

    private final ConfigSource configSource;
    private final ProgressObserver observer;
    private final ConcurrentHashMap<Path, ReentrantLock> configLocks = new ConcurrentHashMap<>();

    public ProfileConfigShell(ConfigSource configSource) {
        this(configSource, ProgressObserver.noop());
    }

    public ProfileConfigShell(ConfigSource configSource, ProgressObserver observer) {
        this.configSource = Objects.requireNonNull(configSource, "configSource must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    /**
     * Validates {@code profileName} and {@code localServices}, surgically updates {@code "profiles"}
     * in {@code procomp.json}, strictly re-validates the resulting config, and writes it atomically.
     */
    public Result<SaveProfileResult, DomainError> saveProfile(
        LoadedProject loaded,
        String profileName,
        List<String> localServices
    ) {
        Objects.requireNonNull(loaded, "loaded must not be null");
        Objects.requireNonNull(profileName, "profileName must not be null");
        Objects.requireNonNull(localServices, "localServices must not be null");

        boolean overwrote = loaded.project().profile(profileName).isPresent();
        return editAndPersist(
            loaded,
            rawJson -> ProfileConfigEditor.upsertProfile(rawJson, loaded.project(), profileName, localServices)
        ).map(nextLoaded -> {
            ProfileDefinition saved = nextLoaded.project().profile(profileName).orElseThrow();
            return new SaveProfileResult(nextLoaded, saved, overwrote);
        });
    }

    /**
     * Validates {@code profileName} exists, surgically removes it from {@code "profiles"}
     * in {@code procomp.json}, strictly re-validates the resulting config, and writes it atomically.
     */
    public Result<DeleteProfileResult, DomainError> deleteProfile(LoadedProject loaded, String profileName) {
        Objects.requireNonNull(loaded, "loaded must not be null");
        Objects.requireNonNull(profileName, "profileName must not be null");

        return editAndPersist(
            loaded,
            rawJson -> ProfileConfigEditor.deleteProfile(rawJson, loaded.project(), profileName)
        ).map(nextLoaded -> new DeleteProfileResult(nextLoaded, profileName));
    }

    private Result<LoadedProject, DomainError> editAndPersist(
        LoadedProject loaded,
        Function<String, Result<String, DomainError>> editor
    ) {
        Path configPath = loaded.configPath().normalize();
        ReentrantLock lock = configLocks.computeIfAbsent(configPath, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Result<String, DomainError> rawRes = configSource.readFile(configPath);
            if (rawRes.isErr()) {
                return rawRes.castError();
            }

            Result<String, DomainError> editedRes = editor.apply(okVal(rawRes));
            if (editedRes.isErr()) {
                return editedRes.castError();
            }
            String updatedJson = okVal(editedRes);

            Result<LoadedProject, DomainError> validatedRes = validateConfig(configPath, updatedJson);
            if (validatedRes.isErr()) {
                return validatedRes.castError();
            }
            LoadedProject nextLoaded = okVal(validatedRes);

            observer.diagnostic(DiagnosticCategory.CONFIG, "Writing updated procomp.json: " + configPath);
            Result<Unit, DomainError> writeRes = configSource.writeFile(configPath, updatedJson);
            if (writeRes.isErr()) {
                return writeRes.castError();
            }
            return Result.ok(nextLoaded);
        } finally {
            lock.unlock();
        }
    }

    private Result<LoadedProject, DomainError> validateConfig(Path configPath, String rawJson) {
        Set<Path> watched = new LinkedHashSet<>();
        watched.add(configPath);
        return ProjectResolver.resolve(
            configPath,
            rawJson,
            path -> {
                watched.add(path.normalize());
                return readOptionalFile(path);
            },
            configSource.allEnvVars(),
            configSource.currentUser()
        ).map(project -> new LoadedProject(configPath, project, new ArrayList<>(watched)));
    }

    private Result<Optional<String>, DomainError> readOptionalFile(Path path) {
        if (!configSource.isRegularFile(path)) {
            return Result.ok(Optional.empty());
        }
        return configSource.readFile(path).map(Optional::of);
    }

    private static <T> T okVal(Result<T, DomainError> res) {
        return ((Result.Ok<T, DomainError>) res).value();
    }
}
