package procomp.web;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.web.StaticAssets.AssetResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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

    public static void testDiscoverDefaultLocatesRepoWebRootAndPrefersCodeSourceOverCwd() throws IOException {
        StaticAssets discovered = Assert.assertOk(StaticAssets.discoverDefault(), "discoverDefault locates repo web/");
        Assert.assertTrue(Files.isRegularFile(discovered.webRoot().resolve("index.html")), "discovered web/ has index.html");
        Assert.assertTrue(Files.isRegularFile(discovered.webRoot().resolve("app.js")), "discovered web/ has app.js");
        String indexHtml = Files.readString(discovered.webRoot().resolve("index.html"), StandardCharsets.UTF_8);
        String appJs = Files.readString(discovered.webRoot().resolve("app.js"), StandardCharsets.UTF_8);
        String apiJs = Files.readString(discovered.webRoot().resolve("api.js"), StandardCharsets.UTF_8);
        String stylesCss = Files.readString(discovered.webRoot().resolve("styles.css"), StandardCharsets.UTF_8);
        Assert.assertContains(indexHtml, "btn-gateway-restart", "index.html includes gateway restart button");
        Assert.assertContains(appJs, "badge-stopping", "app.js maps STOPPING health badge");
        Assert.assertContains(appJs, "handleRestartServices", "app.js wires service restart handler");
        Assert.assertContains(apiJs, "/api/services/restart/stream", "api.js targets restart SSE stream");
        Assert.assertContains(stylesCss, ".badge-stopping", "styles.css styles STOPPING badge");

        Path tempRoot = Files.createTempDirectory("procomp-static-discover-test");
        try {
            // Simulate procomp repo root (CodeSource) vs developer project dir (CWD) that also has web/index.html
            Path repoRoot = tempRoot.resolve("procomp-repo");
            Path repoWeb = repoRoot.resolve("web");
            Path repoClasses = repoRoot.resolve("build/classes/java/main");
            Files.createDirectories(repoWeb);
            Files.createDirectories(repoClasses);
            Files.writeString(repoWeb.resolve("index.html"), "procomp-ui", StandardCharsets.UTF_8);

            Path userProject = tempRoot.resolve("user-app");
            Path userWeb = userProject.resolve("web");
            Files.createDirectories(userWeb);
            Files.writeString(userWeb.resolve("index.html"), "user-app-web", StandardCharsets.UTF_8);

            // CodeSource search root wins over CWD search root
            StaticAssets preferred = Assert.assertOk(
                StaticAssets.discoverFrom(List.of(repoClasses, userProject)),
                "CodeSource root takes precedence over CWD"
            );
            Assert.assertEquals(repoWeb.toAbsolutePath().normalize(), preferred.webRoot(), "resolves repo web/ first");

            // When CodeSource does not locate web/index.html, falls back to CWD
            Path unrelatedCodeSource = tempRoot.resolve("tmp-classes");
            Files.createDirectories(unrelatedCodeSource);
            StaticAssets fallback = Assert.assertOk(
                StaticAssets.discoverFrom(List.of(unrelatedCodeSource, userProject)),
                "falls back to CWD when CodeSource has no web/index.html"
            );
            Assert.assertEquals(userWeb.toAbsolutePath().normalize(), fallback.webRoot(), "falls back to CWD web/");

            // Directory with web/ but no index.html is ignored
            Path emptyWebProject = tempRoot.resolve("empty-web-project");
            Files.createDirectories(emptyWebProject.resolve("web"));
            DomainError notFound = Assert.assertErr(
                StaticAssets.discoverFrom(List.of(emptyWebProject)),
                "web/ without index.html is not accepted"
            );
            Assert.assertEquals("WEB_ASSETS_NOT_FOUND", notFound.code(), "WEB_ASSETS_NOT_FOUND code");
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
