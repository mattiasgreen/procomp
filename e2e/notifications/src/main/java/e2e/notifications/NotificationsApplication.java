package e2e.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
@RestController
public class NotificationsApplication {

    private final Map<String, Map<String, String>> received = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        SpringApplication.run(NotificationsApplication.class, args);
    }

    @JmsListener(destination = "orders.created")
    public void onOrderCreated(String payload) {
        String[] parts = payload.split("\\|", -1);
        if (parts.length >= 3) {
            received.put(parts[0], Map.of(
                "orderId", parts[0],
                "pricedBy", parts[1],
                "price", parts[2],
                "servedBy", "local-notifications"
            ));
        }
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Map<String, String>> getNotification(@PathVariable String orderId) {
        Map<String, String> record = received.get(orderId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }
}
