import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight mock process simulating Spring Boot, Quarkus, ActiveMQ, or WireMock.
 * Exposes a configurable health endpoint after a simulated startup delay, optional
 * WireMock /__admin/mappings endpoint, and realistic text/JSON logs with stack traces.
 */
public class MockService {
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static boolean jsonLogs = false;

    public static void main(String[] args) throws Exception {
        String name = System.getProperty("service.name", "mock-service");
        String type = System.getProperty("service.type", "spring-boot");
        int port = Integer.parseInt(System.getProperty("server.port", "8080"));
        String healthPath = System.getProperty("health.path", "/actuator/health");
        long startupDelayMs = Long.parseLong(System.getProperty("startup.delay.ms", "2500"));
        jsonLogs = "json".equalsIgnoreCase(System.getProperty("log.format", "text"));

        AtomicBoolean ready = new AtomicBoolean(false);
        Random random = new Random();

        log("INFO", name, null, "Starting " + name + " (type=" + type + ", port=" + port + ", health=" + healthPath + ")", null);
        if ("activemq".equalsIgnoreCase(type)) {
            log("INFO", name, null, "Initializing Apache ActiveMQ BrokerService [" + name + "]...", null);
        } else if ("wiremock".equalsIgnoreCase(type)) {
            log("INFO", name, null, "Starting WireMock gateway on port " + port + "...", null);
        } else if ("quarkus".equalsIgnoreCase(type)) {
            log("INFO", name, null, "Bootstrapping Quarkus 3.x fast-jar with profile [local]...", null);
        } else {
            log("INFO", name, null, "Bootstrapping Spring Boot context with active profile [local]...", null);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(healthPath, exchange -> {
            byte[] body;
            int status;
            if (ready.get()) {
                status = 200;
                body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 503;
                body = "{\"status\":\"STARTING\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        if ("wiremock".equalsIgnoreCase(type)) {
            server.createContext("/__admin/mappings", exchange -> {
                String bodyStr = readAll(exchange.getRequestBody());
                String proxyTarget = extractJsonField(bodyStr, "proxyBaseUrl");
                String prefix = extractJsonField(bodyStr, "proxyUrlPrefixToRemove");
                log("INFO", name, null, "Updated proxy route: " + prefix + " -> " + proxyTarget, null);
                byte[] resp = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            });
        }

        server.setExecutor(null);
        server.start();

        Thread.sleep(startupDelayMs);
        ready.set(true);
        log("INFO", name, null, "Started " + name + " in " + String.format("%.2f", startupDelayMs / 1000.0) + "s (port " + port + ")", null);

        int tick = 0;
        while (true) {
            Thread.sleep(2200 + random.nextInt(2200));
            tick++;
            String traceId = "tr-" + String.format("%04x", random.nextInt(0xFFFF));
            if ("activemq".equalsIgnoreCase(type)) {
                log("INFO", name, null, "Broker heartbeat: queues=4, consumers=" + (3 + random.nextInt(5)) + ", inflight=" + random.nextInt(12), null);
            } else if ("wiremock".equalsIgnoreCase(type)) {
                log("INFO", name, traceId, "Proxied GET /orders/api/v1/health -> 200 (12ms)", null);
            } else if (tick % 6 == 0) {
                String stack = "java.lang.IllegalStateException: Downstream timeout for " + name + "\n"
                        + "\tat com.mycorp." + name.replace("-", "") + ".Client.call(Client.java:84)\n"
                        + "\tat com.mycorp." + name.replace("-", "") + ".Handler.handle(Handler.java:42)\n"
                        + "\tCaused by: java.net.SocketTimeoutException: Read timed out";
                log("ERROR", name, traceId, "Failed to process downstream call", stack);
            } else if (tick % 4 == 0) {
                log("WARN", name, traceId, "Slow downstream response detected (latency=" + (180 + random.nextInt(220)) + "ms)", null);
            } else {
                log("INFO", name, traceId, "Processed request status=200", null);
            }
        }
    }

    private static String readAll(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String extractJsonField(String json, String key) {
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return "?";
        int colon = json.indexOf(':', idx + needle.length());
        int q1 = json.indexOf('"', colon + 1);
        int q2 = json.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) return "?";
        return json.substring(q1 + 1, q2);
    }

    private static void log(String level, String service, String traceId, String msg, String stackTrace) {
        if (jsonLogs) {
            String ts = Instant.now().toString();
            StringBuilder sb = new StringBuilder();
            sb.append("{\"@timestamp\":\"").append(ts)
              .append("\",\"level\":\"").append(level)
              .append("\",\"service\":\"").append(service).append("\"");
            if (traceId != null) {
                sb.append(",\"traceId\":\"").append(traceId).append("\"");
            }
            sb.append(",\"message\":\"").append(escapeJson(msg)).append("\"");
            if (stackTrace != null) {
                sb.append(",\"stack_trace\":\"").append(escapeJson(stackTrace)).append("\"");
            }
            sb.append("}");
            System.out.println(sb.toString());
        } else {
            String ts = TS_FMT.format(Instant.now());
            String tTag = (traceId != null) ? " [trace=" + traceId + "]" : "";
            System.out.println(ts + "  " + String.format("%-5s", level) + " [" + service + "]" + tTag + " --- " + msg);
            if (stackTrace != null) {
                System.out.println(stackTrace);
            }
        }
        System.out.flush();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
}
