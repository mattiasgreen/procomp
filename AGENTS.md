# AGENTS.md

Architectural and engineering rules for `procomp`. Keep this file short and authoritative.

## Reference Pointers

- **Domain vocabulary:** Read `GLOSSARY.md` before naming types, modules, flags, or output fields; use its canonical terms and avoid listed synonyms.
- **v1 behavior & CLI contract:** Read `docs/DESIGN.md` before designing or implementing any command, Discovery flow, Health State rule, Gateway Reconciliation, or state layout.
- **Config format:** Read `docs/CONFIG_SCHEMA.md` when touching `procomp.json` or `Catalog` (e.g., `service-index.json`) loading; validate strictly and fail fast on unrecognized fields.

## Runtime & Tooling

- **Pure Zero-Dependency Java 25 LTS Runtime (JEP 458):** Keep `src/` 100% JDK 25 standard library only (`record`, `sealed interface`, `ProcessHandle`, `ProcessBuilder`, `java.net.http.HttpClient`, `java.nio.file`) with zero `implementation`/`compileOnly` dependencies and zero committed binaries. End users run the CLI directly from source via `java src/procomp/cli/Procomp.java [args...]` (wrapped by `./procomp` and `procomp.cmd`).
- **Gradle Dev & Verification Toolchain (Inner vs. Outer Loop):** Gradle caching, parallel builds, and configuration cache are enabled in `gradle.properties`. Always verify from fastest to slowest:
  - **Inner loop (during edits):** `gradle lint` (incremental compile, Error Prone + NullAway, Spotless) or `gradle fastCheck` (`lint` + parallel `unitTest` covering Levels 1–2, zero OS processes/ports).
  - **Pre-commit / ticket completion:** `gradle check` (adds sequential `integrationTest` Level 3 + `:verifyJep458` empty-classpath smoke check).
  - **Outer loop (before merging integration/process/gateway/UI changes):** `gradle outerLoop` (`check` + `:e2e:scenarios:check` + `:e2e:runner:e2eTest` + `:e2e:web-ui:uiE2eTest`; run `:e2e:web-ui:installBrowsers` once to install Playwright Chromium). Never run `:e2e:runner:e2eTest` or `:e2e:web-ui:uiE2eTest` in the inner edit loop.
  - Offline fast testing remains available via `java test/TestRunner.java` (`./procomp-test` / `procomp-test.cmd`, `--fast` for Levels 1–2). See `docs/adr/0001-pure-java-25-jep458.md` and `docs/adr/0003-gradle-dev-toolchain-jep458-runtime.md`.

## Architecture: Functional Core, Imperative Shell (FCIS + DDD)

Organize code into four strict layers with dependencies flowing strictly inward (`cli`/`web`/`tui` → `shell` → `core`; `platform` → `shell/ports` + `core`):

- **`core/` (Functional Core):** Immutable DDD domain types and pure, total functions grouped by domain (`project`, `routing`, `process`, `gateway`). Keep `core/` 100% pure — zero I/O, zero OS/FS/HTTP/clock calls, zero dependencies outside `core/`.
- **`shell/` (Imperative Shell):** Modality-agnostic use-case orchestrators and narrow I/O ports (`shell/ports/`). Keep conditionals and domain decisions in `core/`; `shell/` gathers inputs via ports, calls `core/`, and executes returned actions.
- **`platform/` (I/O Adapters):** Cross-platform (Windows, macOS, Linux) implementations of `shell/ports/` for OS processes, TCP ports, HTTP, state-dir filesystem, and clock.
- **`cli/`, `web/` (and future `tui/`):** Thin modality frontends that parse input/HTTP, call `shell/`, and render output, HTTP/SSE responses, or exit codes.

## Structural Rules

- **Decoupled `core/` domains:** Keep `routing` and `process` completely independent of each other and of `gateway`. `gateway` may reference `project` and `routing` domain types only; compose across domains in `shell/`.
- **Narrow role ports:** Define small, role-specific port interfaces (`ProcessRunner`, `PortScanner`, `HttpClient`, `StateStore`, `Clock`) and inject only the ports each use case needs (e.g., `route` takes `StateStore` only).
- **Modality isolation:** Keep `argv`, `stdout`/`stderr`, ANSI formatting, and exit codes strictly inside `cli/` (or `tui/`). Return typed domain results from `shell/`; emit progress and streaming events through a modality-supplied typed observer/stream.
- **Explicit errors:** Return typed Result / Domain Error values from `core/` and `shell/` for all expected domain and I/O failures; reserve exceptions/panics for programmer bugs.
- **Make illegal states unrepresentable (zero `null`, minimal `Optional`):** Model `core/` domain states with `sealed interface` + `record` sum types so each variant carries only the required, non-null fields valid in that state. Never pass or return `null` in `core/`; avoid `Optional` on domain fields or method parameters to represent states (reserve `Optional` for genuine key lookups or independent optional config attributes). See `docs/adr/0002-non-null-illegal-states-unrepresentable.md`.
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
