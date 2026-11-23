package e2e.webui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
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
import java.util.List;

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
    void cliFooterHistoryPanelShowsFullCommandHistoryWithoutDuplicatesAndCanBeCleared() {
        try (InMemoryWorld world = new InMemoryWorld()) {
            new ScenarioRunner().jumpToStep(ScenarioCatalog.hybridRouting(), world, "hybrid-running");

            try (ScenarioWebUiHost host = ScenarioWebUiHost.start(world, webDir)) {
                page.navigate(host.baseUrl());

                // Initial status load records procomp status once
                assertThat(page.locator("#cli-command-echo")).hasText("procomp status");
                assertThat(page.locator("#cli-history-count")).hasText("1");
                assertThat(page.locator("#cli-history-panel")).isHidden();

                // Flip pricing Route to LOCAL, reconcile Gateway, and start pricing (SSE meta+done)
                Locator pricingRow = serviceRow("pricing");
                pricingRow.locator("button.route-local").click();
                assertThat(page.locator("#cli-command-echo")).hasText("procomp route pricing local");

                page.locator("#btn-drift-reconcile").click();
                assertThat(page.locator("#cli-command-echo")).hasText("procomp gateway reconcile");

                pricingRow.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Start")).click();
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
                assertThat(pricingRow.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Stop"))).isDisabled();

                Locator ordersRow = serviceRow("orders");
                assertThat(ordersRow).containsText("CONFLICT");

                // Trigger Kill Conflict -> native <dialog> must show Inspection details + verbatim CLI command
                ordersRow.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Kill Conflict…")).click();

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
                ordersRow.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Kill Conflict…")).click();
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

                // Recover CRASHED orders right from the Web UI Start button
                ordersRow.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Start")).click();
                assertThat(ordersRow).containsText("HEALTHY");
                assertThat(page.locator("#cli-command-echo")).hasText("procomp start orders");

                // Rebuild Artifact externally -> Refresh -> orders displays STALE
                world.triggerActor(new ExternalActorEvent.RebuildArtifact("orders"));
                page.locator("#btn-refresh").click();
                assertThat(ordersRow).containsText("STALE");

                // Open Inspect drawer on orders -> verify STALE details and secret hygiene
                ordersRow.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Inspect")).click();

                assertThat(page.locator("#detail-drawer")).isVisible();
                assertThat(page.locator("#drawer-service-name")).hasText("orders");
                assertThat(page.locator("#inspect-content")).containsText("Stale");
                assertThat(page.locator("#inspect-content")).containsText("YES");
                assertFalse(
                    page.locator("#inspect-content").innerText().contains(InMemoryConfigSource.DEMO_SECRET_VALUE),
                    "Inspection drawer must never leak secret env values"
                );

                // Open fullscreen Logs view for orders and push a live line while connected
                ordersRow.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Logs")).click();
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

    private Locator serviceRow(String serviceName) {
        return page.locator("#services-tbody tr").filter(
            new Locator.FilterOptions().setHas(page.locator("td:first-child", new Page.LocatorOptions().setHasText(serviceName)))
        );
    }
}
