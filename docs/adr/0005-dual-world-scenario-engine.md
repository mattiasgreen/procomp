---
status: accepted
---

# Dual-World Scenario Engine for E2E Testing, Manual Exploration, and Demo Capture

`procomp` models end-to-end user workflows as reusable **Scenarios** composed of named **Steps** that execute against a pluggable `ScenarioWorld` seam with two implementations: an in-process **`InMemoryWorld`** (wiring the shared `ProcompRuntime` from [ADR 0004](0004-vanilla-es6-web-ui-modality.md) to faithful, stateful in-memory port fakes with configurable lag) and an out-of-process **`RealTopologyWorld`** (invoking the real CLI against Spring Boot, embedded ActiveMQ Artemis, WireMock standalone, and local bare Git repositories). The same Scenario catalog powers automated E2E tests, interactive DevEx exploration (an in-process REPL for `InMemoryWorld`, plus real-shell pause with a local wrapper shim for `RealTopologyWorld`), and deterministic Markdown/ANSI demo snapshot generation (`docs/demos/`).

## Considered Options

- **Separate E2E tests, manual setup scripts, and hand-maintained doc transcripts:** Simple initially, but setup scripts and documentation inevitably drift from actual CLI behavior and duplicate topology configuration across three places.
- **Out-of-process lightweight mock binaries for fast mode:** Spawns real OS processes for mock services and WireMock, keeping external shell usage uniform, but adds ~2–5s startup overhead per switch, requires external process-tree lifecycle management, and duplicates state that already sits behind FCIS ports.
- **Direct state injection into fakes to jump to arbitrary steps:** Skips earlier steps in `InMemoryWorld`, but cannot run portably in `RealTopologyWorld` and risks constructing unreachable states that real `procomp` commands would never produce.
- **Dual-World Scenario Engine with fast-forward Step replay over `ProcompRuntime` (chosen):** Reuses the exact `cli/` → `shell/` → `core/` stack (and upcoming `procomp.web` UI) in-process for sub-50ms scenario switching, deterministic clocks, and lag/scaling simulation, while running the identical Scenarios against real OS processes and networks in `RealTopologyWorld`.

## Consequences

- **One Scenario definition, three modes:** Every Scenario (`hybrid-routing`, `ownership-and-conflicts`, `failures-logs-and-stale`, `repo-sync`, plus `InMemoryWorld`-only `large-estate` scaling scenarios) is defined once and drives automated assertions, interactive exploration, and Markdown/ANSI demo capture with freshness checks.
- **Valid state transitions by construction:** Jumping to any Step fast-forwards steps `1..N` through `Procomp` (with simulated lag disabled during replay, then re-enabled when paused); direct fixture hooks are reserved strictly for external actors outside `procomp` (`Unmanaged` IDE processes, `Conflict` port listeners, external `CRASHED` kills, rebuilt `Stale` artifacts, and local Git repository states).
- **Shared modality seam & zero leakage into `src/`:** `InMemoryWorld` targets the modality-neutral `ProcompRuntime` in `procomp.shell`, so the same in-memory Scenarios and lag profiles can drive the CLI, REPL, demo snapshots, and `procomp ui` without adding any test/mock code or dependencies to `src/` (preserving [ADR 0001](0001-pure-java-25-jep458.md)).
- **Module separation & vocabulary hygiene:** The Scenario Engine, faithful `InMemoryWorld` fakes, Scenario catalog, REPL, and Demo Capture live in `e2e/scenarios` (fast, zero Spring/WireMock deps), while `e2e/runner` hosts `RealTopologyWorld`; `test/procomp/fakes/` stays lean for Level 2 shell unit tests, and test-harness terms (`Scenario`, `Step`) stay outside product `GLOSSARY.md` (never using `preset`, which conflicts with `Profile` and `Runtime`).
