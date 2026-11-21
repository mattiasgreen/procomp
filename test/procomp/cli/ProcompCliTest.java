package procomp.cli;

import procomp.Assert;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

public final class ProcompCliTest {

    public static void testHelpPrintsUsageAndExitsZero() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd);
        var stateStore = new InMemoryStateStore();

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int code = Procomp.run(
            new String[]{"--help"},
            CliRuntime.builder(new PrintWriter(outBuf, true), new PrintWriter(errBuf, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );

        Assert.assertEquals(0, code, "--help should exit 0");
        Assert.assertContains(outBuf.toString(), "Usage: procomp", "help output shows usage");
        Assert.assertContains(outBuf.toString(), "--config", "help output mentions --config");
        Assert.assertEquals("", errBuf.toString(), "--help should write nothing to stderr");
    }

    public static void testInvalidFlagsAndUnknownCommandsFailCleanlyWithExitCode2() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd);
        var stateStore = new InMemoryStateStore();

        StringWriter out1 = new StringWriter();
        StringWriter err1 = new StringWriter();
        int badFlagCode = Procomp.run(
            new String[]{"--bogus"},
            CliRuntime.builder(new PrintWriter(out1, true), new PrintWriter(err1, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );
        Assert.assertEquals(2, badFlagCode, "unknown flag exits 2");
        Assert.assertContains(err1.toString(), "--bogus", "stderr mentions unknown flag");

        StringWriter out2 = new StringWriter();
        StringWriter err2 = new StringWriter();
        int missingCfgArgCode = Procomp.run(
            new String[]{"--config"},
            CliRuntime.builder(new PrintWriter(out2, true), new PrintWriter(err2, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );
        Assert.assertEquals(2, missingCfgArgCode, "missing --config value exits 2");

        StringWriter out3 = new StringWriter();
        StringWriter err3 = new StringWriter();
        int unknownCmdCode = Procomp.run(
            new String[]{"nonexistent-cmd"},
            CliRuntime.builder(new PrintWriter(out3, true), new PrintWriter(err3, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );
        Assert.assertEquals(2, unknownCmdCode, "unknown command exits 2");
        Assert.assertContains(err3.toString(), "nonexistent-cmd", "stderr mentions unknown command");
    }

    public static void testMalformedConfigFailsWithExitCode1AndContextualError() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd)
            .withFile(cwd.resolve("bad.json"), "{\"project\": \"acme\", \"runtimes\": {}, \"badKey\": true}");
        var stateStore = new InMemoryStateStore();

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int code = Procomp.run(
            new String[]{"--config", "bad.json", "status"},
            CliRuntime.builder(new PrintWriter(outBuf, true), new PrintWriter(errBuf, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );

        Assert.assertEquals(1, code, "malformed config should exit 1");
        Assert.assertContains(errBuf.toString(), "CONFIG_UNRECOGNIZED_FIELD", "stderr reports strict config error");
        Assert.assertContains(errBuf.toString(), "badKey", "stderr reports offending field");
    }

    public static void testBareConfigOptionWithoutCommandPrintsUsageAndExitsZero() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd)
            .withFile(cwd.resolve("valid.json"), "{\"project\": \"acme\", \"runtimes\": {}}");
        var stateStore = new InMemoryStateStore();

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int code = Procomp.run(
            new String[]{"--config", "valid.json"},
            CliRuntime.builder(new PrintWriter(outBuf, true), new PrintWriter(errBuf, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );

        Assert.assertEquals(0, code, "bare --config without command should print usage and exit 0");
        Assert.assertContains(outBuf.toString(), "Usage: procomp", "bare --config prints usage");
        Assert.assertNotContains(outBuf.toString(), "Config OK", "bare --config must not emit Config OK");
        Assert.assertEquals("", errBuf.toString(), "bare --config writes nothing to stderr");
    }

    public static void testOmittedPortsFailCleanlyWithoutFallingBackToRealOsAdapters() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd)
            .withFile(cwd.resolve("procomp.json"), """
                {
                  "project": "acme",
                  "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
                  "services": { "orders": { "runtime": "java", "port": 9001, "workingDir": "." } }
                }
                """);
        var stateStore = new InMemoryStateStore();

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int statusCode = Procomp.run(
            new String[]{"status"},
            CliRuntime.builder(new PrintWriter(outBuf, true), new PrintWriter(errBuf, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );
        Assert.assertEquals(1, statusCode, "status without process ports exits 1");
        Assert.assertContains(errBuf.toString(), "PORT_NOT_CONFIGURED", "stderr reports PORT_NOT_CONFIGURED");
        Assert.assertContains(errBuf.toString(), "ProcessRunner", "stderr lists missing ProcessRunner");

        StringWriter outNoState = new StringWriter();
        StringWriter errNoState = new StringWriter();
        int routeCode = Procomp.run(
            new String[]{"route"},
            CliRuntime.builder(new PrintWriter(outNoState, true), new PrintWriter(errNoState, true))
                .configSource(configSource)
                .build()
        );
        Assert.assertEquals(1, routeCode, "route without stateStore exits 1");
        Assert.assertContains(errNoState.toString(), "PORT_NOT_CONFIGURED", "stderr reports PORT_NOT_CONFIGURED");
        Assert.assertContains(errNoState.toString(), "StateStore", "stderr lists missing StateStore");
    }
}
