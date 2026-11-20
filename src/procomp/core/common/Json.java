package procomp.core.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict, zero-dependency recursive-descent JSON parser and serializer.
 */
public final class Json {

    private static final int MAX_DEPTH = 512;

    private Json() {}

    public static JsonValue.JsonObject obj(Map<String, JsonValue> fields) {
        return new JsonValue.JsonObject(fields);
    }

    public static JsonValue.JsonArray arr(List<JsonValue> elements) {
        return new JsonValue.JsonArray(elements);
    }

    public static JsonValue.JsonString str(String value) {
        return new JsonValue.JsonString(value);
    }

    public static JsonValue.JsonNumber num(long value) {
        return new JsonValue.JsonNumber(Long.toString(value));
    }

    public static JsonValue.JsonBoolean bool(boolean value) {
        return new JsonValue.JsonBoolean(value);
    }

    public static JsonValue.JsonNull nil() {
        return JsonValue.JsonNull.INSTANCE;
    }

    public static Result<JsonValue, DomainError> parse(String input) {
        if (input == null) {
            return Result.err(DomainError.of("JSON_PARSE_ERROR", "JSON input must not be null"));
        }
        Parser parser = new Parser(input);
        return parser.parseRoot();
    }

    public static String stringify(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        writeJson(value, sb, false, 0);
        return sb.toString();
    }

    public static String stringifyPretty(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        writeJson(value, sb, true, 0);
        return sb.toString();
    }

    private static void writeJson(JsonValue value, StringBuilder sb, boolean pretty, int indent) {
        switch (value) {
            case JsonValue.JsonNull _ -> sb.append("null");
            case JsonValue.JsonBoolean(boolean b) -> sb.append(b);
            case JsonValue.JsonNumber(String raw) -> sb.append(raw);
            case JsonValue.JsonString(String s) -> writeEscapedString(s, sb);
            case JsonValue.JsonArray(List<JsonValue> elements) -> {
                if (elements.isEmpty()) {
                    sb.append("[]");
                    return;
                }
                sb.append('[');
                for (int i = 0; i < elements.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    if (pretty) {
                        sb.append('\n');
                        appendIndent(sb, indent + 2);
                    }
                    writeJson(elements.get(i), sb, pretty, indent + 2);
                }
                if (pretty) {
                    sb.append('\n');
                    appendIndent(sb, indent);
                }
                sb.append(']');
            }
            case JsonValue.JsonObject(Map<String, JsonValue> fields) -> {
                if (fields.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append('{');
                int i = 0;
                for (Map.Entry<String, JsonValue> entry : fields.entrySet()) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    if (pretty) {
                        sb.append('\n');
                        appendIndent(sb, indent + 2);
                    }
                    writeEscapedString(entry.getKey(), sb);
                    sb.append(pretty ? ": " : ":");
                    writeJson(entry.getValue(), sb, pretty, indent + 2);
                    i++;
                }
                if (pretty) {
                    sb.append('\n');
                    appendIndent(sb, indent);
                }
                sb.append('}');
            }
        }
    }

    private static void appendIndent(StringBuilder sb, int spaces) {
        for (int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
    }

    private static void writeEscapedString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String input;
        private int pos;
        private int line = 1;
        private int col = 1;

        Parser(String input) {
            this.input = input;
        }

        Result<JsonValue, DomainError> parseRoot() {
            skipWhitespace();
            if (isEof()) {
                return error("Unexpected end of JSON input");
            }
            Result<JsonValue, DomainError> valueResult = parseValue(0);
            if (valueResult.isErr()) {
                return valueResult;
            }
            skipWhitespace();
            if (!isEof()) {
                return error("Unexpected trailing character '" + currentChar() + "'");
            }
            return valueResult;
        }

        private Result<JsonValue, DomainError> parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                return error("JSON nesting depth exceeds maximum of " + MAX_DEPTH);
            }
            skipWhitespace();
            if (isEof()) {
                return error("Unexpected end of JSON input");
            }
            char c = currentChar();
            return switch (c) {
                case '{' -> parseObject(depth + 1);
                case '[' -> parseArray(depth + 1);
                case '"' -> parseString().map(JsonValue.JsonString::new);
                case 't' -> parseLiteral("true", new JsonValue.JsonBoolean(true));
                case 'f' -> parseLiteral("false", new JsonValue.JsonBoolean(false));
                case 'n' -> parseLiteral("null", JsonValue.JsonNull.INSTANCE);
                default -> {
                    if (c == '-' || isDigit(c)) {
                        yield parseNumber();
                    }
                    yield error("Unexpected character '" + c + "'");
                }
            };
        }

        private Result<JsonValue, DomainError> parseObject(int depth) {
            advance(); // '{'
            skipWhitespace();
            Map<String, JsonValue> fields = new LinkedHashMap<>();
            if (!isEof() && currentChar() == '}') {
                advance();
                return Result.ok(new JsonValue.JsonObject(fields));
            }
            while (true) {
                skipWhitespace();
                if (isEof() || currentChar() != '"') {
                    return error("Expected string key in JSON object");
                }
                int keyLine = line;
                int keyCol = col;
                Result<String, DomainError> keyRes = parseString();
                if (keyRes.isErr()) {
                    return keyRes.map(JsonValue.JsonString::new);
                }
                String key = ((Result.Ok<String, DomainError>) keyRes).value();
                if (fields.containsKey(key)) {
                    return Result.err(DomainError.of(
                        "JSON_PARSE_ERROR",
                        "Duplicate key '" + key + "' in JSON object",
                        Map.of("line", Integer.toString(keyLine), "column", Integer.toString(keyCol))
                    ));
                }
                skipWhitespace();
                if (isEof() || currentChar() != ':') {
                    return error("Expected ':' after object key '" + key + "'");
                }
                advance();
                Result<JsonValue, DomainError> valRes = parseValue(depth);
                if (valRes.isErr()) {
                    return valRes;
                }
                fields.put(key, ((Result.Ok<JsonValue, DomainError>) valRes).value());

                skipWhitespace();
                if (isEof()) {
                    return error("Unterminated JSON object");
                }
                char next = currentChar();
                if (next == '}') {
                    advance();
                    return Result.ok(new JsonValue.JsonObject(fields));
                }
                if (next == ',') {
                    advance();
                } else {
                    return error("Expected ',' or '}' in JSON object, found '" + next + "'");
                }
            }
        }

        private Result<JsonValue, DomainError> parseArray(int depth) {
            advance(); // '['
            skipWhitespace();
            List<JsonValue> elements = new ArrayList<>();
            if (!isEof() && currentChar() == ']') {
                advance();
                return Result.ok(new JsonValue.JsonArray(elements));
            }
            while (true) {
                Result<JsonValue, DomainError> elemRes = parseValue(depth);
                if (elemRes.isErr()) {
                    return elemRes;
                }
                elements.add(((Result.Ok<JsonValue, DomainError>) elemRes).value());

                skipWhitespace();
                if (isEof()) {
                    return error("Unterminated JSON array");
                }
                char next = currentChar();
                if (next == ']') {
                    advance();
                    return Result.ok(new JsonValue.JsonArray(elements));
                }
                if (next == ',') {
                    advance();
                } else {
                    return error("Expected ',' or ']' in JSON array, found '" + next + "'");
                }
            }
        }

        private Result<String, DomainError> parseString() {
            advance(); // opening '"'
            StringBuilder sb = new StringBuilder();
            while (!isEof()) {
                char c = currentChar();
                if (c == '"') {
                    advance();
                    return Result.ok(sb.toString());
                }
                if (c < 0x20) {
                    return error("Unescaped control character in JSON string");
                }
                if (c == '\\') {
                    advance();
                    if (isEof()) {
                        return error("Unterminated escape sequence in JSON string");
                    }
                    char esc = currentChar();
                    switch (esc) {
                        case '"' -> { sb.append('"'); advance(); }
                        case '\\' -> { sb.append('\\'); advance(); }
                        case '/' -> { sb.append('/'); advance(); }
                        case 'b' -> { sb.append('\b'); advance(); }
                        case 'f' -> { sb.append('\f'); advance(); }
                        case 'n' -> { sb.append('\n'); advance(); }
                        case 'r' -> { sb.append('\r'); advance(); }
                        case 't' -> { sb.append('\t'); advance(); }
                        case 'u' -> {
                            advance();
                            if (pos + 4 > input.length()) {
                                return error("Incomplete \\uXXXX escape in JSON string");
                            }
                            int codeUnit = 0;
                            for (int i = 0; i < 4; i++) {
                                char hex = currentChar();
                                int digit = Character.digit(hex, 16);
                                if (digit < 0) {
                                    return error("Invalid hex digit '" + hex + "' in \\uXXXX escape");
                                }
                                codeUnit = (codeUnit << 4) | digit;
                                advance();
                            }
                            sb.append((char) codeUnit);
                        }
                        default -> {
                            return error("Invalid escape sequence '\\" + esc + "' in JSON string");
                        }
                    }
                } else {
                    sb.append(c);
                    advance();
                }
            }
            return error("Unterminated JSON string");
        }

        private Result<JsonValue, DomainError> parseLiteral(String literal, JsonValue value) {
            for (int i = 0; i < literal.length(); i++) {
                if (isEof() || currentChar() != literal.charAt(i)) {
                    return error("Expected '" + literal + "'");
                }
                advance();
            }
            return Result.ok(value);
        }

        private Result<JsonValue, DomainError> parseNumber() {
            int start = pos;
            if (currentChar() == '-') {
                advance();
                if (isEof() || !isDigit(currentChar())) {
                    return error("Invalid negative number");
                }
            }
            if (currentChar() == '0') {
                advance();
                if (!isEof() && isDigit(currentChar())) {
                    return error("Leading zeros are not permitted in JSON numbers");
                }
            } else if (isDigit1to9(currentChar())) {
                while (!isEof() && isDigit(currentChar())) {
                    advance();
                }
            } else {
                return error("Invalid number");
            }

            if (!isEof() && currentChar() == '.') {
                advance();
                if (isEof() || !isDigit(currentChar())) {
                    return error("Decimal point must be followed by at least one digit");
                }
                while (!isEof() && isDigit(currentChar())) {
                    advance();
                }
            }

            if (!isEof() && (currentChar() == 'e' || currentChar() == 'E')) {
                advance();
                if (!isEof() && (currentChar() == '+' || currentChar() == '-')) {
                    advance();
                }
                if (isEof() || !isDigit(currentChar())) {
                    return error("Exponent must contain at least one digit");
                }
                while (!isEof() && isDigit(currentChar())) {
                    advance();
                }
            }

            return Result.ok(new JsonValue.JsonNumber(input.substring(start, pos)));
        }

        private void skipWhitespace() {
            while (!isEof()) {
                char c = currentChar();
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    advance();
                } else {
                    break;
                }
            }
        }

        private boolean isEof() {
            return pos >= input.length();
        }

        private char currentChar() {
            return input.charAt(pos);
        }

        private void advance() {
            if (input.charAt(pos) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
            pos++;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isDigit1to9(char c) {
            return c >= '1' && c <= '9';
        }

        private <T> Result<T, DomainError> error(String message) {
            return Result.err(DomainError.of(
                "JSON_PARSE_ERROR",
                message,
                Map.of("line", Integer.toString(line), "column", Integer.toString(col))
            ));
        }
    }
}
