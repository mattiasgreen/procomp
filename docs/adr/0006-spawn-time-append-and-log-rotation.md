---
status: accepted
---

# Spawn-Time Append and Size-Threshold Log Rotation for Detached Processes

`procomp` opens `Managed` `Process` log files (for `Service`, `Gateway`, and `Build Run` Processes) in append mode (`ProcessBuilder.Redirect.appendTo`) and performs best-effort size-threshold rotation (`10 MB` threshold, keeping `3` numbered backups `.1` through `.3`) immediately before spawning a new `Process`, keeping raw log files 100% untouched with zero injected separator lines.

## Considered Options

- **Truncate on every start (`ProcessBuilder.Redirect.to`, previous behavior):** Destroys previous run output on every `Process` restart, preventing developers from scrolling back across restarts to inspect crash or startup history.
- **JDK `java.util.logging.FileHandler` or live in-flight rotation:** `FileHandler` only rotates bytes written through a Java `Logger` inside the same JVM, whereas `procomp` spawns detached OS `Process`es that outlive the CLI/Web UI JVM. Furthermore, on Windows (a first-class target), files held open via `ProcessBuilder.Redirect` cannot be renamed while the child `Process` is alive. Live rotation would require a long-lived supervisor process piping every child's stdio, which v1 explicitly avoids.
- **Unbounded append only:** Preserves logs across restarts, but risks multi-gigabyte log files on long-lived developer machines.
- **Always rotate on every start:** Splits every restart into a separate file, forcing multi-file browsing just to compare the previous run with the current one.
- **Append by default + spawn-time size-threshold rotation (chosen):** Preserves continuous history across normal restarts in one raw file, bounds disk growth without a supervisor process, and only renames files before `pb.start()` when no `Process` holds the handle open (Windows-safe).

## Consequences

- **Windows-safe & zero-supervisor:** Rotation happens strictly at spawn time before `ProcessBuilder.start()`. If an external tool (editor, antivirus) briefly holds a lock on Windows, rotation is best-effort and falls back to appending to the active file so `Process` startup is never blocked.
- **Raw log invariant preserved:** `procomp` never injects restart banners or timestamps into `.out.log` / `.err.log`, keeping line-delimited JSON and raw tool output untouched on disk.
- **Single-file ceiling is soft:** A single long-running `Process` can still grow a log file past `10 MB` until its next restart; bounded live rotation remains deferred to a future supervisor or app-managed log file integration (e.g., Logback).
- **Backward pagination compatibility:** Because rotation replaces the active file with a smaller new file, log readers treat any shrink (`beforeOffset > currentFileSize`) gracefully rather than failing.
