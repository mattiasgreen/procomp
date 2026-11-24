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
 │    └── Git Runs[] (tracked mutating Git executions: CLONE, FETCH, PULL, SWITCH, STASH)
 ├── Services[]
 │    ├── Config (identity, optional Repository, optional Builder, runtime, localport, proxyPath, remoteUrl)
 │    ├── Route (LOCAL | REMOTE — stored in Routing Table; defaults to REMOTE)
 │    ├── Process? (0..1 active local Process: Managed | Unmanaged | Conflict)
 │    └── Build Runs[] (tracked build executions)
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

`Discovery` determines what is running for a `Service` (or `Gateway`), who owns it, its `Health State`, and—when a `Repository` is configured—its local, zero-network Git state (branch, dirty/untracked count, ahead/behind counts, local fetch age from `.git/FETCH_HEAD` or `.git/HEAD`, and latest `Git Run`). It never mutates state and never makes network calls.

1. Check if `procomp` has a recorded `Managed` PID for the `Service`:
   - Alive → **`Managed`** (evaluate health; do **not** assume `STARTING`).
   - Exited unexpectedly → **`Managed`** with `Health State = CRASHED`.
2. Otherwise, check if a process is listening on `localport`:
   - Passes the `Service`'s health check → **`Unmanaged`** (`HEALTHY`, optionally tagged `IDE` if an ancestor is a known IDE).
   - Health check not yet passing, **and** an ancestor is a known IDE (still booting in debugger) → **`Unmanaged`** (`STARTING`).
   - Otherwise → **`Conflict`**.
3. Otherwise → **`None`** (`STOPPED`).

### Health State (4 Pure, Stateless Values)

| State | Meaning |
| :--- | :--- |
| `STOPPED` | No active local `Process`. |
| `STARTING` | `Process` is alive, but its health check is not currently passing. |
| `HEALTHY` | `Process` is alive and passing its health check. |
| `CRASHED` | A `Managed` `Process` exited unexpectedly. |

Uptime is displayed alongside `Health State` so a human can tell whether a `STARTING` process just booted or has been running and is currently failing health—without introducing a 5th state or mutable health timestamps.

### Health Check Evaluation

- **`healthPath` configured:** `Process` alive **and** `GET http://127.0.0.1:<localport><healthPath>` returns HTTP `2xx` (framework-agnostic; no response body parsing).
- **`healthPath` empty, `localport` configured:** `Process` alive **and** TCP port `<localport>` is listening.
- **Neither configured:** `Process` alive.

### Inspection (Deep Read-Only Diagnostics)

`Inspection` (`procomp inspect <service>`) examines a `Process` in detail: PID, ownership, full command line, arguments, parent process chain (with parent command lines), RSS, health endpoint status/response, log paths, context-aware copy-pasteable diagnostic commands (e.g., `jcmd <pid> ...` for live JVMs), and whether a `Managed` `Process` is `Stale` (started before the latest `Artifact`).

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
  - Latest `Git Run` records (`git/<repository>.json`: operation `CLONE`/`FETCH`/`PULL`/`SWITCH`/`STASH`, PID, status `RUNNING`/`SUCCEEDED`/`FAILED`, timestamps, exit code, reason, Git log paths)
  - Raw `Process` log files: `<service>.out.log` / `<service>.err.log`, `<service>.build.out.log` / `<service>.build.err.log`, and `<repository>.git.out.log` / `<repository>.git.err.log`, untouched on disk

---

## 5. v1 CLI Contract

All CLI flags use standard `--kebab-case`. Global flags (`--config <path>`, `-v`/`--verbose`, `-h`/`--help`) are accepted anywhere on the command line; `-v`/`--verbose` emits elapsed-time diagnostics (`[+0.124s] [<category>] <message>`) strictly to `stderr` without polluting `stdout` or printing `env` secret values. All read commands (`status`, `route`, `profile list`, `gateway status`, `inspect`) support `--json` for scripting/piping.

### Process & Build Lifecycle

| Command | Behavior |
| :--- | :--- |
| `procomp build [service...]` | Runs tracked in-place `Build Run`(s) sequentially (fail-fast) using each target's configured `Builder`. Bare `build` targets all `LOCAL`-routed Services with a `Builder`. Rejects overlapping `RUNNING` or `UNKNOWN` (dead-PID) runs unless `--force` is passed; supports `--cancel <service...>`, `--timeout <seconds>`, and `--json`. Never mutates the `Routing Table` or starts/restarts Services. |
| `procomp start [service...]` | Runs pre-start `Discovery` per target (`None`/`CRASHED` → spawn `Managed`; `Managed`/`Unmanaged` → reuse without duplicating; `Conflict` → fail fast with `Inspection` details). Bare `start` targets all `LOCAL`-routed Services. Waits for `HEALTHY` by default (`-d`/`--detach` to return immediately). Never auto-builds or mutates the `Routing Table`. |
| `procomp stop [service...]` | Stops `Managed` Service `Process`(es) cleanly. Bare `stop` stops all `Managed` Services while leaving the `Gateway` running (`--all` also stops a `Managed` `Gateway`). Never kills `Unmanaged` Processes. Killing a `Conflict` requires explicit consent. |
| `procomp status` | Prints a concise table (`SERVICE`, `ROUTE`, `OWNER`, `HEALTH`, `BUILD`, `GIT`, `PORT`, `PID`, `UPTIME`, `RSS`, `STALE`) plus a one-line `Gateway` summary and a `Total RSS` summary when any Process is running. `BUILD` spells out explicit states (`RUNNING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`, `ARTIFACT_PRESENT`, `NOT_BUILT`, `NO_BUILDER`). `GIT` shows compact zero-network `Repository` state (branch, `*` dirty, `↑`/`↓`, fetch-age warning, `DETACHED@sha`, `NOT_CLONED`, `NO_REPO`, or active `Git Run`). |
| `procomp inspect <service>` | Deep read-only `Inspection` of a Service's local `Process` (including `RSS` and context-aware diagnostic commands), latest `Build Run`, and `Repository` / latest `Git Run`. |
| `procomp logs <service>` | Minimal raw log reader for `<service>.out.log` (or `<service>.err.log` with `--err`, build logs with `--build`, or Git logs with `--git`). Supports `-n <lines>`, `-f` (follow), `--err`, `--build`, `--git`, and `--path`. Zero JSON parsing, merging, or filtering in v1. |

### Git (`Repository` Lifecycle — Non-Authoring)

All `procomp git` commands accept `Service` or `Repository` names (canonical `Repository` name = local folder basename), deduplicated to unique `Repository`s. Mutating commands default to `LOCAL`-routed `Service`s' `Repository`s (`--all` for all), track execution as `Git Run`s with PIDs/logs/cancellation (`--force` to override `UNKNOWN`, `--timeout <seconds>`), run strictly non-interactively (`GIT_TERMINAL_PROMPT=0`), continue on per-`Repository` error with deterministic output order (non-zero exit if any failed), and never mutate the `Routing Table`, `Process`es, or `Build Run`s. Authoring/publishing (`commit`, `push`, `switch -c`, `stash pop`, `merge`, `rebase`) is deliberately out of scope (see `docs/adr/0006-non-authoring-git-orchestration.md`).

| Command | Behavior |
| :--- | :--- |
| `procomp git status [--local] [--fetch] [--all] [--json]` | Read-only per-`Repository` status table (`REPOSITORY`, `SERVICES`, `STATE`, `BRANCH`, `UPSTREAM`, `DIRTY`, `STASHES`, `FETCHED`, `LAST_RUN`) across all `Repository`s by default (or `--local`). Zero network calls unless `--fetch` is passed (which runs tracked `FETCH` `Git Run`s on `LOCAL` repos unless `--all`). |
| `procomp git branches <target> [--json]` | Lists deduplicated local and `origin/*` branches (excluding `origin/HEAD`) ordered by committer date descending with last commit datetime, ref origin (`local`, `remote`, `local+remote`), and short subject. |
| `procomp git clone [target...] [--all] [--force] [--timeout <sec>] [--json]` | Explicitly clones missing `Repository` checkouts (bounded parallelism). Already-cloned `Repository`s report `SKIPPED`; non-empty non-Git folders or missing `repositoryUrl` fail fast. |
| `procomp git fetch [target...] [--all] [--force] [--timeout <sec>] [--json]` | Runs `git fetch --prune` across targeted `Repository`s (bounded parallelism). Fails fast on `NOT_CLONED`. |
| `procomp git pull [target...] [--all] [--force] [--timeout <sec>] [--json]` | Runs `git pull --ff-only` across targeted `Repository`s (bounded parallelism). Fails fast if `NOT_CLONED` or if the working tree has any staged, unstaged, or untracked changes. |
| `procomp git switch <branch> [target...] [--all] [--force] [--timeout <sec>] [--json]` | Switches targeted `Repository`s sequentially to an existing local or `origin/<branch>` branch (or `--default` instead of `<branch>` for `origin/HEAD`). Strictly non-destructive: fails fast if `NOT_CLONED` or if any staged, unstaged, or untracked changes exist. Never creates branches or auto-pulls. |
| `procomp git stash [push] [target...] [-m <msg>] [-u\|--include-untracked] [--all] [--force] [--json]` | Parks uncommitted changes via `git stash push` sequentially (clean repos report `SKIPPED`). `procomp git stash list [target...] [--all] [--json]` lists stashes read-only. (`stash pop` is intentionally excluded.) |
| `procomp git cancel [target...] [--all] [--json]` | Cleanly terminates `RUNNING` `Git Run`(s). |
| `procomp git logs <target> [-n <lines>] [-f] [--err] [--path]` | Minimal raw log reader for `<repository>.git.out.log` (or `.err.log` with `--err`). |

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
| `procomp gateway status` | Shows `Gateway` ownership, `Health State`, and whether live mappings match the `Routing Table`. |
| `procomp gateway reconcile` | Converges the live `Gateway` mappings to match the Project's `Routing Table`. |
| `procomp gateway logs` | Minimal raw log reader for `gateway.out.log` (or `gateway.err.log` with `--err`). Supports `-n <lines>`, `-f` (follow), `--err`, and `--path`. |

### Web UI

| Command | Behavior |
| :--- | :--- |
| `procomp ui [--port <n>] [--open]` | Starts the zero-build localhost Web UI (default `127.0.0.1:9090`) backed by the same `shell/` runtime. Displays the estate table (including clickable `BUILD` and `GIT` badges), per-Service/Repository Build and Git controls (`Clone`, `Fetch`, `Pull`, `Switch` modal with `-committerdate` branch picker / `Fetch` / `--default` / inline dirty-tree warning & one-click `Stash (incl. untracked)`, `Stash`, `Cancel`), `Build Run` & `Repository`/`Git Run` details in the Inspect drawer, and `Service` / `Build` / `Git` source tabs in the live SSE Logs view, echoing verbatim `procomp` CLI commands on every action. |

---

## 6. Explicitly Deferred (Post-v1)

The following areas are intentionally deferred until v1 core functionality is solid:

- **Implementation language & runtime selection** (to be chosen next).
- **Scaling & performance optimizations:** Batch OS snapshots, parallel worker pools, WireMock batch-import tuning, etc.
- **Rich log viewing:** JSON normalization, `logMapping`, multi-service chronological merging, level filtering, stack-trace folding (to be designed in a dedicated session).
- **Phase 2 commands:** auto-build options (e.g., `procomp start --build`) and `procomp doctor` (preflight checks).
- **Rich `inspect` diagnostics:** Runtime-specific diagnostics (e.g., JVM JFR, Prometheus metrics).
- **Gateway auto-reconciliation:** Having the `Gateway` watch/poll the `Routing Table` for changes rather than coupling `route` to `gateway`.
- **Supervisor / interactive mode:** A `tmux`-style per-project supervisor and optional TUI layered on top of the CLI.
