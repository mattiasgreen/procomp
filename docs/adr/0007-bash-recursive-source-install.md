---
status: accepted
---

# Bash Recursive Source Copy Installer for JEP 458 Runtime

To install `procomp` from a local checkout onto a user's `PATH` in restricted corporate Linux and Windows (Git Bash) environments without shipping binaries or introducing a build step, we provide a pure Bash installer (`install.sh`) that recursively copies the self-contained JEP 458 runtime payload (`src/`, `web/`, `procomp`, `procomp.cmd`, `VERSION`, `.procomp-install`, `uninstall.sh`) into a user-local directory (default `~/.local/share/procomp`, promptable in TTY, overridable via `--dir` / `-y`) and writes thin `PATH` shims (`procomp` everywhere, plus `procomp.cmd` on Git Bash) into a user bin directory (default `~/.local/bin`, with writable `PATH` alternatives detected and suggested). The installer never mutates shell profiles or Windows Registry `PATH` and never runs destructive `rm` commands on user directories: it prints `PATH` export commands for the user to run, moves previous installs aside to `<install-dir>.bak-<timestamp>` on upgrade, and prints exact `rm` commands for cleanup and uninstall.

## Considered Options

- **Compile-at-install (`javac` + `jar`):** Faster per-invocation startup while still distributing source only, but adds compilation steps, requires a full `javac`/`jar` build flow inside the installer, and diverges from the pure JEP 458 source-launch contract. Can be layered later as an optional fast path without changing the install layout.
- **Single-file JEP 330 amalgamation (`Procomp.java`):** Enables single-file distribution with a shebang, but requires building and maintaining a Java source amalgamator (package flattening, import hoisting, FQN rewriting, embedded `web/` string chunking), loses JEP 458 lazy compilation, and degrades stack-trace readability.
- **Cross-platform Java installer (`scripts/Install.java`) or `install.sh` + `install.ps1`:** Avoids Bash-only scope or duplicates logic in PowerShell, but Git Bash + Linux covers our target environments with one small, auditable Bash script.
- **Zero-copy `PATH` shim to the git checkout:** Simplest for contributors, but couples the installed CLI to a mutable working tree (branch switches or repo moves break `procomp`).

## Consequences

- Installed copies are 100% detached from the git checkout and cleanly isolated from `FsStateStore` (`~/.local/state/procomp` on POSIX, `%LOCALAPPDATA%\procomp` on Windows).
- `install.sh` requires JDK 25+ up front (respecting `$JAVA_HOME/bin/java` first) with no escape hatch, and verifies `"$BIN_DIR/procomp" --version` after install.
- `procomp --version` (and `-V`) resolves `VERSION` by walking up from `CodeSource` (matching `StaticAssets`), falling back to `procomp dev` when run directly from a repository checkout.
- The copied payload is self-contained, so a future standalone release `.zip`/`.tar.gz` only needs to archive the same directory structure.
