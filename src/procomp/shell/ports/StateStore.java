package procomp.shell.ports;

import procomp.core.build.BuildRunRecord;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.git.GitRunRecord;
import procomp.core.process.ManagedProcessRecord;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence port for project-scoped routing table, managed process metadata, build runs, and raw logs.
 */
public interface StateStore {

    Path projectStateDir(String project);

    Result<Optional<Map<String, String>>, DomainError> readRouting(String project);

    Result<Unit, DomainError> writeRouting(String project, Map<String, String> routes);

    Result<Optional<ManagedProcessRecord>, DomainError> readManagedProcess(String project, ProcessKey key);

    Result<Unit, DomainError> writeManagedProcess(String project, ProcessKey key, ManagedProcessRecord record);

    Result<Unit, DomainError> deleteManagedProcess(String project, ProcessKey key);

    Result<Optional<BuildRunRecord>, DomainError> readBuildRun(String project, String serviceName);

    Result<Unit, DomainError> writeBuildRun(String project, String serviceName, BuildRunRecord record);

    Result<Optional<GitRunRecord>, DomainError> readGitRun(String project, String repositoryName);

    Result<Unit, DomainError> writeGitRun(String project, String repositoryName, GitRunRecord record);

    Path logPath(String project, ProcessKey key, boolean stderr);

    Result<String, DomainError> readLogText(Path logPath);

    Result<LogChunk, DomainError> readLogChunk(Path logPath, long byteOffset);
}
