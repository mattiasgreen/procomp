package procomp.core.process;

import java.util.Objects;

/**
 * Observed outcome of probing a live Process's configured health mechanism.
 */
public sealed interface HealthProbe
    permits HealthProbe.None, HealthProbe.HttpResult, HealthProbe.HttpFailure, HealthProbe.TcpResult {

    record None() implements HealthProbe {
        public static final None INSTANCE = new None();
    }

    record HttpResult(int statusCode, String summary) implements HealthProbe {
        public HttpResult {
            summary = summary == null ? "" : summary;
        }

        public boolean is2xx() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    record HttpFailure(String detail) implements HealthProbe {
        public HttpFailure {
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }

    record TcpResult(boolean listening) implements HealthProbe {}
}
