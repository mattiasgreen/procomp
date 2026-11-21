package e2e.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@SpringBootApplication
@RestController
public class OrdersApplication {

    private final RestClient restClient;
    private final JmsTemplate jmsTemplate;
    private final String gatewayUrl;

    public OrdersApplication(
        JmsTemplate jmsTemplate,
        @Value("${e2e.gateway-url:http://127.0.0.1:8080}") String gatewayUrl
    ) {
        this.restClient = RestClient.create();
        this.jmsTemplate = jmsTemplate;
        this.gatewayUrl = gatewayUrl;
    }

    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }

    @PostMapping("/create")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> createOrder() {
        Map<String, Object> quote = restClient.get()
            .uri(gatewayUrl + "/pricing/quote")
            .retrieve()
            .body(Map.class);

        String orderId = UUID.randomUUID().toString();
        String pricedBy = quote != null ? String.valueOf(quote.get("servedBy")) : "unknown";
        Object price = quote != null ? quote.get("price") : -1;

        jmsTemplate.convertAndSend("orders.created", orderId + "|" + pricedBy + "|" + price);

        return ResponseEntity.ok(Map.of(
            "orderId", orderId,
            "pricedBy", pricedBy,
            "price", price,
            "servedBy", "local-orders"
        ));
    }
}
