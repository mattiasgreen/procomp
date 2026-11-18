# `procomp` Architecture

`procomp` (Process Composer) is a lightweight, zero-dependency PowerShell TUI and process orchestrator for running, monitoring, and routing Spring Boot microservices, ActiveMQ, and WireMock locally on Windows.

It is compatible with both **Windows PowerShell 5.1+** and **PowerShell 7+**, requiring no external binaries or compilation step.

---

## 1. Core Architectural Decisions

### Direct `java -jar` over `./gradlew bootRun`
Running 10+ Spring Boot services via `./gradlew bootRun` on Windows causes severe resource and lifecycle issues:
1. **Double-JVM Overhead:** Each `bootRun` spawns a Gradle daemon/worker JVM plus a forked application JVM (20+ JVMs for 10 services). `procomp` separates build time (`bootJar`) from run time (`java -jar`), maintaining a strict 1:1 process mapping.
2. **Orphaned Processes:** Terminating a Gradle wrapper on Windows frequently leaves child `java.exe` processes bound to ports `808x`. Direct `java.exe` execution paired with `taskkill.exe /F /T /PID` guarantees clean process-tree teardown.
3. **Explicit Memory Bounding:** Each JVM is launched with explicit `-Xmx` limits (e.g. `-Xmx256m`–`-Xmx384m`), and the TUI surfaces both configured `MAXHEAP` and live Windows `WorkingSet64` (`RAM USED`).

### Parallel Startup & Non-Blocking Health Polling
`procomp` does not enforce a strict sequential startup order:
* Enabled services launch in parallel.
* Health checks run asynchronously via `System.Net.Http.HttpClient.GetAsync()` without blocking the TUI input loop.
* Each service independently transitions `STOPPED` → `STARTING` → `HEALTHY` (or `CRASHED` if the process exits prematurely).

---

## 2. Three-Mode Hybrid Routing (WireMock Bridge)

Instead of running every microservice locally, WireMock (managed on port `8080`) acts as a local service-mesh and upstream bridge. Every service configures its `local` profile to call `http://localhost:8080/<service>/...`, and WireMock routes each prefix to one of three modes:

| Mode | Where it runs | WireMock Target | TUI `STATE` | TUI `ROUTING` |
| :--- | :--- | :--- | :--- | :--- |
| **a) Remote** | Shared dev environment | `https://shared-dev.example.com/<service>` | `-` / `REMOTE` | `REMOTE` |
| **b) Local JAR** | `procomp` (`java -jar`) | `http://127.0.0.1:<localport>` | `HEALTHY` (PID/RAM) | `LOCAL :<port>` |
| **c) Local IDE** | IntelliJ / Eclipse debug | `http://127.0.0.1:<localport>` | `IDE-UP` (PID/RAM) | `LOCAL :<port>` |

* **Single Source of Truth:** `procomp` pushes and updates proxy mappings via WireMock's `/__admin/mappings` API using `services.json`.
* **Persisted Routing State:** Per-service `REMOTE` vs `LOCAL` choices are saved in `.stack_state/routing.json` so your hybrid setup survives restarts.

---

## 3. Port-to-Parent-PID IDE Detection

To seamlessly support **Local IDE** debugging without manual configuration:
1. When a service's routing is `LOCAL` and no `.stack_state/<name>.pid` file exists, `procomp` queries `Get-NetTCPConnection -LocalPort <localport> -State Listen` to find the `OwningProcess` PID.
2. It walks the process's parent chain (`ParentProcessId` via CIM/WMI) looking for known IDE executables (`idea64.exe`, `eclipse.exe`, `Code.exe`, etc.).
3. **Behavior:**
   * If an IDE ancestor is found: `STATE` displays `IDE-UP` along with live `PID`, `UPTIME`, and `RAM USED`, and `procomp` **never kills** it on `q` or `down`.
   * If a non-IDE external process owns the port: `procomp` flags a `PORT-CONFLICT` before attempting `Start-Process`.

---

## 4. Git & Gradle Intelligence

Because `services.json` tracks `gitrepo` and `gradlesubproject` (supporting both separate repos and monorepos):
* **Deduplicated Repo Status:** Multiple services sharing a `gitrepo` are grouped for Git operations (`sync` / status).
* **Taint & Branch Detection:** `procomp` inspects each `gitrepo` for:
  * Current branch (`main`/`develop` vs. feature branch)
  * Working tree cleanliness (clean vs. dirty/tainted)
* **Smart Rebuild Decisions:**
  * Clean `main`/`develop` repos can be fast-forwarded (`git pull --ff-only`), triggering `./gradlew :<subproject>:bootJar` only when new commits land or the JAR is missing/stale.
  * Dirty or feature-branch repos are never overwritten during sync and are surfaced visually in the TUI.

---

## 5. Responsive TUI & Unified Log Viewer

### UI Loop Responsiveness
To keep keyboard navigation instantaneous even with 10+ active services:
* **25 ms Key Poll + Buffer Draining:** `while ([Console]::KeyAvailable)` consumes all queued `↑`/`↓` keypresses before rendering a single frame.
* **Batched OS Queries:** `Get-Process -Id $allPids` runs once per refresh interval (~750 ms) rather than per-row per-frame.
* **Async Health Probes:** `HttpClient.GetAsync()` tasks are polled via `.IsCompleted` with zero UI-thread blocking.
* **Log Caching:** Per-service parsed log entries are cached by `(FileInfo.Length, LastWriteTimeUtc.Ticks, RawMode)`.

### Unified JSON + Plain-Text Logs
Raw service logs in `logs/<service>.log` remain untouched on disk. At render time:
* **Auto-Detection:** JSON lines (`@timestamp`/`timestamp`, `level`/`severity`, `service`, `message`/`msg`, `traceId`) and plain-text lines are normalized into a single format:
  `yyyy-MM-dd HH:mm:ss.fff  LEVEL [service] --- message`
* **Timezone Alignment:** ISO-8601 UTC/offset timestamps in JSON logs are converted to `LocalDateTime` before sorting, ensuring accurate chronological interleaving in `[ALL]` merged mode.
* **Interactive Filters:** Toggle `[ALL]` merged view (`a`/`Tab`), `WARN`/`ERROR` only (`e`), and `Raw` vs. `Unified` (`v`).
