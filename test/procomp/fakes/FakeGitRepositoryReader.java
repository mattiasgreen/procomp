package procomp.fakes;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.git.BranchInfo;
import procomp.core.git.RepositoryObservation;
import procomp.core.git.StashEntry;
import procomp.core.project.RepositoryRef;
import procomp.shell.ports.GitRepositoryReader;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FakeGitRepositoryReader implements GitRepositoryReader {

    private final Map<Path, RepositoryObservation> observations = new LinkedHashMap<>();
    private final Map<Path, List<BranchInfo>> branches = new LinkedHashMap<>();
    private final Map<Path, List<StashEntry>> stashes = new LinkedHashMap<>();
    private final Map<Path, Optional<String>> defaultBranches = new LinkedHashMap<>();

    public synchronized FakeGitRepositoryReader putObservation(RepositoryObservation obs) {
        observations.put(obs.repository().root(), obs);
        return this;
    }

    public synchronized FakeGitRepositoryReader putBranches(Path root, List<BranchInfo> list) {
        branches.put(root, List.copyOf(list));
        return this;
    }

    public synchronized FakeGitRepositoryReader putStashes(Path root, List<StashEntry> list) {
        stashes.put(root, List.copyOf(list));
        return this;
    }

    public synchronized FakeGitRepositoryReader putDefaultBranch(Path root, Optional<String> branch) {
        defaultBranches.put(root, branch);
        return this;
    }

    @Override
    public synchronized Result<RepositoryObservation, DomainError> observe(RepositoryRef repository) {
        RepositoryObservation obs = observations.get(repository.root());
        if (obs == null) {
            return Result.ok(new RepositoryObservation.NotCloned(repository));
        }
        return Result.ok(obs);
    }

    @Override
    public synchronized Result<List<BranchInfo>, DomainError> listBranches(RepositoryRef repository) {
        return Result.ok(branches.getOrDefault(repository.root(), List.of()));
    }

    @Override
    public synchronized Result<List<StashEntry>, DomainError> listStashes(RepositoryRef repository) {
        return Result.ok(stashes.getOrDefault(repository.root(), List.of()));
    }

    @Override
    public synchronized Result<Optional<String>, DomainError> resolveDefaultBranch(RepositoryRef repository) {
        return Result.ok(defaultBranches.getOrDefault(repository.root(), Optional.empty()));
    }
}
