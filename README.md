# `procomp` (Process Composer)

> **Run and debug a slice of a large microservice estate locally — combining IDE-aware process orchestration and hybrid local/remote WireMock routing from one lightweight, portable CLI.**

---

## The Problem

Working on a **40+ microservice** system locally is painful:

- You can't run all 46 services on your laptop (too much RAM, too slow to start, constant port/env headaches).
- You usually only edit **1–2 services** (often inside IntelliJ/VS Code), need **a few others** running locally as plain processes, and want **the rest** routed seamlessly to a shared dev environment.
- Today, developers glue this together manually with fragile terminal tabs, ad-hoc WireMock configs, scattered `git pull` / `gradlew` commands, and unreadable JSON logs.

---

## The Solution

From one declarative config (`procomp.json` + your existing `service-index.json`), `procomp` gives you:

- **Run only what you need, route the rest:** Start a handful of services locally as lightweight processes, automatically recognize the ones you're debugging in your IDE (never colliding or killing them), and transparently proxy all remaining services upstream through a local gateway (WireMock) — switching any service between `LOCAL` and `REMOTE` effortlessly.
- **Zero lock-in, zero heavy infra:** No Docker-in-Docker, no Kubernetes/Tilt required, no hardcoded framework assumptions — just plain OS processes, Git repos, and a single portable config.

---

## Approach: CLI-First

We are deliberately building **`procomp` as a CLI first** to nail the core actions, lifecycle semantics, and ergonomics before layering any interactive TUI on top.

### Core CLI Actions (Target Scope)

| Command | Purpose |
| :--- | :--- |
| `procomp up [service...]` | Start selected (or profile-enabled) `LOCAL` services, reuse IDE-owned services and any already-running WireMock, and configure WireMock routes for all `LOCAL` and `REMOTE` services. |
| `procomp down [service...]` | Stop `procomp`-managed services cleanly (never killing IDE-owned processes or external WireMock). |
| `procomp status` | Show a concise snapshot of every service: `STATE` (`HEALTHY`, `STARTING`, `IDE-UP`, `REMOTE`, `STOPPED`, `CRASHED`, `PORT-CONFLICT`), `ROUTING`, `PID`, `PORT`, and `RAM`. |
| `procomp route <service> <local\|remote>` | Flip a service's routing mode, persist it per project, and update WireMock immediately. |
| `procomp logs [service...]` | Tail and normalize service logs (JSON + plain text) from the terminal. |
| `procomp build <service>` | Build a service's runnable artifact using its configured `builder`. |
| `procomp sync` | Safely update clean Git repositories (`--ff-only`) without touching dirty working trees. |
| `procomp doctor` | Preflight check for runtimes, Git, `.env`, ports/IDE conflicts, repos, and WireMock reachability. |

---

## Configuration Schemas

`procomp` keeps all project and service configuration declarative and completely decoupled from the implementation language:

- **[docs/CONFIG_SCHEMA.md](docs/CONFIG_SCHEMA.md)** — Full schema reference for `procomp.json` and `appsFile` (`service-index.json`), including `defaults`, `builders`, `runtimes`, `gateway`, `profiles`, `workingDir`, and templated `env`.
- **[examples/using-service-index.procomp.json](examples/using-service-index.procomp.json)** — Example `procomp.json` pointing to an external `service-index.json`.
- **[examples/service-index.json](examples/service-index.json)** — Example external service catalog schema.
