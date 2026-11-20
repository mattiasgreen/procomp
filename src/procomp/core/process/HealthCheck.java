package procomp.core.process;

import java.util.Objects;

/**
 * Observed outcome of checking a live Process's configured health mechanism.
 */
public sealed interface HealthCheck
    permits HealthCheck.None, HealthCheck.HttpResult, HealthCheck.HttpFailure, HealthCheck.TcpResult {

    record None() implements HealthCheck {
        public static final None INSTANCE = new None();
    }

    record HttpResult(int statusCode, String summary) implements HealthCheck {
        public HttpResult {
            summary = summary == null ? "" : summary;
        }

        public boolean is2xx() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    record HttpFailure(String detail) implements HealthCheck {
        public HttpFailure {
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }

    record TcpResult(boolean listening) implements HealthCheck {}
}
