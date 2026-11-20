package procomp.fakes;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class FakeHttpClient implements HttpClient {

    private final Map<String, Function<HttpRequestData, Result<HttpResponseData, DomainError>>> routes = new LinkedHashMap<>();
    private final List<HttpRequestData> recordedRequests = new ArrayList<>();

    public FakeHttpClient stubGet(URI uri, int statusCode, String body) {
        routes.put("GET " + uri, _ -> Result.ok(new HttpResponseData(statusCode, Map.of(), body)));
        return this;
    }

    public FakeHttpClient stub(String method, URI uri, Function<HttpRequestData, Result<HttpResponseData, DomainError>> handler) {
        routes.put(method.toUpperCase() + " " + uri, handler);
        return this;
    }

    public FakeHttpClient remove(String method, URI uri) {
        routes.remove(method.toUpperCase() + " " + uri);
        return this;
    }

    public List<HttpRequestData> recordedRequests() {
        return List.copyOf(recordedRequests);
    }

    @Override
    public Result<HttpResponseData, DomainError> get(URI uri, Duration timeout) {
        return send(new HttpRequestData("GET", uri, Map.of(), "", timeout));
    }

    @Override
    public Result<HttpResponseData, DomainError> send(HttpRequestData request) {
        recordedRequests.add(request);
        String key = request.method().toUpperCase() + " " + request.uri();
        var handler = routes.get(key);
        if (handler == null) {
            return Result.err(DomainError.of(
                "HTTP_CONNECT_ERROR",
                "Connection refused to " + request.uri(),
                Map.of("uri", request.uri().toString())
            ));
        }
        return handler.apply(request);
    }
}
