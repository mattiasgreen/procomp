package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP client port used for health checks and WireMock Gateway Admin API requests.
 */
public interface HttpClient {

    Result<HttpResponseData, DomainError> get(URI uri, Duration timeout);

    Result<HttpResponseData, DomainError> send(HttpRequestData request);

    record HttpRequestData(
        String method,
        URI uri,
        Map<String, String> headers,
        String body,
        Duration timeout
    ) {
        public HttpRequestData {
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("HTTP method must be non-blank");
            }
            Objects.requireNonNull(uri, "uri must not be null");
            headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            body = body == null ? "" : body;
            Objects.requireNonNull(timeout, "timeout must not be null");
        }
    }

    record HttpResponseData(int statusCode, Map<String, List<String>> headers, String body) {
        public HttpResponseData {
            headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            body = body == null ? "" : body;
        }

        public boolean is2xx() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
