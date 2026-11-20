package procomp.core.project;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Local Git checkout root (and optional remote URL) shared by one or more Services.
 */
public record RepositoryRef(Path root, Optional<String> remoteUrl) {

    public RepositoryRef {
        Objects.requireNonNull(root, "RepositoryRef root must not be null");
        Objects.requireNonNull(remoteUrl, "RepositoryRef remoteUrl must not be null");
    }
}
