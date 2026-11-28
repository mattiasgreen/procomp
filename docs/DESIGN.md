# `procomp` v1 Design (Language-Agnostic)

This document captures the settled, language-agnostic design for the **`procomp`** reboot. Canonical domain terms are defined in [GLOSSARY.md](../GLOSSARY.md).

---

## 1. Design Principles

1. **UNIX philosophy — zero automagic:** Each command does one job well and composes cleanly (`procomp start svc && procomp route svc local && procomp gateway reconcile`). Starting/stopping a `Process` never mutates the `Routing Table`; editing the `Routing Table` never starts/stops a `Process` or implicitly pokes the `Gateway`.
2. **Clean separation of concerns:** `Service` (logical workload & desired `Route`), `Gateway` (local traffic router), and `Process` (OS process instance) are distinct domain entities.
3. **Declarative, fail-fast routing:** The `Routing Table` is the source of truth. If a `LOCAL` target is down, the `Gateway` fails fast rather than silently falling back to `REMOTE`.
4. **Never kill what you don't own:** `procomp` only terminates `Managed` Processes. `Unmanaged` Processes (IDE or manual shell) are reused and never killed. `Conflict` Processes are only ever killed with explicit user consent.
5. **Strict, fail-fast configuration:** Unrecognized or invalid config fields fail fast rather than being silently ignored.
6. **Cross-platform from day one:** Windows, macOS, and Linux, with OS-specific process/port operations isolated behind a thin platform seam and Windows tested as a first-class target.

---

## 2. Core Domain Model

See [GLOSSARY.md](../GLOSSARY.md) for exact definitions.

```text
Project
 ├── Repositories[] (optional Git checkouts; 1 Repository → 1..N Services)
 ├── Services[]
 │    ├── Config (identity, optional Repository, runtime, localport, proxyPath, remoteUrl)
 │    ├── Route (LOCAL | REMOTE — stored in Routing Table; defaults to REMOTE)
 │    ├── Process? (0..1 active local Process: Managed | Unmanaged | Conflict)
 │    └── Build Runs[] (tracked build executions — Phase 2)
 ├── Profiles[] (named Route presets)
 └── Gateway (local reverse-proxy router, optionally backed by its own Process)
```

### Key Modeling Decisions

- **No `enabled` flag:** A `Service`'s desired routing intent is strictly `LOCAL` or `REMOTE` (defaulting to `REMOTE` on a fresh `Project` so bare `start` never accidentally launches an entire estate).
- **Two orthogonal axes per `Service`:**
  1. **Route** (`LOCAL` | `REMOTE`) — desired traffic destination.
  2. **Local Ownership & Health** — what is actually observed on the machine (`None` | `Managed` | `Unmanaged` | `Conflict` paired with `Health State`).
- **One `proxyPath` prefix per `Service`:** Maps `/<prefix>` and `/<prefix>/**` to `http://127.0.0.1:<localport>` (`LOCAL`) or `remoteUrl` (`REMOTE`).

---

## 3. Discovery, Ownership, Health, and Inspection

### Discovery (Read-Only Observation)

`Discovery` determines what is running for a `Service` (or `Gateway`), who owns it, and its `Health State`. It never mutates state.

1. Check if `procomp` has a recorded `Managed` PID for the `Service`:
   - Alive → **`Managed`** (evaluate health; do **not** assume `STARTING`).
   - Exited unexpectedly → **`Managed`** with `Health State = CRASHED`.
2. Otherwise, check if a process is listening on `localport`:
   - Passes the `Service`'s health check → **`Unmanaged`** (`HEALTHY`, optionally tagged `IDE` if an ancestor is a known IDE).
   - Health check not yet passing, **and** an ancestor is a known IDE (still booting in debugger) → **`Unmanaged`** (`STARTING`).
   - Otherwise → **`Conflict`**.
3. Otherwise → **`None`** (`STOPPED`).

### Health State (5 Pure Values)

| State | Meaning |
| :--- | :--- |
| `STOPPED` | No active local `Process`. |
| `STARTING` | `Process` is alive, but its health check is not currently passing. |
| `HEALTHY` | `Process` is alive and passing its health check. |
| `STOPPING` | A `Managed` `Process` whose graceful/forced termination has been initiated (bounded by the termination period). |
| `CRASHED` | A `Managed` `Process` exited unexpectedly. |

Uptime is displayed alongside `Health State` so a human can tell whether a `STARTING` process just booted or has been running and is currently failing health. `STOPPING` is persisted in the `Managed` `Process` record (`stopRequestedAt`) for the duration of the termination window so CLI and Web UI agree without client-side state.

### Health Check Evaluation

- **`healthPath` configured:** `Process` alive **and** `GET http://127.0.0.1:<localport><healthPath>` returns HTTP `2xx` (framework-agnostic; no response body parsing).
- **`healthPath` empty, `localport` configured:** `Process` alive **and** TCP port `<localport>` is listening.
- **Neither configured:** `Process` alive.

### Inspection (Deep Read-Only Diagnostics)

`Inspection` (`procomp inspect <service>`) examines a `Process` in detail: PID, ownership, full command line, arguments, parent process chain (with parent command lines), RAM, health endpoint status/response, log paths, and whether a `Managed` `Process` is `Stale` (started before the latest `Artifact`).

---

## 4. Process Execution & Local State

- **v1 Execution Model:** Detached OS processes + persisted metadata files, hidden behind a clean **Process Runner** seam so a `tmux`-style per-project supervisor can be introduced later without changing the domain model or CLI contract.
- **State & Log Location:** OS-standard user state directory (never polluting Git repositories containing `procomp.json`), overridable via `PROCOMP_STATE_DIR`:
  - Linux / macOS: `~/.local/state/procomp/<project>/`
  - Windows: `%LOCALAPPDATA%\procomp\<project>\`
- **Persisted per `Project`:**
  - `Routing Table` (desired `LOCAL` / `REMOTE` per `Service`)
  - `Managed` `Process` records (PID, start time, command, artifact timestamp, log paths)
  - Latest `Build Run` records (`builds/<service>.json`: PID, status `RUNNING`/`SUCCEEDED`/`FAILED`, timestamps, exit code, reason, build log paths)
  - Raw `Process` log files: `<service>.out.log` / `<service>.err.log` and `<service>.build.out.log` / `<service>.build.err.log`, untouched on disk

---

## 5. v1 CLI Contract

All CLI flags use standard `--kebab-case`. Global flags (`--config <path>`, `-v`/`--verbose`, `-h`/`--help`) are accepted anywhere on the command line; `-v`/`--verbose` emits elapsed-time diagnostics (`[+0.124s] [<category>] <message>`) strictly to `stderr` without polluting `stdout` or printing `env` secret values. All read commands (`status`, `route`, `profile list`, `gateway status`, `inspect`) support `--json` for scripting/piping.

### Process & Build Lifecycle

| Command | Behavior |
| :--- | :--- |
| `procomp build [service...]` | Runs tracked in-place `Build Run`(s) sequentially (fail-fast) using each target's configured `Builder`. Bare `build` targets all `LOCAL`-routed Services with a `Builder`. Rejects overlapping `RUNNING` or `UNKNOWN` (dead-PID) runs unless `--force` is passed; supports `--cancel <service...>`, `--timeout <seconds>`, and `--json`. Never mutates the `Routing Table` or starts/restarts Services. |
| `procomp start [service...]` | Runs pre-start `Discovery` per target (`None`/`CRASHED` → spawn `Managed`; `Managed`/`Unmanaged` → reuse without duplicating; `Conflict` → fail fast with `Inspection` details). Bare `start` targets all `LOCAL`-routed Services. Waits for `HEALTHY` by default (`-d`/`--detach` to return immediately). Never auto-builds or mutates the `Routing Table`. |
| `procomp stop [service...]` | Stops `Managed` Service `Process`(es) cleanly. Bare `stop` stops all `Managed` Services while leaving the `Gateway` running (`--all` also stops a `Managed` `Gateway`). Never kills `Unmanaged` Processes. Killing a `Conflict` requires explicit consent. |
| `procomp restart [service...]` | Restarts `Managed` Service `Process`(es) with preflight validation (conflicts, `STOPPING` state, and launch plans) *before* stopping any running `Process`. Bare `restart` targets currently running `Managed` Services only; explicit targets start `STOPPED`/`CRASHED` Services, spare `Unmanaged` Services, and fail fast on `Conflict` or `STOPPING`. Supports `-d`/`--detach` and `--timeout <seconds>`. Never mutates the `Routing Table` or `Gateway`. |
| `procomp status` | Prints a concise table (`SERVICE`, `ROUTE`, `OWNER`, `HEALTH`, `BUILD`, `PORT`, `PID`, `UPTIME`, `STALE`) plus a one-line `Gateway` summary. `BUILD` spells out explicit states (`RUNNING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`, `ARTIFACT_PRESENT`, `NOT_BUILT`, `NO_BUILDER`). |
| `procomp inspect <service>` | Deep read-only `Inspection` of a Service's local `Process` and latest `Build Run`. |
| `procomp logs <service>` | Minimal raw log reader for `<service>.out.log` (or `<service>.err.log` with `--err`, or build logs with `--build`). Supports `-n <lines>`, `-f` (follow), `--err`, `--build`, and `--path`. Zero JSON parsing, merging, or filtering in v1. |

### Routing Table & Profiles

| Command | Behavior |
| :--- | :--- |
| `procomp route` | Display the current `Routing Table`. |
| `procomp route <service...> <local\|remote>` | Update desired `Route`(s) in the `Routing Table`. Does **not** start/stop Processes or touch the `Gateway`. |
| `procomp profile list` | List configured `Profile`s. |
| `procomp profile use <name>` | Apply a `Profile` to the `Routing Table`. Does **not** start/stop Processes or touch the `Gateway`. |

### Gateway

| Command | Behavior |
| :--- | :--- |
| `procomp gateway start` | Runs `Discovery` on the `Gateway` (`Unmanaged` → reuse; `None`/`CRASHED` → spawn `Managed`; `Conflict` → fail fast). |
| `procomp gateway stop` | Stops a `Managed` `Gateway` `Process` (never kills an `Unmanaged` `Gateway`). |
| `procomp gateway restart` | Restarts a `Managed` `Gateway` `Process` (or starts when `STOPPED`/`CRASHED`; spares `Unmanaged`; fails fast on `Conflict` or `STOPPING`). Supports `-d`/`--detach` and `--timeout <seconds>`. |
| `procomp gateway status` | Shows `Gateway` ownership, `Health State`, and whether live mappings match the `Routing Table`. |
| `procomp gateway reconcile` | Converges the live `Gateway` mappings to match the Project's `Routing Table`. |
| `procomp gateway logs` | Minimal raw log reader for `gateway.out.log` (or `gateway.err.log` with `--err`). Supports `-n <lines>`, `-f` (follow), `--err`, and `--path`. |

---

## 6. Explicitly Deferred (Post-v1)

The following areas are intentionally deferred until v1 core functionality is solid:

- **Implementation language & runtime selection** (to be chosen next).
- **Scaling & performance optimizations:** Batch OS snapshots, parallel worker pools, WireMock batch-import tuning, etc.
- **Rich log viewing:** JSON normalization, `logMapping`, multi-service chronological merging, level filtering, stack-trace folding (to be designed in a dedicated session).
- **Phase 2 commands:** `procomp build` (tracked `Build Run`s + auto-build options), `procomp sync` (safe Git updates and explicit-demand `Repository` cloning — never auto-cloning), and `procomp doctor` (preflight checks).
- **Rich `inspect` diagnostics:** Runtime-specific diagnostics (e.g., JVM JFR, Prometheus metrics).
- **Gateway auto-reconciliation:** Having the `Gateway` watch/poll the `Routing Table` for changes rather than coupling `route` to `gateway`.
- **Supervisor / interactive mode:** A `tmux`-style per-project supervisor and optional TUI layered on top of the CLI.
