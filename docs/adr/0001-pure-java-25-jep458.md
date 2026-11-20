---
status: accepted
---

# Pure Zero-Dependency Java 25 LTS with JEP 458 Source Launch

`procomp` targets restricted corporate Windows, macOS, and Linux environments where JDK 21+/25 and Bash/Git Bash are available, unsigned binaries and `npm` are unwanted, and onboarding must be clone-and-run offline with zero build/install steps. We build `procomp` in pure, zero-dependency Java 25 LTS launched directly from multi-file source via JEP 458 (`java src/procomp/cli/Procomp.java`), with zero Maven, Gradle, or third-party JARs, and a tiny zero-dependency Java test runner.

## Considered Options

- **Pure Bash / Git Bash:** Available everywhere, but slow on Windows (subshell `fork()` overhead), lacks native JSON/HTTP (`jq`/`curl` not guaranteed), requires OS-specific process/port shell-outs, and cannot enforce FCIS + DDD types or narrow role ports.
- **Node.js / TypeScript:** Strong typing and native `.ts` execution on Node 22+, but rejected to avoid `npm`/Node ecosystem dependency across pilot machines.
- **Static compiled binaries (Go / Rust / .NET AOT):** Fast startup and single-file distribution, but rejected for v1 to avoid building, signing, and distributing binaries across restricted corporate machines.
- **Java 21 with `javac` cache:** Works on JDK 21, but requires managing a `.cache/classes` compilation shim; JEP 458 on JDK 25 LTS eliminates `javac` completely (with a future option to bundle a JEP 330 single-file or compiled artifact if JDK 21 support is later required).

## Consequences

- `ProcessHandle` provides native cross-platform PID, parent-chain, command-line, and start-time inspection without shelling out to PowerShell/WMI/`ps`.
- JSON parsing and CLI argument parsing are implemented cleanly in small, strict, zero-dependency `core/` modules.
- Every CLI invocation compiles referenced `.java` files in memory via JEP 458 (acceptable for v1; an optional AOT/compiled cache fast path can be layered later without changing source structure).
- Because JEP 458 resolves a single source root per `java` invocation, `test/TestRunner.java` compiles `src/` and `test/` together in-JVM via `javax.tools.ToolProvider.getSystemJavaCompiler()` into a temporary directory (deleted in `finally`) rather than importing `src/` packages directly.
