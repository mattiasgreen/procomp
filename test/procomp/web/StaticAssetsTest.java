package procomp.web;

import procomp.Assert;
import procomp.web.StaticAssets.AssetResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public final class StaticAssetsTest {

    public static void testServesStaticAssetsWithMimeTypesAndNoCacheHeader() throws IOException {
        Path tempRoot = Files.createTempDirectory("procomp-static-assets-test");
        try {
            Files.writeString(tempRoot.resolve("index.html"), "<!doctype html><title>procomp</title>", StandardCharsets.UTF_8);
            Files.writeString(tempRoot.resolve("app.js"), "export const x = 1;\n", StandardCharsets.UTF_8);
            Files.writeString(tempRoot.resolve("styles.css"), "body { color: #111; }\n", StandardCharsets.UTF_8);
            Files.writeString(tempRoot.resolve("icon.svg"), "<svg></svg>", StandardCharsets.UTF_8);
            Files.writeString(tempRoot.resolve("manifest.json"), "{\"name\":\"procomp\"}", StandardCharsets.UTF_8);

            StaticAssets assets = StaticAssets.fromDirectory(tempRoot);

            assertFound(assets.resolve("/"), "text/html; charset=utf-8", "no-cache", "<!doctype html><title>procomp</title>");
            assertFound(assets.resolve("/index.html"), "text/html; charset=utf-8", "no-cache", "<!doctype html><title>procomp</title>");
            assertFound(assets.resolve("/app.js"), "text/javascript; charset=utf-8", "no-cache", "export const x = 1;\n");
            assertFound(assets.resolve("/styles.css"), "text/css; charset=utf-8", "no-cache", "body { color: #111; }\n");
            assertFound(assets.resolve("/icon.svg"), "image/svg+xml", "no-cache", "<svg></svg>");
            assertFound(assets.resolve("/manifest.json"), "application/json; charset=utf-8", "no-cache", "{\"name\":\"procomp\"}");

            AssetResult missing = assets.resolve("/does-not-exist.js");
            Assert.assertTrue(missing instanceof AssetResult.NotFound, "missing file returns NotFound, got " + missing);
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    public static void testRejectsPathTraversalAttempts() throws IOException {
        Path tempRoot = Files.createTempDirectory("procomp-static-traversal-test");
        Path webDir = tempRoot.resolve("web");
        Files.createDirectories(webDir);
        try {
            Files.writeString(tempRoot.resolve("secret.txt"), "top-secret", StandardCharsets.UTF_8);
            Files.writeString(webDir.resolve("index.html"), "ok", StandardCharsets.UTF_8);

            StaticAssets assets = StaticAssets.fromDirectory(webDir);

            for (String malicious : new String[]{
                "/../secret.txt",
                "/%2e%2e/secret.txt",
                "/%2e%2e%2fsecret.txt",
                "/..\\secret.txt",
                "/sub/../../secret.txt",
                "/index.html\0.js"
            }) {
                AssetResult res = assets.resolve(malicious);
                Assert.assertTrue(res instanceof AssetResult.Forbidden,
                    "malicious path '" + malicious + "' must return Forbidden, got " + res);
            }
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private static void assertFound(AssetResult result, String expectedMime, String expectedCache, String expectedBody) {
        Assert.assertTrue(result instanceof AssetResult.Found, "expected Found, got " + result);
        AssetResult.Found found = (AssetResult.Found) result;
        Assert.assertEquals(expectedMime, found.contentType(), "contentType");
        Assert.assertEquals(expectedCache, found.cacheControl(), "cacheControl");
        Assert.assertEquals(expectedBody, new String(found.bytes(), StandardCharsets.UTF_8), "body");
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
