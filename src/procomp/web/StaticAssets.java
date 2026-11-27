package procomp.web;

import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
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
import java.util.jar.JarEntry;

/**
 * Static frontend asset resolver for zero-build Vanilla ES6 assets under {@code web/},
 * backed either by a filesystem directory (JEP 458 / local dev) or by JAR/classpath resources
 * (standalone {@code procomp.jar}). Enforces strict normalized path-traversal protection and
 * serves assets with {@code Cache-Control: no-cache}.
 */
public final class StaticAssets {

    public static final String CACHE_CONTROL_NO_CACHE = "no-cache";
    public static final String DEFAULT_RESOURCE_PREFIX = "web";

    public sealed interface Source permits Source.Directory, Source.Classpath {

        record Directory(Path webRoot) implements Source {
            public Directory {
                webRoot = Objects.requireNonNull(webRoot, "webRoot must not be null").toAbsolutePath().normalize();
            }
        }

        record Classpath(ClassLoader classLoader, String resourcePrefix) implements Source {
            public Classpath {
                Objects.requireNonNull(classLoader, "classLoader must not be null");
                Objects.requireNonNull(resourcePrefix, "resourcePrefix must not be null");
                resourcePrefix = normalizeResourcePrefix(resourcePrefix);
            }
        }
    }

    private final Source source;

    private StaticAssets(Source source) {
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    public static StaticAssets fromDirectory(Path webRoot) {
        return new StaticAssets(new Source.Directory(webRoot));
    }

    public static StaticAssets fromClasspath(ClassLoader classLoader, String resourcePrefix) {
        return new StaticAssets(new Source.Classpath(classLoader, resourcePrefix));
    }

    /**
     * Discovers static web assets in priority order:
     * <ol>
     *   <li>If running from a packaged {@code .jar} that bundles {@code web/index.html}, serve directly from the JAR classpath.</li>
     *   <li>Walk up from {@code StaticAssets}'s {@link CodeSource} (procomp's own repository/install root) for disk-backed {@code web/index.html}.</li>
     *   <li>Fall back to {@code web/index.html} on the classpath if present.</li>
     *   <li>Fall back to walking up from the current working directory.</li>
     * </ol>
     */
    public static Result<StaticAssets, DomainError> discoverDefault() {
        Optional<Path> codeSource = codeSourcePath();
        ClassLoader classLoader = defaultClassLoader();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        return discoverDefault(codeSource, classLoader, DEFAULT_RESOURCE_PREFIX, cwd);
    }

    static Result<StaticAssets, DomainError> discoverDefault(
        Optional<Path> codeSource,
        ClassLoader classLoader,
        String resourcePrefix,
        Path cwd
    ) {
        if (codeSource.isPresent() && isJarFile(codeSource.get()) && hasClasspathIndex(classLoader, resourcePrefix)) {
            return Result.ok(fromClasspath(classLoader, resourcePrefix));
        }

        if (codeSource.isPresent()) {
            Optional<Path> found = findWebRootWalkingUp(codeSource.get());
            if (found.isPresent()) {
                return Result.ok(fromDirectory(found.get()));
            }
        }

        if (hasClasspathIndex(classLoader, resourcePrefix)) {
            return Result.ok(fromClasspath(classLoader, resourcePrefix));
        }

        return discoverFrom(List.of(cwd));
    }

    static Result<StaticAssets, DomainError> discoverFrom(List<Path> searchRoots) {
        for (Path root : searchRoots) {
            Optional<Path> found = findWebRootWalkingUp(root);
            if (found.isPresent()) {
                return Result.ok(fromDirectory(found.get()));
            }
        }

        Path cwd = Path.of("").toAbsolutePath().normalize();
        return Result.err(DomainError.of(
            "WEB_ASSETS_NOT_FOUND",
            "Could not locate static web assets directory 'web/' relative to '" + cwd + "'",
            Map.of("cwd", cwd.toString())
        ));
    }

    private static ClassLoader defaultClassLoader() {
        ClassLoader cl = StaticAssets.class.getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    private static boolean isJarFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static boolean hasClasspathIndex(ClassLoader classLoader, String resourcePrefix) {
        String normalized = normalizeResourcePrefix(resourcePrefix);
        String indexPath = normalized.isEmpty() ? "index.html" : normalized + "/index.html";
        return classLoader.getResource(indexPath) != null;
    }

    private static String normalizeResourcePrefix(String rawPrefix) {
        String trimmed = rawPrefix.trim();
        int start = 0;
        while (start < trimmed.length() && trimmed.charAt(start) == '/') {
            start++;
        }
        int end = trimmed.length();
        while (end > start && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(start, end);
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

    public Source source() {
        return source;
    }

    public Optional<Path> webRoot() {
        return switch (source) {
            case Source.Directory(Path root) -> Optional.of(root);
            case Source.Classpath _ -> Optional.empty();
        };
    }

    /**
     * Resolves an HTTP request path (such as {@code "/"} or {@code "/app.js"}) against the configured asset source.
     */
    public AssetResult resolve(String rawRequestPath) {
        Objects.requireNonNull(rawRequestPath, "rawRequestPath must not be null");
        if (rawRequestPath.indexOf('\0') >= 0 || rawRequestPath.indexOf('\\') >= 0) {
            return AssetResult.Forbidden.INSTANCE;
        }

        String decoded;
        try {
            decoded = percentDecodePath(rawRequestPath);
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

        return switch (source) {
            case Source.Directory(Path webRoot) -> resolveFromDirectory(webRoot, relative);
            case Source.Classpath(ClassLoader classLoader, String prefix) ->
                resolveFromClasspath(classLoader, prefix, relative);
        };
    }

    private static AssetResult resolveFromDirectory(Path webRoot, String relative) {
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

    private static AssetResult resolveFromClasspath(ClassLoader classLoader, String prefix, String relative) {
        if (relative.endsWith("/")) {
            return AssetResult.NotFound.INSTANCE;
        }

        List<String> cleanSegments = new ArrayList<>();
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                return AssetResult.NotFound.INSTANCE;
            }
            cleanSegments.add(segment);
        }
        if (cleanSegments.isEmpty()) {
            return AssetResult.NotFound.INSTANCE;
        }

        String normalizedRelative = String.join("/", cleanSegments);
        String resourcePath = prefix.isEmpty() ? normalizedRelative : prefix + "/" + normalizedRelative;
        URL resourceUrl = classLoader.getResource(resourcePath);
        if (resourceUrl == null) {
            return AssetResult.NotFound.INSTANCE;
        }

        try {
            if ("file".equals(resourceUrl.getProtocol())) {
                Path filePath = Path.of(resourceUrl.toURI());
                if (!Files.isRegularFile(filePath)) {
                    return AssetResult.NotFound.INSTANCE;
                }
            }
            URLConnection connection = resourceUrl.openConnection();
            connection.setUseCaches(false);
            if (connection instanceof JarURLConnection jarConn) {
                JarEntry entry = jarConn.getJarEntry();
                if (entry == null || entry.isDirectory()) {
                    return AssetResult.NotFound.INSTANCE;
                }
            }
            byte[] bytes;
            try (InputStream in = connection.getInputStream()) {
                bytes = in.readAllBytes();
            }
            String fileName = cleanSegments.get(cleanSegments.size() - 1);
            String contentType = mimeTypeFor(fileName);
            return new AssetResult.Found(bytes, contentType, CACHE_CONTROL_NO_CACHE);
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            return AssetResult.NotFound.INSTANCE;
        }
    }

    /**
     * RFC 3986 percent-decoding for URI path segments (preserves literal {@code '+'} unlike {@code URLDecoder}).
     */
    static String percentDecodePath(String raw) {
        if (raw.indexOf('%') < 0) {
            return raw;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length());
        int len = raw.length();
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= len) {
                    throw new IllegalArgumentException("Incomplete percent escape in path: " + raw);
                }
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("Invalid hex in percent escape: " + raw);
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(utf8, 0, utf8.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
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
