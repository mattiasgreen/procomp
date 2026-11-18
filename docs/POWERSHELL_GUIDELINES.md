# PowerShell Best Practices & Agentic Guidelines (`procomp`)

This document defines the coding standards, module layout, testing strategy, and Windows PowerShell 5.1+ / PowerShell 7+ compatibility rules for `procomp`.

---

## 1. Module & Directory Layout

Keep the entrypoint thin and split domain logic into focused, testable scripts under `src/`:

```text
procomp/
├── procomp.cmd              # ExecutionPolicy Bypass wrapper
├── procomp.ps1              # CLI arg parsing, entrypoint, TUI event loop
├── src/
│   ├── Config.ps1           # Load/validate services.json, type defaults, routing.json
│   ├── ProcessManager.ps1   # Start/Stop/PID files, batched Get-Process, taskkill
│   ├── HealthMonitor.ps1    # Non-blocking HttpClient.GetAsync() probes
│   ├── IdeDetector.ps1      # Get-NetTCPConnection + parent-PID IDE detection
│   ├── WireMockBridge.ps1   # /__admin/mappings sync & REMOTE <-> LOCAL toggling
│   ├── GitGradle.ps1        # Deduplicated git status/sync & gradlew bootJar
│   ├── LogViewer.ps1        # JSON + plain-text normalization, caching, merging
│   └── Tui.ps1              # Pure ANSI frame rendering (supports -DryRun)
├── tests/
│   └── *.Tests.ps1          # Pester unit & integration tests
└── docs/
```

### Separation of Pure Logic vs. Side Effects
* **Pure functions** (e.g., `Normalize-LogEntry`, `Parse-MaxHeap`, `Build-WireMockMapping`, `Render-Tui -DryRun`) take inputs and return objects/strings without touching disk, network, or console.
* **Side-effecting wrappers** (`Start-ServiceProcess`, `Stop-ServiceProcess`, `Get-ListeningPid`) stay small so they can be easily mocked in Pester tests.

---

## 2. PowerShell 5.1 + 7+ Compatibility Rules

All code must run cleanly in **both** Windows PowerShell 5.1 (`powershell.exe`) and PowerShell 7+ (`pwsh.exe`):

1. **Strict Mode Everywhere:**
   Start scripts/modules with:
   ```powershell
   Set-StrictMode -Version Latest
   $ErrorActionPreference = "Stop"
   ```
2. **Prevent Array Unrolling (`.Count` trap):**
   PowerShell unrolls 0-element arrays to `$null` and 1-element arrays to a scalar, which throws `PropertyNotFoundStrict` on `.Count` under StrictMode.
   * **Rule:** Wrap collection-returning calls in `@(...)` at call sites (`$entries = @(Get-UnifiedLogs ...)`), or use the unary comma operator (`return ,$list`) inside functions.
3. **Avoid PS 7-Only Syntax:**
   * Do **not** use `` `e `` (use `$ESC = [char]27`).
   * Do **not** use `??`, `?.`, `&&`, `||`, or ternary `?:` (use standard `if/else`).
   * Do **not** use `ConvertFrom-Json -AsHashtable` (check `$obj.PSObject.Properties["key"]` instead).
4. **Native Command `stderr` Under `Stop`:**
   With `$ErrorActionPreference = "Stop"`, native tools that write to `stderr` (e.g., `taskkill.exe` when a PID already exited) throw a terminating `NativeCommandError`.
   * **Rule:** Temporarily set `$ErrorActionPreference = "Continue"` inside a `try { ... } finally { $ErrorActionPreference = $prev }` block when calling native executables whose failure is non-fatal.
5. **No Shared Stdout/Stderr Redirect in `Start-Process`:**
   `Start-Process` throws if `-RedirectStandardOutput` and `-RedirectStandardError` point to the same path. Always use separate `<name>.log` and `<name>.err.log` files.
6. **Redirected Console Hosts:**
   `[Console]::WindowWidth`, `[Console]::WindowHeight`, and `[Console]::SetCursorPosition()` throw when stdout is redirected (e.g., in CI or tests). Guard them with `try/catch` and support `-DryRun` on `Render-Tui`.

---

## 3. Performance & TUI Responsiveness Rules

* **Never block the UI loop:** Use `[System.Net.Http.HttpClient]::GetAsync()` tasks and poll `.IsCompleted` instead of synchronous `Invoke-WebRequest` / `HttpWebRequest`.
* **Batch OS queries:** Call `Get-Process -Id $allPids` once per refresh tick (~750 ms), never per row per frame.
* **Drain keyboard input:** Use `while ([Console]::KeyAvailable)` before rendering so rapid `↑`/`↓` keypresses coalesce into one frame.
* **Cache file reads:** Key parsed log caches by `(FileInfo.Length, LastWriteTimeUtc.Ticks, RawMode)` so unchanged logs cost 0 ms when switching rows.

---

## 4. Test Pyramid & Linting

`procomp` follows a 3-layer test pyramid so fast feedback stays under a few seconds while real Windows/JVM behavior is still verified end-to-end:

```text
          /\
         /  \        E2E / Integration Tests (tests/integration/*.Tests.ps1)
        /----\       Real java.exe JVMs, real TCP ports, real WireMock & teardown
       /------\
      /--------\     Unit Tests (tests/unit/*.Tests.ps1)
     /----------\    Pester + Mock (state machines, log normalization, TUI DryRun)
    /------------\
   /--------------\  Static Analysis (PSScriptAnalyzer)
  /________________\ Compatibility, unused vars, strict-mode hygiene
```

1. **Static Analysis (`PSScriptAnalyzer`):**
   Run `Invoke-ScriptAnalyzer -Path . -Recurse` before committing to catch unused variables, unapproved verbs, or PS 5.1/7+ compatibility issues.
2. **Unit Tests (`tests/unit/*.Tests.ps1` via Pester):**
   Fast, deterministic tests with zero OS side effects. Use Pester `Mock` to stub `Get-Process`, `Start-Process`, `Get-NetTCPConnection`, and HTTP calls to verify:
   * Config parsing, type defaults, and `.stack_state/routing.json` persistence
   * `Normalize-LogEntry` & `Get-UnifiedLogs` (JSON UTC → local conversion, plain-text, `[ALL]` sort, `ErrorsOnly`, `RawMode`, 0-line edge cases)
   * `Render-Tui -DryRun` across all states (`HEALTHY`, `STARTING`, `STOPPING`, `CRASHED`, `IDE-UP`, `PORT-CONFLICT`, `ConfirmQuit`)
   * Git taint/branch detection and Gradle rebuild decision logic
3. **Integration Tests (`tests/integration/*.Tests.ps1` via Pester):**
   Real-world scenarios using real `java.exe` processes (`mock-service.jar` + WireMock) on ephemeral/test ports, always wrapped in `AfterEach`/`AfterAll` `taskkill` cleanup:
   * **Parallel Boot & Async Health:** Launch multiple real JVMs, verify `STARTING` → `HEALTHY` transition, `-Xmx` parsing, and live `WorkingSet64` RAM reporting.
   * **Crash & Port Conflict Detection:** Bind a port with a conflicting process and verify `CRASHED` / `PORT-CONFLICT` detection and `stderr` capture.
   * **Clean Teardown & Detach/Re-attach:** Verify `Stop-ServiceProcess` leaves 0 orphaned `java.exe` PIDs, and detach/re-attach recovers live PIDs from `.stack_state/*.pid`.
   * **IDE Parent-PID Detection:** Spawn a child `java.exe` from a simulated parent process and verify `IdeDetector` classifies it as `IDE-UP` and refuses to kill it on `down`.
   * **WireMock 3-Mode Routing:** Verify `REMOTE` ↔ `LOCAL` toggling updates `/__admin/mappings` and routes traffic accordingly.
