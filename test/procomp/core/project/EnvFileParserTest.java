package procomp.core.project;

import procomp.Assert;
import procomp.core.common.DomainError;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EnvFileParserTest {

    public static void testParsesDotEnvWithCommentsQuotesAndExports() {
        String content = """
            # Database settings
            DB_HOST=localhost
            export DB_PORT = 5432 # inline comment
            API_KEY="secret\\nline2\\"q\\""
            RAW_PASS='p@ss#123$raw'
            HASH_NO_SPACE=abc#123
            EMPTY_VAL=
            """;

        Map<String, String> parsed = Assert.assertOk(
            EnvFileParser.parse(".env", content),
            "valid .env should parse"
        );

        Assert.assertEquals("localhost", parsed.get("DB_HOST"), "plain key=val");
        Assert.assertEquals("5432", parsed.get("DB_PORT"), "export with inline comment");
        Assert.assertEquals("secret\nline2\"q\"", parsed.get("API_KEY"), "double-quoted escapes");
        Assert.assertEquals("p@ss#123$raw", parsed.get("RAW_PASS"), "single-quoted literal");
        Assert.assertEquals("abc#123", parsed.get("HASH_NO_SPACE"), "# without preceding space stays literal");
        Assert.assertEquals("", parsed.get("EMPTY_VAL"), "empty unquoted value");
    }

    public static void testRejectsMalformedEnvLines() {
        Assert.assertErr(EnvFileParser.parse(".env", "NO_EQUALS"), "missing '=' must fail");
        Assert.assertErr(EnvFileParser.parse(".env", "123BAD=val"), "invalid key must fail");
        Assert.assertErr(EnvFileParser.parse(".env", "A=\"unclosed"), "unclosed double quote must fail");
        Assert.assertErr(EnvFileParser.parse(".env", "A='unclosed"), "unclosed single quote must fail");
        Assert.assertErr(EnvFileParser.parse(".env", "A=\"ok\" trailing"), "trailing text after quote must fail");
    }

    public static void testSecretEnvRedactsValuesAndExpandsHostPassThrough() {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("DB_PASSWORD", "super-secret-value-999");
        secrets.put("API_TOKEN", "tok-12345");
        SecretEnv secretEnv = SecretEnv.of(secrets);

        Assert.assertNotContains(secretEnv.toString(), "super-secret-value-999", "SecretEnv.toString must redact values");
        Assert.assertNotContains(secretEnv.toString(), "tok-12345", "SecretEnv.toString must redact all values");
        Assert.assertContains(secretEnv.toString(), "DB_PASSWORD", "SecretEnv.toString may expose key names");

        String expanded = Assert.assertOk(
            EnvFileParser.expandHostEnvRefs("Bearer ${env:MY_TOKEN}", Map.of("MY_TOKEN", "secret-tok"), "defaults.env.AUTH"),
            "set host env var should expand"
        );
        Assert.assertEquals("Bearer secret-tok", expanded, "host env expanded");

        DomainError missingErr = Assert.assertErr(
            EnvFileParser.expandHostEnvRefs("${env:UNSET_VAR}", Map.of(), "defaults.env.DB_PASSWORD"),
            "unset host env var must fail fast"
        );
        Assert.assertEquals("CONFIG_UNSET_ENV_VAR", missingErr.code(), "error code for unset host env");
        Assert.assertContains(missingErr.message(), "UNSET_VAR", "error mentions missing variable name");
    }
}
