# Coding Standards

Judgement-call standards enforced during `/code-review` (mechanical import, purity, and banned-synonym checks are enforced deterministically by `ArchitectureRulesTest` in `test/TestRunner.java`).

## Modality Isolation

- Keep `DomainError` messages in `core/` and `shell/` strictly in domain terms (`GLOSSARY.md`). Never embed CLI command names, subcommands, or flag syntax (such as `--kill-conflict`, `--config`, or `'procomp gateway start'`) in `shell/` error strings; add CLI usage hints in `cli/` only.

## Reuse Shared Primitives

- Reuse existing shared helpers rather than copying local variants across modules:
  - `Result.castError()` for propagating typed errors across `Result<T, E>` return types.
  - `CliFormat` for CLI option parsing (`--timeout`, positive integer seconds), table rendering, duration formatting, and JSON null helpers.
  - `ProcessLifecycle` for shared `Service`/`Gateway` discovery, inspection, start orchestration, health waiting, and teardown.

## Domain Clarity & Minimalism

- Prefer `sealed interface` + `record` sum types over flat records with `Optional` fields whenever field presence depends on state (make illegal states unrepresentable; see `docs/adr/0002-non-null-illegal-states-unrepresentable.md`). Avoid `Optional` method parameters and never use `null` in `core/`.
- Prefer small domain enums or records over raw status strings whenever the set of values is closed.
- Keep `shell/ports/` narrow: avoid adding port methods without an active caller in `src/`.
- Avoid middle-man wrapper types that only forward fields from an inner record without adding domain behavior.
