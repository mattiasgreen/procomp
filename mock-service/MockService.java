import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight mock process simulating a Spring Boot microservice or ActiveMQ broker.
 * Exposes a configurable health endpoint after a simulated startup delay and emits
 * realistic log lines to stdout/stderr.
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

        log("INFO", name, "Starting " + name + " (type=" + type + ", port=" + port + ", health=" + healthPath + ")");
        if ("activemq".equalsIgnoreCase(type)) {
            log("INFO", name, "Initializing Apache ActiveMQ BrokerService [" + name + "]...");
        } else {
            log("INFO", name, "Bootstrapping Spring Boot context with active profile [local]...");
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
        server.setExecutor(null);
        server.start();

        Thread.sleep(startupDelayMs);
        ready.set(true);
        log("INFO", name, "Started " + name + " in " + String.format("%.2f", startupDelayMs / 1000.0) + "s (port " + port + ")");

        // Emit periodic heartbeat / simulated traffic logs
        int tick = 0;
        while (true) {
            Thread.sleep(2500 + random.nextInt(2500));
            tick++;
            if ("activemq".equalsIgnoreCase(type)) {
                log("INFO", name, "Broker heartbeat: queues=4, consumers=" + (3 + random.nextInt(5)) + ", inflight=" + random.nextInt(12));
            } else if (tick % 7 == 0) {
                log("WARN", name, "Slow downstream response detected (latency=" + (180 + random.nextInt(220)) + "ms)");
            } else {
                log("INFO", name, "Processed request corrId=req-" + String.format("%04x", random.nextInt(0xFFFF)) + " status=200");
            }
        }
    }

    private static void log(String level, String service, String msg) {
        if (jsonLogs) {
            String ts = Instant.now().toString();
            String escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"");
            System.out.println("{\"@timestamp\":\"" + ts + "\",\"level\":\"" + level + "\",\"service\":\"" + service + "\",\"message\":\"" + escaped + "\"}");
        } else {
            String ts = TS_FMT.format(Instant.now());
            System.out.println(ts + "  " + String.format("%-5s", level) + " [" + service + "] --- " + msg);
        }
        System.out.flush();
    }
}
