package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.git.ObservedHookFile;
import procomp.core.git.ObservedRepository;
import procomp.core.project.GitHookInstallMode;
import procomp.shell.ports.GitHooksStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Cross-platform filesystem adapter for {@link GitHooksStore}.
 * Supports standard {@code .git/} directories as well as {@code .git} gitdir files (worktrees and submodules).
 */
public final class FsGitHooksStore implements GitHooksStore {

    private static final boolean POSIX_SUPPORTED =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @Override
    public Result<List<String>, DomainError> listSourceHooks(Path sourceDir) {
        Objects.requireNonNull(sourceDir, "sourceDir must not be null");
        Path normalized = sourceDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            return Result.err(DomainError.of(
                "GIT_HOOK_SOURCE_NOT_FOUND",
                "Git hooks sourceDir not found: '" + normalized + "'",
                Map.of("sourceDir", normalized.toString())
            ));
        }
        try (Stream<Path> entries = Files.list(normalized)) {
            List<String> names = new ArrayList<>();
            entries
                .filter(p -> Files.isRegularFile(p))
                .sorted(Comparator.comparing(p -> {
                    Path fn = p.getFileName();
                    return fn != null ? fn.toString() : p.toString();
                }))
                .forEach(p -> {
                    Path fn = p.getFileName();
                    if (fn != null) {
                        String name = fn.toString();
                        if (!name.startsWith(".")) {
                            names.add(name);
                        }
                    }
                });
            return Result.ok(List.copyOf(names));
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "GIT_HOOK_IO_ERROR",
                "Failed listing Git hooks in '" + normalized + "': " + e.getMessage(),
                Map.of("sourceDir", normalized.toString())
            ));
        }
    }

    @Override
    public Result<String, DomainError> readSourceDigest(Path sourcePath) {
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Path normalized = sourcePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            return Result.err(DomainError.of(
                "GIT_HOOK_SOURCE_NOT_FOUND",
                "Git hook source file not found: '" + normalized + "'",
                Map.of("sourcePath", normalized.toString())
            ));
        }
        try {
            return Result.ok(sha256Hex(Files.readAllBytes(normalized)));
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "GIT_HOOK_IO_ERROR",
                "Failed reading Git hook source file '" + normalized + "': " + e.getMessage(),
                Map.of("sourcePath", normalized.toString())
            ));
        }
    }

    @Override
    public Result<ObservedRepository, DomainError> inspectRepository(Path repositoryRoot, List<String> hookNames) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
        Objects.requireNonNull(hookNames, "hookNames must not be null");
        Path normalizedRoot = repositoryRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            return Result.ok(new ObservedRepository.NotCloned());
        }

        Optional<Path> gitDirOpt;
        try {
            gitDirOpt = resolveGitCommonDir(normalizedRoot);
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "GIT_HOOK_IO_ERROR",
                "Failed resolving Git metadata directory for Repository '" + normalizedRoot + "': " + e.getMessage(),
                Map.of("repository", normalizedRoot.toString())
            ));
        }
        if (gitDirOpt.isEmpty()) {
            return Result.ok(new ObservedRepository.NotGitRepository());
        }

        Path hooksDir = gitDirOpt.get().resolve("hooks").normalize();
        Map<String, ObservedHookFile> observed = new LinkedHashMap<>();
        for (String hookName : hookNames) {
            Path target = hooksDir.resolve(hookName);
            Result<ObservedHookFile, DomainError> obsRes = observeHookFile(target);
            if (obsRes.isErr()) {
                return obsRes.castError();
            }
            observed.put(hookName, ((Result.Ok<ObservedHookFile, DomainError>) obsRes).value());
        }
        return Result.ok(new ObservedRepository.GitRepository(hooksDir, observed));
    }

    @Override
    public Result<Unit, DomainError> installHook(Path sourcePath, Path targetPath, GitHookInstallMode mode) {
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(targetPath, "targetPath must not be null");
        Objects.requireNonNull(mode, "mode must not be null");

        Path normSource = sourcePath.toAbsolutePath().normalize();
        Path normTarget = targetPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normSource)) {
            return Result.err(DomainError.of(
                "GIT_HOOK_SOURCE_NOT_FOUND",
                "Git hook source file not found: '" + normSource + "'",
                Map.of("sourcePath", normSource.toString())
            ));
        }

        try {
            Path parent = normTarget.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (mode == GitHookInstallMode.SYMLINK) {
                Files.deleteIfExists(normTarget);
                Files.createSymbolicLink(normTarget, normSource);
                if (POSIX_SUPPORTED) {
                    ensurePosixExecutable(normSource);
                }
            } else {
                if (Files.isSymbolicLink(normTarget)) {
                    Files.deleteIfExists(normTarget);
                }
                Files.copy(normSource, normTarget, StandardCopyOption.REPLACE_EXISTING);
                if (POSIX_SUPPORTED) {
                    ensurePosixExecutable(normTarget);
                }
            }
            return Result.ok(Unit.VALUE);
        } catch (IOException | UnsupportedOperationException e) {
            return Result.err(DomainError.of(
                "GIT_HOOK_INSTALL_FAILED",
                "Failed installing Git hook '" + normTarget + "': " + e.getMessage(),
                Map.of("sourcePath", normSource.toString(), "targetPath", normTarget.toString())
            ));
        }
    }

    @Override
    public boolean supportsPosixExecutableBit() {
        return POSIX_SUPPORTED;
    }

    private static Optional<Path> resolveGitCommonDir(Path repoRoot) throws IOException {
        Path dotGit = repoRoot.resolve(".git");
        Path gitDir;
        if (Files.isDirectory(dotGit)) {
            gitDir = dotGit;
        } else if (Files.isRegularFile(dotGit)) {
            String firstLine = Files.readString(dotGit, StandardCharsets.UTF_8).trim();
            if (!firstLine.startsWith("gitdir:")) {
                return Optional.empty();
            }
            String rawTarget = firstLine.substring("gitdir:".length()).trim();
            if (rawTarget.isEmpty()) {
                return Optional.empty();
            }
            Path resolved = Path.of(rawTarget);
            gitDir = resolved.isAbsolute() ? resolved.normalize() : repoRoot.resolve(resolved).normalize();
            if (!Files.isDirectory(gitDir)) {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }

        Path commonDirFile = gitDir.resolve("commondir");
        if (Files.isRegularFile(commonDirFile)) {
            String rawCommon = Files.readString(commonDirFile, StandardCharsets.UTF_8).trim();
            if (!rawCommon.isEmpty()) {
                Path commonPath = Path.of(rawCommon);
                Path resolvedCommon = commonPath.isAbsolute()
                    ? commonPath.normalize()
                    : gitDir.resolve(commonPath).normalize();
                if (Files.isDirectory(resolvedCommon)) {
                    return Optional.of(resolvedCommon);
                }
            }
        }
        return Optional.of(gitDir);
    }

    private static Result<ObservedHookFile, DomainError> observeHookFile(Path target) {
        boolean isSymlink = Files.isSymbolicLink(target);
        boolean exists = isSymlink || Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (!exists) {
            return Result.ok(new ObservedHookFile.Missing());
        }

        if (isSymlink) {
            Path resolvedTarget;
            try {
                Path rawLink = Files.readSymbolicLink(target);
                Path parent = target.getParent();
                resolvedTarget = rawLink.isAbsolute() || parent == null
                    ? rawLink.normalize()
                    : parent.resolve(rawLink).normalize();
            } catch (IOException e) {
                return Result.err(DomainError.of(
                    "GIT_HOOK_IO_ERROR",
                    "Failed reading Git hook symlink '" + target + "': " + e.getMessage(),
                    Map.of("targetPath", target.toString())
                ));
            }
            Optional<String> digest = Optional.empty();
            boolean executable = false;
            if (Files.isRegularFile(target)) {
                try {
                    digest = Optional.of(sha256Hex(Files.readAllBytes(target)));
                    executable = isExecutable(target);
                } catch (IOException e) {
                    return Result.err(DomainError.of(
                        "GIT_HOOK_IO_ERROR",
                        "Failed reading Git hook '" + target + "': " + e.getMessage(),
                        Map.of("targetPath", target.toString())
                    ));
                }
            }
            return Result.ok(new ObservedHookFile.Symlink(resolvedTarget, digest, executable));
        }

        if (!Files.isRegularFile(target)) {
            return Result.ok(new ObservedHookFile.Unreadable());
        }
        try {
            String digest = sha256Hex(Files.readAllBytes(target));
            boolean executable = isExecutable(target);
            return Result.ok(new ObservedHookFile.RegularFile(digest, executable));
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "GIT_HOOK_IO_ERROR",
                "Failed reading Git hook '" + target + "': " + e.getMessage(),
                Map.of("targetPath", target.toString())
            ));
        }
    }

    private static boolean isExecutable(Path path) throws IOException {
        if (!POSIX_SUPPORTED) {
            return true;
        }
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
        return perms.contains(PosixFilePermission.OWNER_EXECUTE);
    }

    private static void ensurePosixExecutable(Path path) throws IOException {
        Set<PosixFilePermission> current = EnumSet.copyOf(Files.getPosixFilePermissions(path));
        current.add(PosixFilePermission.OWNER_EXECUTE);
        if (current.contains(PosixFilePermission.GROUP_READ)) {
            current.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if (current.contains(PosixFilePermission.OTHERS_READ)) {
            current.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        Files.setPosixFilePermissions(path, current);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
        }
    }
}
