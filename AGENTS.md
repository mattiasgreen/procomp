# AGENTS.md

Architectural and engineering rules for `procomp`. Keep this file short and authoritative.

## Reference Pointers

- **Domain vocabulary:** Read `GLOSSARY.md` before naming types, modules, flags, or output fields; use its canonical terms and avoid listed synonyms.
- **v1 behavior & CLI contract:** Read `docs/DESIGN.md` before designing or implementing any command, Discovery flow, Health State rule, Gateway Reconciliation, or state layout.
- **Config format:** Read `docs/CONFIG_SCHEMA.md` when touching `procomp.json` or `service-index.json` loading; validate strictly and fail fast on unrecognized fields.

## Runtime & Tooling

- **Pure Zero-Dependency Java 25 LTS (JEP 458):** Use the JDK 25 standard library only (`record`, `sealed interface`, `ProcessHandle`, `ProcessBuilder`, `java.net.http.HttpClient`, `java.nio.file`). Keep zero Maven, zero Gradle, zero `npm`, zero third-party JARs, and zero committed binaries.
- **Direct source launch:** Run the CLI via `java src/procomp/cli/Procomp.java [args...]` (wrapped by thin `./procomp` and `procomp.cmd` shims) and run tests via `java test/TestRunner.java`. See `docs/adr/0001-pure-java-25-jep458.md` for the rationale.

## Architecture: Functional Core, Imperative Shell (FCIS + DDD)

Organize code into four strict layers with dependencies flowing strictly inward (`cli`/`tui` → `shell` → `core`; `platform` → `shell/ports` + `core`):

- **`core/` (Functional Core):** Immutable DDD domain types and pure, total functions grouped by domain (`project`, `routing`, `process`, `gateway`). Keep `core/` 100% pure — zero I/O, zero OS/FS/HTTP/clock calls, zero dependencies outside `core/`.
- **`shell/` (Imperative Shell):** Modality-agnostic use-case orchestrators and narrow I/O ports (`shell/ports/`). Keep conditionals and domain decisions in `core/`; `shell/` gathers inputs via ports, calls `core/`, and executes returned actions.
- **`platform/` (I/O Adapters):** Cross-platform (Windows, macOS, Linux) implementations of `shell/ports/` for OS processes, TCP ports, HTTP, state-dir filesystem, and clock.
- **`cli/` (and future `tui/`):** Thin modality frontends that parse input, call `shell/`, and render output or exit codes.

## Structural Rules

- **Decoupled `core/` domains:** Keep `routing` and `process` completely independent of each other and of `gateway`. `gateway` may reference `project` and `routing` domain types only; compose across domains in `shell/`.
- **Narrow role ports:** Define small, role-specific port interfaces (`ProcessRunner`, `PortScanner`, `HttpClient`, `StateStore`, `Clock`) and inject only the ports each use case needs (e.g., `route` takes `StateStore` only).
- **Modality isolation:** Keep `argv`, `stdout`/`stderr`, ANSI formatting, and exit codes strictly inside `cli/` (or `tui/`). Return typed domain results from `shell/`; emit progress and streaming events through a modality-supplied typed observer/stream.
- **Explicit errors:** Return typed Result / Domain Error values from `core/` and `shell/` for all expected domain and I/O failures; reserve exceptions/panics for programmer bugs.
- **UNIX separation:** Keep every command focused on one responsibility—starting/stopping a `Process` never mutates the `Routing Table`, and editing the `Routing Table` never starts/stops a `Process` or touches the `Gateway`.

## Testing Pyramid

1. **Level 1 (`core/`):** Fast, deterministic unit tests of pure functions—data in, data out, zero mocks or fakes, zero I/O.
2. **Level 2 (`shell/` + `core/`):** Full use-case orchestration E2E tests wired to reusable in-memory fakes implementing `shell/ports/`—deterministic, zero real OS I/O, no spy/mock frameworks.
3. **Level 3 (`platform/` + smoke E2E):** Focused integration tests against real OS processes, real TCP ports, temporary state directories, and real HTTP endpoints (tested first-class on Windows as well as POSIX).

## Agent skills

### Issue tracker

Local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five canonical labels (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context (`GLOSSARY.md` and `docs/adr/` at the repo root). See `docs/agents/domain.md`.
