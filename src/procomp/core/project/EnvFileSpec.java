package procomp.core.project;

import java.util.Objects;

/**
 * Specification for an explicit environment file loaded into Process environments.
 */
public record EnvFileSpec(String path, boolean optional) {

    public EnvFileSpec {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("EnvFileSpec path must be non-blank");
        }
        Objects.requireNonNull(path);
    }
}
