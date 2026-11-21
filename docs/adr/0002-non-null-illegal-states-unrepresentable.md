---
status: accepted
---

# Non-Null by Default and Making Illegal States Unrepresentable

`procomp`'s functional core (`core/`) models process lifecycles, ownership, health, routing, and gateway drift. Representing states with flat records full of nullable fields or `Optional<T>` fields ("Maybe Not" / typed `null`) allows impossible combinations to compile (for example, a `STOPPED` process carrying a PID or a `LOCAL` route missing a port) and pushes defensive null/presence checks onto every caller.

We adopt two reinforcing rules:

1. **Make illegal states unrepresentable in `core/`:** Model domain states and variants using Java 25 `sealed interface` + `record` sum types so every variant carries **only** the required, non-null fields valid in that state.
2. **Avoid both `null` and `Optional` state bags:** Never pass or return `null` in `core/`. Reserve `Optional<T>` for genuine key lookups (such as `project.service(name)`) or truly independent optional configuration attributes—never for encoding state variants or method parameters.

## Considered Options

- **Nullable fields with runtime `Objects.requireNonNull` checks:** Easy to write, but bugs surface only at runtime and callers cannot tell from types which fields exist in which state.
- **Flat records with `Optional<T>` fields:** Eliminates `NullPointerException`, but leaves illegal field combinations representable and leaks optionality across every consumer (Rich Hickey's *"Maybe Not"*).
- **Third-party JSpecify / JSR-305 `@Nullable` annotations in `src/`:** Incompatible with zero-dependency JEP 458 direct source launch (`java src/procomp/cli/Procomp.java` runs with an empty classpath).
- **`sealed interface` ADTs + compile-time Error Prone & NullAway (chosen):** Enforces non-null by default at compile time via Gradle without adding any runtime or `compileOnly` dependency to `src/`. Any nullable JDK APIs (`Path.getParent()`, `System.getenv()`, `Map.get()`) are handled and normalized at the imperative `platform/` and `cli/` boundaries before entering `core/`.

## Consequences

- `core/` must remain free of `null` and free of `@Nullable` annotations; NullAway enforces the `null` ban at compile time.
- Existing `Optional`-bearing records may be tightened to `sealed interface` variants incrementally; all new `core/` types must follow this rule from day one.
- Because `src/` has zero third-party dependencies, any boundary helper that must explicitly mark a nullable return/parameter in `platform/` or `cli/` uses a small internal `@Nullable` annotation (or converts immediately at the JDK call site) rather than importing an external annotation JAR.
