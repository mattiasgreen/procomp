# 0006 — Non-Authoring Git Orchestration & Tracked `Git Run`s

**Status:** Accepted

`procomp` manages local checkouts across a microservice estate via `procomp git <subcommand>` (`status`, `branches`, `clone`, `fetch`, `pull`, `switch`, `stash` push/list, `cancel`, `logs`), deliberately excluding Git authoring and publishing commands (`commit`, `push`, `merge`, `rebase`, `switch -c`, `stash pop`, `reset --hard`).

## Context & Trade-offs

Developers working on a slice of a microservice estate need a single tool to clone missing `Repository`s, keep them current, and switch to teammates' feature branches for local integration testing—without `procomp` turning into a full Git client or risking uncommitted work.

1. **Deliberate non-authoring boundary:** `switch` never creates branches (`-c` omitted) and `stash` only supports `push` and `list` (never `pop`, which can produce merge conflicts). `procomp` is for safely parking work and running remote branches locally; authoring and conflict resolution remain in the user's shell or IDE.
2. **Strict dirty + untracked guard:** Native `git switch` permits switching with untracked files or non-conflicting local edits, silently carrying them onto another branch. `procomp` checks for any staged, unstaged, or untracked files before `switch` or `pull` and fails fast rather than mutating or carrying work across branches; `pull` always uses `--ff-only`, and `clone` is always explicit (never triggered by `start`, `build`, `fetch`, or `pull`).
3. **Zero-network `Discovery` + fetch-age visibility:** Running `git fetch` during `procomp status` or `procomp git status` would break offline workflows and add unpredictable network latency. Instead, `Discovery` runs local, zero-network Git observation once per unique `Repository` (parallelized on virtual threads in `StatusShell`) and surfaces the local `.git/FETCH_HEAD` (or `.git/HEAD`) age alongside a configurable warning threshold (`repositories.fetchWarnAfterHours`, default `24`). Network refreshes happen only on explicit `procomp git fetch` or `procomp git status --fetch`.
4. **System `git` & tracked `Git Run`s:** Consistent with ADR 0001 (pure Java 25, zero runtime dependencies), `procomp` invokes the system `git` binary strictly non-interactively (`GIT_TERMINAL_PROMPT=0`, closed stdin), relying on the developer's existing SSH agent or Git credential helper. Mutating Git operations are tracked as **`Git Run`**s per `Repository` (`git/<repository>.json`, `<repository>.git.out.log`/`.err.log`, PID, status, exit code, cancellation, and overlapping-run protection)—mirroring `Build Run` so CLI and Web UI share unified logs, progress, and cancellation semantics.
