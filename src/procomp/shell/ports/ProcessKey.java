package procomp.shell.ports;

import java.util.Objects;

/**
 * Identifies the target owning a ManagedProcessRecord or raw log file within a Project.
 */
public sealed interface ProcessKey permits ProcessKey.Service, ProcessKey.Gateway, ProcessKey.Build {

    record Service(String serviceName) implements ProcessKey {
        public Service {
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalArgumentException("serviceName must be non-blank");
            }
        }
    }

    record Gateway() implements ProcessKey {
        public static final Gateway INSTANCE = new Gateway();
    }

    record Build(String serviceName) implements ProcessKey {
        public Build {
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalArgumentException("serviceName must be non-blank");
            }
        }
    }

    static ProcessKey service(String serviceName) {
        return new Service(Objects.requireNonNull(serviceName));
    }

    static ProcessKey gateway() {
        return Gateway.INSTANCE;
    }

    static ProcessKey build(String serviceName) {
        return new Build(Objects.requireNonNull(serviceName));
    }
}
