package procomp.core.gateway;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.core.project.ServiceDefinition;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure domain functions for WireMock proxy mapping generation and Gateway drift detection.
 */
public final class GatewayEngine {

    public static final int DEFAULT_MAPPING_PRIORITY = 5;
    private static final String REGEX_META_CHARS = "\\.+*?^$()[]{}|";

    private GatewayEngine() {}

    /**
     * Builds the desired {@link GatewayPlan} for all Services in {@code project} according to {@code routing}.
     * Fails fast with zero silent fallback if a {@code LOCAL} Service lacks {@code port}
     * or a {@code REMOTE} Service lacks {@code remoteUrl}.
     */
    public static Result<GatewayPlan, DomainError> buildPlan(Project project, RoutingTable routing) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(routing, "routing must not be null");

        List<WireMockMapping> mappings = new ArrayList<>(project.services().size());
        for (ServiceDefinition service : project.services().values()) {
            Route route = routing.routeOf(service.name()).orElse(Route.REMOTE);
            Result<String, DomainError> targetRes = resolveTargetUrl(service, route);
            if (targetRes instanceof Result.Err<String, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            String targetUrl = ((Result.Ok<String, DomainError>) targetRes).value();
            String prefix = normalizePrefix(service.proxyPath());
            String urlPathPattern = toUrlPathPattern(prefix);
            Optional<String> prefixToRemove = "/".equals(prefix) ? Optional.empty() : Optional.of(prefix);

            mappings.add(new WireMockMapping(
                deterministicMappingId(project.name(), service.name()),
                "procomp-" + project.name() + "-" + service.name(),
                DEFAULT_MAPPING_PRIORITY,
                project.name(),
                service.name(),
                route,
                urlPathPattern,
                targetUrl,
                prefixToRemove
            ));
        }
        return Result.ok(new GatewayPlan(project.name(), mappings));
    }

    /**
     * Serializes a single {@link WireMockMapping} to WireMock Admin API JSON.
     */
    public static JsonValue.JsonObject toMappingJsonValue(WireMockMapping mapping) {
        Objects.requireNonNull(mapping, "mapping must not be null");

        Map<String, JsonValue> request = new LinkedHashMap<>();
        request.put("method", Json.str("ANY"));
        request.put("urlPathPattern", Json.str(mapping.urlPathPattern()));

        Map<String, JsonValue> response = new LinkedHashMap<>();
        response.put("proxyBaseUrl", Json.str(mapping.targetUrl()));
        mapping.proxyUrlPrefixToRemove().ifPresent(p -> response.put("proxyUrlPrefixToRemove", Json.str(p)));

        Map<String, JsonValue> metadata = new LinkedHashMap<>();
        metadata.put("procomp", Json.bool(true));
        metadata.put("procompProject", Json.str(mapping.projectName()));
        metadata.put("service", Json.str(mapping.service()));
        metadata.put("route", Json.str(mapping.route().name()));

        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("id", Json.str(mapping.id()));
        root.put("name", Json.str(mapping.name()));
        root.put("priority", Json.num(mapping.priority()));
        root.put("request", Json.obj(request));
        root.put("response", Json.obj(response));
        root.put("metadata", Json.obj(metadata));
        return Json.obj(root);
    }

    /**
     * Serializes a single {@link WireMockMapping} to compact JSON suitable for {@code POST /__admin/mappings}.
     */
    public static String toMappingJson(WireMockMapping mapping) {
        return Json.stringify(toMappingJsonValue(mapping));
    }

    /**
     * Serializes a {@link GatewayPlan} to WireMock {@code POST /__admin/mappings/import} JSON.
     */
    public static String toImportPayloadJson(GatewayPlan plan) {
        Objects.requireNonNull(plan, "plan must not be null");
        List<JsonValue> mappingNodes = new ArrayList<>(plan.mappings().size());
        for (WireMockMapping mapping : plan.mappings()) {
            mappingNodes.add(toMappingJsonValue(mapping));
        }

        Map<String, JsonValue> importOptions = new LinkedHashMap<>();
        importOptions.put("duplicatePolicy", Json.str("OVERWRITE"));
        importOptions.put("deleteAllNotInImport", Json.bool(false));

        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("mappings", Json.arr(mappingNodes));
        root.put("importOptions", Json.obj(importOptions));
        return Json.stringify(Json.obj(root));
    }

    /**
     * Parses WireMock {@code GET /__admin/mappings} JSON and returns all mapping IDs managed by
     * {@code procomp} for {@code project}.
     */
    public static Result<List<String>, DomainError> findManagedMappingIds(Project project, String adminMappingsJson) {
        Objects.requireNonNull(project, "project must not be null");
        Result<List<ManagedLiveMapping>, DomainError> parsed = parseManagedMappings(project, adminMappingsJson);
        if (parsed instanceof Result.Err<List<ManagedLiveMapping>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        List<String> ids = new ArrayList<>();
        for (ManagedLiveMapping m : ((Result.Ok<List<ManagedLiveMapping>, DomainError>) parsed).value()) {
            m.id().ifPresent(ids::add);
        }
        return Result.ok(List.copyOf(ids));
    }

    /**
     * Compares live WireMock mappings against {@code project} and {@code routing}.
     */
    public static Result<GatewayDriftReport, DomainError> computeDrift(
        Project project,
        RoutingTable routing,
        boolean gatewayHealthy,
        String adminMappingsJson
    ) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(routing, "routing must not be null");

        if (!gatewayHealthy) {
            return Result.ok(GatewayDriftReport.offline(project, routing));
        }

        Result<GatewayPlan, DomainError> planRes = buildPlan(project, routing);
        if (planRes instanceof Result.Err<GatewayPlan, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        GatewayPlan plan = ((Result.Ok<GatewayPlan, DomainError>) planRes).value();

        Result<List<ManagedLiveMapping>, DomainError> liveRes = parseManagedMappings(project, adminMappingsJson);
        if (liveRes instanceof Result.Err<List<ManagedLiveMapping>, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        List<ManagedLiveMapping> liveMappings = ((Result.Ok<List<ManagedLiveMapping>, DomainError>) liveRes).value();

        Map<String, List<ManagedLiveMapping>> byService = new LinkedHashMap<>();
        List<StaleManagedMapping> staleMappings = new ArrayList<>();
        for (ManagedLiveMapping live : liveMappings) {
            Optional<String> svcOpt = live.service();
            if (svcOpt.isEmpty() || project.service(svcOpt.get()).isEmpty()) {
                staleMappings.add(new StaleManagedMapping(live.id(), svcOpt, live.proxyBaseUrl()));
            } else {
                byService.computeIfAbsent(svcOpt.get(), _ -> new ArrayList<>()).add(live);
            }
        }

        List<RouteDrift> drifted = new ArrayList<>();
        Map<String, String> liveRoutes = new LinkedHashMap<>();
        for (WireMockMapping desired : plan.mappings()) {
            ServiceDefinition svc = project.service(desired.service()).orElseThrow();
            List<ManagedLiveMapping> actuals = byService.getOrDefault(desired.service(), List.of());
            String liveState = classifyServiceLiveState(svc, desired, actuals);
            liveRoutes.put(desired.service(), liveState);
            if (!desired.route().name().equals(liveState)) {
                drifted.add(new RouteDrift(desired.service(), desired.route(), liveState));
            }
        }

        GatewaySyncStatus status = drifted.isEmpty() && staleMappings.isEmpty()
            ? GatewaySyncStatus.IN_SYNC
            : GatewaySyncStatus.DRIFTED;
        return Result.ok(new GatewayDriftReport(status, drifted, staleMappings, liveRoutes));
    }

    private static String classifyServiceLiveState(
        ServiceDefinition svc,
        WireMockMapping desired,
        List<ManagedLiveMapping> actuals
    ) {
        if (actuals.isEmpty()) {
            return "UNSET";
        }
        if (actuals.size() > 1) {
            return "MISMATCH";
        }
        ManagedLiveMapping actual = actuals.get(0);
        if (matchesMappingSpec(
            actual,
            desired.urlPathPattern(),
            desired.targetUrl(),
            desired.proxyUrlPrefixToRemove(),
            desired.route()
        )) {
            return desired.route().name();
        }
        Route alternate = desired.route() == Route.LOCAL ? Route.REMOTE : Route.LOCAL;
        Result<String, DomainError> altTargetRes = resolveTargetUrl(svc, alternate);
        if (altTargetRes instanceof Result.Ok<String, DomainError>(String altTarget)
            && matchesMappingSpec(
                actual,
                desired.urlPathPattern(),
                altTarget,
                desired.proxyUrlPrefixToRemove(),
                alternate
            )) {
            return alternate.name();
        }
        return "MISMATCH";
    }

    private static boolean matchesMappingSpec(
        ManagedLiveMapping actual,
        String expectedUrlPathPattern,
        String expectedTargetUrl,
        Optional<String> expectedPrefixToRemove,
        Route expectedRoute
    ) {
        if (actual.method().isPresent() && !"ANY".equalsIgnoreCase(actual.method().get())) {
            return false;
        }
        if (!Optional.of(expectedUrlPathPattern).equals(actual.urlPathPattern())) {
            return false;
        }
        if (!Optional.of(expectedTargetUrl).equals(actual.proxyBaseUrl())) {
            return false;
        }
        if (!expectedPrefixToRemove.equals(actual.proxyUrlPrefixToRemove())) {
            return false;
        }
        if (actual.metadataRoute().isPresent() && !expectedRoute.name().equalsIgnoreCase(actual.metadataRoute().get())) {
            return false;
        }
        return true;
    }

    private static Result<List<ManagedLiveMapping>, DomainError> parseManagedMappings(Project project, String adminMappingsJson) {
        if (adminMappingsJson == null || adminMappingsJson.isBlank()) {
            return Result.err(DomainError.of(
                "GATEWAY_INVALID_MAPPINGS",
                "WireMock /__admin/mappings response body was empty"
            ));
        }
        Result<JsonValue, DomainError> parseRes = Json.parse(adminMappingsJson);
        if (parseRes instanceof Result.Err<JsonValue, DomainError>(DomainError err)) {
            return Result.err(DomainError.of(
                "GATEWAY_INVALID_MAPPINGS",
                "Failed to parse WireMock /__admin/mappings JSON: " + err.message(),
                err.details()
            ));
        }
        JsonValue rootVal = ((Result.Ok<JsonValue, DomainError>) parseRes).value();
        if (!(rootVal instanceof JsonValue.JsonObject rootObj)) {
            return Result.err(DomainError.of(
                "GATEWAY_INVALID_MAPPINGS",
                "Expected top-level JSON object from WireMock /__admin/mappings"
            ));
        }
        Optional<JsonValue> mappingsField = rootObj.get("mappings");
        if (mappingsField.isEmpty() || !(mappingsField.get() instanceof JsonValue.JsonArray(List<JsonValue> elements))) {
            return Result.err(DomainError.of(
                "GATEWAY_INVALID_MAPPINGS",
                "Missing or non-array 'mappings' field in WireMock /__admin/mappings response"
            ));
        }

        Map<String, String> serviceByDeterministicId = new LinkedHashMap<>();
        for (String svcName : project.services().keySet()) {
            serviceByDeterministicId.put(deterministicMappingId(project.name(), svcName), svcName);
        }

        List<ManagedLiveMapping> out = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            JsonValue elem = elements.get(i);
            if (!(elem instanceof JsonValue.JsonObject mObj)) {
                return Result.err(DomainError.of(
                    "GATEWAY_INVALID_MAPPINGS",
                    "Non-object entry at mappings[" + i + "] in WireMock /__admin/mappings"
                ));
            }
            Optional<String> id = nonBlankString(mObj, "id").or(() -> nonBlankString(mObj, "uuid"));
            Optional<JsonObjectView> metaOpt = mObj.get("metadata")
                .filter(JsonValue.JsonObject.class::isInstance)
                .map(JsonValue.JsonObject.class::cast)
                .map(JsonObjectView::new);

            Optional<String> matchedByIdService = id.map(serviceByDeterministicId::get);
            boolean isManagedForProject = isManagedMappingForProject(project.name(), metaOpt, matchedByIdService);
            if (!isManagedForProject) {
                continue;
            }

            Optional<String> svcName = metaOpt.flatMap(m -> m.nonBlankString("service")).or(() -> matchedByIdService);
            Optional<String> metaRoute = metaOpt.flatMap(m -> m.nonBlankString("route"));

            Optional<JsonValue.JsonObject> reqObj = mObj.get("request")
                .filter(JsonValue.JsonObject.class::isInstance)
                .map(JsonValue.JsonObject.class::cast);
            Optional<String> method = reqObj.flatMap(r -> nonBlankString(r, "method"));
            Optional<String> urlPathPattern = reqObj.flatMap(r -> nonBlankString(r, "urlPathPattern"));

            Optional<JsonValue.JsonObject> respObj = mObj.get("response")
                .filter(JsonValue.JsonObject.class::isInstance)
                .map(JsonValue.JsonObject.class::cast);
            Optional<String> proxyBaseUrl = respObj.flatMap(r -> nonBlankString(r, "proxyBaseUrl"))
                .map(GatewayEngine::normalizeTargetUrl);
            Optional<String> proxyUrlPrefixToRemove = respObj.flatMap(r -> nonBlankString(r, "proxyUrlPrefixToRemove"))
                .map(GatewayEngine::normalizePrefix);

            out.add(new ManagedLiveMapping(
                id,
                svcName,
                metaRoute,
                method,
                urlPathPattern,
                proxyBaseUrl,
                proxyUrlPrefixToRemove
            ));
        }
        return Result.ok(out);
    }

    private static boolean isManagedMappingForProject(
        String projectName,
        Optional<JsonObjectView> metaOpt,
        Optional<String> matchedByIdService
    ) {
        if (metaOpt.isPresent()) {
            JsonObjectView meta = metaOpt.get();
            Optional<Boolean> procompFlag = meta.booleanValue("procomp");
            if (procompFlag.isPresent()) {
                if (!procompFlag.get()) {
                    return false;
                }
                Optional<String> metaProj = meta.nonBlankString("procompProject");
                if (metaProj.isPresent()) {
                    return projectName.equals(metaProj.get());
                }
                return meta.nonBlankString("service").isPresent() || matchedByIdService.isPresent();
            }
        }
        return matchedByIdService.isPresent();
    }

    private static Optional<String> nonBlankString(JsonValue.JsonObject obj, String key) {
        return obj.get(key)
            .filter(JsonValue.JsonString.class::isInstance)
            .map(v -> ((JsonValue.JsonString) v).value().trim())
            .filter(s -> !s.isEmpty());
    }

    private record JsonObjectView(JsonValue.JsonObject obj) {
        Optional<String> nonBlankString(String key) {
            return GatewayEngine.nonBlankString(obj, key);
        }

        Optional<Boolean> booleanValue(String key) {
            return obj.get(key)
                .filter(JsonValue.JsonBoolean.class::isInstance)
                .map(v -> ((JsonValue.JsonBoolean) v).value());
        }
    }

    private record ManagedLiveMapping(
        Optional<String> id,
        Optional<String> service,
        Optional<String> metadataRoute,
        Optional<String> method,
        Optional<String> urlPathPattern,
        Optional<String> proxyBaseUrl,
        Optional<String> proxyUrlPrefixToRemove
    ) {}

    static String deterministicMappingId(String projectName, String serviceName) {
        return UUID.nameUUIDFromBytes(
            ("procomp:" + projectName + ":" + serviceName).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    static String normalizePrefix(String proxyPath) {
        String trimmed = proxyPath == null ? "" : proxyPath.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return "/";
        }
        String withSlash = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        int end = withSlash.length();
        while (end > 1 && withSlash.charAt(end - 1) == '/') {
            end--;
        }
        return withSlash.substring(0, end);
    }

    static String normalizeTargetUrl(String rawUrl) {
        String trimmed = rawUrl.trim();
        int end = trimmed.length();
        while (end > 1 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }

    private static Result<String, DomainError> resolveTargetUrl(ServiceDefinition service, Route route) {
        return switch (route) {
            case LOCAL -> service.port()
                .map(port -> Result.<String, DomainError>ok("http://127.0.0.1:" + port))
                .orElseGet(() -> Result.err(DomainError.of(
                    "GATEWAY_MISSING_LOCAL_PORT",
                    "Service '" + service.name() + "' is routed LOCAL but has no local port configured",
                    Map.of("service", service.name())
                )));
            case REMOTE -> service.remoteUrl()
                .map(String::trim)
                .filter(url -> !url.isBlank())
                .map(GatewayEngine::normalizeTargetUrl)
                .<Result<String, DomainError>>map(Result::ok)
                .orElseGet(() -> Result.err(DomainError.of(
                    "GATEWAY_MISSING_REMOTE_URL",
                    "Service '" + service.name() + "' is routed REMOTE but has no remoteUrl configured",
                    Map.of("service", service.name())
                )));
        };
    }

    private static String toUrlPathPattern(String normalizedPrefix) {
        if ("/".equals(normalizedPrefix)) {
            return "/.*";
        }
        return escapeRegexLiteral(normalizedPrefix) + "(/.*)?";
    }

    private static String escapeRegexLiteral(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (REGEX_META_CHARS.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
