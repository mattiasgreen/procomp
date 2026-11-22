package procomp.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.LogsShell;
import procomp.shell.ProcompRuntime;
import procomp.web.ApiRouter.ApiRequest;
import procomp.web.ApiRouter.ApiResponse;
import procomp.web.StaticAssets.AssetResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Localhost-only JDK 25 {@link HttpServer} hosting the zero-build Vanilla ES6 Web UI and
 * {@link ApiRouter} HTTP/SSE endpoints on virtual threads.
 *
 * <p>Stopping {@code WebUi} closes all active SSE streams and shuts down the HTTP server without
 * touching any {@code Managed} Service or {@code Gateway} {@code Process}.</p>
 */
public final class WebUi implements AutoCloseable {

    public static final String BIND_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 9090;
    public static final Duration DEFAULT_SSE_HEARTBEAT = Duration.ofSeconds(1);

    private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;
    private static final byte[] LOOPBACK_IPV4 = {127, 0, 0, 1};

    private final ProcompRuntime runtime;
    private final LiveProjectHolder projectHolder;
    private final StaticAssets staticAssets;
    private final int requestedPort;
    private final Duration logsPollInterval;
    private final Duration sseHeartbeatInterval;
    private final Set<SseStream> activeStreams = ConcurrentHashMap.newKeySet();
    private volatile Optional<RunningState> state = Optional.empty();

    public WebUi(
        ProcompRuntime runtime,
        LiveProjectHolder projectHolder,
        StaticAssets staticAssets,
        int requestedPort
    ) {
        this(runtime, projectHolder, staticAssets, requestedPort, LogsShell.DEFAULT_POLL_INTERVAL, DEFAULT_SSE_HEARTBEAT);
    }

    public WebUi(
        ProcompRuntime runtime,
        LiveProjectHolder projectHolder,
        StaticAssets staticAssets,
        int requestedPort,
        Duration logsPollInterval,
        Duration sseHeartbeatInterval
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.projectHolder = Objects.requireNonNull(projectHolder, "projectHolder must not be null");
        this.staticAssets = Objects.requireNonNull(staticAssets, "staticAssets must not be null");
        if (requestedPort < 0 || requestedPort > 65535) {
            throw new IllegalArgumentException("requestedPort must be in 0..65535: " + requestedPort);
        }
        this.requestedPort = requestedPort;
        this.logsPollInterval = Objects.requireNonNull(logsPollInterval, "logsPollInterval must not be null");
        this.sseHeartbeatInterval = Objects.requireNonNull(sseHeartbeatInterval, "sseHeartbeatInterval must not be null");
    }

    /**
     * Binds strictly to {@code 127.0.0.1:<requestedPort>} and starts serving requests on virtual threads.
     *
     * @return the actual bound port (especially useful when {@code requestedPort == 0}), or a {@link DomainError}.
     */
    public synchronized Result<Integer, DomainError> start() {
        Optional<RunningState> existing = state;
        if (existing.isPresent()) {
            return Result.ok(existing.get().port());
        }

        HttpServer server;
        try {
            InetSocketAddress bindAddress = new InetSocketAddress(InetAddress.getByAddress(LOOPBACK_IPV4), requestedPort);
            server = HttpServer.create(bindAddress, 0);
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "WEB_UI_BIND_FAILED",
                "Failed to bind Web UI server to " + BIND_HOST + ":" + requestedPort + ": " + e.getMessage(),
                Map.of("host", BIND_HOST, "port", Integer.toString(requestedPort))
            ));
        }

        int actualPort = server.getAddress().getPort();
        ApiRouter router = new ApiRouter(runtime, projectHolder, actualPort, logsPollInterval);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> handleExchange(exchange, router));
        server.start();

        state = Optional.of(new RunningState(server, executor, actualPort));
        return Result.ok(actualPort);
    }

    /**
     * Stops the HTTP server and closes all active SSE streams without touching any {@code Managed}
     * Service or {@code Gateway} {@code Process}. Idempotent.
     */
    public synchronized void stop() {
        Optional<RunningState> runningOpt = state;
        state = Optional.empty();
        for (SseStream stream : activeStreams) {
            stream.close();
        }
        activeStreams.clear();
        if (runningOpt.isEmpty()) {
            return;
        }
        RunningState running = runningOpt.get();
        running.server().stop(0);
        running.executor().shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return state.isPresent();
    }

    public int port() {
        return state
            .map(RunningState::port)
            .orElseThrow(() -> new IllegalStateException("WebUi is not started"));
    }

    public String baseUrl() {
        return "http://" + BIND_HOST + ":" + port();
    }

    public int activeSseStreamCount() {
        return activeStreams.size();
    }

    /**
     * Best-effort OS browser launch for {@code procomp ui --open}. Never throws.
     */
    public static boolean openBrowser(String url) {
        Objects.requireNonNull(url, "url must not be null");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd;
        if (os.contains("win")) {
            cmd = List.of("rundll32", "url.dll,FileProtocolHandler", url);
        } else if (os.contains("mac")) {
            cmd = List.of("open", url);
        } else {
            cmd = List.of("xdg-open", url);
        }
        try {
            new ProcessBuilder(cmd).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void handleExchange(HttpExchange exchange, ApiRouter router) {
        try {
            String method = exchange.getRequestMethod();
            URI uri = exchange.getRequestURI();
            String rawPath = uri.getRawPath() == null ? "/" : uri.getRawPath();
            String rawQuery = uri.getRawQuery() == null ? "" : uri.getRawQuery();

            if ("/api".equals(rawPath) || rawPath.startsWith("/api/")) {
                handleApi(exchange, router, method, rawPath, rawQuery);
            } else {
                handleStatic(exchange, method, rawPath);
            }
        } catch (IOException ignored) {
            // Client disconnected before response finished.
        } finally {
            exchange.close();
        }
    }

    private void handleApi(
        HttpExchange exchange,
        ApiRouter router,
        String method,
        String rawPath,
        String rawQuery
    ) throws IOException {
        Map<String, String> headers = flattenHeaders(exchange.getRequestHeaders());

        if (router.isSseEndpoint(method, rawPath)) {
            ApiRequest request = ApiRequest.of(method, rawPath, rawQuery, headers, "");
            Optional<ApiResponse> preflightError = router.preflightSse(request);
            if (preflightError.isPresent()) {
                sendApiResponse(exchange, preflightError.get());
                return;
            }
            Headers respHeaders = exchange.getResponseHeaders();
            respHeaders.set("Content-Type", SseStream.CONTENT_TYPE);
            respHeaders.set("Cache-Control", "no-cache");
            respHeaders.set("Connection", "keep-alive");
            respHeaders.set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);

            SseStream stream = new SseStream(exchange.getResponseBody());
            activeStreams.add(stream);
            Thread heartbeat = Thread.startVirtualThread(() -> runSseHeartbeat(stream));
            try {
                router.handleSse(request, stream);
            } finally {
                stream.close();
                heartbeat.interrupt();
                activeStreams.remove(stream);
            }
            return;
        }

        String body = readBodyUtf8(exchange.getRequestBody());
        ApiRequest request = ApiRequest.of(method, rawPath, rawQuery, headers, body);
        sendApiResponse(exchange, router.handle(request));
    }

    private static void sendApiResponse(HttpExchange exchange, ApiResponse response) throws IOException {
        byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
        Headers respHeaders = exchange.getResponseHeaders();
        respHeaders.set("Content-Type", response.contentType());
        respHeaders.set("Cache-Control", "no-store");
        if (payload.length == 0) {
            exchange.sendResponseHeaders(response.statusCode(), -1);
        } else {
            exchange.sendResponseHeaders(response.statusCode(), payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        }
    }

    private void runSseHeartbeat(SseStream stream) {
        long sleepMillis = Math.max(25L, sseHeartbeatInterval.toMillis());
        while (stream.isOpen() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!stream.isOpen()) {
                return;
            }
            if (!stream.sendComment("keepalive")) {
                return;
            }
        }
    }

    private void handleStatic(HttpExchange exchange, String method, String rawPath) throws IOException {
        boolean isGet = "GET".equalsIgnoreCase(method);
        boolean isHead = "HEAD".equalsIgnoreCase(method);
        if (!isGet && !isHead) {
            sendPlainText(exchange, 405, "Method Not Allowed");
            return;
        }

        AssetResult result = staticAssets.resolve(rawPath);
        switch (result) {
            case AssetResult.Found found -> {
                byte[] bytes = found.bytes();
                Headers respHeaders = exchange.getResponseHeaders();
                respHeaders.set("Content-Type", found.contentType());
                respHeaders.set("Cache-Control", found.cacheControl());
                if (isHead) {
                    respHeaders.set("Content-Length", Integer.toString(bytes.length));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                }
            }
            case AssetResult.NotFound _ -> sendPlainText(exchange, 404, "Not Found");
            case AssetResult.Forbidden _ -> sendPlainText(exchange, 403, "Forbidden");
        }
    }

    private static void sendPlainText(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> flattenHeaders(Headers requestHeaders) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : requestHeaders.entrySet()) {
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                result.put(entry.getKey(), String.join(", ", values));
            }
        }
        return result;
    }

    private static String readBodyUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > MAX_REQUEST_BODY_BYTES) {
                throw new IOException("Request body exceeded limit of " + MAX_REQUEST_BODY_BYTES + " bytes");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private record RunningState(HttpServer server, ExecutorService executor, int port) {}
}
