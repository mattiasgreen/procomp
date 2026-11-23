package procomp.cli;

import procomp.Assert;
import procomp.core.common.Result;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

public final class UiCliTest {

    public static void testParseDefaultsAndValidFlags() {
        UiCli.Options defaults = Assert.assertOk(UiCli.parse(List.of()), "default ui args");
        Assert.assertEquals(new UiCli.Options(UiCli.DEFAULT_PORT, false), defaults, "defaults to port 9090 and open=false");
        Assert.assertEquals(9090, UiCli.DEFAULT_PORT, "default port is 9090");

        UiCli.Options portAndOpen = Assert.assertOk(
            UiCli.parse(List.of("--port", "8085", "--open")),
            "--port 8085 --open"
        );
        Assert.assertEquals(new UiCli.Options(8085, true), portAndOpen, "parses --port and --open");

        UiCli.Options ephemeralEquals = Assert.assertOk(
            UiCli.parse(List.of("--port=0")),
            "--port=0"
        );
        Assert.assertEquals(new UiCli.Options(0, false), ephemeralEquals, "accepts ephemeral port 0");

        UiCli.Options maxPort = Assert.assertOk(
            UiCli.parse(List.of("--open", "--port=65535")),
            "--open --port=65535"
        );
        Assert.assertEquals(new UiCli.Options(65535, true), maxPort, "accepts max port 65535");
    }

    public static void testParseRejectsInvalidPortsAndUnexpectedArgs() {
        for (List<String> badArgs : List.of(
            List.of("--port"),
            List.of("--port="),
            List.of("--port", "-1"),
            List.of("--port", "65536"),
            List.of("--port", "abc"),
            List.of("--port=99999"),
            List.of("--unknown"),
            List.of("extra-arg")
        )) {
            Result<UiCli.Options, String> res = UiCli.parse(badArgs);
            Assert.assertErr(res, "invalid ui args should fail: " + badArgs);
        }
    }

    public static void testProcompHelpIncludesUiAndBadUiFlagsExitCode2() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd);
        var stateStore = new InMemoryStateStore();

        StringWriter helpOut = new StringWriter();
        StringWriter helpErr = new StringWriter();
        int helpCode = Procomp.run(
            new String[]{"--help"},
            CliRuntime.builder(new PrintWriter(helpOut, true), new PrintWriter(helpErr, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );
        Assert.assertEquals(0, helpCode, "--help exits 0");
        Assert.assertContains(helpOut.toString(), "ui [--port <n>] [--open]", "--help documents ui command");

        StringWriter badOut = new StringWriter();
        StringWriter badErr = new StringWriter();
        int badCode = Procomp.run(
            new String[]{"ui", "--port", "70000"},
            CliRuntime.builder(new PrintWriter(badOut, true), new PrintWriter(badErr, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );
        Assert.assertEquals(2, badCode, "invalid ui port exits 2");
        Assert.assertContains(badErr.toString(), "70000", "stderr mentions invalid port");
    }

    public static void testProcompUiFailsFastWithExitCode1WhenConfigMissing() {
        Path cwd = Path.of("C:/work/acme").toAbsolutePath().normalize();
        var configSource = new InMemoryConfigSource(cwd);
        var stateStore = new InMemoryStateStore();

        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int code = Procomp.run(
            new String[]{"ui", "--port", "0"},
            CliRuntime.builder(new PrintWriter(outBuf, true), new PrintWriter(errBuf, true))
                .configSource(configSource)
                .stateStore(stateStore)
                .build()
        );
        Assert.assertEquals(1, code, "missing procomp.json exits 1");
        Assert.assertContains(errBuf.toString(), "CONFIG_NOT_FOUND", "stderr reports CONFIG_NOT_FOUND");
    }
}
