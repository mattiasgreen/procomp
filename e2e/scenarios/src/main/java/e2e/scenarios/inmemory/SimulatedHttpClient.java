package e2e.scenarios.inmemory;

import e2e.scenarios.engine.HttpProbeResult;
import e2e.scenarios.inmemory.InMemoryConfigSource.EstateLayout;
import e2e.scenarios.inmemory.InMemoryConfigSource.ServiceSpec;
import e2e.scenarios.inmemory.VirtualOsState.ProcessKind;
import e2e.scenarios.inmemory.VirtualOsState.VirtualProcess;
import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.shell.ports.HttpClient;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Stateful in-memory {@link HttpClient} handling:
 * <ul>
 *   <li>Service HTTP health checks ({@code /actuator/health})</li>
 *   <li>Faithful WireMock Admin API ({@code /__admin/health}, {@code /__admin/mappings}, {@code /__admin/mappings/import}, {@code DELETE /__admin/mappings/{id}})</li>
 *   <li>Simulated Gateway reverse-proxying to LOCAL and REMOTE targets (failing fast with HTTP 502 when a LOCAL target is stopped)</li>
 * </ul>
 */
public final class SimulatedHttpClient implements HttpClient {

    private final VirtualOsState os;
    private final Supplier<EstateLayout> layoutSupplier;
    private final Map<String, JsonValue.JsonObject> wireMockMappings = new LinkedHashMap<>();
    private final Map<String, NotificationRecord> notificationsByOrderId = new LinkedHashMap<>();
    private int orderSequence = 0;

    private record NotificationRecord(String orderId, String pricedBy, int price) {}

    SimulatedHttpClient(VirtualOsState os, Supplier<EstateLayout> layoutSupplier) {
        this.os = Objects.requireNonNull(os, "os must not be null");
        this.layoutSupplier = Objects.requireNonNull(layoutSupplier, "layoutSupplier must not be null");
        this.os.setOnGatewayStateChanged(this::clearWireMockMappings);
    }

    void reset() {
        wireMockMappings.clear();
        notificationsByOrderId.clear();
        orderSequence = 0;
    }

    void clearWireMockMappings() {
        wireMockMappings.clear();
    }

    public HttpProbeResult probeGateway(String method, String path) {
        EstateLayout layout = layoutSupplier.get();
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        URI uri = URI.create("http://127.0.0.1:" + layout.gatewayPort() + normalizedPath);
        Result<HttpResponseData, DomainError> res = send(
            new HttpRequestData(method, uri, Map.of(), "", Duration.ofSeconds(5))
        );
        return switch (res) {
            case Result.Ok<HttpResponseData, DomainError>(HttpResponseData resp) ->
                new HttpProbeResult(resp.statusCode(), resp.body());
            case Result.Err<HttpResponseData, DomainError>(DomainError err) ->
                new HttpProbeResult(503, "{\"error\":\"" + escapeJson(err.message()) + "\"}");
        };
    }

    @Override
    public Result<HttpResponseData, DomainError> get(URI uri, Duration timeout) {
        return send(new HttpRequestData("GET", uri, Map.of(), "", timeout));
    }

    @Override
    public Result<HttpResponseData, DomainError> send(HttpRequestData request) {
        Objects.requireNonNull(request, "request must not be null");
        EstateLayout layout = layoutSupplier.get();
        int port = request.uri().getPort();
        String path = request.uri().getRawPath() == null || request.uri().getRawPath().isEmpty()
            ? "/"
            : request.uri().getRawPath();

        if (port == layout.gatewayPort()) {
            return handleGatewayRequest(layout, request.method(), path, request.body());
        }
        if (port == layout.remotePort()) {
            return Result.ok(handleRemoteRequest(layout, request.method(), path));
        }
        return handleLocalServiceRequest(layout, port, request.method(), path);
    }

    private Result<HttpResponseData, DomainError> handleGatewayRequest(
        EstateLayout layout,
        String method,
        String path,
        String body
    ) {
        Optional<VirtualProcess> listenerOpt = os.aliveListenerOnPort(layout.gatewayPort());
        if (listenerOpt.isEmpty()) {
            return Result.err(DomainError.of(
                "HTTP_CONNECTION_REFUSED",
                "Connection refused to Gateway on port " + layout.gatewayPort()
            ));
        }
        VirtualProcess listener = listenerOpt.get();
        if (listener.kind == ProcessKind.CONFLICT) {
            return Result.ok(jsonResponse(404, "{\"error\":\"Not Found\"}"));
        }

        if ("/__admin/health".equals(path) && "GET".equalsIgnoreCase(method)) {
            return Result.ok(jsonResponse(200, "{\"status\":\"healthy\"}"));
        }
        if ("/__admin/mappings".equals(path) && "GET".equalsIgnoreCase(method)) {
            return Result.ok(jsonResponse(200, buildMappingsListJson()));
        }
        if ("/__admin/mappings/import".equals(path) && "POST".equalsIgnoreCase(method)) {
            return Result.ok(handleImportMappings(body));
        }
        if ("/__admin/mappings".equals(path) && "POST".equalsIgnoreCase(method)) {
            return Result.ok(handleCreateSingleMapping(body));
        }
        if (path.startsWith("/__admin/mappings/") && "DELETE".equalsIgnoreCase(method)) {
            String rawId = path.substring("/__admin/mappings/".length());
            String decodedId = URLDecoder.decode(rawId, StandardCharsets.UTF_8);
            boolean removed = wireMockMappings.remove(decodedId) != null;
            return Result.ok(jsonResponse(removed ? 200 : 404, "{}"));
        }

        return Result.ok(proxyThroughGateway(layout, method, path));
    }

    private String buildMappingsListJson() {
        List<JsonValue> list = new ArrayList<>(wireMockMappings.values());
        Map<String, JsonValue> meta = Map.of("total", Json.num(list.size()));
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("mappings", Json.arr(list));
        root.put("meta", Json.obj(meta));
        return Json.stringify(Json.obj(root));
    }

    private HttpResponseData handleImportMappings(String body) {
        Result<JsonValue, DomainError> parsed = Json.parse(body);
        if (!(parsed instanceof Result.Ok<JsonValue, DomainError>(JsonValue rootVal))
            || !(rootVal instanceof JsonValue.JsonObject rootObj)) {
            return jsonResponse(400, "{\"error\":\"Invalid JSON import payload\"}");
        }
        Optional<JsonValue> mappingsField = rootObj.get("mappings");
        if (mappingsField.isEmpty() || !(mappingsField.get() instanceof JsonValue.JsonArray(List<JsonValue> elements))) {
            return jsonResponse(400, "{\"error\":\"Missing mappings array\"}");
        }
        for (JsonValue elem : elements) {
            if (elem instanceof JsonValue.JsonObject mappingObj) {
                extractMappingId(mappingObj).ifPresent(id -> wireMockMappings.put(id, mappingObj));
            }
        }
        return jsonResponse(200, "{}");
    }

    private HttpResponseData handleCreateSingleMapping(String body) {
        Result<JsonValue, DomainError> parsed = Json.parse(body);
        if (!(parsed instanceof Result.Ok<JsonValue, DomainError>(JsonValue rootVal))
            || !(rootVal instanceof JsonValue.JsonObject mappingObj)) {
            return jsonResponse(400, "{\"error\":\"Invalid JSON mapping payload\"}");
        }
        Optional<String> idOpt = extractMappingId(mappingObj);
        if (idOpt.isEmpty()) {
            return jsonResponse(400, "{\"error\":\"Missing mapping id\"}");
        }
        wireMockMappings.put(idOpt.get(), mappingObj);
        return jsonResponse(201, Json.stringify(mappingObj));
    }

    private HttpResponseData proxyThroughGateway(EstateLayout layout, String method, String path) {
        JsonValue.JsonObject matched = findMatchingMapping(path);
        if (matched == null) {
            return jsonResponse(404, "{\"error\":\"No Gateway mapping matched\",\"path\":\"" + escapeJson(path) + "\"}");
        }

        Optional<JsonValue.JsonObject> respObj = matched.get("response")
            .filter(JsonValue.JsonObject.class::isInstance)
            .map(JsonValue.JsonObject.class::cast);
        Optional<String> proxyBaseUrlOpt = respObj.flatMap(r -> stringField(r, "proxyBaseUrl"));
        if (proxyBaseUrlOpt.isEmpty()) {
            return jsonResponse(500, "{\"error\":\"Matched mapping missing proxyBaseUrl\"}");
        }

        String proxyBaseUrl = stripTrailingSlash(proxyBaseUrlOpt.get());
        String forwardedPath = path;
        Optional<String> prefixToRemove = respObj.flatMap(r -> stringField(r, "proxyUrlPrefixToRemove"));
        if (prefixToRemove.isPresent() && forwardedPath.startsWith(prefixToRemove.get())) {
            forwardedPath = forwardedPath.substring(prefixToRemove.get().length());
            if (forwardedPath.isEmpty()) {
                forwardedPath = "/";
            }
        }

        URI targetUri = URI.create(proxyBaseUrl + forwardedPath);
        int targetPort = targetUri.getPort();
        String targetPath = targetUri.getRawPath() == null || targetUri.getRawPath().isEmpty()
            ? "/"
            : targetUri.getRawPath();

        if (targetPort == layout.remotePort()) {
            return handleRemoteRequest(layout, method, targetPath);
        }

        // LOCAL target: fail fast with HTTP 502 if stopped (never fall back to REMOTE!)
        Optional<VirtualProcess> targetProc = os.aliveListenerOnPort(targetPort);
        if (targetProc.isEmpty() || targetProc.get().kind == ProcessKind.CONFLICT) {
            return jsonResponse(
                502,
                "{\"error\":\"Bad Gateway\",\"reason\":\"LOCAL target not running\",\"target\":\""
                    + escapeJson(targetUri.toString()) + "\"}"
            );
        }

        Result<HttpResponseData, DomainError> localRes = handleLocalServiceRequest(layout, targetPort, method, targetPath);
        return switch (localRes) {
            case Result.Ok<HttpResponseData, DomainError>(HttpResponseData resp) -> resp;
            case Result.Err<HttpResponseData, DomainError>(DomainError err) ->
                jsonResponse(502, "{\"error\":\"Bad Gateway\",\"reason\":\"" + escapeJson(err.message()) + "\"}");
        };
    }

    private JsonValue.JsonObject findMatchingMapping(String path) {
        for (JsonValue.JsonObject mapping : wireMockMappings.values()) {
            Optional<String> pattern = mapping.get("request")
                .filter(JsonValue.JsonObject.class::isInstance)
                .map(JsonValue.JsonObject.class::cast)
                .flatMap(req -> stringField(req, "urlPathPattern"));
            if (pattern.isPresent() && Pattern.matches(pattern.get(), path)) {
                return mapping;
            }
        }
        return null;
    }

    private Result<HttpResponseData, DomainError> handleLocalServiceRequest(
        EstateLayout layout,
        int port,
        String method,
        String path
    ) {
        Optional<VirtualProcess> listenerOpt = os.aliveListenerOnPort(port);
        if (listenerOpt.isEmpty()) {
            return Result.err(DomainError.of(
                "HTTP_CONNECTION_REFUSED",
                "Connection refused on port " + port
            ));
        }
        VirtualProcess listener = listenerOpt.get();
        if (listener.kind == ProcessKind.CONFLICT) {
            return Result.ok(jsonResponse(404, "{\"error\":\"Not Found\"}"));
        }
        if ("/actuator/health".equals(path) && "GET".equalsIgnoreCase(method)) {
            return Result.ok(jsonResponse(200, "{\"status\":\"UP\"}"));
        }

        String serviceName = layout.serviceByPort(port)
            .map(ServiceSpec::name)
            .orElse(listener.name);

        return Result.ok(dispatchLocalService(serviceName, method, path));
    }

    private HttpResponseData dispatchLocalService(String serviceName, String method, String path) {
        if ("pricing".equals(serviceName) && "GET".equalsIgnoreCase(method) && path.endsWith("/quote")) {
            return jsonResponse(200, "{\"price\":42,\"servedBy\":\"local-pricing\"}");
        }
        if ("orders".equals(serviceName) && "POST".equalsIgnoreCase(method) && path.endsWith("/create")) {
            HttpProbeResult quote = probeGateway("GET", "/pricing/quote");
            if (!quote.is2xx()) {
                return jsonResponse(502, "{\"error\":\"Upstream pricing failed\",\"status\":" + quote.statusCode() + "}");
            }
            int price = extractIntField(quote.body(), "price").orElse(0);
            String pricedBy = extractStringField(quote.body(), "servedBy").orElse("unknown");
            String orderId = "ord-" + (++orderSequence);
            if (os.isServiceAlive("broker") && os.isServiceAlive("notifications")) {
                notificationsByOrderId.put(orderId, new NotificationRecord(orderId, pricedBy, price));
            }
            return jsonResponse(
                200,
                "{\"orderId\":\"" + orderId + "\",\"servedBy\":\"local-orders\",\"pricedBy\":\""
                    + escapeJson(pricedBy) + "\",\"price\":" + price + "}"
            );
        }
        if ("notifications".equals(serviceName) && "GET".equalsIgnoreCase(method)) {
            String orderId = path.startsWith("/") ? path.substring(1) : path;
            NotificationRecord record = notificationsByOrderId.get(orderId);
            if (record == null) {
                return jsonResponse(404, "{\"error\":\"Notification not found\",\"orderId\":\"" + escapeJson(orderId) + "\"}");
            }
            return jsonResponse(
                200,
                "{\"orderId\":\"" + record.orderId() + "\",\"servedBy\":\"local-notifications\",\"pricedBy\":\""
                    + escapeJson(record.pricedBy()) + "\",\"price\":" + record.price() + "}"
            );
        }
        return jsonResponse(200, "{\"service\":\"" + escapeJson(serviceName) + "\",\"servedBy\":\"local-" + escapeJson(serviceName) + "\"}");
    }

    private HttpResponseData handleRemoteRequest(EstateLayout layout, String method, String path) {
        if ("/pricing/quote".equals(path) && "GET".equalsIgnoreCase(method)) {
            return jsonResponse(200, "{\"price\":99,\"servedBy\":\"remote-wiremock-pricing\"}");
        }
        if ("/orders/create".equals(path) && "POST".equalsIgnoreCase(method)) {
            String orderId = "remote-ord-" + (++orderSequence);
            return jsonResponse(
                200,
                "{\"orderId\":\"" + orderId + "\",\"servedBy\":\"remote-wiremock-orders\",\"pricedBy\":\"remote-wiremock-pricing\",\"price\":99}"
            );
        }
        for (String serviceName : layout.services().keySet()) {
            if (path.equals("/" + serviceName) || path.startsWith("/" + serviceName + "/")) {
                return jsonResponse(
                    200,
                    "{\"service\":\"" + escapeJson(serviceName) + "\",\"servedBy\":\"remote-" + escapeJson(serviceName) + "\"}"
                );
            }
        }
        return jsonResponse(404, "{\"error\":\"Remote stub not found\",\"path\":\"" + escapeJson(path) + "\"}");
    }

    private static Optional<String> extractMappingId(JsonValue.JsonObject obj) {
        return stringField(obj, "id").or(() -> stringField(obj, "uuid"));
    }

    private static Optional<String> stringField(JsonValue.JsonObject obj, String key) {
        return obj.get(key)
            .filter(JsonValue.JsonString.class::isInstance)
            .map(v -> ((JsonValue.JsonString) v).value())
            .filter(s -> !s.isBlank());
    }

    private static Optional<String> extractStringField(String json, String key) {
        Result<JsonValue, DomainError> parsed = Json.parse(json);
        if (parsed instanceof Result.Ok<JsonValue, DomainError>(JsonValue val)
            && val instanceof JsonValue.JsonObject obj) {
            return stringField(obj, key);
        }
        return Optional.empty();
    }

    private static Optional<Integer> extractIntField(String json, String key) {
        Result<JsonValue, DomainError> parsed = Json.parse(json);
        if (parsed instanceof Result.Ok<JsonValue, DomainError>(JsonValue val)
            && val instanceof JsonValue.JsonObject obj) {
            return obj.get(key)
                .filter(JsonValue.JsonNumber.class::isInstance)
                .flatMap(v -> ((JsonValue.JsonNumber) v).asInt().stream().boxed().findFirst());
        }
        return Optional.empty();
    }

    private static HttpResponseData jsonResponse(int statusCode, String body) {
        return new HttpResponseData(
            statusCode,
            Map.of("Content-Type", List.of("application/json")),
            body
        );
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url.trim();
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
