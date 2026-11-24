package procomp.core.gateway;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.project.FallbackRemote;
import procomp.core.project.GatewayDefinition;
import procomp.core.project.Project;
import procomp.core.project.ServiceDefinition;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure domain functions for WireMock proxy mapping generation and Gateway drift detection.
 */
public final class GatewayEngine {

    public static final int DEFAULT_MAPPING_PRIORITY = 5;
    public static final int FALLBACK_REMOTE_PRIORITY = 10;
    public static final String FALLBACK_REMOTE_ROLE = "fallback-remote";
    private static final String REGEX_META_CHARS = "\\.+*?^$()[]{}|";

    private GatewayEngine() {}

    /**
     * Builds the desired {@link GatewayPlan} for all Services in {@code project} according to {@code routing}.
     * Fails fast with zero silent fallback if a {@code LOCAL} Service lacks {@code port}
     * or a {@code REMOTE} Service lacks both {@code remoteUrl} and {@code gateway.fallbackRemote}.
     */
    public static Result<GatewayPlan, DomainError> buildPlan(Project project, RoutingTable routing) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(routing, "routing must not be null");

        Map<String, String> proxyHeaders = project.gateway()
            .map(GatewayDefinition::proxyHeaders)
            .orElse(Map.of());
        List<String> removeHeaders = deriveRemoveProxyRequestHeaders(proxyHeaders);
        Optional<FallbackRemote> fallbackRemote = project.gateway().flatMap(GatewayDefinition::fallbackRemote);

        List<WireMockMapping> mappings = new ArrayList<>();
        for (ServiceDefinition service : project.services().values()) {
            Route route = routing.routeOf(service.name()).orElse(Route.REMOTE);
            if (route == Route.REMOTE && service.remoteUrl().isEmpty() && fallbackRemote.isPresent()) {
                // Covered by the priority-10 catch-all fallbackRemote mapping
                continue;
            }
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
                prefixToRemove,
                proxyHeaders,
                removeHeaders,
                false
            ));
        }

        if (fallbackRemote.isPresent()) {
            FallbackRemote fb = fallbackRemote.get();
            Optional<String> stripPrefix = fb.stripPrefix()
                .map(GatewayEngine::normalizePrefix)
                .filter(p -> !"/".equals(p));
            mappings.add(new WireMockMapping(
                deterministicFallbackMappingId(project.name()),
                "procomp-" + project.name() + "-" + FALLBACK_REMOTE_ROLE,
                FALLBACK_REMOTE_PRIORITY,
                project.name(),
                FALLBACK_REMOTE_ROLE,
                Route.REMOTE,
                "/.*",
                normalizeTargetUrl(fb.targetUrl()),
                stripPrefix,
                proxyHeaders,
                removeHeaders,
                true
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
        if (!mapping.additionalProxyRequestHeaders().isEmpty()) {
            Map<String, JsonValue> headerNodes = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : mapping.additionalProxyRequestHeaders().entrySet()) {
                headerNodes.put(entry.getKey(), Json.str(entry.getValue()));
            }
            response.put("additionalProxyRequestHeaders", Json.obj(headerNodes));
        }
        if (!mapping.removeProxyRequestHeaders().isEmpty()) {
            List<JsonValue> removeNodes = new ArrayList<>(mapping.removeProxyRequestHeaders().size());
            for (String header : mapping.removeProxyRequestHeaders()) {
                removeNodes.add(Json.str(header));
            }
            response.put("removeProxyRequestHeaders", Json.arr(removeNodes));
        }

        Map<String, JsonValue> metadata = new LinkedHashMap<>();
        metadata.put("procomp", Json.bool(true));
        metadata.put("procompProject", Json.str(mapping.projectName()));
        if (mapping.fallbackRemote()) {
            metadata.put("role", Json.str(FALLBACK_REMOTE_ROLE));
        } else {
            metadata.put("service", Json.str(mapping.service()));
        }
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

        Optional<WireMockMapping> desiredFallback = plan.mappings().stream()
            .filter(WireMockMapping::fallbackRemote)
            .findFirst();
        Map<String, WireMockMapping> desiredByService = new LinkedHashMap<>();
        for (WireMockMapping m : plan.mappings()) {
            if (!m.fallbackRemote()) {
                desiredByService.put(m.service(), m);
            }
        }

        Map<String, List<ManagedLiveMapping>> byService = new LinkedHashMap<>();
        List<ManagedLiveMapping> liveFallbacks = new ArrayList<>();
        List<StaleManagedMapping> staleMappings = new ArrayList<>();
        for (ManagedLiveMapping live : liveMappings) {
            if (live.fallbackRemote()) {
                if (desiredFallback.isPresent()) {
                    liveFallbacks.add(live);
                } else {
                    staleMappings.add(new StaleManagedMapping(live.id(), Optional.of(FALLBACK_REMOTE_ROLE), live.proxyBaseUrl()));
                }
                continue;
            }
            Optional<String> svcOpt = live.service();
            if (svcOpt.isEmpty() || project.service(svcOpt.get()).isEmpty()) {
                staleMappings.add(new StaleManagedMapping(live.id(), svcOpt, live.proxyBaseUrl()));
            } else {
                byService.computeIfAbsent(svcOpt.get(), _ -> new ArrayList<>()).add(live);
            }
        }

        Map<String, String> proxyHeaders = project.gateway()
            .map(GatewayDefinition::proxyHeaders)
            .orElse(Map.of());
        List<String> removeHeaders = deriveRemoveProxyRequestHeaders(proxyHeaders);

        List<RouteDrift> drifted = new ArrayList<>();
        boolean fallbackInSync = true;
        if (desiredFallback.isPresent()) {
            WireMockMapping fb = desiredFallback.get();
            if (liveFallbacks.size() != 1
                || !matchesMappingSpec(
                    liveFallbacks.get(0),
                    fb.priority(),
                    fb.urlPathPattern(),
                    fb.targetUrl(),
                    fb.proxyUrlPrefixToRemove(),
                    Route.REMOTE,
                    fb.additionalProxyRequestHeaders(),
                    fb.removeProxyRequestHeaders()
                )) {
                fallbackInSync = false;
                String liveFbState = liveFallbacks.isEmpty() ? "UNSET" : "MISMATCH";
                drifted.add(new RouteDrift(FALLBACK_REMOTE_ROLE, Route.REMOTE, liveFbState));
            }
        }

        Map<String, String> liveRoutes = new LinkedHashMap<>();
        for (ServiceDefinition svc : project.services().values()) {
            Route desiredRoute = routing.routeOf(svc.name()).orElse(Route.REMOTE);
            List<ManagedLiveMapping> actuals = byService.getOrDefault(svc.name(), List.of());
            WireMockMapping desired = desiredByService.get(svc.name());
            if (desired != null) {
                String liveState = classifyServiceLiveState(svc, desired, actuals, proxyHeaders, removeHeaders);
                liveRoutes.put(svc.name(), liveState);
                if (!desired.route().name().equals(liveState)) {
                    drifted.add(new RouteDrift(svc.name(), desired.route(), liveState));
                }
            } else {
                // REMOTE Service covered by gateway.fallbackRemote (no per-service mapping should exist)
                if (!actuals.isEmpty()) {
                    String liveState = classifyLingeringPerServiceMapping(svc, actuals, proxyHeaders, removeHeaders);
                    liveRoutes.put(svc.name(), liveState);
                    drifted.add(new RouteDrift(svc.name(), desiredRoute, liveState));
                } else if (!fallbackInSync) {
                    liveRoutes.put(svc.name(), liveFallbacks.isEmpty() ? "UNSET" : "MISMATCH");
                } else {
                    liveRoutes.put(svc.name(), Route.REMOTE.name());
                }
            }
        }

        GatewaySyncStatus status = drifted.isEmpty() && staleMappings.isEmpty()
            ? GatewaySyncStatus.IN_SYNC
            : GatewaySyncStatus.DRIFTED;
        return Result.ok(new GatewayDriftReport(status, drifted, staleMappings, liveRoutes));
    }

    private static String classifyLingeringPerServiceMapping(
        ServiceDefinition svc,
        List<ManagedLiveMapping> actuals,
        Map<String, String> proxyHeaders,
        List<String> removeHeaders
    ) {
        if (actuals.size() > 1) {
            return "MISMATCH";
        }
        String prefix = normalizePrefix(svc.proxyPath());
        String urlPathPattern = toUrlPathPattern(prefix);
        Optional<String> prefixToRemove = "/".equals(prefix) ? Optional.empty() : Optional.of(prefix);
        Result<String, DomainError> localTargetRes = resolveTargetUrl(svc, Route.LOCAL);
        if (localTargetRes instanceof Result.Ok<String, DomainError>(String localTarget)
            && matchesMappingSpec(
                actuals.get(0),
                DEFAULT_MAPPING_PRIORITY,
                urlPathPattern,
                localTarget,
                prefixToRemove,
                Route.LOCAL,
                proxyHeaders,
                removeHeaders
            )) {
            return Route.LOCAL.name();
        }
        return "MISMATCH";
    }

    private static String classifyServiceLiveState(
        ServiceDefinition svc,
        WireMockMapping desired,
        List<ManagedLiveMapping> actuals,
        Map<String, String> proxyHeaders,
        List<String> removeHeaders
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
            desired.priority(),
            desired.urlPathPattern(),
            desired.targetUrl(),
            desired.proxyUrlPrefixToRemove(),
            desired.route(),
            desired.additionalProxyRequestHeaders(),
            desired.removeProxyRequestHeaders()
        )) {
            return desired.route().name();
        }
        Route alternate = desired.route() == Route.LOCAL ? Route.REMOTE : Route.LOCAL;
        Result<String, DomainError> altTargetRes = resolveTargetUrl(svc, alternate);
        if (altTargetRes instanceof Result.Ok<String, DomainError>(String altTarget)
            && matchesMappingSpec(
                actual,
                DEFAULT_MAPPING_PRIORITY,
                desired.urlPathPattern(),
                altTarget,
                desired.proxyUrlPrefixToRemove(),
                alternate,
                proxyHeaders,
                removeHeaders
            )) {
            return alternate.name();
        }
        return "MISMATCH";
    }

    private static boolean matchesMappingSpec(
        ManagedLiveMapping actual,
        int expectedPriority,
        String expectedUrlPathPattern,
        String expectedTargetUrl,
        Optional<String> expectedPrefixToRemove,
        Route expectedRoute,
        Map<String, String> expectedHeaders,
        List<String> expectedRemoveHeaders
    ) {
        if (actual.priority().orElse(DEFAULT_MAPPING_PRIORITY) != expectedPriority) {
            return false;
        }
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
        if (!expectedHeaders.equals(actual.additionalProxyRequestHeaders())) {
            return false;
        }
        if (!expectedRemoveHeaders.equals(actual.removeProxyRequestHeaders())) {
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
        String fallbackId = deterministicFallbackMappingId(project.name());

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

            boolean isFallbackById = id.map(fallbackId::equals).orElse(false);
            boolean isFallbackByMeta = metaOpt.flatMap(m -> m.nonBlankString("role"))
                .map(FALLBACK_REMOTE_ROLE::equals)
                .orElse(false);
            boolean isFallback = isFallbackById || isFallbackByMeta;

            Optional<String> matchedByIdService = id.map(serviceByDeterministicId::get);
            boolean isManagedForProject = isManagedMappingForProject(project.name(), metaOpt, matchedByIdService, isFallbackById);
            if (!isManagedForProject) {
                continue;
            }

            Optional<String> svcName = isFallback
                ? Optional.empty()
                : metaOpt.flatMap(m -> m.nonBlankString("service")).or(() -> matchedByIdService);
            Optional<String> metaRoute = metaOpt.flatMap(m -> m.nonBlankString("route"));
            Optional<Integer> priority = mObj.get("priority")
                .filter(JsonValue.JsonNumber.class::isInstance)
                .map(JsonValue.JsonNumber.class::cast)
                .filter(JsonValue.JsonNumber::isInteger)
                .flatMap(n -> n.asInt().isPresent() ? Optional.of(n.asInt().getAsInt()) : Optional.empty());

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
            Map<String, String> additionalHeaders = respObj
                .map(GatewayEngine::parseAdditionalProxyRequestHeaders)
                .orElse(Map.of());
            List<String> removeHeaders = respObj
                .map(GatewayEngine::parseRemoveProxyRequestHeaders)
                .orElse(List.of());

            out.add(new ManagedLiveMapping(
                id,
                svcName,
                isFallback,
                priority,
                metaRoute,
                method,
                urlPathPattern,
                proxyBaseUrl,
                proxyUrlPrefixToRemove,
                additionalHeaders,
                removeHeaders
            ));
        }
        return Result.ok(out);
    }

    private static boolean isManagedMappingForProject(
        String projectName,
        Optional<JsonObjectView> metaOpt,
        Optional<String> matchedByIdService,
        boolean isFallbackById
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
                return isFallbackById
                    || meta.nonBlankString("role").map(FALLBACK_REMOTE_ROLE::equals).orElse(false)
                    || meta.nonBlankString("service").isPresent()
                    || matchedByIdService.isPresent();
            }
        }
        return isFallbackById || matchedByIdService.isPresent();
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

    private static List<String> deriveRemoveProxyRequestHeaders(Map<String, String> proxyHeaders) {
        if (proxyHeaders.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String header : proxyHeaders.keySet()) {
            names.add(header);
            names.add(header.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }

    private static List<String> parseRemoveProxyRequestHeaders(JsonValue.JsonObject respObj) {
        Optional<JsonValue> raw = respObj.get("removeProxyRequestHeaders");
        if (raw.isEmpty() || !(raw.get() instanceof JsonValue.JsonArray(List<JsonValue> elements))) {
            return List.of();
        }
        List<String> out = new ArrayList<>(elements.size());
        for (JsonValue el : elements) {
            if (el instanceof JsonValue.JsonString(String val)) {
                out.add(val);
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, String> parseAdditionalProxyRequestHeaders(JsonValue.JsonObject respObj) {
        Optional<JsonValue> raw = respObj.get("additionalProxyRequestHeaders");
        if (raw.isEmpty() || !(raw.get() instanceof JsonValue.JsonObject obj)) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : obj.fields().entrySet()) {
            if (entry.getValue() instanceof JsonValue.JsonString(String val)) {
                headers.put(entry.getKey(), val);
            }
        }
        return Collections.unmodifiableMap(headers);
    }

    private record ManagedLiveMapping(
        Optional<String> id,
        Optional<String> service,
        boolean fallbackRemote,
        Optional<Integer> priority,
        Optional<String> metadataRoute,
        Optional<String> method,
        Optional<String> urlPathPattern,
        Optional<String> proxyBaseUrl,
        Optional<String> proxyUrlPrefixToRemove,
        Map<String, String> additionalProxyRequestHeaders,
        List<String> removeProxyRequestHeaders
    ) {}

    static String deterministicMappingId(String projectName, String serviceName) {
        return UUID.nameUUIDFromBytes(
            ("procomp:" + projectName + ":" + serviceName).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    static String deterministicFallbackMappingId(String projectName) {
        return UUID.nameUUIDFromBytes(
            ("procomp:" + projectName + ":__" + FALLBACK_REMOTE_ROLE + "__").getBytes(StandardCharsets.UTF_8)
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
