# Architecture Decision Records (ADRs)

This document records the core architectural decisions behind **`procomp`**, including lessons learned from real-world 46-service testing on Windows.

---

## ADR-001: Data-Driven `builders`, `runtimes`, `appsFile`, and `defaults` (Zero Hardcoded Presets)
- **Status:** Accepted
- **Context:** Real microservice stacks mix Spring Boot (Gradle/Maven), Quarkus, Node.js, WireMock, ActiveMQ, and custom test runners (`autotest`), and often maintain their service catalog in an external GitOps file (`service-index.json`).
- **Decision:** Keep `procomp` completely free of hardcoded framework logic. All build/run commands live in `procomp.json` under `builders` and `runtimes` using `{{var}}` templates. Support `appsFile` (pointing to `service-index.json`) combined with `defaults` (`builder`, `enabled`, `logFormat`, `proxyPathTemplate`, `remoteUrlTemplate`, `workingDir`, `vars`, `env`) and wildcard profiles (`"remote": ["*"]`).
- **Consequences:** Any language or runtime (including `nodejs`, `autotest` with custom `env`, or Gradle subprojects) can be added purely via JSON with zero code changes.

---

## ADR-002: Direct 1:1 Execution & Async Missing-Artifact Builds
- **Status:** Accepted
- **Context:** Running `gradlew bootRun` spawns a Gradle daemon plus a child JVM, causing high RAM usage, lock contention, and orphaned JVMs on teardown. Conversely, running `gradlew assemble` synchronously when a JAR is missing froze the TUI for 30s–2m.
- **Decision:** Separate build (`builders`, defaulting to `assemble`) from execution (`runtimes`, direct `java -jar {{artifact}}`). In TUI mode, pass `-AsyncBuild` so missing-artifact builds run asynchronously (`BUILDING` state), stream live stdout/stderr into the service's log pane, and auto-launch the service on exit `0`.
- **Implementation Note (PS 5.1):** `Start-Process -PassThru` with `-RedirectStandardOutput` leaves `.ExitCode` as `$null` in Windows PowerShell 5.1. Async builds therefore wrap `cmd.exe /c "<cmd> 1>log 2>err"` and cache `$proc.Handle` + call `WaitForExit()` before reading `.ExitCode`.

---

## ADR-003: Strict UI/Worker Thread Separation, 4-Thread Pool, and Deferred Async Git
- **Status:** Accepted
- **Context:** With 16–46 services, running `Start-ServiceProcess`, `Sync-WireMockMappings`, or `Refresh-GitSummaries` on the UI thread froze keyboard input; running Git synchronously inside `BackgroundWorker` blocked `Poll-AsyncHealth` so services stayed `STARTING` even after booting.
- **Decision:**
  1. The UI thread ONLY reads input, renders snapshots, and enqueues jobs onto `CommandJobQueue` / `GitJobQueue`.
  2. `BackgroundWorker` executes `Startup` (`LOCAL`-first), lifecycle commands (`start`/`stop`/`restart`/`build`/`route`/`SyncGateway`), and `BuildProc` exit checks off the UI thread.
  3. Git status (`Refresh-GitSummaries`), batch fetch/pull, and WireMock fallback POSTs run across a **4-thread `RunspacePool`** (`Invoke-ParallelWork -MaxThreads 4`), with thread-safe `ProcessStartInfo` env handling for `git`.
  4. Periodic Git status polling is **deferred by 4s on startup**, runs every **30 minutes** inside its own non-blocking async `Runspace`, and uses `-Quiet` so it never stalls health polling or hijacks the ticker.

---

## ADR-004: Project-Scoped Runtime State & Logs
- **Status:** Accepted
- **Context:** Multiple projects (or `demo` vs. real corporate stack) sharing `.stack_state/` and `logs/` overwrote each other's `routing.json`, `.pid` files, and logs.
- **Decision:** Scope state and service logs under `.stack_state/<project>/` and `logs/<project>/`, where `<project>` resolves from `-Project` > `config.project` > config filename stem > `"default"`. Keep state/logs inside the `procomp` directory rather than inside target Git repos to avoid untracked repo clutter.

---

## ADR-005: WireMock Clear-and-Batch-Import Strategy & External Gateway Reuse
- **Status:** Accepted
- **Context:** Looping `PUT` + `POST` per service across 46 services when WireMock wasn't running caused 92 failed HTTP requests; diffing mappings added complexity when stale mappings existed; and configuring WireMock as a fake app in `apps` was awkward with `appsFile`.
- **Decision:**
  1. Allow configuring `port`, `adminUrl`, `command`, `workingDir`, and `healthPath` directly on `gateway` in `procomp.json`, synthesizing a managed `wiremock-gateway` service automatically (immune to `"remote": ["*"]` wildcard profiles).
  2. Pre-check `Test-WireMockReachable` using a `< 1 ms` localhost TCP listener check before HTTP.
  3. If WireMock is already reachable externally on `gateway.port`, reuse it (`IDE-UP`, never kill on exit) instead of throwing `PORT-CONFLICT`.
  4. On full sync (`Sync-WireMockMappings`), clear routes (`DELETE /__admin/mappings`) and batch-import in **one HTTP request** via `POST /__admin/mappings/import` (falling back to 4-thread `POST /__admin/mappings` if `/import` is unavailable), guarded by `GatewaySynced` to prevent double-sync.

---

## ADR-006: `LOCAL`-Only Adaptive Health Polling & `PROBING` State
- **Status:** Accepted
- **Context:** Polling `/__admin/health` and `/actuator/health` every 1.2s generated thousands of health requests over a work session; showing `STOPPED` while probing already-running services was misleading.
- **Decision:**
  1. Never health-poll `REMOTE` services; skip HTTP polling for services with empty `healthPath`.
  2. Poll `STARTING` / `IDE-INIT` services every **1.5s** and slow down to **10s** once `HEALTHY` / `IDE-UP`.
  3. Mark enabled services `PROBING` (yellow) on startup and publish per-service metrics immediately after each `Start-ServiceProcess`.

---

## ADR-007: Smart `stderr` Placement & Timestamp-Less `SortKey` Inheritance
- **Status:** Accepted
- **Context:** Pinning `stderr` to `SortKey = "9999-99-99 ..."` with forced `Level = "ERROR"` caused `SLF4J:` / Logback startup messages to crowd the bottom of live logs in red forever. Conversely, hiding `stderr` during builds hid Gradle errors.
- **Decision:** Parse `stderr` via `Normalize-LogEntry` (respecting actual log level). Pin `stderr` to the bottom under `--- stderr ---` **only** during `BUILDING`, `BUILD-FAIL`, `CRASHED`, or when `stdout` is empty; once running with `stdout`, place `stderr` at the top (`0000-...`) so startup noise scrolls off naturally. Timestamp-less `stdout` lines inherit the preceding entry's `SortKey`.

---

## ADR-008: Three-Tier Observability & In-TUI `PROCOMP` View
- **Status:** Accepted
- **Context:** Debugging real-world failures (UTF-8/symlink `appsFile`, silent async build exit codes, slow port/Git steps) required full visibility without leaving the terminal.
- **Decision:** Maintain `logs/procomp-error.log` (crashes + `AppsFile`), `logs/procomp-debug.log` (all external commands), and default-on `logs/procomp-trace.log` (full lifecycle, step progress, env key names without values, and mirrored subprocess `stdout`/`stderr`; opt-out via `-NoTrace` / `PROCOMP_TRACE=0`). Surface `procomp-trace.log` directly inside the TUI as peer main view **`4` / `L` (`PROCOMP`)** with `v` cycling **`INFO` / `DEBUG` / `TRACE`**, alongside **`1` Services**, **`2` Logs**, and **`3` WireMock**.

---

## ADR-009: Explicit Path, Encoding, and Executable Resolution
- **Status:** Accepted
- **Context:** PS 5.1 `Get-Content` defaults to ANSI on non-BOM UTF-8 files (mangling Swedish characters), `ConvertFrom-Json` unrolls 1-element arrays and omits the filename on error, and `Start-Process` with redirected streams does not search `-WorkingDirectory` for relative `.bat` files like `gradlew.bat`.
- **Decision:** Centralize JSON loading in `Read-ProcompJsonFile` (UTF-8/BOM-safe `[System.IO.File]::ReadAllText`, unary-comma array preservation, full path + 120-char preview on error). Support explicit `workingDir` on `gateway`, `runtimes`, `defaults`, and `apps`, and resolve relative executables against `WorkingDirectory` before `Start-Process`.

---

## ADR-010: Real-JVM, Real-WireMock, and 46-Service Scale Verification
- **Status:** Accepted
- **Context:** Unit mocks missed PS 5.1 `Start-Process` exit-code bugs, real WireMock `/import` behavior, and 46-service startup contention.
- **Decision:** Keep the test suite compatible with both Pester 3.4 (local Windows PS 5.1) and Pester 5.9 (GitHub Actions) via `tests/TestHelpers.ps1`, and run integration tests against real JVMs (`mock-service.jar`), a real **WireMock 3.10.0 JVM** (cached in `.tools/`), and a **46-service + 3s simulated Git latency** scale test (`PROCOMP_MOCK_GIT_DELAY_MS=3000`, also enabled in `demo.cmd`).
