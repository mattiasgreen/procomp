package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.HttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@link HttpClient} adapter backed by {@link java.net.http.HttpClient}.
 */
public final class JdkHttpClient implements HttpClient {

    private final java.net.http.HttpClient delegate;

    public JdkHttpClient() {
        this.delegate = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public Result<HttpResponseData, DomainError> get(URI uri, Duration timeout) {
        Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        return send(new HttpRequestData("GET", uri, Map.of(), "", timeout));
    }

    @Override
    public Result<HttpResponseData, DomainError> send(HttpRequestData request) {
        Objects.requireNonNull(request, "request must not be null");
        String method = request.method().trim().toUpperCase(Locale.ROOT);
        HttpRequest.BodyPublisher publisher = ("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method))
            && request.body().isEmpty()
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8);

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(request.timeout())
                .method(method, publisher);
            for (Map.Entry<String, String> header : request.headers().entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            HttpResponse<String> response = delegate.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            return Result.ok(new HttpResponseData(
                response.statusCode(),
                response.headers().map(),
                response.body()
            ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(DomainError.of(
                "HTTP_REQUEST_INTERRUPTED",
                "HTTP " + method + " request to " + request.uri() + " was interrupted",
                Map.of("uri", request.uri().toString(), "method", method)
            ));
        } catch (IOException | IllegalArgumentException e) {
            return Result.err(DomainError.of(
                "HTTP_CONNECT_ERROR",
                "HTTP " + method + " to " + request.uri() + " failed: " + describeException(e),
                Map.of("uri", request.uri().toString(), "method", method)
            ));
        }
    }

    private static String describeException(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }
}
