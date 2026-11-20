package procomp.core.common;

import procomp.Assert;

import java.util.List;
import java.util.Map;

public final class JsonTest {

    public static void testParsePrimitivesAndPreservesObjectKeyOrder() {
        String input = """
            {
              "zKey": "hello\\nworld\\t\\"quoted\\"\\\\slash\\/\\u0041",
              "aInt": 8080,
              "negInt": -42,
              "dec": 12.5,
              "flag": true,
              "off": false,
              "empty": null,
              "list": [1, "two", false, null, {"nested": 9}]
            }
            """;

        JsonValue parsed = Assert.assertOk(Json.parse(input), "valid JSON should parse");
        Assert.assertTrue(parsed instanceof JsonValue.JsonObject, "root should be JsonObject");
        var obj = (JsonValue.JsonObject) parsed;

        Assert.assertEquals(
            List.of("zKey", "aInt", "negInt", "dec", "flag", "off", "empty", "list"),
            List.copyOf(obj.fields().keySet()),
            "object key insertion order must be preserved"
        );

        Assert.assertEquals(
            new JsonValue.JsonString("hello\nworld\t\"quoted\"\\slash/A"),
            obj.fields().get("zKey"),
            "string escapes including \\uXXXX should decode"
        );

        var aInt = (JsonValue.JsonNumber) obj.fields().get("aInt");
        Assert.assertTrue(aInt.isInteger(), "8080 should be integer");
        Assert.assertEquals(8080, aInt.asInt().orElseThrow(), "8080 as int");

        var dec = (JsonValue.JsonNumber) obj.fields().get("dec");
        Assert.assertFalse(dec.isInteger(), "12.5 should not be integer");

        Assert.assertEquals(new JsonValue.JsonBoolean(true), obj.fields().get("flag"), "true boolean");
        Assert.assertEquals(new JsonValue.JsonBoolean(false), obj.fields().get("off"), "false boolean");
        Assert.assertEquals(JsonValue.JsonNull.INSTANCE, obj.fields().get("empty"), "null value");

        var list = (JsonValue.JsonArray) obj.fields().get("list");
        Assert.assertEquals(5, list.elements().size(), "array length");
        Assert.assertTrue(list.elements().get(0) instanceof JsonValue.JsonNumber, "array[0] is number");
        Assert.assertTrue(list.elements().get(1) instanceof JsonValue.JsonString, "array[1] is string");
    }

    public static void testDistinguishesIntegerFromStringAndRejectsMalformedJson() {
        var numObj = (JsonValue.JsonObject) Assert.assertOk(Json.parse("{\"num\": 9001, \"str\": \"9001\"}"), "parse");
        Assert.assertTrue(numObj.fields().get("num") instanceof JsonValue.JsonNumber, "9001 is JsonNumber");
        Assert.assertTrue(numObj.fields().get("str") instanceof JsonValue.JsonString, "\"9001\" is JsonString");

        Assert.assertEquals(Json.obj(Map.of()), Assert.assertOk(Json.parse("{}"), "empty object parses"), "empty obj");

        List<String> invalidInputs = List.of(
            "",
            "   ",
            "{\"a\": 1} trailing",
            "{\"a\": 1, \"a\": 2}",
            "{\"a\": 1,}",
            "[1, 2,]",
            "{\"a\": \"bad\\xescape\"}",
            "{\"a\": \"bad\\u12Z4\"}",
            "{\"a\": \"raw\nnewline\"}",
            "{\"a\": 01}",
            "{\"a\": +1}",
            "{\"a\": 1.}",
            "{\"a\": .5}",
            "{\"a\": 1e}",
            "{unquoted: 1}",
            "'single'"
        );

        for (String bad : invalidInputs) {
            DomainError err = Assert.assertErr(Json.parse(bad), "should reject malformed JSON: " + bad);
            Assert.assertEquals("JSON_PARSE_ERROR", err.code(), "error code for: " + bad);
        }
    }

    public static void testStringifyAndRoundTripPreservesOrderAndEscapes() {
        var obj = Json.obj(mapOfOrdered(
            "b", Json.str("line1\nline2\"q\"\\b"),
            "a", Json.num(42),
            "c", Json.arr(List.of(Json.bool(true), Json.nil(), Json.obj(Map.of("k", Json.str("v")))))
        ));

        String compact = Json.stringify(obj);
        Assert.assertEquals(
            "{\"b\":\"line1\\nline2\\\"q\\\"\\\\b\",\"a\":42,\"c\":[true,null,{\"k\":\"v\"}]}",
            compact,
            "compact stringify preserves order and escapes"
        );

        String pretty = Json.stringifyPretty(obj);
        JsonValue reparsed = Assert.assertOk(Json.parse(pretty), "pretty JSON should reparse");
        Assert.assertEquals(obj, reparsed, "pretty round-trip equals original");
    }

    private static java.util.LinkedHashMap<String, JsonValue> mapOfOrdered(
        String k1, JsonValue v1,
        String k2, JsonValue v2,
        String k3, JsonValue v3
    ) {
        var m = new java.util.LinkedHashMap<String, JsonValue>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return m;
    }
}
