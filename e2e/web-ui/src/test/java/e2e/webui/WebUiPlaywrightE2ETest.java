package e2e.webui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import e2e.scenarios.catalog.ScenarioCatalog;
import e2e.scenarios.engine.ExternalActorEvent;
import e2e.scenarios.engine.ScenarioRunner;
import e2e.scenarios.inmemory.InMemoryConfigSource;
import e2e.scenarios.inmemory.InMemoryWorld;
import e2e.scenarios.web.ScenarioWebUiHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless Chromium Playwright E2E tests exercising the real Vanilla ES6 Web UI ({@code web/})
 * served by JDK 25 {@code WebUi} directly over {@link InMemoryWorld} Scenarios.
 */
final class WebUiPlaywrightE2ETest {

    private static Playwright playwright;
    private static Browser browser;
    private static Path webDir;
    private static Path failureScreenshotsDir;

    private BrowserContext context;
    private Page page;
    private final List<String> pageErrors = new ArrayList<>();
    private final List<String> consoleErrors = new ArrayList<>();

    @RegisterExtension
    final org.junit.jupiter.api.extension.TestExecutionExceptionHandler failureScreenshotHandler =
        (ExtensionContext ctx, Throwable cause) -> {
            if (page != null && !page.isClosed()) {
                try {
                    Files.createDirectories(failureScreenshotsDir);
                    String safeName = ctx.getDisplayName().replaceAll("[^a-zA-Z0-9._-]", "_");
                    Path screenshot = failureScreenshotsDir.resolve(safeName + ".png");
                    page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setFullPage(true));
                    System.err.println("Saved Playwright failure screenshot: " + screenshot);
                } catch (Exception ignored) {
                }
            }
            throw cause;
        };

    @BeforeAll
    static void launchBrowser() {
        webDir = Path.of(System.getProperty("e2e.webDir", "../../web")).toAbsolutePath().normalize();
        failureScreenshotsDir = Path.of(
            System.getProperty("e2e.failureScreenshotsDir", "build/playwright-failures")
        ).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(webDir), "web/ directory must exist: " + webDir);

        PlaywrightAssertions.setDefaultAssertionTimeout(10_000);
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void openPage() {
        pageErrors.clear();
        consoleErrors.clear();
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 900));
        page = context.newPage();
        page.onPageError(pageErrors::add);
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                consoleErrors.add(msg.text());
            }
        });
    }

    @AfterEach
    void closePage(TestInfo info) {
        if (context != null) {
            context.close();
        }
        assertEquals(List.of(), pageErrors, "Uncaught browser page errors in " + info.getDisplayName());
        assertEquals(List.of(), consoleErrors, "Browser console errors in " + info.getDisplayName());
    }

    @Test
    void hybridRoutingFlipShowsGatewayDriftVerbatimCliEchoAndReconcilesLive() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            new ScenarioRunner().jumpToStep(ScenarioCatalog.hybridRouting(), world, "hybrid-running");

            try (ScenarioWebUiHost host = ScenarioWebUiHost.start(world, webDir)) {
                page.navigate(host.baseUrl());

                assertThat(page.locator("#gateway-sync")).hasText("IN_SYNC");
                assertThat(page.locator("#gateway-drift-alert")).isHidden();

                Locator autoReconcile = page.locator("#chk-gateway-auto-reconcile");
                assertThat(autoReconcile).isVisible();
                assertThat(autoReconcile).isChecked();

                // Disable Auto-reconcile to verify UNIX separation (DRIFTED until explicit reconcile)
                autoReconcile.uncheck();
                assertThat(autoReconcile).not().isChecked();

                Locator pricingRow = serviceRow("pricing");
                assertThat(pricingRow).isVisible();
                assertThat(pricingRow.locator("button.route-remote")).hasAttribute("aria-pressed", "true");

                // Flip pricing Route to LOCAL -> proves UNIX separation (DRIFTED until explicit reconcile)
                pricingRow.locator("button.route-local").click();

                assertThat(page.locator("#gateway-drift-alert")).isVisible();
                assertThat(page.locator("#gateway-sync")).containsText("DRIFTED");
                assertThat(page.locator("#cli-command-echo")).hasText("procomp route pricing local");

                // Reconcile explicitly -> Gateway returns to IN_SYNC
                page.locator("#btn-drift-reconcile").click();

                assertThat(page.locator("#gateway-sync")).hasText("IN_SYNC");
                assertThat(page.locator("#gateway-drift-alert")).isHidden();
                assertThat(page.locator("#cli-command-echo")).hasText("procomp gateway reconcile");
            }
        }
    }

    @Test
    void gatewayAutoReconcileReconcilesOnRouteAndProfileMutationsWhenHealthyAndSkipsWhenStopped() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            new ScenarioRunner().jumpToStep(ScenarioCatalog.hybridRouting(), world, "hybrid-running");

            try (ScenarioWebUiHost host = ScenarioWebUiHost.start(world, webDir)) {
                page.navigate(host.baseUrl());

                Locator autoReconcile = page.locator("#chk-gateway-auto-reconcile");
                assertThat(autoReconcile).isVisible();
                assertThat(autoReconcile).isChecked();
                assertThat(page.locator("#gateway-sync")).hasText("IN_SYNC");

                // 1. Route flip with Auto-reconcile checked -> automatically reconciles Gateway
                Locator pricingRow = serviceRow("pricing");
                pricingRow.locator("button.route-local").click();

                assertThat(pricingRow.locator("button.route-local")).hasAttribute("aria-pressed", "true");
                assertThat(page.locator("#gateway-sync")).hasText("IN_SYNC");
                assertThat(page.locator("#gateway-drift-alert")).isHidden();
                assertThat(page.locator("#cli-command-echo")).hasText("procomp gateway reconcile");

                // 2. Profile apply with Auto-reconcile checked -> automatically reconciles Gateway
                page.locator(".profile-pill", new Page.LocatorOptions().setHasText("orders-local")).click();

                assertThat(pricingRow.locator("button.route-remote")).hasAttribute("aria-pressed", "true");
                assertThat(page.locator("#gateway-sync")).hasText("IN_SYNC");
                assertThat(page.locator("#gateway-drift-alert")).isHidden();
                assertThat(page.locator("#cli-command-echo")).hasText("procomp gateway reconcile");

                // Verify CLI history captured both mutations and both auto-reconcile commands
                page.locator("#btn-cli-history").click();
                Locator items = page.locator("#cli-history-list .cli-history-cmd");
                assertThat(items).hasCount(5);
                assertThat(items.nth(0)).hasText("procomp gateway reconcile");
                assertThat(items.nth(1)).hasText("procomp profile use orders-local");
                assertThat(items.nth(2)).hasText("procomp gateway reconcile");
                assertThat(items.nth(3)).hasText("procomp route pricing local");
                assertThat(items.nth(4)).hasText("procomp status");
                page.locator("#btn-close-cli-history").click();

                // 3. Stopping Gateway -> Route flip must NOT fire a failing auto-reconcile request
                page.locator("#btn-gateway-stop").click();
                assertThat(page.locator("#gateway-health")).hasText("STOPPED");

                pricingRow.locator("button.route-local").click();
                assertThat(pricingRow.locator("button.route-local")).hasAttribute("aria-pressed", "true");
                assertThat(page.locator("#cli-command-echo")).hasText("procomp route pricing local");
                assertThat(page.locator("#error-banner")).isHidden();

                // 4. Unchecked preference persists across reload and leaves running Gateway DRIFTED
                page.locator("#btn-gateway-start").click();
                assertThat(page.locator("#gateway-health")).hasText("HEALTHY");
                page.locator("#btn-gateway-reconcile").click();
                assertThat(page.locator("#gateway-sync")).hasText("IN_SYNC");

                autoReconcile.uncheck();
                page.reload();
                assertThat(autoReconcile).not().isChecked();

                pricingRow.locator("button.route-remote").click();
                assertThat(page.locator("#gateway-sync")).containsText("DRIFTED");
                assertThat(page.locator("#gateway-drift-alert")).isVisible();
                assertThat(page.locator("#cli-command-echo")).hasText("procomp route pricing remote");
            }
        }
    }

    @Test
    void cliFooterHistoryPanelShowsFullCommandHistoryWithoutDuplicatesAndCanBeCleared() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            new ScenarioRunner().jumpToStep(ScenarioCatalog.hybridRouting(), world, "hybrid-running");

            try (ScenarioWebUiHost host = ScenarioWebUiHost.start(world, webDir)) {
                page.navigate(host.baseUrl());

                // Initial status load records procomp status once
                assertThat(page.locator("#cli-command-echo")).hasText("procomp status");
                assertThat(page.locator("#cli-history-count")).hasText("1");
                assertThat(page.locator("#cli-history-panel")).isHidden();

                // Flip pricing Route to LOCAL (auto-reconciles Gateway) and start pricing (SSE meta+done)
                Locator pricingRow = serviceRow("pricing");
                pricingRow.locator("button.route-local").click();
                assertThat(pricingRow.locator("button.route-local")).hasAttribute("aria-pressed", "true");
                assertThat(page.locator("#cli-command-echo")).hasText("procomp gateway reconcile");

                clickHealthMenuAction(pricingRow, "Start");
                assertThat(pricingRow).containsText("HEALTHY");
                assertThat(page.locator("#cli-command-echo")).hasText("procomp start pricing");

                // History count must be 4 (no duplicate for SSE start meta+done)
                assertThat(page.locator("#cli-history-count")).hasText("4");

                // Open CLI history panel from the footer
                Locator historyBtn = page.locator("#btn-cli-history");
                historyBtn.click();
                assertThat(historyBtn).hasAttribute("aria-expanded", "true");
                assertThat(page.locator("#cli-history-panel")).isVisible();
                assertThat(page.locator("#cli-history-summary")).hasText("4 commands");

                Locator items = page.locator("#cli-history-list .cli-history-cmd");
                assertThat(items).hasCount(4);
                assertThat(items.nth(0)).hasText("procomp start pricing");
                assertThat(items.nth(1)).hasText("procomp gateway reconcile");
                assertThat(items.nth(2)).hasText("procomp route pricing local");
                assertThat(items.nth(3)).hasText("procomp status");

                // Clear history -> shows empty state and resets count to 0
                page.locator("#btn-clear-cli-history").click();
                assertThat(page.locator("#cli-history-count")).hasText("0");
                assertThat(page.locator("#cli-history-empty")).isVisible();
                assertThat(page.locator("#cli-history-list")).isHidden();

                // Subsequent explicit action records cleanly
                page.locator("#btn-refresh").click();
                assertThat(page.locator("#cli-history-count")).hasText("1");
                assertThat(items).hasCount(1);
                assertThat(items.nth(0)).hasText("procomp status");

                // Close panel via close button
                page.locator("#btn-close-cli-history").click();
                assertThat(page.locator("#cli-history-panel")).isHidden();
                assertThat(historyBtn).hasAttribute("aria-expanded", "false");
            }
        }
    }

    @Test
    void ownershipAndConflictShowsConfirmationDialogBeforeKillingConflictProcess() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            new ScenarioRunner().jumpToStep(ScenarioCatalog.ownershipAndConflicts(), world, "port-conflict");

            try (ScenarioWebUiHost host = ScenarioWebUiHost.start(world, webDir)) {
                page.navigate(host.baseUrl());

                Locator pricingRow = serviceRow("pricing");
                assertThat(pricingRow).containsText("UNMANAGED");
                pricingRow.locator("button[data-pill='health']").click();
                assertThat(page.locator("#pill-action-menu").getByRole(com.microsoft.playwright.options.AriaRole.MENUITEM,
                    new Locator.GetByRoleOptions().setName("Stop"))).isDisabled();

                Locator ordersRow = serviceRow("orders");
                assertThat(ordersRow).containsText("CONFLICT");

                // Trigger Kill Conflict -> native <dialog> must show Inspection details + verbatim CLI command
                clickHealthMenuAction(ordersRow, "Kill Conflict…");

                Locator dialog = page.locator("#conflict-dialog");
                assertThat(dialog).isVisible();
                assertThat(page.locator("#conflict-service-name")).hasText("orders");
                assertThat(page.locator("#conflict-pid")).not().hasText("—");
                assertThat(page.locator("#conflict-cmdline")).not().hasText("—");
                assertThat(page.locator("#conflict-cli-preview")).containsText("procomp stop orders --kill-conflict");

                // Cancel leaves the Conflict Process untouched
                page.locator("#btn-conflict-cancel").click();
                assertThat(dialog).isHidden();
                assertThat(ordersRow).containsText("CONFLICT");

                // Confirming kills the Conflict Process and echoes the verbatim CLI command
                clickHealthMenuAction(ordersRow, "Kill Conflict…");
                assertThat(dialog).isVisible();
                page.locator("#btn-conflict-confirm").click();

                assertThat(dialog).isHidden();
                assertThat(ordersRow).containsText("NONE");
                assertThat(ordersRow).containsText("STOPPED");
                assertThat(page.locator("#cli-command-echo")).hasText("procomp stop orders --kill-conflict");
            }
        }
    }

    @Test
    void failuresDrawerDisplaysCrashedStaleInspectionAndStreamsLiveSseLogsWithoutLeakingSecrets() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            new ScenarioRunner().jumpToStep(ScenarioCatalog.failuresLogsAndStale(), world, "service-crashed");

            try (ScenarioWebUiHost host = ScenarioWebUiHost.start(world, webDir)) {
                page.navigate(host.baseUrl());

                Locator ordersRow = serviceRow("orders");
                assertThat(ordersRow).containsText("CRASHED");

                // Recover CRASHED orders right from the Web UI HEALTH pill menu
                clickHealthMenuAction(ordersRow, "Start");
                assertThat(ordersRow).containsText("HEALTHY");
                assertThat(page.locator("#cli-command-echo")).hasText("procomp start orders");

                // Rebuild Artifact externally -> Refresh -> orders displays inline STALE badge beside HEALTH
                world.triggerActor(new ExternalActorEvent.RebuildArtifact("orders"));
                page.locator("#btn-refresh").click();
                assertThat(ordersRow).containsText("STALE");

                // Open Inspect drawer on orders -> verify STALE details and secret hygiene
                clickHealthMenuAction(ordersRow, "Inspect");

                assertThat(page.locator("#detail-drawer")).isVisible();
                assertThat(page.locator("#drawer-service-name")).hasText("orders");
                assertThat(page.locator("#inspect-content")).containsText("Stale");
                assertThat(page.locator("#inspect-content")).containsText("YES");
                assertFalse(
                    page.locator("#inspect-content").innerText().contains(InMemoryConfigSource.DEMO_SECRET_VALUE),
                    "Inspection drawer must never leak secret env values"
                );

                // Open fullscreen Logs view for orders and push a live line while connected
                clickHealthMenuAction(ordersRow, "Logs");
                assertThat(page.locator("#view-logs")).isVisible();
                assertThat(page.locator("#logs-target-name")).hasText("orders");
                assertThat(page.locator("#logs-stream-status")).hasText("Streaming");

                String liveMarker = "playwright-sse-live-line-42";
                world.triggerActor(new ExternalActorEvent.AppendServiceLog("orders", liveMarker, false));

                assertThat(page.locator("#logs-output")).containsText(liveMarker);
                assertFalse(
                    page.locator("#logs-output").innerText().contains(InMemoryConfigSource.DEMO_SECRET_VALUE),
                    "Live logs must never leak secret env values"
                );
            }
        }
    }

    @Test
    void logsBackwardPaginationAutoScrollAndDevToolsProfileHealth() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            new ScenarioRunner().jumpToStep(ScenarioCatalog.failuresLogsAndStale(), world, "service-crashed");

            // Seed 550 historical lines so initial 200-line tail leaves 2 backward pages (200 + 150)
            for (int i = 1; i <= 550; i++) {
                world.triggerActor(new ExternalActorEvent.AppendServiceLog(
                    "orders",
                    String.format("hist-line-%04d", i),
                    false
                ));
            }

            try (ScenarioWebUiHost host = ScenarioWebUiHost.start(world, webDir)) {
                CDPSession cdp = context.newCDPSession(page);
                cdp.send("Performance.enable");
                cdp.send("Profiler.enable");
                cdp.send("Profiler.start");

                page.navigate(host.baseUrl());

                Locator ordersRow = serviceRow("orders");
                clickHealthMenuAction(ordersRow, "Logs");

                assertThat(page.locator("#view-logs")).isVisible();
                assertThat(page.locator("#logs-stream-status")).hasText("Streaming");
                assertThat(page.locator("#logs-line-count")).hasText("200 lines");
                assertThat(page.locator("#logs-output")).containsText("hist-line-0550");
                assertThat(page.locator("#logs-output")).containsText("hist-line-0351");
                assertThat(page.locator("#btn-logs-load-older")).isVisible();
                assertThat(page.locator("#chk-logs-autoscroll")).isChecked();

                // Disengage Auto-scroll by scrolling upward with mouse wheel over #logs-output
                page.locator("#logs-output").hover();
                page.mouse().wheel(0, -120);
                assertThat(page.locator("#chk-logs-autoscroll")).not().isChecked();

                // Push live lines while scrolled up: viewport must NOT snap back to bottom
                Number scrollTopBeforeLive = (Number) page.evaluate("() => document.getElementById('logs-output').scrollTop");
                for (int i = 1; i <= 10; i++) {
                    world.triggerActor(new ExternalActorEvent.AppendServiceLog("orders", "live-burst-" + i, false));
                }
                assertThat(page.locator("#logs-line-count")).hasText("210 lines");
                assertThat(page.locator("#chk-logs-autoscroll")).not().isChecked();
                Number scrollTopAfterLive = (Number) page.evaluate("() => document.getElementById('logs-output').scrollTop");
                assertEquals(
                    scrollTopBeforeLive.intValue(),
                    scrollTopAfterLive.intValue(),
                    "Incoming live lines must not snap scrollTop back while Auto-scroll is disengaged"
                );

                // Scroll near top -> automatically loads previous 200 lines and preserves reading anchor
                page.evaluate("() => { const el = document.getElementById('logs-output'); el.scrollTop = 0; }");
                assertThat(page.locator("#logs-line-count")).hasText("410 lines");
                assertThat(page.locator("#logs-output")).containsText("hist-line-0151");
                Number anchoredScrollTop = (Number) page.evaluate("() => document.getElementById('logs-output').scrollTop");
                assertTrue(
                    anchoredScrollTop.intValue() > 100,
                    "Prepending older lines must preserve reading position (scrollTop=" + anchoredScrollTop + ")"
                );

                // Click "Load older" button directly -> loads remaining lines up to beginning of log
                assertThat(page.locator("#btn-logs-load-older")).hasText("Load older");
                page.locator("#btn-logs-load-older").evaluate("el => el.click()");
                assertThat(page.locator("#logs-output")).containsText("hist-line-0001");
                assertThat(page.locator("#logs-history-banner")).containsText("Start of log");
                assertThat(page.locator("#btn-logs-load-older")).hasCount(0);

                // Scroll back to bottom -> Auto-scroll automatically re-engages
                page.evaluate("() => { const el = document.getElementById('logs-output'); el.scrollTop = el.scrollHeight; }");
                assertThat(page.locator("#chk-logs-autoscroll")).isChecked();

                // Verify suspended top-eviction while scrolled up (>5000 lines) and trim-back to 5000 on re-engage
                page.locator("#logs-output").hover();
                page.mouse().wheel(0, -120);
                assertThat(page.locator("#chk-logs-autoscroll")).not().isChecked();

                StringBuilder bulk = new StringBuilder(60_000);
                for (int i = 1; i <= 4600; i++) {
                    bulk.append("bulk-line-").append(i).append('\n');
                }
                world.triggerActor(new ExternalActorEvent.AppendServiceLog("orders", bulk.toString(), false));
                assertThat(page.locator("#logs-output")).containsText("bulk-line-4600");
                assertThat(page.locator("#logs-output")).containsText("hist-line-0001");
                Number pausedCount = (Number) page.evaluate(
                    "() => document.querySelectorAll('#logs-lines .log-line').length"
                );
                assertTrue(
                    pausedCount.intValue() > 5000,
                    "Top eviction must be suspended while Auto-scroll is off (got " + pausedCount + ")"
                );

                // Re-enabling Auto-scroll trims excess lines back down to 5000
                page.locator("#chk-logs-autoscroll").check();
                assertThat(page.locator("#logs-line-count")).hasText("5000 lines");

                // Clear resets viewport and hides Load older
                page.locator("#btn-logs-clear").click();
                assertThat(page.locator("#logs-line-count")).hasText("0 lines");
                assertThat(page.locator("#btn-logs-load-older")).hasCount(0);

                // Inspect Chromium DevTools Profiler + Performance metrics (force GC so detached nodes are collected)
                JsonObject profileResult = cdp.send("Profiler.stop");
                assertTrue(profileResult.has("profile"), "CDP Profiler.stop should return a CPU profile");

                cdp.send("HeapProfiler.collectGarbage");
                JsonObject metricsResult = cdp.send("Performance.getMetrics");
                Map<String, Double> metrics = new HashMap<>();
                for (JsonElement elem : metricsResult.getAsJsonArray("metrics")) {
                    JsonObject entry = elem.getAsJsonObject();
                    metrics.put(entry.get("name").getAsString(), entry.get("value").getAsDouble());
                }
                double nodes = metrics.getOrDefault("Nodes", 0.0);
                double jsHeapBytes = metrics.getOrDefault("JSHeapUsedSize", 0.0);
                double layoutSeconds = metrics.getOrDefault("LayoutDuration", 0.0);
                assertTrue(nodes < 10_000, "DOM node count should stay bounded after Clear, got " + nodes);
                assertTrue(
                    jsHeapBytes < 128.0 * 1024 * 1024,
                    "JS heap should stay under 128 MB, got " + (jsHeapBytes / (1024 * 1024)) + " MB"
                );
                assertTrue(layoutSeconds < 5.0, "Cumulative layout duration should stay responsive, got " + layoutSeconds + "s");

                cdp.detach();
            }
        }
    }

    @Test
    void progressStripDisplaysSpinnerAndDynamicTitleForNonStartAndConcurrentActions() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            new ScenarioRunner().jumpToStep(ScenarioCatalog.hybridRouting(), world, "hybrid-running");

            try (ScenarioWebUiHost host = ScenarioWebUiHost.start(world, webDir)) {
                page.navigate(host.baseUrl());

                Locator progressStrip = page.locator("#progress-strip");
                Locator progressTitle = page.locator("#progress-strip-title");
                Locator progressList = page.locator("#progress-list");
                Locator ordersRow = serviceRow("orders");

                assertThat(ordersRow).containsText("HEALTHY");
                assertThat(progressStrip).isHidden();

                // Hold both stop and gateway reconcile requests open to verify single + concurrent progress display
                List<Route> pendingStops = new ArrayList<>();
                List<Route> pendingReconciles = new ArrayList<>();
                page.route("**/api/services/stop", pendingStops::add);
                page.route("**/api/gateway/reconcile", pendingReconciles::add);

                // 1. Trigger Stop on orders -> blue strip shows "Stopping Processes" with [orders]
                clickHealthMenuAction(ordersRow, "Stop");

                assertThat(progressStrip).isVisible();
                assertThat(progressStrip.locator(".spinner")).isVisible();
                assertThat(progressTitle).hasText("Stopping Processes");
                assertThat(progressList).containsText("[orders]");
                assertThat(progressList).containsText("Stopping Process…");

                // 2. While stop is still in flight, trigger Gateway Reconcile -> combined title "Running Actions"
                page.locator("#btn-gateway-reconcile").click();

                assertThat(progressTitle).hasText("Running Actions");
                assertThat(progressList.locator("li")).hasCount(2);
                assertThat(progressList).containsText("[orders]");
                assertThat(progressList).containsText("[gateway]");
                assertThat(progressList).containsText("Reconciling Gateway…");

                // 3. Release Stop -> header switches to remaining action's title ("Reconciling Gateway")
                assertEquals(1, pendingStops.size(), "Expected one intercepted stop request");
                pendingStops.removeFirst().resume();

                assertThat(ordersRow).containsText("STOPPED");
                assertThat(progressStrip).isVisible();
                assertThat(progressTitle).hasText("Reconciling Gateway");
                assertThat(progressList.locator("li")).hasCount(1);
                assertThat(progressList).containsText("[gateway]");

                // 4. Release Gateway Reconcile -> strip hides cleanly
                assertEquals(1, pendingReconciles.size(), "Expected one intercepted reconcile request");
                pendingReconciles.removeFirst().resume();

                assertThat(progressStrip).isHidden();
                page.unroute("**/api/services/stop");
                page.unroute("**/api/gateway/reconcile");

                // 5. Explicit Refresh shows "Running Discovery" while in flight and hides when done
                List<Route> pendingStatuses = new ArrayList<>();
                page.route("**/api/status", pendingStatuses::add);

                page.locator("#btn-refresh").click();
                assertThat(progressStrip).isVisible();
                assertThat(progressTitle).hasText("Running Discovery");
                assertThat(progressList).containsText("Running Discovery…");

                while (!pendingStatuses.isEmpty()) {
                    pendingStatuses.removeFirst().resume();
                }
                page.unroute("**/api/status");
                assertThat(progressStrip).isHidden();
            }
        }
    }

    @Test
    void profileManagementCreatesEditsHighlightsAndDeletesProfilesInProcompJson() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            new ScenarioRunner().jumpToStep(ScenarioCatalog.hybridRouting(), world, "hybrid-running");

            try (ScenarioWebUiHost host = ScenarioWebUiHost.start(world, webDir)) {
                page.navigate(host.baseUrl());

                // 1. Existing orders-local profile matches current Routing Table -> highlighted active
                Locator ordersLocalPill = page.locator(".profile-pill-group[data-profile='orders-local'] .profile-pill");
                assertThat(ordersLocalPill).hasClass(Pattern.compile(".*\\bactive\\b.*"));

                // 2. Flip pricing LOCAL -> orders-local is no longer active
                serviceRow("pricing").getByRole(
                    com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("LOCAL")
                ).click();
                assertThat(ordersLocalPill).not().hasClass(Pattern.compile(".*\\bactive\\b.*"));

                // 3. Click "+ Save Profile" -> pre-selected with current LOCAL services, overwrite warning on existing name
                page.locator("#btn-save-profile").click();
                assertThat(page.locator("#profile-dialog")).isVisible();
                page.locator("#profile-name-input").fill("orders-local");
                assertThat(page.locator("#profile-overwrite-warning")).isVisible();
                assertThat(page.locator("#btn-profile-save")).hasText("Overwrite Profile");

                // Change to new name -> snapshot CLI preview & save
                page.locator("#profile-name-input").fill("full-local");
                assertThat(page.locator("#profile-overwrite-warning")).isHidden();
                assertThat(page.locator("#profile-cli-preview")).hasText("procomp profile save full-local");
                page.locator("#btn-profile-save").click();

                assertThat(page.locator("#profile-dialog")).isHidden();
                assertThat(page.locator("#cli-command-echo")).hasText("procomp profile save full-local");

                // New pill appears and is immediately active (matches current Routing Table)
                Locator fullLocalPill = page.locator(".profile-pill-group[data-profile='full-local'] .profile-pill");
                assertThat(fullLocalPill).isVisible();
                assertThat(fullLocalPill).containsText("4 LOCAL");
                assertThat(fullLocalPill).hasClass(Pattern.compile(".*\\bactive\\b.*"));

                // 4. Edit full-local via inline edit icon -> Clear all -> saves as --none without mutating current Routing Table
                page.locator(".profile-pill-group[data-profile='full-local'] .profile-pill-edit").click();
                assertThat(page.locator("#profile-dialog")).isVisible();
                assertThat(page.locator("#profile-name-input")).isDisabled();
                page.locator("#btn-profile-clear-all").click();
                assertThat(page.locator("#profile-cli-preview")).hasText("procomp profile save full-local --none");
                page.locator("#btn-profile-save").click();

                assertThat(page.locator("#profile-dialog")).isHidden();
                assertThat(page.locator("#cli-command-echo")).hasText("procomp profile save full-local --none");
                assertThat(fullLocalPill).containsText("0 LOCAL");
                assertThat(fullLocalPill).not().hasClass(Pattern.compile(".*\\bactive\\b.*"));

                // Reload page -> persisted in procomp.json
                page.reload();
                assertThat(page.locator(".profile-pill-group[data-profile='full-local'] .profile-pill")).containsText("0 LOCAL");

                // 5. Delete full-local with confirmation inside Edit dialog
                page.locator(".profile-pill-group[data-profile='full-local'] .profile-pill-edit").click();
                assertThat(page.locator("#profile-dialog")).isVisible();
                page.locator("#btn-profile-delete").click();
                assertThat(page.locator("#profile-delete-confirm")).isVisible();
                assertThat(page.locator("#profile-cli-preview")).hasText("procomp profile delete full-local");
                page.locator("#btn-profile-delete-confirm").click();

                assertThat(page.locator("#profile-dialog")).isHidden();
                assertThat(page.locator("#cli-command-echo")).hasText("procomp profile delete full-local");
                assertThat(page.locator(".profile-pill-group[data-profile='full-local']")).hasCount(0);
            }
        }
    }

    @Test
    void fullscreenLogsViewAndServiceTableFilteringSupportOrQueriesWrapAndShortcuts() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            new ScenarioRunner().jumpToStep(ScenarioCatalog.hybridRouting(), world, "hybrid-running");

            try (ScenarioWebUiHost host = ScenarioWebUiHost.start(world, webDir)) {
                page.navigate(host.baseUrl());

                // LOCAL-routed services (broker, orders, notifications) are sorted above REMOTE (pricing)
                assertThat(page.locator("#services-tbody tr").first().locator("td:first-child")).hasText("broker");
                assertThat(page.locator("#services-tbody tr").last().locator("td:first-child")).hasText("pricing");

                // LOCAL only chip collapses table to the 3 LOCAL services
                page.locator("#btn-services-local-only").click();
                assertThat(page.locator("#btn-services-local-only")).hasAttribute("aria-pressed", "true");
                assertThat(page.locator("#services-tbody tr")).hasCount(3);
                assertThat(serviceRow("pricing")).hasCount(0);

                // Turn off LOCAL only and test case-insensitive |-OR filter (including trailing |)
                page.locator("#btn-services-local-only").click();
                page.locator("#services-filter-input").fill("PRICING|");
                assertThat(page.locator("#services-tbody tr")).hasCount(1);
                assertThat(serviceRow("pricing")).isVisible();

                page.locator("#services-filter-input").fill("pricing|orders");
                assertThat(page.locator("#services-tbody tr")).hasCount(2);

                // Clear filter via Escape inside input, then blur via second Escape
                page.locator("#services-filter-input").press("Escape");
                assertThat(page.locator("#services-filter-input")).hasValue("");
                page.locator("#services-filter-input").press("Escape");

                // Press '2' to switch to fullscreen Logs view (auto-selects first LOCAL service: broker)
                page.keyboard().press("2");
                assertThat(page.locator("#view-logs")).isVisible();
                assertThat(page.locator("#view-services")).isHidden();
                assertThat(page.locator("#logs-target-name")).hasText("broker");
                assertThat(page.locator("#nav-tab-logs-target")).containsText("broker");
                assertThat(page.locator("#logs-stream-status")).hasText("Streaming");

                // Switch target to orders via sidebar in 1 click
                page.locator("#logs-target-list button[data-service='orders']").click();
                assertThat(page.locator("#logs-target-name")).hasText("orders");
                assertThat(page.locator("#nav-tab-logs-target")).containsText("orders");
                assertThat(page.locator("#logs-stream-status")).hasText("Streaming");

                // Push mixed log lines and filter with |-OR
                world.triggerActor(new ExternalActorEvent.AppendServiceLog("orders", "INFO startup ok", false));
                world.triggerActor(new ExternalActorEvent.AppendServiceLog("orders", "WARN slow query", false));
                world.triggerActor(new ExternalActorEvent.AppendServiceLog("orders", "ERROR payment timeout", false));
                assertThat(page.locator("#logs-output")).containsText("ERROR payment timeout");

                // Press '/' to focus log line filter
                page.keyboard().press("/");
                page.locator("#logs-filter-input").fill("error|warn");
                assertThat(page.locator("#logs-output")).containsText("WARN slow query");
                assertThat(page.locator("#logs-output")).containsText("ERROR payment timeout");
                assertFalse(page.locator("#logs-output").innerText().contains("INFO startup ok"));
                assertThat(page.locator("#logs-line-count")).containsText("/");

                // Wrap toggle defaults to off (.no-wrap) and toggles cleanly
                assertThat(page.locator("#logs-output")).hasClass(java.util.regex.Pattern.compile(".*\\bno-wrap\\b.*"));
                page.locator("#chk-logs-wrap").check();
                assertThat(page.locator("#logs-output")).not().hasClass(java.util.regex.Pattern.compile(".*\\bno-wrap\\b.*"));

                // Switch target to Gateway via sidebar in 1 click
                page.locator("#logs-target-list button[data-target-type='gateway']").click();
                assertThat(page.locator("#logs-target-name")).hasText("gateway");
                assertThat(page.locator("#nav-tab-logs-target")).containsText("gateway");
                assertThat(page.locator("#logs-source-group")).isHidden();

                // Press '1' to return to Services view
                page.keyboard().press("1");
                assertThat(page.locator("#view-services")).isVisible();
                assertThat(page.locator("#view-logs")).isHidden();
            }
        }
    }

    private void clickHealthMenuAction(Locator row, String actionName) {
        row.locator("button[data-pill='health']").click();
        page.locator("#pill-action-menu")
            .getByRole(com.microsoft.playwright.options.AriaRole.MENUITEM, new Locator.GetByRoleOptions().setName(actionName))
            .click();
    }

    private Locator serviceRow(String serviceName) {
        return page.locator("#services-tbody tr").filter(
            new Locator.FilterOptions().setHas(page.locator("td:first-child", new Page.LocatorOptions().setHasText(serviceName)))
        );
    }
}
