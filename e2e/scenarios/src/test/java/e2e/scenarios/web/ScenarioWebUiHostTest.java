package e2e.scenarios.web;

import e2e.scenarios.catalog.ScenarioCatalog;
import e2e.scenarios.engine.ScenarioRunner;
import e2e.scenarios.inmemory.InMemoryWorld;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScenarioWebUiHostTest {

    @Test
    void servesRealWebUiOverInMemoryWorldScenarioState() throws Exception {
        Path webDir = Path.of(System.getProperty("e2e.webDir", "../../web")).toAbsolutePath().normalize();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        try (InMemoryWorld world = new InMemoryWorld()) {
            new ScenarioRunner().jumpToStep(ScenarioCatalog.hybridRouting(), world, "hybrid-running");

            try (ScenarioWebUiHost host = ScenarioWebUiHost.start(world, webDir)) {
                assertTrue(host.boundPort() > 0, "Should bind an ephemeral loopback port");

                HttpResponse<String> htmlResp = http.send(
                    HttpRequest.newBuilder(URI.create(host.baseUrl() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                assertEquals(200, htmlResp.statusCode());
                assertTrue(htmlResp.body().contains("procomp"), "Root HTML should serve web/index.html");

                HttpResponse<String> statusResp = http.send(
                    HttpRequest.newBuilder(URI.create(host.baseUrl() + "/api/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                assertEquals(200, statusResp.statusCode());
                assertTrue(statusResp.body().contains("\"syncStatus\":\"IN_SYNC\""), statusResp.body());
                assertTrue(statusResp.body().contains("\"service\":\"orders\""), statusResp.body());
            }
        }
    }
}
