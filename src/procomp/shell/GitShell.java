package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.git.BranchInfo;
import procomp.core.git.GitCancelPlan;
import procomp.core.git.GitEngine;
import procomp.core.git.GitOperation;
import procomp.core.git.GitPlan;
import procomp.core.git.GitRunRecord;
import procomp.core.git.GitRunStatus;
import procomp.core.git.ObservedGitRun;
import procomp.core.git.RepositoryObservation;
import procomp.core.git.StashEntry;
import procomp.core.project.Project;
import procomp.core.project.RepositoryRef;
import procomp.core.project.ServiceDefinition;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.ports.Clock;
import procomp.shell.ports.GitRepositoryReader;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner;
import procomp.shell.ports.ProcessRunner.ExitOutcome;
import procomp.shell.ports.ProcessRunner.RunningProcess;
import procomp.shell.ports.ProcessRunner.SpawnRequest;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.DiagnosticCategory;
import procomp.shell.ports.ProgressObserver.ProgressEvent;
import procomp.shell.ports.StateStore;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Modality-agnostic orchestrator for {@code Repository} Git observation, branch/stash listing,
 * and tracked non-authoring {@code Git Run}s ({@code clone}, {@code fetch}, {@code pull}, {@code switch}, {@code stash}, {@code cancel}).
 */
public final class GitShell {

    public static final Duration DEFAULT_CANCEL_GRACEFUL_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_CONCURRENT_GIT_OPS = 6;
    private static final Map<String, String> NON_INTERACTIVE_GIT_ENV = Map.of("GIT_TERMINAL_PROMPT", "0");

    private final StateStore stateStore;
    private final ProcessRunner processRunner;
    private final GitRepositoryReader gitReader;
    private final RoutingShell routingShell;
    private final Clock clock;
    private final ProgressObserver observer;

    public GitShell(
        StateStore stateStore,
        ProcessRunner processRunner,
        GitRepositoryReader gitReader,
        Clock clock,
        ProgressObserver observer
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
        this.gitReader = Objects.requireNonNull(gitReader, "gitReader must not be null");
        this.routingShell = new RoutingShell(stateStore);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    public record RepositoryStatusRow(
        RepositoryRef repository,
        List<String> services,
        RepositoryObservation observation,
        Optional<ObservedGitRun> latestRun,
        boolean fetchAgeWarning,
        String compactDisplay
    ) {
        public RepositoryStatusRow {
            Objects.requireNonNull(repository);
            services = List.copyOf(services);
            Objects.requireNonNull(observation);
            Objects.requireNonNull(latestRun);
            Objects.requireNonNull(compactDisplay);
        }
    }

    public record GitStatusReport(
        String projectName,
        Instant observedAt,
        int fetchWarnAfterHours,
        List<RepositoryStatusRow> repositories,
        Optional<GitBatchReport> fetchReport
    ) {
        public GitStatusReport {
            Objects.requireNonNull(projectName);
            Objects.requireNonNull(observedAt);
            repositories = List.copyOf(repositories);
            Objects.requireNonNull(fetchReport);
        }
    }

    public record BranchesReport(
        RepositoryRef repository,
        List<String> services,
        RepositoryObservation observation,
        List<BranchInfo> branches
    ) {
        public BranchesReport {
            Objects.requireNonNull(repository);
            services = List.copyOf(services);
            Objects.requireNonNull(observation);
            branches = List.copyOf(branches);
        }
    }

    public record RepositoryStashes(
        RepositoryRef repository,
        List<String> services,
        List<StashEntry> stashes
    ) {
        public RepositoryStashes {
            Objects.requireNonNull(repository);
            services = List.copyOf(services);
            stashes = List.copyOf(stashes);
        }
    }

    public sealed interface GitRepoOutcome
        permits GitRepoOutcome.Succeeded, GitRepoOutcome.Skipped, GitRepoOutcome.Failed {

        RepositoryRef repository();

        record Succeeded(RepositoryRef repository, GitRunRecord record) implements GitRepoOutcome {}

        record Skipped(RepositoryRef repository, String reason) implements GitRepoOutcome {}

        record Failed(RepositoryRef repository, DomainError error, Optional<GitRunRecord> record) implements GitRepoOutcome {}
    }

    public record GitBatchReport(GitOperation operation, List<GitRepoOutcome> outcomes) {
        public GitBatchReport {
            Objects.requireNonNull(operation);
            outcomes = List.copyOf(outcomes);
        }

        public boolean hasFailures() {
            for (GitRepoOutcome outcome : outcomes) {
                if (outcome instanceof GitRepoOutcome.Failed) {
                    return true;
                }
            }
            return false;
        }
    }

    public sealed interface CancelOutcome
        permits CancelOutcome.Cancelled, CancelOutcome.NoActiveRun, CancelOutcome.Failed {

        RepositoryRef repository();

        record Cancelled(RepositoryRef repository, GitRunRecord record) implements CancelOutcome {}

        record NoActiveRun(RepositoryRef repository, Optional<GitRunRecord> latestRun) implements CancelOutcome {}

        record Failed(RepositoryRef repository, DomainError error) implements CancelOutcome {}
    }

    public record GitCancelReport(List<CancelOutcome> outcomes) {
        public GitCancelReport {
            outcomes = List.copyOf(outcomes);
        }

        public boolean hasFailures() {
            for (CancelOutcome o : outcomes) {
                if (o instanceof CancelOutcome.Failed) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Zero-network local snapshot of every unique {@code Repository} in the {@code Project},
     * executed with bounded parallelism on virtual threads for {@link StatusShell}.
     */
    public Result<Map<Path, RepositoryStatusRow>, DomainError> snapshotRepositories(Project project) {
        Objects.requireNonNull(project, "project must not be null");
        List<RepositoryRef> repos = project.repositories();
        if (repos.isEmpty()) {
            return Result.ok(Map.of());
        }
        Instant now = clock.now();
        Map<Path, List<String>> servicesByRepo = servicesByRepository(project);
        List<Result<RepositoryStatusRow, DomainError>> results = runBoundedParallel(
            repos,
            repo -> observeRepositoryRow(project, repo, servicesByRepo.getOrDefault(repo.root(), List.of()), now)
        );
        Map<Path, RepositoryStatusRow> map = new LinkedHashMap<>();
        for (Result<RepositoryStatusRow, DomainError> res : results) {
            if (res.isErr()) {
                return res.castError();
            }
            RepositoryStatusRow row = okVal(res);
            map.put(row.repository().root(), row);
        }
        return Result.ok(map);
    }

    /**
     * Read-only observation of a single {@code Repository} (used by {@code procomp inspect <service>}).
     */
    public Result<RepositoryStatusRow, DomainError> observeRepository(Project project, RepositoryRef repo) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(repo, "repo must not be null");
        Map<Path, List<String>> servicesByRepo = servicesByRepository(project);
        return observeRepositoryRow(project, repo, servicesByRepo.getOrDefault(repo.root(), List.of()), clock.now());
    }

    public Result<GitStatusReport, DomainError> status(
        Project project,
        List<String> targets,
        boolean localOnly,
        boolean fetchFirst,
        boolean allForFetch,
        boolean force,
        Optional<Duration> timeout
    ) {
        Objects.requireNonNull(project, "project must not be null");
        Optional<GitBatchReport> fetchReport = Optional.empty();
        if (fetchFirst) {
            Result<GitBatchReport, DomainError> fetchRes = fetch(project, targets, allForFetch, force, timeout);
            if (fetchRes.isErr()) {
                return fetchRes.castError();
            }
            fetchReport = Optional.of(okVal(fetchRes));
        }

        Result<List<RepositoryRef>, DomainError> targetReposRes = targets.isEmpty()
            ? resolveDefaultRepositories(project, !localOnly)
            : resolveNamedTargets(project, targets);
        if (targetReposRes.isErr()) {
            return targetReposRes.castError();
        }
        List<RepositoryRef> repos = okVal(targetReposRes);
        Instant now = clock.now();
        Map<Path, List<String>> servicesByRepo = servicesByRepository(project);

        List<Result<RepositoryStatusRow, DomainError>> rowResults = runBoundedParallel(
            repos,
            repo -> observeRepositoryRow(project, repo, servicesByRepo.getOrDefault(repo.root(), List.of()), now)
        );
        List<RepositoryStatusRow> rows = new ArrayList<>(rowResults.size());
        for (Result<RepositoryStatusRow, DomainError> res : rowResults) {
            if (res.isErr()) {
                return res.castError();
            }
            rows.add(okVal(res));
        }
        return Result.ok(new GitStatusReport(
            project.name(),
            now,
            project.repositorySettings().fetchWarnAfterHours(),
            rows,
            fetchReport
        ));
    }

    public Result<BranchesReport, DomainError> branches(Project project, String target) {
        Objects.requireNonNull(project, "project must not be null");
        Result<RepositoryRef, DomainError> repoRes = resolveSingleTarget(project, target);
        if (repoRes.isErr()) {
            return repoRes.castError();
        }
        RepositoryRef repo = okVal(repoRes);
        Result<RepositoryObservation, DomainError> obsRes = gitReader.observe(repo);
        if (obsRes.isErr()) {
            return obsRes.castError();
        }
        RepositoryObservation obs = okVal(obsRes);
        if (!(obs instanceof RepositoryObservation.Cloned)) {
            return Result.err(DomainError.of(
                "GIT_NOT_CLONED",
                "Repository '" + repo.name() + "' is not cloned at '" + repo.root() + "'",
                Map.of("repository", repo.name())
            ));
        }
        Result<List<BranchInfo>, DomainError> listRes = gitReader.listBranches(repo);
        if (listRes.isErr()) {
            return listRes.castError();
        }
        List<String> services = servicesByRepository(project).getOrDefault(repo.root(), List.of());
        return Result.ok(new BranchesReport(repo, services, obs, okVal(listRes)));
    }

    public Result<List<RepositoryStashes>, DomainError> stashList(Project project, List<String> targets, boolean all) {
        Objects.requireNonNull(project, "project must not be null");
        Result<List<RepositoryRef>, DomainError> reposRes = targets.isEmpty()
            ? resolveDefaultRepositories(project, all)
            : resolveNamedTargets(project, targets);
        if (reposRes.isErr()) {
            return reposRes.castError();
        }
        Map<Path, List<String>> servicesByRepo = servicesByRepository(project);
        List<RepositoryStashes> out = new ArrayList<>();
        for (RepositoryRef repo : okVal(reposRes)) {
            Result<RepositoryObservation, DomainError> obsRes = gitReader.observe(repo);
            if (obsRes.isErr()) {
                return obsRes.castError();
            }
            if (!(okVal(obsRes) instanceof RepositoryObservation.Cloned)) {
                out.add(new RepositoryStashes(repo, servicesByRepo.getOrDefault(repo.root(), List.of()), List.of()));
                continue;
            }
            Result<List<StashEntry>, DomainError> stashesRes = gitReader.listStashes(repo);
            if (stashesRes.isErr()) {
                return stashesRes.castError();
            }
            out.add(new RepositoryStashes(repo, servicesByRepo.getOrDefault(repo.root(), List.of()), okVal(stashesRes)));
        }
        return Result.ok(out);
    }

    public Result<GitBatchReport, DomainError> cloneRepositories(
        Project project, List<String> targets, boolean all, boolean force, Optional<Duration> timeout
    ) {
        return runBatch(project, targets, all, GitOperation.CLONE, true, repo -> {
            Result<ObservationAndRun, DomainError> stateRes = readState(project, repo);
            if (stateRes.isErr()) {
                return new GitRepoOutcome.Failed(repo, errVal(stateRes), Optional.empty());
            }
            ObservationAndRun state = okVal(stateRes);
            GitPlan plan = GitEngine.planClone(repo, state.obs(), state.record(), state.pidAlive(), force);
            return executePlan(project, plan, effectiveTimeout(project, timeout));
        });
    }

    public Result<GitBatchReport, DomainError> fetch(
        Project project, List<String> targets, boolean all, boolean force, Optional<Duration> timeout
    ) {
        return runBatch(project, targets, all, GitOperation.FETCH, true, repo -> {
            Result<ObservationAndRun, DomainError> stateRes = readState(project, repo);
            if (stateRes.isErr()) {
                return new GitRepoOutcome.Failed(repo, errVal(stateRes), Optional.empty());
            }
            ObservationAndRun state = okVal(stateRes);
            GitPlan plan = GitEngine.planFetch(repo, state.obs(), state.record(), state.pidAlive(), force);
            return executePlan(project, plan, effectiveTimeout(project, timeout));
        });
    }

    public Result<GitBatchReport, DomainError> pull(
        Project project, List<String> targets, boolean all, boolean force, Optional<Duration> timeout
    ) {
        return runBatch(project, targets, all, GitOperation.PULL, true, repo -> {
            Result<ObservationAndRun, DomainError> stateRes = readState(project, repo);
            if (stateRes.isErr()) {
                return new GitRepoOutcome.Failed(repo, errVal(stateRes), Optional.empty());
            }
            ObservationAndRun state = okVal(stateRes);
            GitPlan plan = GitEngine.planPull(repo, state.obs(), state.record(), state.pidAlive(), force);
            return executePlan(project, plan, effectiveTimeout(project, timeout));
        });
    }

    public Result<GitBatchReport, DomainError> switchBranch(
        Project project,
        Optional<String> branch,
        boolean useDefault,
        List<String> targets,
        boolean all,
        boolean force,
        Optional<Duration> timeout
    ) {
        if (useDefault == branch.isPresent()) {
            return Result.err(DomainError.of(
                "GIT_INVALID_ARGUMENTS",
                "Specify either a branch name or --default for 'git switch'"
            ));
        }
        return runBatch(project, targets, all, GitOperation.SWITCH, false, repo -> {
            Result<ObservationAndRun, DomainError> stateRes = readState(project, repo);
            if (stateRes.isErr()) {
                return new GitRepoOutcome.Failed(repo, errVal(stateRes), Optional.empty());
            }
            ObservationAndRun state = okVal(stateRes);
            String resolvedBranch;
            if (useDefault) {
                if (!(state.obs() instanceof RepositoryObservation.Cloned)) {
                    GitPlan plan = GitEngine.planSwitch(repo, state.obs(), "default", state.record(), state.pidAlive(), force);
                    return executePlan(project, plan, effectiveTimeout(project, timeout));
                }
                Result<Optional<String>, DomainError> defRes = gitReader.resolveDefaultBranch(repo);
                if (defRes.isErr()) {
                    return new GitRepoOutcome.Failed(repo, errVal(defRes), Optional.empty());
                }
                Optional<String> defOpt = okVal(defRes);
                if (defOpt.isEmpty()) {
                    return new GitRepoOutcome.Failed(
                        repo,
                        DomainError.of(
                            "GIT_DEFAULT_BRANCH_UNKNOWN",
                            "Could not resolve default branch (origin/HEAD) for Repository '" + repo.name()
                                + "'. Run 'git remote set-head origin -a' or specify a branch explicitly.",
                            Map.of("repository", repo.name())
                        ),
                        Optional.empty()
                    );
                }
                resolvedBranch = defOpt.get();
            } else {
                resolvedBranch = branch.orElseThrow();
            }
            GitPlan plan = GitEngine.planSwitch(repo, state.obs(), resolvedBranch, state.record(), state.pidAlive(), force);
            return executePlan(project, plan, effectiveTimeout(project, timeout));
        });
    }

    public Result<GitBatchReport, DomainError> stashPush(
        Project project,
        List<String> targets,
        boolean all,
        Optional<String> message,
        boolean includeUntracked,
        boolean force,
        Optional<Duration> timeout
    ) {
        return runBatch(project, targets, all, GitOperation.STASH, false, repo -> {
            Result<ObservationAndRun, DomainError> stateRes = readState(project, repo);
            if (stateRes.isErr()) {
                return new GitRepoOutcome.Failed(repo, errVal(stateRes), Optional.empty());
            }
            ObservationAndRun state = okVal(stateRes);
            GitPlan plan = GitEngine.planStash(repo, state.obs(), message, includeUntracked, state.record(), state.pidAlive(), force);
            return executePlan(project, plan, effectiveTimeout(project, timeout));
        });
    }

    public Result<GitCancelReport, DomainError> cancel(Project project, List<String> targets, boolean all) {
        Objects.requireNonNull(project, "project must not be null");
        Result<List<RepositoryRef>, DomainError> reposRes = targets.isEmpty()
            ? resolveDefaultRepositories(project, all)
            : resolveNamedTargets(project, targets);
        if (reposRes.isErr()) {
            return reposRes.castError();
        }
        List<CancelOutcome> outcomes = new ArrayList<>();
        for (RepositoryRef repo : okVal(reposRes)) {
            Result<Optional<GitRunRecord>, DomainError> readRes = stateStore.readGitRun(project.name(), repo.name());
            if (readRes.isErr()) {
                outcomes.add(new CancelOutcome.Failed(repo, errVal(readRes)));
                continue;
            }
            Optional<GitRunRecord> latest = okVal(readRes);
            boolean alive = latest.isPresent()
                && latest.get().status() == GitRunStatus.RUNNING
                && processRunner.isAlive(latest.get().pid());
            GitCancelPlan plan = GitEngine.planCancel(repo, latest, alive);
            switch (plan) {
                case GitCancelPlan.NoActiveRun(_, Optional<GitRunRecord> prev) ->
                    outcomes.add(new CancelOutcome.NoActiveRun(repo, prev));
                case GitCancelPlan.RejectUnknown(_, GitRunRecord.Running unknownRecord) ->
                    outcomes.add(new CancelOutcome.Failed(repo, DomainError.of(
                        "GIT_RUN_UNKNOWN_STATE",
                        "Repository '" + repo.name() + "' has an UNKNOWN Git Run (dead PID " + unknownRecord.pid() + ")",
                        Map.of("repository", repo.name(), "pid", String.valueOf(unknownRecord.pid()))
                    )));
                case GitCancelPlan.Terminate(_, long pid, GitRunRecord.Running running) -> {
                    Result<Unit, DomainError> stopRes = processRunner.stopTree(pid, DEFAULT_CANCEL_GRACEFUL_TIMEOUT);
                    if (stopRes.isErr()) {
                        outcomes.add(new CancelOutcome.Failed(repo, errVal(stopRes)));
                        continue;
                    }
                    GitRunRecord cancelled = running.failed(
                        clock.now(),
                        Optional.empty(),
                        "Cancelled by user"
                    );
                    Result<Unit, DomainError> writeRes = stateStore.writeGitRun(project.name(), repo.name(), cancelled);
                    if (writeRes.isErr()) {
                        outcomes.add(new CancelOutcome.Failed(repo, errVal(writeRes)));
                        continue;
                    }
                    observer.onEvent(new ProgressEvent.StatusMessage(
                        "git:" + repo.name(),
                        "Cancelled Git Run (" + running.operation().cliName() + ", PID " + pid + ")"
                    ));
                    outcomes.add(new CancelOutcome.Cancelled(repo, cancelled));
                }
            }
        }
        return Result.ok(new GitCancelReport(outcomes));
    }

    /**
     * Resolves a single target (either {@code Repository} canonical name or {@code Service} name) to its {@code RepositoryRef}.
     */
    public static Result<RepositoryRef, DomainError> resolveTarget(Project project, String target) {
        Objects.requireNonNull(project, "project must not be null");
        if (target == null || target.isBlank()) {
            return Result.err(DomainError.of("GIT_UNKNOWN_TARGET", "Target name must not be blank"));
        }
        Optional<RepositoryRef> directRepo = project.repository(target);
        if (directRepo.isPresent()) {
            return Result.ok(directRepo.get());
        }
        Optional<ServiceDefinition> svc = project.service(target);
        if (svc.isPresent()) {
            if (svc.get().repository().isEmpty()) {
                return Result.err(DomainError.of(
                    "GIT_NO_REPOSITORY",
                    "Service '" + target + "' has no Repository configured",
                    Map.of("service", target)
                ));
            }
            return Result.ok(svc.get().repository().get());
        }
        return Result.err(DomainError.of(
            "GIT_UNKNOWN_TARGET",
            "Unknown Service or Repository '" + target + "' in project '" + project.name() + "'",
            Map.of("target", target)
        ));
    }

    public Result<RepositoryRef, DomainError> resolveSingleTarget(Project project, String target) {
        return resolveTarget(project, target);
    }

    public static Map<Path, List<String>> servicesByRepository(Project project) {
        Map<Path, List<String>> map = new LinkedHashMap<>();
        for (RepositoryRef ref : project.repositories()) {
            map.put(ref.root(), new ArrayList<>());
        }
        for (ServiceDefinition svc : project.services().values()) {
            if (svc.repository().isPresent()) {
                map.computeIfAbsent(svc.repository().get().root(), _ -> new ArrayList<>()).add(svc.name());
            }
        }
        return map;
    }

    private Result<GitBatchReport, DomainError> runBatch(
        Project project,
        List<String> targets,
        boolean all,
        GitOperation operation,
        boolean parallel,
        java.util.function.Function<RepositoryRef, GitRepoOutcome> executorFn
    ) {
        Objects.requireNonNull(project, "project must not be null");
        Result<List<RepositoryRef>, DomainError> reposRes = targets.isEmpty()
            ? resolveDefaultRepositories(project, all)
            : resolveNamedTargets(project, targets);
        if (reposRes.isErr()) {
            return reposRes.castError();
        }
        List<RepositoryRef> repos = okVal(reposRes);
        if (repos.isEmpty()) {
            return Result.ok(new GitBatchReport(operation, List.of()));
        }
        List<GitRepoOutcome> outcomes;
        if (parallel && repos.size() > 1) {
            outcomes = runBoundedParallel(repos, executorFn);
        } else {
            outcomes = new ArrayList<>(repos.size());
            for (RepositoryRef repo : repos) {
                outcomes.add(executorFn.apply(repo));
            }
        }
        return Result.ok(new GitBatchReport(operation, outcomes));
    }

    private GitRepoOutcome executePlan(Project project, GitPlan plan, Duration timeout) {
        return switch (plan) {
            case GitPlan.Skip(RepositoryRef repo, GitOperation op, String reason) -> {
                observer.onEvent(new ProgressEvent.StatusMessage("git:" + repo.name(), "Skipped " + op.cliName() + " (" + reason + ")"));
                yield new GitRepoOutcome.Skipped(repo, reason);
            }
            case GitPlan.Reject(RepositoryRef repo, GitOperation _, DomainError err) ->
                new GitRepoOutcome.Failed(repo, err, Optional.empty());
            case GitPlan.Execute exec -> runTrackedGitCommand(project, exec, timeout);
        };
    }

    private GitRepoOutcome runTrackedGitCommand(Project project, GitPlan.Execute exec, Duration timeout) {
        RepositoryRef repo = exec.repository();
        Path stdoutLog = stateStore.logPath(project.name(), ProcessKey.git(repo.name()), false);
        Path stderrLog = stateStore.logPath(project.name(), ProcessKey.git(repo.name()), true);
        observer.onEvent(new ProgressEvent.StatusMessage(
            "git:" + repo.name(),
            "Running git " + exec.operation().cliName() + " for " + repo.name()
        ));
        observer.diagnostic(DiagnosticCategory.PROCESS, "git " + exec.operation().cliName() + " cmd=" + exec.command());

        Result<RunningProcess, DomainError> spawnRes = processRunner.spawnAttached(new SpawnRequest(
            exec.command(),
            exec.workingDir(),
            NON_INTERACTIVE_GIT_ENV,
            stdoutLog,
            stderrLog
        ));
        if (spawnRes.isErr()) {
            return new GitRepoOutcome.Failed(repo, errVal(spawnRes), Optional.empty());
        }
        RunningProcess proc = okVal(spawnRes);
        GitRunRecord.Running runningRecord = new GitRunRecord.Running(
            repo.name(),
            exec.operation(),
            proc.pid(),
            proc.startedAt(),
            proc.commandLine(),
            stdoutLog,
            stderrLog
        );
        Result<Unit, DomainError> writeStart = stateStore.writeGitRun(project.name(), repo.name(), runningRecord);
        if (writeStart.isErr()) {
            processRunner.stopTree(proc.pid(), DEFAULT_CANCEL_GRACEFUL_TIMEOUT);
            return new GitRepoOutcome.Failed(repo, errVal(writeStart), Optional.of(runningRecord));
        }

        ExitOutcome exit = proc.awaitExit(Optional.of(timeout));
        Instant finishedAt = clock.now();

        return switch (exit) {
            case ExitOutcome.Exited(int code) when code == 0 -> {
                GitRunRecord succeeded = runningRecord.succeeded(finishedAt);
                Result<Unit, DomainError> writeEnd = stateStore.writeGitRun(project.name(), repo.name(), succeeded);
                if (writeEnd.isErr()) {
                    yield new GitRepoOutcome.Failed(repo, errVal(writeEnd), Optional.of(succeeded));
                }
                observer.onEvent(new ProgressEvent.StatusMessage(
                    "git:" + repo.name(),
                    "Completed git " + exec.operation().cliName() + " for " + repo.name()
                ));
                yield new GitRepoOutcome.Succeeded(repo, succeeded);
            }
            case ExitOutcome.Exited(int code) -> {
                String reason = "git " + exec.operation().cliName() + " exited with code " + code;
                GitRunRecord failed = runningRecord.failed(finishedAt, Optional.of(code), reason);
                stateStore.writeGitRun(project.name(), repo.name(), failed);
                yield new GitRepoOutcome.Failed(
                    repo,
                    DomainError.of(
                        "GIT_COMMAND_FAILED",
                        "Repository '" + repo.name() + "': " + reason + " (see logs: " + stderrLog + ")",
                        Map.of("repository", repo.name(), "operation", exec.operation().cliName(), "exitCode", String.valueOf(code))
                    ),
                    Optional.of(failed)
                );
            }
            case ExitOutcome.TimedOut _ -> {
                processRunner.stopTree(proc.pid(), DEFAULT_CANCEL_GRACEFUL_TIMEOUT);
                String reason = "Timed out after " + timeout.toSeconds() + "s";
                GitRunRecord failed = runningRecord.failed(finishedAt, Optional.empty(), reason);
                stateStore.writeGitRun(project.name(), repo.name(), failed);
                yield new GitRepoOutcome.Failed(
                    repo,
                    DomainError.of(
                        "GIT_COMMAND_TIMED_OUT",
                        "Repository '" + repo.name() + "': git " + exec.operation().cliName() + " " + reason,
                        Map.of("repository", repo.name(), "operation", exec.operation().cliName())
                    ),
                    Optional.of(failed)
                );
            }
            case ExitOutcome.Interrupted _ -> {
                processRunner.stopTree(proc.pid(), DEFAULT_CANCEL_GRACEFUL_TIMEOUT);
                Thread.currentThread().interrupt();
                String reason = "Interrupted";
                GitRunRecord failed = runningRecord.failed(finishedAt, Optional.empty(), reason);
                stateStore.writeGitRun(project.name(), repo.name(), failed);
                yield new GitRepoOutcome.Failed(
                    repo,
                    DomainError.of(
                        "GIT_COMMAND_INTERRUPTED",
                        "Repository '" + repo.name() + "': git " + exec.operation().cliName() + " was interrupted",
                        Map.of("repository", repo.name(), "operation", exec.operation().cliName())
                    ),
                    Optional.of(failed)
                );
            }
        };
    }

    private Result<RepositoryStatusRow, DomainError> observeRepositoryRow(
        Project project, RepositoryRef repo, List<String> services, Instant now
    ) {
        Result<RepositoryObservation, DomainError> obsRes = gitReader.observe(repo);
        if (obsRes.isErr()) {
            return obsRes.castError();
        }
        RepositoryObservation obs = okVal(obsRes);
        Result<Optional<GitRunRecord>, DomainError> runRes = stateStore.readGitRun(project.name(), repo.name());
        if (runRes.isErr()) {
            return runRes.castError();
        }
        Optional<GitRunRecord> persisted = okVal(runRes);
        boolean alive = persisted.isPresent()
            && persisted.get().status() == GitRunStatus.RUNNING
            && processRunner.isAlive(persisted.get().pid());
        Optional<ObservedGitRun> observedRun = GitEngine.evaluateRun(persisted, alive);
        int warnHours = project.repositorySettings().fetchWarnAfterHours();
        boolean warn = GitEngine.isFetchAgeWarning(obs, now, warnHours);
        String compact = GitEngine.formatCompactDisplay(Optional.of(obs), observedRun, now, warnHours);
        return Result.ok(new RepositoryStatusRow(repo, services, obs, observedRun, warn, compact));
    }

    private record ObservationAndRun(RepositoryObservation obs, Optional<GitRunRecord> record, boolean pidAlive) {}

    private Result<ObservationAndRun, DomainError> readState(Project project, RepositoryRef repo) {
        Result<RepositoryObservation, DomainError> obsRes = gitReader.observe(repo);
        if (obsRes.isErr()) {
            return obsRes.castError();
        }
        Result<Optional<GitRunRecord>, DomainError> runRes = stateStore.readGitRun(project.name(), repo.name());
        if (runRes.isErr()) {
            return runRes.castError();
        }
        Optional<GitRunRecord> rec = okVal(runRes);
        boolean alive = rec.isPresent()
            && rec.get().status() == GitRunStatus.RUNNING
            && processRunner.isAlive(rec.get().pid());
        return Result.ok(new ObservationAndRun(okVal(obsRes), rec, alive));
    }

    private Result<List<RepositoryRef>, DomainError> resolveDefaultRepositories(Project project, boolean all) {
        if (all) {
            return Result.ok(project.repositories());
        }
        Result<RoutingTable, DomainError> routingRes = routingShell.showRoutes(project);
        if (routingRes.isErr()) {
            return routingRes.castError();
        }
        RoutingTable routing = okVal(routingRes);
        Set<Path> seen = new LinkedHashSet<>();
        List<RepositoryRef> localRepos = new ArrayList<>();
        for (ServiceDefinition svc : project.services().values()) {
            if (routing.routeOf(svc.name()).orElse(Route.REMOTE) == Route.LOCAL && svc.repository().isPresent()) {
                RepositoryRef ref = svc.repository().get();
                if (seen.add(ref.root())) {
                    localRepos.add(ref);
                }
            }
        }
        return Result.ok(List.copyOf(localRepos));
    }

    private Result<List<RepositoryRef>, DomainError> resolveNamedTargets(Project project, List<String> targets) {
        Set<Path> requestedRoots = new LinkedHashSet<>();
        for (String target : targets) {
            Result<RepositoryRef, DomainError> single = resolveTarget(project, target);
            if (single.isErr()) {
                return single.castError();
            }
            requestedRoots.add(okVal(single).root());
        }
        // Preserve deterministic Project config order.
        List<RepositoryRef> ordered = new ArrayList<>(requestedRoots.size());
        for (RepositoryRef ref : project.repositories()) {
            if (requestedRoots.contains(ref.root())) {
                ordered.add(ref);
            }
        }
        return Result.ok(List.copyOf(ordered));
    }

    private static Duration effectiveTimeout(Project project, Optional<Duration> override) {
        return override.orElse(Duration.ofSeconds(project.repositorySettings().timeoutSeconds()));
    }

    private static <T, R> List<R> runBoundedParallel(List<T> items, java.util.function.Function<T, R> fn) {
        if (items.size() <= 1) {
            List<R> out = new ArrayList<>(items.size());
            for (T item : items) {
                out.add(fn.apply(item));
            }
            return out;
        }
        Semaphore permits = new Semaphore(MAX_CONCURRENT_GIT_OPS);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<R>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return fn.apply(item);
                    } finally {
                        permits.release();
                    }
                }));
            }
            List<R> results = new ArrayList<>(items.size());
            for (Future<R> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during parallel Git operation", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("Unexpected checked exception in parallel Git operation", cause);
        }
    }

    private static <T> T okVal(Result<T, DomainError> res) {
        return ((Result.Ok<T, DomainError>) res).value();
    }

    private static <T> DomainError errVal(Result<T, DomainError> res) {
        return ((Result.Err<T, DomainError>) res).error();
    }
}
