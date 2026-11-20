package procomp.core.project;

import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure parser for .env files and strict ${env:VAR} host-environment references.
 */
public final class EnvFileParser {

    private EnvFileParser() {}

    public static Result<Map<String, String>, DomainError> parse(String sourceLabel, String content) {
        if (content == null) {
            return Result.err(DomainError.of("ENV_FILE_PARSE_ERROR", "Env file content must not be null", Map.of("file", sourceLabel)));
        }
        Map<String, String> result = new LinkedHashMap<>();
        String[] lines = content.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String entry = trimmed;
            if (entry.startsWith("export ") || entry.startsWith("export\t")) {
                entry = entry.substring("export".length()).trim();
            }
            int eqIdx = entry.indexOf('=');
            if (eqIdx <= 0) {
                return Result.err(DomainError.of(
                    "ENV_FILE_PARSE_ERROR",
                    "Malformed line " + lineNum + " in env file '" + sourceLabel + "' (expected KEY=VALUE)",
                    Map.of("file", sourceLabel, "line", Integer.toString(lineNum))
                ));
            }
            String key = entry.substring(0, eqIdx).trim();
            if (!isValidEnvKey(key)) {
                return Result.err(DomainError.of(
                    "ENV_FILE_PARSE_ERROR",
                    "Invalid environment variable name '" + key + "' at line " + lineNum + " in '" + sourceLabel + "'",
                    Map.of("file", sourceLabel, "line", Integer.toString(lineNum), "key", key)
                ));
            }
            String rawVal = entry.substring(eqIdx + 1).trim();
            Result<String, DomainError> valRes = parseValue(rawVal, sourceLabel, lineNum);
            if (valRes.isErr()) {
                return valRes.fold(ignored -> { throw new IllegalStateException(); }, Result::err);
            }
            result.put(key, ((Result.Ok<String, DomainError>) valRes).value());
        }
        return Result.ok(result);
    }

    public static Result<String, DomainError> expandHostEnvRefs(
        String raw,
        Map<String, String> hostEnv,
        String fieldContext
    ) {
        if (raw == null || !raw.contains("${env:")) {
            return Result.ok(raw == null ? "" : raw);
        }
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (idx < raw.length()) {
            int start = raw.indexOf("${env:", idx);
            if (start < 0) {
                sb.append(raw, idx, raw.length());
                break;
            }
            sb.append(raw, idx, start);
            int nameStart = start + "${env:".length();
            int end = raw.indexOf('}', nameStart);
            if (end < 0) {
                return Result.err(DomainError.of(
                    "CONFIG_MALFORMED_ENV_REF",
                    "Unclosed ${env:...} reference in '" + fieldContext + "'",
                    Map.of("field", fieldContext)
                ));
            }
            String varName = raw.substring(nameStart, end);
            if (varName.isBlank()) {
                return Result.err(DomainError.of(
                    "CONFIG_MALFORMED_ENV_REF",
                    "Empty ${env:} reference in '" + fieldContext + "'",
                    Map.of("field", fieldContext)
                ));
            }
            if (!hostEnv.containsKey(varName)) {
                return Result.err(DomainError.of(
                    "CONFIG_UNSET_ENV_VAR",
                    "Required host environment variable '" + varName + "' referenced by '" + fieldContext + "' is not set",
                    Map.of("field", fieldContext, "envVar", varName)
                ));
            }
            String val = hostEnv.get(varName);
            sb.append(val == null ? "" : val);
            idx = end + 1;
        }
        return Result.ok(sb.toString());
    }

    private static Result<String, DomainError> parseValue(String raw, String sourceLabel, int lineNum) {
        if (raw.isEmpty()) {
            return Result.ok("");
        }
        char first = raw.charAt(0);
        if (first == '"' || first == '\'') {
            return parseQuotedValue(raw, first, sourceLabel, lineNum);
        }
        return Result.ok(stripInlineComment(raw).trim());
    }

    private static Result<String, DomainError> parseQuotedValue(String raw, char quote, String sourceLabel, int lineNum) {
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote == '"' && escaped) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append('\\').append(c);
                }
                escaped = false;
                continue;
            }
            if (quote == '"' && c == '\\') {
                escaped = true;
                continue;
            }
            if (c == quote) {
                String trailing = raw.substring(i + 1).trim();
                if (!trailing.isEmpty() && !trailing.startsWith("#")) {
                    return Result.err(DomainError.of(
                        "ENV_FILE_PARSE_ERROR",
                        "Unexpected trailing characters after closing quote at line " + lineNum + " in '" + sourceLabel + "'",
                        Map.of("file", sourceLabel, "line", Integer.toString(lineNum))
                    ));
                }
                return Result.ok(sb.toString());
            }
            sb.append(c);
        }
        return Result.err(DomainError.of(
            "ENV_FILE_PARSE_ERROR",
            "Unterminated quoted value at line " + lineNum + " in '" + sourceLabel + "'",
            Map.of("file", sourceLabel, "line", Integer.toString(lineNum))
        ));
    }

    private static String stripInlineComment(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '#' && i > 0 && Character.isWhitespace(raw.charAt(i - 1))) {
                return raw.substring(0, i);
            }
        }
        return raw;
    }

    private static boolean isValidEnvKey(String key) {
        if (key.isEmpty()) {
            return false;
        }
        char first = key.charAt(0);
        if (!(Character.isLetter(first) || first == '_')) {
            return false;
        }
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }
}
