package procomp.core.project;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Local Git checkout root (with canonical basename name and optional remote URL) shared by one or more Services.
 */
public record RepositoryRef(String name, Path root, Optional<String> remoteUrl) {

    public RepositoryRef(Path root, Optional<String> remoteUrl) {
        this(deriveName(root), root, remoteUrl);
    }

    public RepositoryRef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("RepositoryRef name must be non-blank");
        }
        Objects.requireNonNull(root, "RepositoryRef root must not be null");
        Objects.requireNonNull(remoteUrl, "RepositoryRef remoteUrl must not be null");
    }

    public static String deriveName(Path root) {
        Objects.requireNonNull(root, "RepositoryRef root must not be null");
        Path normalized = root.normalize();
        Path fileName = normalized.getFileName();
        if (fileName == null || fileName.toString().isBlank()) {
            throw new IllegalArgumentException("Repository root must have a directory basename: " + root);
        }
        return fileName.toString();
    }
}
