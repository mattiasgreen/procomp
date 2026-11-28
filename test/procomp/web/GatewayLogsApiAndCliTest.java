package procomp.web;

import procomp.Assert;
import procomp.cli.CliRuntime;
import procomp.cli.Procomp;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeProjectFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.shell.ProcompRuntime;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProgressObserver;
import procomp.web.ApiRouter.ApiRequest;
import procomp.web.ApiRouter.ApiResponse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public final class GatewayLogsApiAndCliTest {

    public static void testGatewayLogsAcrossCliAndWebApi() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String configJson = """
            {
              "project": "acme",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "gateway": {
                "type": "wiremock",
                "port": 8080,
                "healthPath": "/__admin/health"
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "workingDir": "." }
              }
            }
            """;

        InMemoryConfigSource configSource = new InMemoryConfigSource(configPath.getParent())
            .withFile(configPath, configJson);
        InMemoryStateStore stateStore = new InMemoryStateStore();
        Path gwOut = stateStore.logPath("acme", ProcessKey.gateway(), false);
        Path gwErr = stateStore.logPath("acme", ProcessKey.gateway(), true);
        stateStore.replaceLog(gwOut, "WireMock started on 8080\nMapped /orders\n");
        stateStore.replaceLog(gwErr, "WARN proxy timeout\n");

        FakeClock clock = new FakeClock(Instant.parse("2026-11-23T10:00:00Z"));
        ProcompRuntime runtime = ProcompRuntime.builder()
            .configSource(configSource)
            .stateStore(stateStore)
            .processRunner(new FakeProcessRunner(clock))
            .portScanner(new FakePortScanner())
            .httpClient(new FakeHttpClient())
            .projectFiles(new FakeProjectFiles())
            .clock(clock)
            .observer(ProgressObserver.noop())
            .build();

        // 1. CLI: procomp gateway logs -n 1
        StringWriter out1 = new StringWriter();
        StringWriter err1 = new StringWriter();
        int exit1 = Procomp.run(
            new String[] {"gateway", "logs", "-n", "1"},
            CliRuntime.of(runtime, new PrintWriter(out1), new PrintWriter(err1))
        );
        Assert.assertEquals(0, exit1, "gateway logs exit 0; err=" + err1);
        Assert.assertEquals("Mapped /orders\n", out1.toString(), "tail 1 line of gateway stdout");

        // 2. CLI: procomp gateway logs --err --path
        StringWriter out2 = new StringWriter();
        StringWriter err2 = new StringWriter();
        int exit2 = Procomp.run(
            new String[] {"gateway", "logs", "--err", "--path"},
            CliRuntime.of(runtime, new PrintWriter(out2), new PrintWriter(err2))
        );
        Assert.assertEquals(0, exit2, "gateway logs --err --path exit 0");
        Assert.assertEquals(gwErr.toString(), out2.toString().trim(), "gateway stderr log path");

        // 3. Web API: GET /api/gateway/logs and /api/gateway/logs?err=true
        LiveProjectHolder holder = new LiveProjectHolder(
            Assert.assertOk(runtime.projectLoader(), "projectLoader"),
            Optional.of(configPath)
        );
        ApiRouter router = new ApiRouter(runtime, holder, 9090, Duration.ofMillis(10));
        Assert.assertTrue(router.isSseEndpoint("GET", "/api/gateway/logs/stream"), "gateway logs stream is SSE");

        Map<String, String> headers = Map.of("Host", "127.0.0.1:9090");
        ApiResponse outRes = router.handle(ApiRequest.of("GET", "/api/gateway/logs", "lines=50", headers, ""));
        Assert.assertEquals(200, outRes.statusCode(), "200 gateway stdout logs");
        Assert.assertContains(outRes.body(), "WireMock started on 8080", "gateway stdout content");
        Assert.assertContains(outRes.body(), "procomp", "cliCommand echo");

        ApiResponse errRes = router.handle(ApiRequest.of("GET", "/api/gateway/logs", "err=true", headers, ""));
        Assert.assertEquals(200, errRes.statusCode(), "200 gateway stderr logs");
        Assert.assertContains(errRes.body(), "WARN proxy timeout", "gateway stderr content");

        // 4. Backward pagination on GET /api/gateway/logs?lines=1&beforeOffset=...
        ApiResponse gwPage1 = router.handle(ApiRequest.of("GET", "/api/gateway/logs", "lines=1", headers, ""));
        Assert.assertEquals(200, gwPage1.statusCode(), "200 gateway page 1");
        Assert.assertContains(gwPage1.body(), "\"hasOlder\":true", "gateway page 1 hasOlder");
        int offsetBeforeSecondLine = "WireMock started on 8080\n".length();
        ApiResponse gwPage2 = router.handle(
            ApiRequest.of("GET", "/api/gateway/logs", "lines=1&beforeOffset=" + offsetBeforeSecondLine, headers, "")
        );
        Assert.assertEquals(200, gwPage2.statusCode(), "200 gateway page 2");
        Assert.assertContains(gwPage2.body(), "WireMock started on 8080", "gateway page 2 returns first line");
        Assert.assertContains(gwPage2.body(), "\"startOffset\":0", "gateway page 2 reaches offset 0");
        Assert.assertContains(gwPage2.body(), "\"hasOlder\":false", "gateway page 2 hasOlder false");

        // 5. Gateway SSE meta includes startOffset and hasOlder
        java.io.ByteArrayOutputStream gwStreamOut = new java.io.ByteArrayOutputStream();
        java.util.concurrent.atomic.AtomicInteger gwWrites = new java.util.concurrent.atomic.AtomicInteger(0);
        SseStream gwSink = new SseStream(new java.io.OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws java.io.IOException {
                gwStreamOut.write(b, off, len);
                if (gwWrites.incrementAndGet() >= 2) {
                    throw new java.io.IOException("client disconnected");
                }
            }
        });
        router.handleSse(ApiRequest.of("GET", "/api/gateway/logs/stream", "lines=1", headers, ""), gwSink);
        String gwWire = gwStreamOut.toString(java.nio.charset.StandardCharsets.UTF_8);
        Assert.assertContains(gwWire, "\"startOffset\":" + offsetBeforeSecondLine, "gateway SSE meta startOffset");
        Assert.assertContains(gwWire, "\"hasOlder\":true", "gateway SSE meta hasOlder");
    }
}
