package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.git.BranchInfo;
import procomp.core.git.RepositoryObservation;
import procomp.core.git.StashEntry;
import procomp.core.project.RepositoryRef;

import java.util.List;
import java.util.Optional;

/**
 * Narrow read-only port for local, zero-network Git {@code Repository} observation, branch listing,
 * stash listing, and {@code origin/HEAD} resolution.
 */
public interface GitRepositoryReader {

    Result<RepositoryObservation, DomainError> observe(RepositoryRef repository);

    Result<List<BranchInfo>, DomainError> listBranches(RepositoryRef repository);

    Result<List<StashEntry>, DomainError> listStashes(RepositoryRef repository);

    Result<Optional<String>, DomainError> resolveDefaultBranch(RepositoryRef repository);
}
