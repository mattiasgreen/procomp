package procomp.core.common;

import procomp.Assert;

import java.util.Map;

public final class ResultTest {

    public static void testOkAndMapAndFlatMap() {
        Result<Integer, DomainError> res = Result.ok(21);
        Assert.assertTrue(res.isOk(), "Result.ok should be Ok");
        Assert.assertFalse(res.isErr(), "Result.ok should not be Err");

        Result<Integer, DomainError> doubled = res.map(x -> x * 2);
        Assert.assertEquals(42, Assert.assertOk(doubled, "map should transform value"), "mapped value");

        Result<String, DomainError> chained = doubled.flatMap(x -> Result.ok("val=" + x));
        Assert.assertEquals("val=42", Assert.assertOk(chained, "flatMap should chain Ok"), "chained value");
    }

    public static void testErrPreservesErrorThroughMapAndFlatMap() {
        DomainError error = DomainError.of("CONFIG_INVALID", "Invalid config", Map.of("field", "project"));
        Result<Integer, DomainError> res = Result.err(error);

        Assert.assertFalse(res.isOk(), "Result.err should not be Ok");
        Assert.assertTrue(res.isErr(), "Result.err should be Err");

        Result<String, DomainError> mapped = res.map(x -> "never" + x).flatMap(s -> Result.ok(s));
        DomainError actual = Assert.assertErr(mapped, "Err should bypass map/flatMap");
        Assert.assertEquals("CONFIG_INVALID", actual.code(), "error code");
        Assert.assertEquals("Invalid config", actual.message(), "error message");
        Assert.assertEquals("project", actual.details().get("field"), "error details");

        Result<String, DomainError> castErr = res.castError();
        Assert.assertEquals(error, Assert.assertErr(castErr, "castError preserves Err"), "castError error");

        boolean threw = false;
        try {
            Result.ok(1).castError();
        } catch (IllegalStateException expected) {
            threw = true;
        }
        Assert.assertTrue(threw, "castError on Ok throws IllegalStateException");
    }
}
