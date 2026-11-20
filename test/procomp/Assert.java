package procomp;

import procomp.core.common.Result;

import java.util.Objects;

public final class Assert {

    private Assert() {}

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected: <" + expected + ">, actual: <" + actual + ">)");
        }
    }

    public static void assertNotEquals(Object unexpected, Object actual, String message) {
        if (Objects.equals(unexpected, actual)) {
            throw new AssertionError(message + " (unexpected equal value: <" + actual + ">)");
        }
    }

    public static void assertNotNull(Object actual, String message) {
        if (actual == null) {
            throw new AssertionError(message + " (expected non-null)");
        }
    }

    public static void assertContains(String haystack, String needle, String message) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError(message + " (expected <" + haystack + "> to contain <" + needle + ">)");
        }
    }

    public static void assertNotContains(String haystack, String needle, String message) {
        if (haystack != null && haystack.contains(needle)) {
            throw new AssertionError(message + " (expected <" + haystack + "> NOT to contain <" + needle + ">)");
        }
    }

    public static <T, E> T assertOk(Result<T, E> result, String message) {
        return switch (result) {
            case Result.Ok<T, E>(T value) -> value;
            case Result.Err<T, E>(E error) -> throw new AssertionError(message + " (expected Ok, got Err: " + error + ")");
        };
    }

    public static <T, E> E assertErr(Result<T, E> result, String message) {
        return switch (result) {
            case Result.Ok<T, E>(T value) -> throw new AssertionError(message + " (expected Err, got Ok: " + value + ")");
            case Result.Err<T, E>(E error) -> error;
        };
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }
}
