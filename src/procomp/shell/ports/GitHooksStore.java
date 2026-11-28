package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.git.ObservedRepository;
import procomp.core.project.GitHookInstallMode;

import java.nio.file.Path;
import java.util.List;

/**
 * Narrow I/O port for inspecting and installing mandatory Git hooks across Repositories.
 */
public interface GitHooksStore {

    /**
     * Lists regular non-hidden hook file names directly under {@code sourceDir}, sorted alphabetically.
     */
    Result<List<String>, DomainError> listSourceHooks(Path sourceDir);

    /**
     * Computes the SHA-256 hex digest of a mandatory hook source file.
     */
    Result<String, DomainError> readSourceDigest(Path sourcePath);

    /**
     * Inspects a Repository root directory and observes the requested hook names in its Git hooks directory.
     */
    Result<ObservedRepository, DomainError> inspectRepository(Path repositoryRoot, List<String> hookNames);

    /**
     * Installs or upgrades a single hook file from {@code sourcePath} to {@code targetPath}.
     */
    Result<Unit, DomainError> installHook(Path sourcePath, Path targetPath, GitHookInstallMode mode);

    /**
     * Returns {@code true} when the underlying filesystem enforces POSIX executable permission bits.
     */
    boolean supportsPosixExecutableBit();
}
