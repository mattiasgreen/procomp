---
status: accepted
---

# Zero-Build Vanilla ES6 Web UI Modality over JDK HttpServer

`procomp` adds an interactive Web UI modality (`procomp ui [--port <n>] [--open]`, default `127.0.0.1:9090`) served by JDK 25's built-in `com.sun.net.httpserver.HttpServer` (`src/procomp/web/`) with zero-build Vanilla ES6 modules (`web/`). All FCIS domain logic (`core/` and `shell/`) stays 100% in Java: the Web UI operates as an in-process equivalent of CLI `--json` mode plus SSE streaming, so zero domain logic is duplicated in JavaScript and zero `npm`, bundler, or third-party Java dependencies are introduced (preserving [ADR 0001](0001-pure-java-25-jep458.md)).

## Considered Options

- **Pure-Java TUI (`procomp tui`):** Stays in the terminal and works over SSH, but raw terminal mode, arrow-key input, and resize handling across Windows, macOS, and Linux require Win32 FFM / `stty` plumbing without third-party libraries (JLine/Lanterna), and wide estate tables + `Inspection` parent chains + live logs are cramped in ANSI grids. A TUI remains possible later on the same shared backend.
- **Server-Rendered HTML / Hypermedia:** Keeps zero FCIS duplication with even less JS, but embeds HTML templating in Java strings and forfeits direct reuse of the CLI `--json` wire shapes.
- **Client-Heavy SPA (React / Vite / `npm`):** Violates the zero-`npm`, zero-build-step corporate constraint and encourages duplicating FCIS domain state and rules in JavaScript.
- **Thin Vanilla ES6 UI + Fat Java Backend over JDK `HttpServer` (chosen):** Native to JDK 25 (`jdk.httpserver` + virtual threads) and modern browsers (`<script type="module">`, CSS Grid/Flexbox, `<dialog>`), cross-platform first-class on Windows, and reuses the exact same `shell/` backend and JSON shapes as the CLI.

## Consequences

- **Shared backend runtime & pure codecs:** Port wiring and lazy shell factories move out of `CliRuntime` into a modality-neutral `ProcompRuntime` in `procomp.shell`. Pure, zero-I/O `DomainJson` and `CliCommandFormatter` live in `procomp.shell` (keeping `core/` free of wire-format concerns) so CLI `--json` and the Web HTTP API share one canonical JSON representation and Java is the single source of truth for verbatim, copy-pasteable `procomp ...` CLI commands echoed by the UI.
- **Lifecycle & UNIX separation:** `procomp ui` runs as a foreground command scoped to one resolved `Project`, reads live `StateStore` state on every observation (seamless coexistence with CLI invocations), reloads `procomp.json` automatically on disk changes, and never stops `Managed` Service or Gateway `Process`es on exit. Every UI action maps 1:1 to a single `shell/` operation—route/profile edits surface Gateway drift with an explicit **Reconcile** action, and killing a `Conflict` Process requires an explicit `<dialog>` confirmation showing PID, command line, and verbatim `--kill-conflict` command.
- **Hybrid live updates & concurrency:** Estate status uses visibility-aware polling (`document.visibilityState`), while `logs -f` and `start` progress stream over **SSE** (`text/event-stream`) on Java 25 virtual threads backed by `ProgressObserver`. Reads and SSE streams run concurrently; state-file mutations take a lightweight per-Project lock without blocking concurrent `start` health waits.
- **Localhost security & JEP 458 assets:** Binds strictly to `127.0.0.1` (default port `9090`, `--port 0` for ephemeral, opt-in `--open`) and validates `Origin`/`Host` headers on mutating requests (`POST`/`PUT`/`DELETE`). Static assets under `web/` are served directly from disk with path-traversal protection so JEP 458 source launch works with zero build step.
