package procomp.core.git;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Raw filesystem observation of a single target Git Hook file inside a Repository's hooks directory.
 */
public sealed interface ObservedHookFile
    permits ObservedHookFile.Missing,
        ObservedHookFile.RegularFile,
        ObservedHookFile.Symlink,
        ObservedHookFile.Unreadable {

    record Missing() implements ObservedHookFile {}

    record RegularFile(String contentDigest, boolean executable) implements ObservedHookFile {
        public RegularFile {
            if (contentDigest == null || contentDigest.isBlank()) {
                throw new IllegalArgumentException("contentDigest must be non-blank");
            }
        }
    }

    record Symlink(
        Path target,
        Optional<String> contentDigest,
        boolean executable
    ) implements ObservedHookFile {
        public Symlink {
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(contentDigest, "contentDigest must not be null");
        }
    }

    record Unreadable() implements ObservedHookFile {}
}
