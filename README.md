# `procomp` (Process Composer)

> **Run and debug a slice of a large microservice estate locally — combining IDE-aware process orchestration and hybrid local/remote routing from one lightweight, UNIX-style CLI.**

---

## The Problem

Working on a **40+ microservice** system locally is painful:

- You can't run all 46 services on your laptop (too much RAM, too slow to start, constant port/env headaches).
- You usually only edit **1–2 services** (often inside IntelliJ/VS Code), need **a few others** running locally as plain processes, and want **the rest** routed seamlessly to a shared dev environment.
- Today, developers glue this together manually with fragile terminal tabs, ad-hoc WireMock configs, scattered `git pull` / `gradlew` commands, and scattered logs.

---

## The Solution

From one declarative config (`procomp.json` + your existing `service-index.json`), `procomp` gives you:

- **A hybrid local service network:** Run a handful of `Service`s locally as lightweight `Process`es, automatically recognize `Unmanaged` ones you're debugging in your IDE or shell (never colliding or killing them), and route all remaining `Service`s upstream through a local `Gateway` (WireMock) — switching any `Service` between `LOCAL` and `REMOTE` declaratively.
- **UNIX-style composability, zero automagic:** Process lifecycle (`start`/`stop`), the `Routing Table` (`route`/`profile`), and the `Gateway` (`gateway`) are cleanly separated so every command does one job well and composes predictably (`procomp start svc && procomp route svc local && procomp gateway reconcile`).
- **Zero lock-in, zero heavy infra:** No Docker-in-Docker, no Kubernetes/Tilt required, no hardcoded framework assumptions — just plain OS processes, Git repos, and a single portable config.

---

## Approach: CLI-First

We are deliberately building **`procomp` as a focused CLI first** to nail the domain model, lifecycle semantics, and ergonomics before layering any supervisor or interactive UI on top.

### Core CLI Actions (v1 Scope)

| Command | Purpose |
| :--- | :--- |
| `procomp start [service...]` | Run pre-start `Discovery` (reusing `Managed`/`Unmanaged` processes, failing fast on `Conflict`), start `Managed` local processes, and wait for `HEALTHY` (`-d` to detach). Bare `start` starts all `LOCAL`-routed services. |
| `procomp stop [service...]` | Stop `Managed` service processes cleanly (never killing `Unmanaged` processes; leaves `Gateway` running unless `--all`). |
| `procomp status` | Show a concise snapshot (`SERVICE`, `ROUTE`, `OWNER`, `HEALTH`, `PORT`, `PID`, `UPTIME`, `STALE`) plus `Gateway` summary (`--json` supported). |
| `procomp inspect <service>` | Deep read-only `Inspection` of a service's local process (PID, command line, parent chain, RAM, health, log paths). |
| `procomp route [service... <local\|remote>]` | Show or update the Project's declarative `Routing Table` (never starts/stops processes or touches the `Gateway`). |
| `procomp profile <list\|use <name>>` | List or apply named routing `Profile`s to the `Routing Table`. |
| `procomp gateway <start\|stop\|status\|reconcile>` | Manage the local `Gateway` process and explicitly reconcile live proxy mappings to the `Routing Table`. |
| `procomp logs <service>` | Minimal raw log reader (`-n`, `-f`, `--err`, `--path`) for `<service>.out.log` / `<service>.err.log`. |
| `procomp ui [--port <n>] [--open]` | Start the zero-build localhost Web UI (default `127.0.0.1:9090`) backed by the same `shell/` runtime. |

*(Phase 2 will add `procomp build`, `procomp sync`, and `procomp doctor`.)*

---

## Quick Start & Verification

Requires **JDK 25 LTS** (zero external runtime dependencies via JEP 458):

```sh
./procomp --help          # POSIX (macOS / Linux / Git Bash / WSL)
procomp.cmd --help        # Windows
gradle fastCheck          # Fast inner-loop verification (Spotless, Error Prone + NullAway, Levels 1-2)
gradle check              # Full Gradle verification (Spotless, Error Prone + NullAway, Levels 1-3, JEP 458, Scenarios, Demos)
```

---

## Documentation

- **[GLOSSARY.md](GLOSSARY.md)** — Canonical domain vocabulary (`Project`, `Service`, `Gateway`, `Process`, `Route`, `Discovery`, `Inspection`, etc.).
- **[docs/DESIGN.md](docs/DESIGN.md)** — Settled language-agnostic v1 design, CLI contract, and explicitly deferred post-v1 areas.
- **[docs/CONFIG_SCHEMA.md](docs/CONFIG_SCHEMA.md)** — Configuration schema reference for `procomp.json` and external `Catalog` files (e.g. `service-index.json`).
- **[examples/using-service-index.procomp.json](examples/using-service-index.procomp.json)** — Example `procomp.json` pointing to an external `Catalog` (`service-index.json`).
- **[examples/service-index.json](examples/service-index.json)** — Example external service `Catalog` schema.
