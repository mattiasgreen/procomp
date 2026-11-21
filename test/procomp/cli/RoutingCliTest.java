package procomp.cli;

import procomp.Assert;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RoutingCliTest {

    public static void testRouteShowUpdateTableJsonPersistenceAndErrors() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = sampleConfigSource(root);
        var stateStore = new InMemoryStateStore();

        // 1. Fresh project route --json defaults all services to REMOTE
        CliResult initialJson = runCli(configSource, stateStore, "route", "--json");
        Assert.assertEquals(0, initialJson.exitCode(), "route --json exits 0; stderr=" + initialJson.stderr());
        Assert.assertEquals("", initialJson.stderr(), "route --json writes nothing to stderr");

        JsonValue.JsonObject parsedInitial = asObject(Json.parse(initialJson.stdout().trim()).fold(
            v -> v,
            err -> { throw new AssertionError("route --json must output valid JSON: " + err.format()); }
        ));
        Assert.assertEquals("acme", ((JsonValue.JsonString) parsedInitial.get("project").orElseThrow()).value(), "json project");
        JsonValue.JsonArray initialRoutes = (JsonValue.JsonArray) parsedInitial.get("routes").orElseThrow();
        Assert.assertEquals(3, initialRoutes.elements().size(), "json lists all 3 services");
        Assert.assertEquals("REMOTE", routeField(initialRoutes, 0, "route"), "orders starts REMOTE");
        Assert.assertEquals("REMOTE", routeField(initialRoutes, 1, "route"), "payments starts REMOTE");
        Assert.assertEquals("REMOTE", routeField(initialRoutes, 2, "route"), "inventory starts REMOTE");

        // 2. Update two services to LOCAL in one call, verify table & persistence across CLI calls
        CliResult updateLocal = runCli(configSource, stateStore, "route", "orders", "inventory", "local");
        Assert.assertEquals(0, updateLocal.exitCode(), "route multi local exits 0; stderr=" + updateLocal.stderr());
        Assert.assertContains(updateLocal.stdout(), "orders", "update output mentions orders");
        Assert.assertContains(updateLocal.stdout(), "LOCAL", "update output mentions LOCAL");
        Assert.assertContains(updateLocal.stdout(), "inventory", "update output mentions inventory");

        CliResult tableShow = runCli(configSource, stateStore, "route");
        Assert.assertEquals(0, tableShow.exitCode(), "route table exits 0");
        Assert.assertContains(tableShow.stdout(), "SERVICE", "table header SERVICE");
        Assert.assertContains(tableShow.stdout(), "ROUTE", "table header ROUTE");
        Assert.assertContains(tableShow.stdout(), "orders", "table shows orders");
        Assert.assertContains(tableShow.stdout(), "LOCAL", "table shows LOCAL");
        Assert.assertContains(tableShow.stdout(), "REMOTE", "table shows REMOTE");
        Assert.assertContains(tableShow.stdout(), "http://127.0.0.1:9001", "table shows local target for LOCAL service");
        Assert.assertContains(tableShow.stdout(), "https://shared.corp/payments", "table shows remoteUrl for REMOTE service");

        // 3. Case-insensitive target route & update back to REMOTE
        CliResult updateRemote = runCli(configSource, stateStore, "route", "orders", "REMOTE");
        Assert.assertEquals(0, updateRemote.exitCode(), "route orders REMOTE exits 0");

        // 4. Unknown service fails with exit code 1 and does not mutate persisted state
        Map<String, String> stateBeforeError = Assert.assertOk(stateStore.readRouting("acme"), "read state before error").orElseThrow();
        CliResult unknownSvc = runCli(configSource, stateStore, "route", "payments", "ghost-svc", "local");
        Assert.assertEquals(1, unknownSvc.exitCode(), "unknown service exits 1");
        Assert.assertContains(unknownSvc.stderr(), "UNKNOWN_SERVICE", "stderr reports UNKNOWN_SERVICE");
        Assert.assertContains(unknownSvc.stderr(), "ghost-svc", "stderr identifies ghost-svc");
        Assert.assertEquals(
            Optional.of(stateBeforeError),
            Assert.assertOk(stateStore.readRouting("acme"), "read state after error"),
            "persisted routing untouched on unknown service error"
        );

        // 5. Invalid CLI syntax and flags fail with exit code 2 without mutating state
        for (String[] badArgs : List.of(
            new String[]{"route", "--bogus"},
            new String[]{"route", "orders"},
            new String[]{"route", "local"},
            new String[]{"route", "orders", "local", "--json"},
            new String[]{"route", "orders", "mock"}
        )) {
            CliResult bad = runCli(configSource, stateStore, badArgs);
            Assert.assertEquals(2, bad.exitCode(), "bad route syntax " + String.join(" ", badArgs) + " should exit 2");
        }
        Assert.assertEquals(
            Optional.of(stateBeforeError),
            Assert.assertOk(stateStore.readRouting("acme"), "read state after syntax errors"),
            "persisted routing untouched on syntax errors"
        );
    }

    public static void testProfileListAndUseTableJsonPersistenceAndErrors() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = sampleConfigSource(root);
        var stateStore = new InMemoryStateStore();

        // 1. profile list (table)
        CliResult listTable = runCli(configSource, stateStore, "profile", "list");
        Assert.assertEquals(0, listTable.exitCode(), "profile list exits 0; stderr=" + listTable.stderr());
        Assert.assertContains(listTable.stdout(), "PROFILE", "profile table header PROFILE");
        Assert.assertContains(listTable.stdout(), "LOCAL", "profile table header LOCAL");
        Assert.assertContains(listTable.stdout(), "orders-only", "profile table lists orders-only");
        Assert.assertContains(listTable.stdout(), "orders", "profile table lists local services");
        Assert.assertContains(listTable.stdout(), "empty-slice", "profile table lists empty-slice");

        // 2. profile list --json
        CliResult listJson = runCli(configSource, stateStore, "profile", "list", "--json");
        Assert.assertEquals(0, listJson.exitCode(), "profile list --json exits 0");
        JsonValue.JsonObject parsedProfiles = asObject(Json.parse(listJson.stdout().trim()).fold(
            v -> v,
            err -> { throw new AssertionError("profile list --json must output valid JSON: " + err.format()); }
        ));
        Assert.assertEquals("acme", ((JsonValue.JsonString) parsedProfiles.get("project").orElseThrow()).value(), "json project");
        JsonValue.JsonArray profileArr = (JsonValue.JsonArray) parsedProfiles.get("profiles").orElseThrow();
        Assert.assertEquals(2, profileArr.elements().size(), "json lists both profiles");
        JsonValue.JsonObject firstProf = (JsonValue.JsonObject) profileArr.elements().get(0);
        Assert.assertEquals("orders-only", ((JsonValue.JsonString) firstProf.get("name").orElseThrow()).value(), "first profile name");
        JsonValue.JsonArray firstLocal = (JsonValue.JsonArray) firstProf.get("local").orElseThrow();
        Assert.assertEquals(1, firstLocal.elements().size(), "orders-only has 1 local service");
        Assert.assertEquals("orders", ((JsonValue.JsonString) firstLocal.elements().get(0)).value(), "orders-only local entry");

        // 3. Set payments LOCAL first, then apply orders-only profile -> payments resets to REMOTE, orders becomes LOCAL
        Assert.assertEquals(0, runCli(configSource, stateStore, "route", "payments", "local").exitCode(), "pre-set payments local");
        CliResult useRes = runCli(configSource, stateStore, "profile", "use", "orders-only");
        Assert.assertEquals(0, useRes.exitCode(), "profile use exits 0; stderr=" + useRes.stderr());
        Assert.assertContains(useRes.stdout(), "orders-only", "profile use output mentions profile");

        CliResult afterUseJson = runCli(configSource, stateStore, "route", "--json");
        JsonValue.JsonArray routesAfter = (JsonValue.JsonArray) asObject(Json.parse(afterUseJson.stdout().trim()).fold(
            v -> v,
            err -> { throw new AssertionError("valid json: " + err.format()); }
        )).get("routes").orElseThrow();
        Assert.assertEquals("LOCAL", routeField(routesAfter, 0, "route"), "orders became LOCAL");
        Assert.assertEquals("REMOTE", routeField(routesAfter, 1, "route"), "payments reset to REMOTE");
        Assert.assertEquals("REMOTE", routeField(routesAfter, 2, "route"), "inventory stayed REMOTE");

        // 4. Unknown profile fails with exit code 1 and does not modify routing state
        Map<String, String> stateBeforeError = Assert.assertOk(stateStore.readRouting("acme"), "read state before profile err").orElseThrow();
        CliResult unknownProf = runCli(configSource, stateStore, "profile", "use", "missing-profile");
        Assert.assertEquals(1, unknownProf.exitCode(), "unknown profile exits 1");
        Assert.assertContains(unknownProf.stderr(), "UNKNOWN_PROFILE", "stderr reports UNKNOWN_PROFILE");
        Assert.assertEquals(
            Optional.of(stateBeforeError),
            Assert.assertOk(stateStore.readRouting("acme"), "read state after unknown profile"),
            "persisted routing untouched on unknown profile"
        );

        // 5. Invalid profile CLI syntax fails with exit code 2 without mutating state
        for (String[] badArgs : List.of(
            new String[]{"profile"},
            new String[]{"profile", "--json"},
            new String[]{"profile", "bogus"},
            new String[]{"profile", "list", "extra"},
            new String[]{"profile", "list", "--bogus"},
            new String[]{"profile", "use"},
            new String[]{"profile", "use", "--bogus"},
            new String[]{"profile", "use", "orders-only", "extra"},
            new String[]{"profile", "use", "orders-only", "--json"}
        )) {
            CliResult bad = runCli(configSource, stateStore, badArgs);
            Assert.assertEquals(2, bad.exitCode(), "bad profile syntax " + String.join(" ", badArgs) + " should exit 2");
        }
        Assert.assertEquals(
            Optional.of(stateBeforeError),
            Assert.assertOk(stateStore.readRouting("acme"), "read state after profile syntax errors"),
            "persisted routing untouched on profile syntax errors"
        );
    }

    public static void testEmptyServicesAndProfilesRenderCleanly() {
        Path root = Path.of("C:/work/empty").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "empty-proj",
                  "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                  }
                }
                """);
        var stateStore = new InMemoryStateStore();

        CliResult routeTable = runCli(configSource, stateStore, "route");
        Assert.assertEquals(0, routeTable.exitCode(), "empty route table exits 0");
        Assert.assertContains(routeTable.stdout(), "No services configured", "empty route message");

        CliResult routeJson = runCli(configSource, stateStore, "route", "--json");
        Assert.assertEquals(0, routeJson.exitCode(), "empty route --json exits 0");
        JsonValue.JsonObject routeObj = asObject(Assert.assertOk(Json.parse(routeJson.stdout().trim()), "valid json"));
        Assert.assertEquals(0, ((JsonValue.JsonArray) routeObj.get("routes").orElseThrow()).elements().size(), "empty routes array");

        CliResult profTable = runCli(configSource, stateStore, "profile", "list");
        Assert.assertEquals(0, profTable.exitCode(), "empty profile list exits 0");
        Assert.assertContains(profTable.stdout(), "No profiles configured", "empty profiles message");

        CliResult profJson = runCli(configSource, stateStore, "profile", "list", "--json");
        Assert.assertEquals(0, profJson.exitCode(), "empty profile list --json exits 0");
        JsonValue.JsonObject profObj = asObject(Assert.assertOk(Json.parse(profJson.stdout().trim()), "valid json"));
        Assert.assertEquals(0, ((JsonValue.JsonArray) profObj.get("profiles").orElseThrow()).elements().size(), "empty profiles array");
    }

    private static String routeField(JsonValue.JsonArray arr, int index, String key) {
        JsonValue.JsonObject obj = (JsonValue.JsonObject) arr.elements().get(index);
        return ((JsonValue.JsonString) obj.get(key).orElseThrow()).value();
    }

    private static JsonValue.JsonObject asObject(JsonValue val) {
        return (JsonValue.JsonObject) val;
    }

    private static CliResult runCli(InMemoryConfigSource cfg, InMemoryStateStore state, String... args) {
        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int code = Procomp.run(
            args,
            CliRuntime.builder(new PrintWriter(outBuf, true), new PrintWriter(errBuf, true))
                .configSource(cfg)
                .stateStore(state)
                .build()
        );
        return new CliResult(code, outBuf.toString(), errBuf.toString());
    }

    private static InMemoryConfigSource sampleConfigSource(Path root) {
        return new InMemoryConfigSource(root)
            .withFile(root.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "defaults": {
                    "proxyPathTemplate": "/{{service}}",
                    "remoteUrlTemplate": "https://shared.corp/{{service}}"
                  },
                  "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "." },
                    "payments": { "runtime": "java", "port": 9002, "workingDir": "." },
                    "inventory": { "runtime": "java", "port": 9003, "workingDir": "." }
                  },
                  "profiles": {
                    "orders-only": { "local": ["orders"] },
                    "empty-slice": { "local": [] }
                  }
                }
                """);
    }

    private record CliResult(int exitCode, String stdout, String stderr) {}
}
