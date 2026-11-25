package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.git.BranchInfo;
import procomp.core.git.GitOutputParser;
import procomp.core.git.RepositoryObservation;
import procomp.core.git.StashEntry;
import procomp.core.project.RepositoryRef;
import procomp.shell.ports.GitRepositoryReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Cross-platform (Windows, macOS, Linux) read-only local Git adapter shelling out to system {@code git}
 * strictly non-interactively ({@code GIT_TERMINAL_PROMPT=0}, closed stdin) with zero network calls.
 */
public final class OsGitRepositoryReader implements GitRepositoryReader {

    private static final Duration LOCAL_READ_TIMEOUT = Duration.ofSeconds(30);

    @Override
    public Result<RepositoryObservation, DomainError> observe(RepositoryRef repository) {
        Path root = repository.root();
        if (!Files.exists(root)) {
            return Result.ok(new RepositoryObservation.NotCloned(repository));
        }
        if (!Files.isDirectory(root)) {
            return Result.ok(new RepositoryObservation.NotAGitRepository(repository, false));
        }
        Path dotGit = root.resolve(".git");
        if (!Files.exists(dotGit)) {
            return Result.ok(new RepositoryObservation.NotAGitRepository(repository, isDirectoryEmpty(root)));
        }

        Result<ExecResult, DomainError> statusRes = runGit(
            root,
            List.of("git", "status", "--porcelain=v2", "--branch")
        );
        if (statusRes.isErr()) {
            return statusRes.castError();
        }
        ExecResult statusExec = okVal(statusRes);
        if (statusExec.exitCode() != 0) {
            return Result.err(DomainError.of(
                "GIT_OBSERVE_FAILED",
                "Failed to read Git status for Repository '" + repository.name() + "': " + statusExec.stderr().trim(),
                Map.of("repository", repository.name(), "exitCode", String.valueOf(statusExec.exitCode()))
            ));
        }

        GitOutputParser.ParsedStatus parsed = GitOutputParser.parseStatusPorcelainV2(statusExec.stdout());

        int stashCount = 0;
        Result<ExecResult, DomainError> stashRes = runGit(root, List.of("git", "stash", "list"));
        if (stashRes instanceof Result.Ok<ExecResult, DomainError>(ExecResult se) && se.exitCode() == 0) {
            stashCount = GitOutputParser.parseStashList(se.stdout()).size();
        }

        Optional<Instant> lastFetchedAt = readLocalFetchTimestamp(root, dotGit);

        return Result.ok(new RepositoryObservation.Cloned(
            repository,
            parsed.head(),
            parsed.upstream(),
            parsed.ahead(),
            parsed.behind(),
            parsed.stagedCount(),
            parsed.unstagedCount(),
            parsed.untrackedCount(),
            stashCount,
            lastFetchedAt
        ));
    }

    @Override
    public Result<List<BranchInfo>, DomainError> listBranches(RepositoryRef repository) {
        Path root = repository.root();
        Optional<String> currentBranch = Optional.empty();
        Result<ExecResult, DomainError> headRes = runGit(root, List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"));
        if (headRes instanceof Result.Ok<ExecResult, DomainError>(ExecResult he) && he.exitCode() == 0) {
            String trimmed = he.stdout().trim();
            if (!trimmed.isEmpty()) {
                currentBranch = Optional.of(trimmed);
            }
        }

        Result<ExecResult, DomainError> refsRes = runGit(
            root,
            List.of(
                "git", "for-each-ref",
                "--sort=-committerdate",
                "--format=%(refname)\t%(committerdate:iso-strict)\t%(subject)",
                "refs/heads",
                "refs/remotes/origin"
            )
        );
        if (refsRes.isErr()) {
            return refsRes.castError();
        }
        ExecResult exec = okVal(refsRes);
        if (exec.exitCode() != 0) {
            return Result.err(DomainError.of(
                "GIT_BRANCHES_FAILED",
                "Failed to list branches for Repository '" + repository.name() + "': " + exec.stderr().trim(),
                Map.of("repository", repository.name(), "exitCode", String.valueOf(exec.exitCode()))
            ));
        }
        return Result.ok(GitOutputParser.parseBranchesForEachRef(exec.stdout(), currentBranch));
    }

    @Override
    public Result<List<StashEntry>, DomainError> listStashes(RepositoryRef repository) {
        Result<ExecResult, DomainError> res = runGit(repository.root(), List.of("git", "stash", "list"));
        if (res.isErr()) {
            return res.castError();
        }
        ExecResult exec = okVal(res);
        if (exec.exitCode() != 0) {
            return Result.err(DomainError.of(
                "GIT_STASH_LIST_FAILED",
                "Failed to list stashes for Repository '" + repository.name() + "': " + exec.stderr().trim(),
                Map.of("repository", repository.name(), "exitCode", String.valueOf(exec.exitCode()))
            ));
        }
        return Result.ok(GitOutputParser.parseStashList(exec.stdout()));
    }

    @Override
    public Result<Optional<String>, DomainError> resolveDefaultBranch(RepositoryRef repository) {
        Result<ExecResult, DomainError> res = runGit(
            repository.root(),
            List.of("git", "symbolic-ref", "--quiet", "refs/remotes/origin/HEAD")
        );
        if (res.isErr()) {
            return res.castError();
        }
        ExecResult exec = okVal(res);
        if (exec.exitCode() != 0) {
            return Result.ok(Optional.empty());
        }
        return Result.ok(GitOutputParser.parseOriginHead(exec.stdout()));
    }

    private static Optional<Instant> readLocalFetchTimestamp(Path root, Path dotGit) {
        Optional<Path> gitDirOpt = resolveGitDir(root, dotGit);
        if (gitDirOpt.isEmpty()) {
            return Optional.empty();
        }
        Path gitDir = gitDirOpt.get();
        Path fetchHead = gitDir.resolve("FETCH_HEAD");
        if (Files.isRegularFile(fetchHead)) {
            Optional<Instant> mtime = fileMtime(fetchHead);
            if (mtime.isPresent()) {
                return mtime;
            }
        }
        Path head = gitDir.resolve("HEAD");
        if (Files.isRegularFile(head)) {
            return fileMtime(head);
        }
        return Optional.empty();
    }

    private static Optional<Path> resolveGitDir(Path root, Path dotGit) {
        if (Files.isDirectory(dotGit)) {
            return Optional.of(dotGit);
        }
        if (Files.isRegularFile(dotGit)) {
            try {
                String content = Files.readString(dotGit, StandardCharsets.UTF_8).trim();
                if (content.startsWith("gitdir:")) {
                    String rawPath = content.substring("gitdir:".length()).trim();
                    Path resolved = Path.of(rawPath);
                    return Optional.of(resolved.isAbsolute() ? resolved.normalize() : root.resolve(resolved).normalize());
                }
            } catch (IOException | InvalidPathException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<Instant> fileMtime(Path file) {
        try {
            return Optional.of(Files.getLastModifiedTime(file).toInstant());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean isDirectoryEmpty(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private record ExecResult(int exitCode, String stdout, String stderr) {}

    private static Result<ExecResult, DomainError> runGit(Path workingDir, List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "GIT_EXEC_ERROR",
                "Failed to execute git command " + command + ": " + e.getMessage(),
                Map.of("workingDir", workingDir.toString())
            ));
        }

        try {
            proc.getOutputStream().close();
        } catch (IOException ignored) {
            // Best-effort close stdin for non-interactive execution.
        }

        StringBuilder stdoutBuf = new StringBuilder();
        StringBuilder stderrBuf = new StringBuilder();
        Thread outThread = Thread.ofVirtual().start(() -> readAll(proc.getInputStream(), stdoutBuf));
        Thread errThread = Thread.ofVirtual().start(() -> readAll(proc.getErrorStream(), stderrBuf));

        try {
            boolean exited = proc.waitFor(LOCAL_READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                outThread.join(1000);
                errThread.join(1000);
                return Result.err(DomainError.of(
                    "GIT_TIMEOUT",
                    "Local git command timed out after " + LOCAL_READ_TIMEOUT.toSeconds() + "s: " + command
                ));
            }
            outThread.join(5000);
            errThread.join(5000);
            return Result.ok(new ExecResult(proc.exitValue(), stdoutBuf.toString(), stderrBuf.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            return Result.err(DomainError.of("GIT_INTERRUPTED", "Interrupted while running git command"));
        }
    }

    private static void readAll(InputStream in, StringBuilder target) {
        try (InputStream stream = in) {
            target.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Stream closed on termination.
        }
    }

    private static <T> T okVal(Result<T, DomainError> res) {
        return ((Result.Ok<T, DomainError>) res).value();
    }
}
