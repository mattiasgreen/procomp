package e2e.scenarios.engine;

import java.util.Objects;

/**
 * Captured HTTP response from probing the Gateway or a Service endpoint.
 */
public record HttpProbeResult(int statusCode, String body) {

    public HttpProbeResult {
        Objects.requireNonNull(body, "body must not be null");
    }

    public boolean is2xx() {
        return statusCode >= 200 && statusCode < 300;
    }

    public boolean is5xx() {
        return statusCode >= 500 && statusCode < 600;
    }
}
