package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.git.GitHooksEngine;
import procomp.core.git.GitHooksInstallResult;
import procomp.core.git.GitHooksReport;
import procomp.core.git.HookCheckResult;
import procomp.core.git.HookInstallAction;
import procomp.core.git.HookInstallOutcome;
import procomp.core.git.HookSpec;
import procomp.core.git.ObservedRepository;
import procomp.core.git.RepositoryHookStatus;
import procomp.core.git.RepositoryInstallOutcome;
import procomp.core.project.GitHooksConfig;
import procomp.core.project.Project;
import procomp.core.project.RepositoryRef;
import procomp.shell.ports.GitHooksStore;
import procomp.shell.ports.ProgressObserver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Modality-agnostic orchestrator for checking and installing/upgrading mandatory Git hooks
 * across all Repositories configured in a Project.
 */
public final class GitHooksShell {

    private final GitHooksStore store;
    private final ProgressObserver observer;

    public GitHooksShell(GitHooksStore store) {
        this(store, ProgressObserver.noop());
    }

    public GitHooksShell(GitHooksStore store, ProgressObserver observer) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    /**
     * Read-only inspection of mandatory Git hooks across all Repositories in {@code project}.
     */
    public Result<GitHooksReport, DomainError> status(Project project) {
        Objects.requireNonNull(project, "project must not be null");
        Optional<GitHooksConfig> hooksOpt = project.git().flatMap(g -> g.hooks());
        if (hooksOpt.isEmpty()) {
            return Result.ok(GitHooksReport.notConfigured(project.name()));
        }
        GitHooksConfig config = hooksOpt.get();

        Result<List<HookSpec>, DomainError> specsRes = resolveHookSpecs(config);
        if (specsRes.isErr()) {
            return specsRes.castError();
        }
        List<HookSpec> specs = ((Result.Ok<List<HookSpec>, DomainError>) specsRes).value();
        List<String> hookNames = specs.stream().map(HookSpec::name).toList();

        List<RepositoryRef> repositories = GitHooksEngine.collectTargetRepositories(project);
        boolean posix = store.supportsPosixExecutableBit();
        List<RepositoryHookStatus> statuses = new ArrayList<>(repositories.size());
        for (RepositoryRef repo : repositories) {
            Result<ObservedRepository, DomainError> obsRes = store.inspectRepository(repo.root(), hookNames);
            if (obsRes.isErr()) {
                return obsRes.castError();
            }
            ObservedRepository observed = ((Result.Ok<ObservedRepository, DomainError>) obsRes).value();
            statuses.add(GitHooksEngine.evaluateRepository(repo, observed, specs, config.mode(), posix));
        }

        return Result.ok(new GitHooksReport.Configured(project.name(), config.mode(), hookNames, statuses));
    }

    /**
     * Resilient read-only inspection for unified estate status ({@link StatusShell}):
     * degrades missing/empty Git Hook source paths into {@link GitHooksReport.SourceUnavailable}
     * so Services and Gateway status remain available while surfacing a warning.
     */
    public Result<GitHooksReport, DomainError> statusForEstate(Project project) {
        Result<GitHooksReport, DomainError> res = status(project);
        if (res instanceof Result.Err<GitHooksReport, DomainError>(DomainError err)
            && ("GIT_HOOK_SOURCE_NOT_FOUND".equals(err.code()) || "GIT_HOOKS_EMPTY".equals(err.code()))) {
            return Result.ok(GitHooksReport.sourceUnavailable(project.name(), err.message()));
        }
        return res;
    }

    /**
     * Installs missing mandatory Git hooks and upgrades outdated hooks across all (or filtered)
     * cloned Git Repositories in {@code project}.
     */
    public Result<GitHooksInstallResult, DomainError> install(Project project, List<String> repositoryFilters) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(repositoryFilters, "repositoryFilters must not be null");

        if (project.git().flatMap(g -> g.hooks()).isEmpty()) {
            return Result.err(DomainError.of(
                "GIT_HOOKS_NOT_CONFIGURED",
                "No mandatory Git hooks are configured in project '" + project.name() + "'",
                Map.of("project", project.name())
            ));
        }

        Result<GitHooksReport, DomainError> reportRes = status(project);
        if (reportRes.isErr()) {
            return reportRes.castError();
        }
        GitHooksReport.Configured report = (GitHooksReport.Configured) ((Result.Ok<GitHooksReport, DomainError>) reportRes).value();

        Result<List<RepositoryHookStatus>, DomainError> selectedRes =
            GitHooksEngine.selectRepositories(report.repositories(), repositoryFilters);
        if (selectedRes.isErr()) {
            return selectedRes.castError();
        }
        List<RepositoryHookStatus> selected = ((Result.Ok<List<RepositoryHookStatus>, DomainError>) selectedRes).value();

        List<RepositoryInstallOutcome> outcomes = new ArrayList<>(selected.size());
        for (RepositoryHookStatus repoStatus : selected) {
            switch (repoStatus) {
                case RepositoryHookStatus.NotCloned nc ->
                    outcomes.add(new RepositoryInstallOutcome.SkippedNotCloned(nc.repositoryRoot()));
                case RepositoryHookStatus.NotGitRepository ngr ->
                    outcomes.add(new RepositoryInstallOutcome.SkippedNotGitRepository(ngr.repositoryRoot()));
                case RepositoryHookStatus.Checked checked -> {
                    List<HookInstallOutcome> hookOutcomes = new ArrayList<>(checked.hooks().size());
                    for (HookCheckResult hook : checked.hooks()) {
                        HookInstallAction action = GitHooksEngine.plannedActionFor(hook.state());
                        if (action != HookInstallAction.UNCHANGED) {
                            observer.onEvent(new ProgressObserver.ProgressEvent.StatusMessage(
                                checked.displayName(),
                                (action == HookInstallAction.INSTALLED ? "Installing" : "Upgrading")
                                    + " Git hook '" + hook.hookName() + "'"
                            ));
                            Result<Unit, DomainError> writeRes =
                                store.installHook(hook.sourcePath(), hook.targetPath(), report.mode());
                            if (writeRes.isErr()) {
                                return writeRes.castError();
                            }
                        }
                        hookOutcomes.add(new HookInstallOutcome(hook.hookName(), hook.targetPath(), action));
                    }
                    outcomes.add(new RepositoryInstallOutcome.Applied(checked.repositoryRoot(), hookOutcomes));
                }
            }
        }

        return Result.ok(new GitHooksInstallResult(project.name(), report.mode(), outcomes));
    }

    private Result<List<HookSpec>, DomainError> resolveHookSpecs(GitHooksConfig config) {
        Map<String, Path> hookSources = new LinkedHashMap<>();
        if (config.sourceDir().isPresent()) {
            Path sourceDir = config.sourceDir().get();
            List<String> namesFromDir;
            if (config.requiredHooks().isEmpty()) {
                Result<List<String>, DomainError> listRes = store.listSourceHooks(sourceDir);
                if (listRes.isErr()) {
                    return listRes.castError();
                }
                namesFromDir = ((Result.Ok<List<String>, DomainError>) listRes).value();
            } else {
                namesFromDir = config.requiredHooks();
            }
            for (String hookName : namesFromDir) {
                hookSources.put(hookName, sourceDir.resolve(hookName).normalize());
            }
        }
        for (Map.Entry<String, Path> entry : config.explicitFiles().entrySet()) {
            hookSources.put(entry.getKey(), entry.getValue().normalize());
        }

        if (hookSources.isEmpty()) {
            return Result.err(DomainError.of(
                "GIT_HOOKS_EMPTY",
                "No mandatory Git hook files found in configured source directory",
                config.sourceDir().map(p -> Map.of("sourceDir", p.toString())).orElse(Map.of())
            ));
        }

        List<HookSpec> specs = new ArrayList<>(hookSources.size());
        for (Map.Entry<String, Path> entry : hookSources.entrySet()) {
            String hookName = entry.getKey();
            Path sourcePath = entry.getValue();
            Result<String, DomainError> digestRes = store.readSourceDigest(sourcePath);
            if (digestRes.isErr()) {
                return digestRes.castError();
            }
            specs.add(new HookSpec(hookName, sourcePath, ((Result.Ok<String, DomainError>) digestRes).value()));
        }
        return Result.ok(List.copyOf(specs));
    }
}
