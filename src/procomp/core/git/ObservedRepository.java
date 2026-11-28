package procomp.core.git;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Raw observation of a configured Repository directory on disk.
 */
public sealed interface ObservedRepository
    permits ObservedRepository.NotCloned, ObservedRepository.NotGitRepository, ObservedRepository.GitRepository {

    record NotCloned() implements ObservedRepository {}

    record NotGitRepository() implements ObservedRepository {}

    record GitRepository(Path hooksDir, Map<String, ObservedHookFile> hooks) implements ObservedRepository {
        public GitRepository {
            Objects.requireNonNull(hooksDir, "hooksDir must not be null");
            hooks = Collections.unmodifiableMap(new LinkedHashMap<>(hooks));
        }
    }
}
