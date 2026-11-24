package procomp.core.process;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure, zero-I/O advisor that suggests copy-pasteable terminal diagnostic commands
 * based on an observed live Process's PID, executable, and arguments.
 */
public final class DiagnosticCommandAdvisor {

    private static final Set<String> JVM_EXECUTABLES = Set.of("java", "java.exe", "javaw", "javaw.exe");
    private static final String NMT_FLAG_PREFIX = "-xx:nativememorytracking=";
    private static final String NMT_NOTE = "requires -XX:NativeMemoryTracking=summary in jvmArgs";

    private DiagnosticCommandAdvisor() {}

    public static List<DiagnosticCommand> suggest(DiscoveryResult discovery) {
        Objects.requireNonNull(discovery, "discovery must not be null");
        if (!discovery.health().isLive()) {
            return List.of();
        }
        if (discovery.pid().isEmpty() || discovery.process().isEmpty()) {
            return List.of();
        }
        ObservedProcess proc = discovery.process().get();
        if (!proc.alive()) {
            return List.of();
        }
        if (!isJvmProcess(proc)) {
            return List.of();
        }

        long pid = discovery.pid().get();
        boolean nmtEnabled = hasNativeMemoryTrackingEnabled(proc);
        DiagnosticCommand nmtCommand = nmtEnabled
            ? DiagnosticCommand.of("JVM native memory", "jcmd " + pid + " VM.native_memory summary")
            : DiagnosticCommand.withNote("JVM native memory", "jcmd " + pid + " VM.native_memory summary", NMT_NOTE);

        return List.of(
            DiagnosticCommand.of("JVM heap summary", "jcmd " + pid + " GC.heap_info"),
            DiagnosticCommand.of("Effective JVM flags", "jcmd " + pid + " VM.flags"),
            nmtCommand
        );
    }

    private static boolean isJvmProcess(ObservedProcess proc) {
        Optional<String> baseName = DiscoveryEngine.extractExecutableBaseName(proc.executable(), proc.commandLine());
        return baseName.isPresent() && JVM_EXECUTABLES.contains(baseName.get().toLowerCase(Locale.ROOT));
    }

    private static boolean hasNativeMemoryTrackingEnabled(ObservedProcess proc) {
        for (String arg : proc.arguments()) {
            if (isEnabledNmtToken(arg)) {
                return true;
            }
        }
        if (proc.commandLine().isPresent()) {
            for (String token : proc.commandLine().get().split("\\s+", -1)) {
                if (isEnabledNmtToken(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isEnabledNmtToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(NMT_FLAG_PREFIX)) {
            return false;
        }
        String mode = lower.substring(NMT_FLAG_PREFIX.length());
        return !mode.isEmpty() && !"off".equals(mode);
    }
}
