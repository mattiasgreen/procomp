# `procomp` (Process Composer)

A lightweight, Process-Compose-inspired PowerShell TUI and process orchestrator for running, monitoring, and routing Spring Boot microservices, ActiveMQ, and WireMock locally on Windows—without Docker overhead or orphaned `./gradlew bootRun` JVMs.

## Why `procomp`?
* **1:1 JVM Process Model:** Runs flat `java -jar` processes with explicit `-Xmx` bounds instead of `./gradlew bootRun` (avoiding double JVMs, Gradle daemon lock contention, and orphaned child processes).
* **Hybrid 3-Mode Routing via WireMock:** Run only the 1–2 services you're actively working on (either as a `LOCAL-JAR` or `LOCAL-IDE` debugger in IntelliJ/Eclipse) while WireMock bridges the rest to a `REMOTE` shared dev environment.
* **IDE Process Detection:** Automatically detects IDE-launched processes bound to a service's port via `Get-NetTCPConnection` + parent PID inspection (`IDE-UP`), surfacing their RAM/uptime without ever killing them on exit.
* **Unified Log Viewer:** Tails and merges JSON (UTC/offset) and plain-text logs in real time with `WARN`/`ERROR` filtering.
* **Zero Dependencies:** Pure PowerShell (compatible with Windows PowerShell 5.1+ and PowerShell 7+).

## Documentation
* [Architecture & Design](docs/ARCHITECTURE.md)
* [Configuration Schema (`services.json`)](docs/CONFIG_SCHEMA.md)
* [PowerShell Best Practices & Agentic Guidelines](docs/POWERSHELL_GUIDELINES.md)

## Quick Start (PoC)
Compile the included Java mock service and launch the TUI:

```powershell
cd mock-service
javac MockService.java
jar cfe mock-service.jar MockService MockService.class
cd ..
.\stack.cmd
```

### TUI Keybindings
| Key | Action |
| :--- | :--- |
| `↑` / `↓` or `j` / `k` | Select service & switch log tail |
| `s` | Start selected service |
| `x` | Stop selected service |
| `r` | Restart selected service |
| `a` / `Tab` | Toggle `[ALL]` merged log stream vs. selected service |
| `e` | Toggle `WARN`/`ERROR` only filter |
| `v` | Toggle Unified/Normalized vs. Raw log lines |
| `d` | Detach (exit TUI while leaving services running) |
| `q` / `Ctrl+C` | Confirm & stop all managed services |

### CLI Mode
```powershell
.\stack.cmd -Action up        # Start enabled services in background
.\stack.cmd -Action status    # Print status, PID, heap, and RAM table
.\stack.cmd -Action down      # Cleanly stop all managed services
.\stack.cmd -Action selftest  # Run automated PoC verification suite
```
