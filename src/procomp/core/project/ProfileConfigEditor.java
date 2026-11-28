package procomp.core.project;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure domain validator and surgical JSON editor for {@code procomp.json}'s top-level
 * {@code "profiles"} section. Preserves surrounding formatting, indentation, line endings,
 * and key ordering outside {@code "profiles"}.
 */
public final class ProfileConfigEditor {

    private static final String DEFAULT_INDENT = "  ";

    private ProfileConfigEditor() {}

    /**
     * Validates a Profile name for CLI and URL safety: non-blank, no leading {@code '-'},
     * and no whitespace, {@code '/'}, or {@code '\\'}.
     */
    public static Result<String, DomainError> validateProfileName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            return Result.err(DomainError.of(
                "PROFILE_INVALID_NAME",
                "Profile name must not be blank"
            ));
        }
        if (name.startsWith("-")) {
            return Result.err(DomainError.of(
                "PROFILE_INVALID_NAME",
                "Profile name '" + name + "' must not start with '-'",
                Map.of("profile", name)
            ));
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '\\' || Character.isISOControl(c)) {
                return Result.err(DomainError.of(
                    "PROFILE_INVALID_NAME",
                    "Profile name '" + name + "' must not contain whitespace, '/', '\\', or control characters",
                    Map.of("profile", name)
                ));
            }
        }
        return Result.ok(name);
    }

    /**
     * Validates all {@code localServices} exist in {@code project} and returns a deduplicated
     * list preserving declaration order.
     */
    public static Result<List<String>, DomainError> validateLocalServices(Project project, List<String> localServices) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(localServices, "localServices must not be null");

        Set<String> deduped = new LinkedHashSet<>();
        for (String svc : localServices) {
            Objects.requireNonNull(svc, "service name must not be null");
            if (svc.isBlank() || project.service(svc).isEmpty()) {
                return Result.err(DomainError.of(
                    "UNKNOWN_SERVICE",
                    "Unknown service '" + svc + "' in project '" + project.name() + "'",
                    Map.of("service", svc, "project", project.name())
                ));
            }
            deduped.add(svc);
        }
        return Result.ok(List.copyOf(deduped));
    }

    /**
     * Validates {@code profileName} and {@code localServices}, then returns an updated
     * {@code procomp.json} string with the Profile created or overwritten in place.
     */
    public static Result<String, DomainError> upsertProfile(
        String rawJson,
        Project project,
        String profileName,
        List<String> localServices
    ) {
        Objects.requireNonNull(rawJson, "rawJson must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(localServices, "localServices must not be null");

        Result<String, DomainError> nameRes = validateProfileName(profileName);
        if (nameRes.isErr()) {
            return nameRes.castError();
        }
        Result<List<String>, DomainError> svcsRes = validateLocalServices(project, localServices);
        if (svcsRes.isErr()) {
            return svcsRes.castError();
        }
        List<String> validServices = ((Result.Ok<List<String>, DomainError>) svcsRes).value();

        Map<String, ProfileDefinition> nextProfiles = new LinkedHashMap<>(project.profiles());
        nextProfiles.put(profileName, new ProfileDefinition(profileName, validServices));

        return rewriteProfilesSection(rawJson, nextProfiles);
    }

    /**
     * Validates {@code profileName} exists in {@code project}, then returns an updated
     * {@code procomp.json} string with the Profile removed (leaving {@code "profiles": {}}
     * when the last Profile is deleted).
     */
    public static Result<String, DomainError> deleteProfile(
        String rawJson,
        Project project,
        String profileName
    ) {
        Objects.requireNonNull(rawJson, "rawJson must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(profileName, "profileName must not be null");

        if (profileName.isBlank() || project.profile(profileName).isEmpty()) {
            return Result.err(DomainError.of(
                "UNKNOWN_PROFILE",
                "Unknown profile '" + profileName + "' in project '" + project.name() + "'",
                Map.of("profile", profileName, "project", project.name())
            ));
        }

        Map<String, ProfileDefinition> nextProfiles = new LinkedHashMap<>(project.profiles());
        nextProfiles.remove(profileName);

        return rewriteProfilesSection(rawJson, nextProfiles);
    }

    private static Result<String, DomainError> rewriteProfilesSection(
        String rawJson,
        Map<String, ProfileDefinition> profiles
    ) {
        // Ensure rawJson is valid JSON object before surgical edit
        Result<JsonValue, DomainError> parsed = Json.parse(rawJson);
        if (parsed.isErr()) {
            return parsed.castError();
        }
        if (!(((Result.Ok<JsonValue, DomainError>) parsed).value() instanceof JsonValue.JsonObject)) {
            return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "Root procomp.json must be a JSON object"));
        }

        Optional<RootScan> scanOpt = scanRootObject(rawJson);
        if (scanOpt.isEmpty()) {
            return Result.err(DomainError.of("JSON_PARSE_ERROR", "Unable to locate root JSON object in procomp.json"));
        }
        RootScan scan = scanOpt.get();

        String lineEnding = detectLineEnding(rawJson);
        String indentUnit = detectIndentUnit(rawJson);
        String formattedProfilesValue = formatProfilesValue(profiles, indentUnit, lineEnding);

        return switch (scan) {
            case RootScan.WithProfiles(Span span) -> Result.ok(
                rawJson.substring(0, span.start()) + formattedProfilesValue + rawJson.substring(span.end())
            );
            case RootScan.Empty(int openBraceIndex, int closeBraceIndex) -> {
                StringBuilder out = new StringBuilder(rawJson.length() + formattedProfilesValue.length() + 32);
                out.append(rawJson, 0, openBraceIndex + 1);
                out.append(lineEnding);
                out.append(indentUnit).append("\"profiles\": ").append(formattedProfilesValue);
                out.append(lineEnding);
                out.append(rawJson, closeBraceIndex, rawJson.length());
                yield Result.ok(out.toString());
            }
            case RootScan.WithoutProfiles(int lastValueEndIndex) -> {
                StringBuilder out = new StringBuilder(rawJson.length() + formattedProfilesValue.length() + 32);
                out.append(rawJson, 0, lastValueEndIndex);
                out.append(',');
                out.append(lineEnding);
                out.append(indentUnit).append("\"profiles\": ").append(formattedProfilesValue);
                out.append(rawJson, lastValueEndIndex, rawJson.length());
                yield Result.ok(out.toString());
            }
        };
    }

    private static String formatProfilesValue(
        Map<String, ProfileDefinition> profiles,
        String indentUnit,
        String lineEnding
    ) {
        if (profiles.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{').append(lineEnding);
        List<ProfileDefinition> list = new ArrayList<>(profiles.values());
        for (int i = 0; i < list.size(); i++) {
            ProfileDefinition profile = list.get(i);
            sb.append(indentUnit).append(indentUnit)
                .append(Json.stringify(Json.str(profile.name())))
                .append(": {").append(lineEnding);
            sb.append(indentUnit).append(indentUnit).append(indentUnit)
                .append("\"local\": ").append(formatStringArray(profile.localServices()))
                .append(lineEnding);
            sb.append(indentUnit).append(indentUnit).append('}');
            if (i + 1 < list.size()) {
                sb.append(',');
            }
            sb.append(lineEnding);
        }
        sb.append(indentUnit).append('}');
        return sb.toString();
    }

    private static String formatStringArray(List<String> items) {
        if (items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(Json.stringify(Json.str(items.get(i))));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String detectLineEnding(String rawJson) {
        return rawJson.contains("\r\n") ? "\r\n" : "\n";
    }

    private static String detectIndentUnit(String rawJson) {
        int len = rawJson.length();
        int i = 0;
        while (i < len) {
            int nl = rawJson.indexOf('\n', i);
            if (nl < 0) {
                break;
            }
            int lineStart = nl + 1;
            int j = lineStart;
            while (j < len && (rawJson.charAt(j) == ' ' || rawJson.charAt(j) == '\t')) {
                j++;
            }
            if (j > lineStart && j < len && rawJson.charAt(j) == '"') {
                return rawJson.substring(lineStart, j);
            }
            i = lineStart;
        }
        return DEFAULT_INDENT;
    }

    private record Span(int start, int end) {}

    private sealed interface RootScan permits RootScan.Empty, RootScan.WithoutProfiles, RootScan.WithProfiles {
        record Empty(int openBraceIndex, int closeBraceIndex) implements RootScan {}
        record WithoutProfiles(int lastValueEndIndex) implements RootScan {}
        record WithProfiles(Span profilesValueSpan) implements RootScan {}
    }

    /**
     * Scans the top-level JSON object in {@code s} to locate the exact character span of the
     * top-level {@code "profiles"} property value (if present) and the root object bounds.
     */
    private static Optional<RootScan> scanRootObject(String s) {
        int n = s.length();
        int i = skipWs(s, 0);
        if (i >= n || s.charAt(i) != '{') {
            return Optional.empty();
        }
        int openBrace = i;
        i = skipWs(s, i + 1);
        if (i < n && s.charAt(i) == '}') {
            return Optional.of(new RootScan.Empty(openBrace, i));
        }

        int lastValueEnd = openBrace + 1;
        Optional<Span> profilesSpan = Optional.empty();

        while (i < n) {
            if (s.charAt(i) != '"') {
                return Optional.empty();
            }
            int keyEnd = scanStringEnd(s, i);
            if (keyEnd < 0) {
                return Optional.empty();
            }
            String rawKey = s.substring(i, keyEnd);
            String key = unescapeKey(rawKey);

            i = skipWs(s, keyEnd);
            if (i >= n || s.charAt(i) != ':') {
                return Optional.empty();
            }
            i = skipWs(s, i + 1);
            int valStart = i;
            int valEnd = scanValueEnd(s, valStart);
            if (valEnd < 0) {
                return Optional.empty();
            }
            lastValueEnd = valEnd;
            if ("profiles".equals(key)) {
                profilesSpan = Optional.of(new Span(valStart, valEnd));
            }

            i = skipWs(s, valEnd);
            if (i >= n) {
                return Optional.empty();
            }
            char next = s.charAt(i);
            if (next == ',') {
                i = skipWs(s, i + 1);
                continue;
            }
            if (next == '}') {
                RootScan result = profilesSpan.isPresent()
                    ? new RootScan.WithProfiles(profilesSpan.get())
                    : new RootScan.WithoutProfiles(lastValueEnd);
                return Optional.of(result);
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static String unescapeKey(String rawQuoted) {
        Result<JsonValue, DomainError> res = Json.parse(rawQuoted);
        if (res instanceof Result.Ok<JsonValue, DomainError>(JsonValue val)
            && val instanceof JsonValue.JsonString(String decoded)) {
            return decoded;
        }
        return rawQuoted;
    }

    private static int skipWs(String s, int i) {
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static int scanStringEnd(String s, int start) {
        int n = s.length();
        int i = start + 1;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    private static int scanValueEnd(String s, int start) {
        int n = s.length();
        if (start >= n) {
            return -1;
        }
        char c = s.charAt(start);
        if (c == '"') {
            return scanStringEnd(s, start);
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            int i = start;
            while (i < n) {
                char ch = s.charAt(i);
                if (ch == '"') {
                    i = scanStringEnd(s, i);
                    if (i < 0) {
                        return -1;
                    }
                    continue;
                }
                if (ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ']') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
                i++;
            }
            return -1;
        }
        // Primitive: number, true, false, null
        int i = start;
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == ',' || ch == '}' || ch == ']' || ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                return i;
            }
            i++;
        }
        return n;
    }
}
