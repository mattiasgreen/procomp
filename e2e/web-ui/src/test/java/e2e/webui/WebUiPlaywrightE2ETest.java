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

                // Switch to live SSE Logs tab on orders and push a new log line while connected
                page.locator("#tab-logs").click();
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

    private Locator serviceRow(String serviceName) {
        return page.locator("#services-tbody tr").filter(
            new Locator.FilterOptions().setHas(page.locator("td:first-child", new Page.LocatorOptions().setHasText(serviceName)))
        );
    }
}
