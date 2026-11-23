package e2e.scenarios.inmemory;

import e2e.scenarios.engine.CliResult;
import e2e.scenarios.engine.EstateSpec;
import e2e.scenarios.engine.ExternalActorEvent;
import e2e.scenarios.engine.HttpProbeResult;
import e2e.scenarios.engine.LagProfile;
import e2e.scenarios.engine.WorldKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InMemoryWorldTest {

    private InMemoryWorld world;

    @BeforeEach
    void setUp() {
        world = new InMemoryWorld();
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void hybridRoutingDriftReconcileLiveFlipAndFailFastOnStoppedLocalTarget() {
        assertEquals(WorldKind.IN_MEMORY, world.worldKind());

        assertOk(world.runProcomp("profile", "use", "orders-local"), "profile use");
        assertOk(world.runProcomp("gateway", "start"), "gateway start");
        assertOk(world.runProcomp("gateway", "reconcile"), "gateway reconcile");
        assertOk(world.runProcomp("start"), "start local slice");

        CliResult status1 = assertOk(world.runProcomp("status", "--json"), "status --json");
        assertTrue(status1.stdout().contains("\"syncStatus\":\"IN_SYNC\""), status1.stdout());
        assertFalse(status1.stdout().contains(InMemoryConfigSource.DEMO_SECRET_VALUE), "Must never leak secrets");

        // Hybrid flow: orders LOCAL -> pricing REMOTE via Gateway -> broker -> notifications LOCAL
        HttpProbeResult order1 = world.httpPost("/orders/create");
        assertEquals(200, order1.statusCode(), order1.body());
        assertTrue(order1.body().contains("\"servedBy\":\"local-orders\""), order1.body());
        assertTrue(order1.body().contains("\"pricedBy\":\"remote-wiremock-pricing\""), order1.body());
        assertTrue(order1.body().contains("\"price\":99"), order1.body());

        HttpProbeResult notif1 = world.httpGet("/notifications/ord-1");
        assertEquals(200, notif1.statusCode(), notif1.body());
        assertTrue(notif1.body().contains("\"servedBy\":\"local-notifications\""), notif1.body());
        assertTrue(notif1.body().contains("\"pricedBy\":\"remote-wiremock-pricing\""), notif1.body());

        // Start pricing + flip route LOCAL: UNIX separation -> Gateway DRIFTED and still routes REMOTE
        assertOk(world.runProcomp("start", "pricing"), "start pricing");
        assertOk(world.runProcomp("route", "pricing", "local"), "route pricing local");

        CliResult drifted = assertOk(world.runProcomp("status", "--json"), "status drifted");
        assertTrue(drifted.stdout().contains("\"syncStatus\":\"DRIFTED\""), drifted.stdout());

        HttpProbeResult preReconcile = world.httpPost("/orders/create");
        assertEquals(200, preReconcile.statusCode(), preReconcile.body());
        assertTrue(preReconcile.body().contains("\"pricedBy\":\"remote-wiremock-pricing\""),
            "Gateway must still route REMOTE before reconcile: " + preReconcile.body());

        // Reconcile -> orders hits local pricing without restarting orders
        assertOk(world.runProcomp("gateway", "reconcile"), "gateway reconcile local");

        HttpProbeResult order2 = world.httpPost("/orders/create");
        assertEquals(200, order2.statusCode(), order2.body());
        assertTrue(order2.body().contains("\"pricedBy\":\"local-pricing\""), order2.body());
        assertTrue(order2.body().contains("\"price\":42"), order2.body());

        // Stop LOCAL pricing -> fail fast with 5xx, zero silent fallback to REMOTE
        assertOk(world.runProcomp("stop", "pricing"), "stop pricing");
        HttpProbeResult failedPricing = world.httpGet("/pricing/quote");
        assertTrue(failedPricing.is5xx(), "Expected 5xx for stopped LOCAL target, got " + failedPricing);
        assertFalse(failedPricing.body().contains("remote-wiremock-pricing"), failedPricing.body());

        // Secret hygiene on inspect --json
        CliResult inspectOrders = assertOk(world.runProcomp("inspect", "orders", "--json"), "inspect orders");
        assertFalse(inspectOrders.stdout().contains(InMemoryConfigSource.DEMO_SECRET_VALUE), "Must never leak secrets");
    }

    @Test
    void unmanagedIdeServiceIsReusedOnStartAndSparedOnStop() {
        world.triggerActor(new ExternalActorEvent.SpawnUnmanagedService("pricing", true));

        CliResult status = assertOk(world.runProcomp("status", "--json"), "status unmanaged");
        assertTrue(status.stdout().contains("\"owner\":\"UNMANAGED\""), status.stdout());
        assertTrue(status.stdout().contains("\"provenance\":\"IDE\""), status.stdout());

        CliResult startRes = assertOk(world.runProcomp("start", "pricing"), "start reuses unmanaged");
        assertTrue(startRes.stdout().toLowerCase().contains("unmanaged") || startRes.stdout().toLowerCase().contains("reus"),
            startRes.stdout());

        // Stop must spare Unmanaged process
        world.runProcomp("stop", "pricing");
        CliResult afterStop = assertOk(world.runProcomp("status", "--json"), "status after stop");
        assertTrue(afterStop.stdout().contains("\"owner\":\"UNMANAGED\""), afterStop.stdout());
        assertTrue(afterStop.stdout().contains("\"health\":\"HEALTHY\""), afterStop.stdout());
    }

    @Test
    void conflictFailsFastOnStartAndReportsInspectionDetails() {
        world.triggerActor(new ExternalActorEvent.SpawnConflict("orders"));

        CliResult startRes = world.runProcomp("start", "orders");
        assertNotEquals(0, startRes.exitCode(), "start must fail fast on port Conflict");

        CliResult inspectRes = assertOk(world.runProcomp("inspect", "orders", "--json"), "inspect conflict");
        assertTrue(inspectRes.stdout().contains("\"owner\":\"CONFLICT\""), inspectRes.stdout());
        assertTrue(inspectRes.stdout().contains("python3"), inspectRes.stdout());
    }

    @Test
    void unmanagedGatewayIsReusedReconciledAndNeverKilledByStop() {
        world.triggerActor(new ExternalActorEvent.SpawnUnmanagedGateway());

        assertOk(world.runProcomp("gateway", "start"), "gateway start reuses unmanaged");
        assertOk(world.runProcomp("gateway", "reconcile"), "gateway reconcile on unmanaged");

        CliResult gwStatus = assertOk(world.runProcomp("gateway", "status", "--json"), "gateway status");
        assertTrue(gwStatus.stdout().contains("\"owner\":\"UNMANAGED\""), gwStatus.stdout());
        assertTrue(gwStatus.stdout().contains("\"syncStatus\":\"IN_SYNC\""), gwStatus.stdout());

        CliResult stopRes = world.runProcomp("gateway", "stop");
        assertNotEquals(0, stopRes.exitCode(), "gateway stop must refuse to kill Unmanaged Gateway");
    }

    @Test
    void externalKillProducesCrashedStateAndRecoversOnStart() {
        assertOk(world.runProcomp("start", "orders"), "start orders");
        world.triggerActor(new ExternalActorEvent.KillManagedService("orders"));

        CliResult crashedStatus = assertOk(world.runProcomp("status", "--json"), "status crashed");
        assertTrue(crashedStatus.stdout().contains("\"health\":\"CRASHED\""), crashedStatus.stdout());

        CliResult errLogs = assertOk(world.runProcomp("logs", "orders", "--err"), "logs --err");
        assertTrue(errLogs.stdout().contains("SIGKILL"), errLogs.stdout());

        assertOk(world.runProcomp("start", "orders"), "recover crashed orders");
        CliResult recovered = assertOk(world.runProcomp("status", "--json"), "status recovered");
        assertFalse(recovered.stdout().contains("\"health\":\"CRASHED\""), recovered.stdout());
        assertTrue(recovered.stdout().contains("\"health\":\"HEALTHY\""), recovered.stdout());
    }

    @Test
    void rebuiltArtifactMarksManagedServiceAsStaleUntilRestart() {
        assertOk(world.runProcomp("start", "orders"), "start orders");
        world.triggerActor(new ExternalActorEvent.RebuildArtifact("orders"));

        CliResult inspectStale = assertOk(world.runProcomp("inspect", "orders", "--json"), "inspect stale");
        assertTrue(inspectStale.stdout().contains("\"stale\":true"), inspectStale.stdout());

        assertOk(world.runProcomp("stop", "orders"), "stop stale orders");
        assertOk(world.runProcomp("start", "orders"), "restart orders");

        CliResult inspectFresh = assertOk(world.runProcomp("inspect", "orders", "--json"), "inspect fresh");
        assertTrue(inspectFresh.stdout().contains("\"stale\":false"), inspectFresh.stdout());
    }

    @Test
    void logsSupportsTailCountStderrAndPath() {
        assertOk(world.runProcomp("start", "orders"), "start orders");
        world.triggerActor(new ExternalActorEvent.AppendServiceLog("orders", "line-alpha", false));
        world.triggerActor(new ExternalActorEvent.AppendServiceLog("orders", "line-beta", false));
        world.triggerActor(new ExternalActorEvent.AppendServiceLog("orders", "err-gamma", true));

        CliResult tail2 = assertOk(world.runProcomp("logs", "orders", "-n", "2"), "logs -n 2");
        assertEquals("line-alpha\nline-beta\n", tail2.stdout().replace("\r\n", "\n"));

        CliResult stderr = assertOk(world.runProcomp("logs", "orders", "--err"), "logs --err");
        assertTrue(stderr.stdout().contains("err-gamma"), stderr.stdout());

        CliResult path = assertOk(world.runProcomp("logs", "orders", "--path"), "logs --path");
        assertTrue(path.stdout().trim().endsWith("orders.out.log"), path.stdout());
    }

    @Test
    void largeEstateSupportsTwentyFiveServicesAndLocalSliceProfile() {
        world.reset(new EstateSpec.LargeEstate(25));

        assertOk(world.runProcomp("profile", "use", "checkout-slice"), "profile use checkout-slice");
        assertOk(world.runProcomp("gateway", "start"), "gateway start");
        assertOk(world.runProcomp("gateway", "reconcile"), "gateway reconcile");
        assertOk(world.runProcomp("start"), "start 8 local services");

        CliResult status = assertOk(world.runProcomp("status", "--json"), "status --json");
        assertTrue(status.stdout().contains("\"svc-01\""), status.stdout());
        assertTrue(status.stdout().contains("\"svc-25\""), status.stdout());
        assertTrue(status.stdout().contains("\"syncStatus\":\"IN_SYNC\""), status.stdout());

        HttpProbeResult localResp = world.httpGet("/svc-01/ping");
        assertEquals(200, localResp.statusCode(), localResp.body());
        assertTrue(localResp.body().contains("\"servedBy\":\"local-svc-01\""), localResp.body());

        HttpProbeResult remoteResp = world.httpGet("/svc-25/ping");
        assertEquals(200, remoteResp.statusCode(), remoteResp.body());
        assertTrue(remoteResp.body().contains("\"servedBy\":\"remote-svc-25\""), remoteResp.body());
    }

    @Test
    void lagProfileAppliesSimulatedDelayWhenEnabled() {
        world.setLag(LagProfile.FAST);
        long startNanos = System.nanoTime();
        assertOk(world.runProcomp("start", "orders"), "start orders with FAST lag");
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        assertTrue(elapsedMs >= 100L, "Expected FAST lag (~150ms), elapsed=" + elapsedMs + "ms");
    }

    @Test
    void lagProfileAppliesSimulatedDelayToHealthChecksAndGatewayReconciliation() {
        assertOk(world.runProcomp("gateway", "start"), "gateway start");
        assertOk(world.runProcomp("start", "orders"), "start orders");

        world.setLag(LagProfile.FAST);

        long healthStartNanos = System.nanoTime();
        assertOk(world.runProcomp("status", "--json"), "status with FAST lag");
        long healthElapsedMs = (System.nanoTime() - healthStartNanos) / 1_000_000L;
        assertTrue(healthElapsedMs >= 25L, "Expected FAST lag on health checks, elapsed=" + healthElapsedMs + "ms");

        long reconcileStartNanos = System.nanoTime();
        assertOk(world.runProcomp("gateway", "reconcile"), "gateway reconcile with FAST lag");
        long reconcileElapsedMs = (System.nanoTime() - reconcileStartNanos) / 1_000_000L;
        assertTrue(reconcileElapsedMs >= 50L, "Expected FAST lag on gateway reconciliation, elapsed=" + reconcileElapsedMs + "ms");
    }

    @Test
    void reservedGitRepositoryFixtureSeamTracksStates() {
        world.triggerActor(new ExternalActorEvent.SeedGitRepositories());
        world.triggerActor(new ExternalActorEvent.AdvanceGitRemote("orders-repo"));
        world.triggerActor(new ExternalActorEvent.DirtyGitRepository("notifications-repo"));

        Map<String, InMemoryWorld.GitRepoState> repos = world.gitRepositories();
        assertEquals(InMemoryWorld.GitRepoState.UNCLONED, repos.get("pricing-repo"));
        assertEquals(InMemoryWorld.GitRepoState.BEHIND_REMOTE, repos.get("orders-repo"));
        assertEquals(InMemoryWorld.GitRepoState.DIRTY, repos.get("notifications-repo"));
        assertEquals(InMemoryWorld.GitRepoState.CLEAN, repos.get("broker-repo"));
    }

    private static CliResult assertOk(CliResult result, String label) {
        assertEquals(
            0,
            result.exitCode(),
            label + " failed (exit " + result.exitCode() + "). stdout:\n" + result.stdout() + "\nstderr:\n" + result.stderr()
        );
        return result;
    }
}
