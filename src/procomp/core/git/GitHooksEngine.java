package procomp.core.git;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.GitConfig;
import procomp.core.project.GitHookInstallMode;
import procomp.core.project.Project;
import procomp.core.project.RepositoryRef;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure, total domain functions for mandatory Git Hook evaluation, Repository targeting, and install planning.
 */
public final class GitHooksEngine {

    private GitHooksEngine() {}

    /**
     * Returns the short display name for a Repository root path (its final path segment, or the full path if root).
     */
    public static String displayName(Path repositoryRoot) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
        Path fileName = repositoryRoot.getFileName();
        return fileName != null ? fileName.toString() : repositoryRoot.toString();
    }

    /**
     * Collects all unique target Repositories for a Project, combining Service Repositories
     * with any configured {@code git.extraRepositories} (preserving declaration order).
     */
    public static List<RepositoryRef> collectTargetRepositories(Project project) {
        Objects.requireNonNull(project, "project must not be null");
        Map<Path, RepositoryRef> byRoot = new LinkedHashMap<>();
        for (RepositoryRef ref : project.repositories()) {
            Path normalized = ref.root().normalize();
            byRoot.putIfAbsent(normalized, new RepositoryRef(normalized, ref.remoteUrl()));
        }
        Optional<GitConfig> gitOpt = project.git();
        if (gitOpt.isPresent()) {
            for (Path extra : gitOpt.get().extraRepositories()) {
                Path normalized = extra.normalize();
                byRoot.putIfAbsent(normalized, new RepositoryRef(normalized, Optional.empty()));
            }
        }
        return List.copyOf(byRoot.values());
    }

    /**
     * Evaluates a single mandatory Git Hook against its raw filesystem observation.
     */
    public static HookCheckResult evaluateHook(
        HookSpec spec,
        Path hooksDir,
        ObservedHookFile observed,
        GitHookInstallMode mode,
        boolean posixPermissions
    ) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(hooksDir, "hooksDir must not be null");
        Objects.requireNonNull(observed, "observed must not be null");
        Objects.requireNonNull(mode, "mode must not be null");

        Path targetPath = hooksDir.resolve(spec.name());
        HookState state = switch (observed) {
            case ObservedHookFile.Missing _ -> HookState.MISSING;
            case ObservedHookFile.Unreadable _ -> HookState.OUTDATED;
            case ObservedHookFile.RegularFile file -> mode == GitHookInstallMode.COPY
                ? evaluateContentAndPerms(spec, Optional.of(file.contentDigest()), file.executable(), posixPermissions)
                : HookState.OUTDATED;
            case ObservedHookFile.Symlink link -> {
                if (mode != GitHookInstallMode.SYMLINK) {
                    yield HookState.OUTDATED;
                }
                if (!spec.sourcePath().normalize().equals(link.target().normalize())) {
                    yield HookState.OUTDATED;
                }
                yield evaluateContentAndPerms(spec, link.contentDigest(), link.executable(), posixPermissions);
            }
        };
        return new HookCheckResult(spec.name(), spec.sourcePath(), targetPath, state);
    }

    private static HookState evaluateContentAndPerms(
        HookSpec spec,
        Optional<String> contentDigest,
        boolean executable,
        boolean posixPermissions
    ) {
        if (contentDigest.isEmpty() || !spec.sourceDigest().equals(contentDigest.get())) {
            return HookState.OUTDATED;
        }
        if (posixPermissions && !executable) {
            return HookState.NOT_EXECUTABLE;
        }
        return HookState.INSTALLED;
    }

    /**
     * Evaluates all mandatory Git Hooks for a single Repository.
     */
    public static RepositoryHookStatus evaluateRepository(
        RepositoryRef repository,
        ObservedRepository observed,
        List<HookSpec> specs,
        GitHookInstallMode mode,
        boolean posixPermissions
    ) {
        Objects.requireNonNull(repository, "repository must not be null");
        Objects.requireNonNull(observed, "observed must not be null");
        Objects.requireNonNull(specs, "specs must not be null");
        Objects.requireNonNull(mode, "mode must not be null");

        return switch (observed) {
            case ObservedRepository.NotCloned _ ->
                new RepositoryHookStatus.NotCloned(repository.root(), repository.remoteUrl());
            case ObservedRepository.NotGitRepository _ ->
                new RepositoryHookStatus.NotGitRepository(repository.root(), repository.remoteUrl());
            case ObservedRepository.GitRepository gitRepo -> {
                List<HookCheckResult> results = new ArrayList<>(specs.size());
                for (HookSpec spec : specs) {
                    ObservedHookFile obsFile = gitRepo.hooks().getOrDefault(spec.name(), new ObservedHookFile.Missing());
                    results.add(evaluateHook(spec, gitRepo.hooksDir(), obsFile, mode, posixPermissions));
                }
                yield new RepositoryHookStatus.Checked(
                    repository.root(),
                    repository.remoteUrl(),
                    gitRepo.hooksDir(),
                    results
                );
            }
        };
    }

    /**
     * Resolves optional user-supplied Repository filters (matching either display name or normalized path).
     * When {@code filters} is empty, returns all Repositories in {@code allStatuses}.
     */
    public static Result<List<RepositoryHookStatus>, DomainError> selectRepositories(
        List<RepositoryHookStatus> allStatuses,
        List<String> filters
    ) {
        Objects.requireNonNull(allStatuses, "allStatuses must not be null");
        Objects.requireNonNull(filters, "filters must not be null");
        if (filters.isEmpty()) {
            return Result.ok(List.copyOf(allStatuses));
        }

        Set<Path> selectedPaths = new LinkedHashSet<>();
        List<RepositoryHookStatus> selected = new ArrayList<>();
        for (String rawFilter : filters) {
            if (rawFilter == null || rawFilter.isBlank()) {
                return Result.err(DomainError.of(
                    "GIT_REPOSITORY_INVALID_FILTER",
                    "Repository filter must not be blank"
                ));
            }
            String trimmed = rawFilter.trim();
            List<RepositoryHookStatus> matches = new ArrayList<>();
            for (RepositoryHookStatus status : allStatuses) {
                if (matchesFilter(status, trimmed)) {
                    matches.add(status);
                }
            }
            if (matches.isEmpty()) {
                return Result.err(DomainError.of(
                    "GIT_REPOSITORY_NOT_FOUND",
                    "No configured Repository matches '" + trimmed + "'",
                    Map.of("repository", trimmed)
                ));
            }
            for (RepositoryHookStatus match : matches) {
                if (selectedPaths.add(match.repositoryRoot())) {
                    selected.add(match);
                }
            }
        }
        return Result.ok(List.copyOf(selected));
    }

    /**
     * Maps a {@link HookState} to the {@link HookInstallAction} that will be performed on it.
     */
    public static HookInstallAction plannedActionFor(HookState state) {
        Objects.requireNonNull(state, "state must not be null");
        return switch (state) {
            case INSTALLED -> HookInstallAction.UNCHANGED;
            case MISSING -> HookInstallAction.INSTALLED;
            case OUTDATED, NOT_EXECUTABLE -> HookInstallAction.UPGRADED;
        };
    }

    private static boolean matchesFilter(RepositoryHookStatus status, String filter) {
        if (status.displayName().equals(filter)) {
            return true;
        }
        String normRoot = normalizeSlashPath(status.repositoryRoot().normalize().toString());
        String normFilter = normalizeSlashPath(filter);
        if (normRoot.equals(normFilter)) {
            return true;
        }
        try {
            Path parsedFilter = Path.of(filter).normalize();
            return status.repositoryRoot().normalize().equals(parsedFilter);
        } catch (java.nio.file.InvalidPathException ignored) {
            return false;
        }
    }

    private static String normalizeSlashPath(String raw) {
        String s = raw.replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
