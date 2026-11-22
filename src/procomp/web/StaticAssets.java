package procomp.web;

import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Disk-backed static frontend asset resolver for zero-build Vanilla ES6 assets under {@code web/}.
 * Enforces strict normalized path-traversal protection and serves assets with {@code Cache-Control: no-cache}.
 */
public final class StaticAssets {

    public static final String CACHE_CONTROL_NO_CACHE = "no-cache";

    private final Path webRoot;

    private StaticAssets(Path webRoot) {
        this.webRoot = Objects.requireNonNull(webRoot, "webRoot must not be null").toAbsolutePath().normalize();
    }

    public static StaticAssets fromDirectory(Path webRoot) {
        return new StaticAssets(webRoot);
    }

    /**
     * Discovers the {@code web/} directory by walking up from {@code StaticAssets}'s
     * {@link CodeSource} (procomp's own repository/install root) first, falling back to the
     * current working directory only when {@code CodeSource} does not locate {@code web/index.html}.
     */
    public static Result<StaticAssets, DomainError> discoverDefault() {
        List<Path> searchRoots = new ArrayList<>(2);
        codeSourcePath().ifPresent(searchRoots::add);
        searchRoots.add(Path.of("").toAbsolutePath().normalize());
        return discoverFrom(searchRoots);
    }

    static Result<StaticAssets, DomainError> discoverFrom(List<Path> searchRoots) {
        for (Path root : searchRoots) {
            Optional<Path> found = findWebRootWalkingUp(root);
            if (found.isPresent()) {
                return Result.ok(new StaticAssets(found.get()));
            }
        }

        Path cwd = Path.of("").toAbsolutePath().normalize();
        return Result.err(DomainError.of(
            "WEB_ASSETS_NOT_FOUND",
            "Could not locate static web assets directory 'web/' relative to '" + cwd + "'",
            Map.of("cwd", cwd.toString())
        ));
    }

    private static Optional<Path> codeSourcePath() {
        CodeSource codeSource = StaticAssets.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> findWebRootWalkingUp(Path start) {
        Path cursor = start.toAbsolutePath().normalize();
        if (Files.isRegularFile(cursor)) {
            cursor = cursor.getParent();
        }
        while (cursor != null) {
            Path candidate = cursor.resolve("web").normalize();
            if (Files.isDirectory(candidate) && Files.isRegularFile(candidate.resolve("index.html"))) {
                return Optional.of(candidate);
            }
            cursor = cursor.getParent();
        }
        return Optional.empty();
    }

    public Path webRoot() {
        return webRoot;
    }

    /**
     * Resolves an HTTP request path (such as {@code "/"} or {@code "/app.js"}) against {@code webRoot}.
     */
    public AssetResult resolve(String rawRequestPath) {
        Objects.requireNonNull(rawRequestPath, "rawRequestPath must not be null");
        if (rawRequestPath.indexOf('\0') >= 0 || rawRequestPath.indexOf('\\') >= 0) {
            return AssetResult.Forbidden.INSTANCE;
        }

        String decoded;
        try {
            decoded = URLDecoder.decode(rawRequestPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return AssetResult.Forbidden.INSTANCE;
        }

        if (decoded.indexOf('\0') >= 0 || decoded.indexOf('\\') >= 0) {
            return AssetResult.Forbidden.INSTANCE;
        }

        String relative = decoded.isEmpty() || "/".equals(decoded)
            ? "index.html"
            : stripLeadingSlashes(decoded);

        if (relative.isEmpty()) {
            relative = "index.html";
        }

        for (String segment : relative.split("/", -1)) {
            if ("..".equals(segment)) {
                return AssetResult.Forbidden.INSTANCE;
            }
        }

        Path resolved;
        try {
            resolved = webRoot.resolve(relative).normalize();
        } catch (InvalidPathException e) {
            return AssetResult.Forbidden.INSTANCE;
        }

        if (!resolved.startsWith(webRoot)) {
            return AssetResult.Forbidden.INSTANCE;
        }

        if (!Files.isRegularFile(resolved)) {
            return AssetResult.NotFound.INSTANCE;
        }

        try {
            Path realRoot = webRoot.toRealPath();
            Path realFile = resolved.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                return AssetResult.Forbidden.INSTANCE;
            }
            byte[] bytes = Files.readAllBytes(realFile);
            String contentType = mimeTypeFor(realFile.getFileName().toString());
            return new AssetResult.Found(bytes, contentType, CACHE_CONTROL_NO_CACHE);
        } catch (IOException e) {
            return AssetResult.NotFound.INSTANCE;
        }
    }

    private static String stripLeadingSlashes(String path) {
        int i = 0;
        while (i < path.length() && path.charAt(i) == '/') {
            i++;
        }
        return path.substring(i);
    }

    public static String mimeTypeFor(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        return "application/octet-stream";
    }

    public sealed interface AssetResult permits AssetResult.Found, AssetResult.NotFound, AssetResult.Forbidden {

        final class Found implements AssetResult {
            private final byte[] bytes;
            private final String contentType;
            private final String cacheControl;

            public Found(byte[] bytes, String contentType, String cacheControl) {
                this.bytes = Objects.requireNonNull(bytes, "bytes must not be null").clone();
                this.contentType = Objects.requireNonNull(contentType, "contentType must not be null");
                this.cacheControl = Objects.requireNonNull(cacheControl, "cacheControl must not be null");
            }

            public byte[] bytes() {
                return bytes.clone();
            }

            public String contentType() {
                return contentType;
            }

            public String cacheControl() {
                return cacheControl;
            }
        }

        record NotFound() implements AssetResult {
            public static final NotFound INSTANCE = new NotFound();
        }

        record Forbidden() implements AssetResult {
            public static final Forbidden INSTANCE = new Forbidden();
        }
    }
}
