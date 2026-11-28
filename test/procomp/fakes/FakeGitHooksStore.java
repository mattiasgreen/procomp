package procomp.fakes;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.git.ObservedHookFile;
import procomp.core.git.ObservedRepository;
import procomp.core.project.GitHookInstallMode;
import procomp.shell.ports.GitHooksStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic in-memory fake for {@link GitHooksStore} used in Level 2 shell, CLI, and web tests.
 */
public final class FakeGitHooksStore implements GitHooksStore {

    public record StoredHook(boolean symlink, Optional<Path> symlinkTarget, String content, boolean executable) {}

    private final Set<Path> sourceDirs = new LinkedHashSet<>();
    private final Map<Path, String> sourceFiles = new LinkedHashMap<>();
    private final Set<Path> nonGitDirs = new LinkedHashSet<>();
    private final Map<Path, Path> gitHooksDirs = new LinkedHashMap<>();
    private final Map<Path, StoredHook> installedHooks = new LinkedHashMap<>();
    private boolean posixExecutableBit = true;

    public FakeGitHooksStore withPosixExecutableBit(boolean enabled) {
        this.posixExecutableBit = enabled;
        return this;
    }

    public FakeGitHooksStore withSourceDir(Path sourceDir) {
        sourceDirs.add(norm(sourceDir));
        return this;
    }

    public FakeGitHooksStore withSourceFile(Path sourcePath, String content) {
        Path p = norm(sourcePath);
        sourceFiles.put(p, content);
        if (p.getParent() != null) {
            sourceDirs.add(p.getParent());
        }
        return this;
    }

    public FakeGitHooksStore withGitRepository(Path repoRoot) {
        Path normRoot = norm(repoRoot);
        nonGitDirs.remove(normRoot);
        gitHooksDirs.put(normRoot, normRoot.resolve(".git").resolve("hooks"));
        return this;
    }

    public FakeGitHooksStore withNonGitDirectory(Path repoRoot) {
        Path normRoot = norm(repoRoot);
        gitHooksDirs.remove(normRoot);
        nonGitDirs.add(normRoot);
        return this;
    }

    public FakeGitHooksStore withInstalledHook(Path repoRoot, String hookName, String content, boolean executable) {
        Path hooksDir = gitHooksDirs.computeIfAbsent(norm(repoRoot), r -> r.resolve(".git").resolve("hooks"));
        installedHooks.put(norm(hooksDir.resolve(hookName)), new StoredHook(false, Optional.empty(), content, executable));
        return this;
    }

    public FakeGitHooksStore withInstalledSymlink(
        Path repoRoot,
        String hookName,
        Path target,
        String content,
        boolean executable
    ) {
        Path hooksDir = gitHooksDirs.computeIfAbsent(norm(repoRoot), r -> r.resolve(".git").resolve("hooks"));
        installedHooks.put(norm(hooksDir.resolve(hookName)), new StoredHook(true, Optional.of(norm(target)), content, executable));
        return this;
    }

    public Optional<StoredHook> installedHook(Path repoRoot, String hookName) {
        Path hooksDir = gitHooksDirs.get(norm(repoRoot));
        if (hooksDir == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(installedHooks.get(norm(hooksDir.resolve(hookName))));
    }

    @Override
    public Result<List<String>, DomainError> listSourceHooks(Path sourceDir) {
        Path normDir = norm(sourceDir);
        if (!sourceDirs.contains(normDir)) {
            return Result.err(DomainError.of(
                "GIT_HOOK_SOURCE_NOT_FOUND",
                "Git hooks sourceDir not found: '" + normDir + "'",
                Map.of("sourceDir", normDir.toString())
            ));
        }
        List<String> names = new ArrayList<>();
        for (Path file : sourceFiles.keySet()) {
            if (normDir.equals(file.getParent())) {
                Path fn = file.getFileName();
                if (fn != null && !fn.toString().startsWith(".")) {
                    names.add(fn.toString());
                }
            }
        }
        names.sort(Comparator.naturalOrder());
        return Result.ok(List.copyOf(names));
    }

    @Override
    public Result<String, DomainError> readSourceDigest(Path sourcePath) {
        Path normPath = norm(sourcePath);
        String content = sourceFiles.get(normPath);
        if (content == null) {
            return Result.err(DomainError.of(
                "GIT_HOOK_SOURCE_NOT_FOUND",
                "Git hook source file not found: '" + normPath + "'",
                Map.of("sourcePath", normPath.toString())
            ));
        }
        return Result.ok(sha256Hex(content));
    }

    @Override
    public Result<ObservedRepository, DomainError> inspectRepository(Path repositoryRoot, List<String> hookNames) {
        Path normRoot = norm(repositoryRoot);
        if (nonGitDirs.contains(normRoot)) {
            return Result.ok(new ObservedRepository.NotGitRepository());
        }
        Path hooksDir = gitHooksDirs.get(normRoot);
        if (hooksDir == null) {
            return Result.ok(new ObservedRepository.NotCloned());
        }
        Map<String, ObservedHookFile> map = new LinkedHashMap<>();
        for (String name : hookNames) {
            StoredHook stored = installedHooks.get(norm(hooksDir.resolve(name)));
            if (stored == null) {
                map.put(name, new ObservedHookFile.Missing());
            } else if (stored.symlink()) {
                map.put(name, new ObservedHookFile.Symlink(
                    stored.symlinkTarget().orElseThrow(),
                    Optional.of(sha256Hex(stored.content())),
                    stored.executable()
                ));
            } else {
                map.put(name, new ObservedHookFile.RegularFile(
                    sha256Hex(stored.content()),
                    stored.executable()
                ));
            }
        }
        return Result.ok(new ObservedRepository.GitRepository(hooksDir, map));
    }

    @Override
    public Result<Unit, DomainError> installHook(Path sourcePath, Path targetPath, GitHookInstallMode mode) {
        Path normSource = norm(sourcePath);
        String content = sourceFiles.get(normSource);
        if (content == null) {
            return Result.err(DomainError.of(
                "GIT_HOOK_SOURCE_NOT_FOUND",
                "Git hook source file not found: '" + normSource + "'",
                Map.of("sourcePath", normSource.toString())
            ));
        }
        boolean isSymlink = mode == GitHookInstallMode.SYMLINK;
        Optional<Path> linkTarget = isSymlink ? Optional.of(normSource) : Optional.empty();
        installedHooks.put(norm(targetPath), new StoredHook(isSymlink, linkTarget, content, true));
        return Result.ok(Unit.VALUE);
    }

    @Override
    public boolean supportsPosixExecutableBit() {
        return posixExecutableBit;
    }

    private static Path norm(Path p) {
        return p.toAbsolutePath().normalize();
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
