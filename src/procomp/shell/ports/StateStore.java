package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence port for project-scoped routing table, managed process metadata, and raw logs.
 */
public interface StateStore {

    Path projectStateDir(String project);

    Result<Optional<Map<String, String>>, DomainError> readRouting(String project);

    Result<Unit, DomainError> writeRouting(String project, Map<String, String> routes);

    Result<Optional<ManagedProcessRecord>, DomainError> readManagedProcess(String project, ProcessKey key);

    Result<Unit, DomainError> writeManagedProcess(String project, ProcessKey key, ManagedProcessRecord record);

    Result<Unit, DomainError> deleteManagedProcess(String project, ProcessKey key);

    Result<Map<String, ManagedProcessRecord>, DomainError> listManagedServiceProcesses(String project);

    Path logPath(String project, ProcessKey key, boolean stderr);

    boolean logExists(Path logPath);

    Result<String, DomainError> readLogText(Path logPath);

    Result<LogChunk, DomainError> readLogChunk(Path logPath, long byteOffset);
}
