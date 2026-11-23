---
status: accepted
---

# Gradle Dev & Verification Toolchain with Zero-Dependency JEP 458 Runtime

This ADR amends [ADR 0001](0001-pure-java-25-jep458.md) to separate **end-user runtime** from **developer/CI verification tooling**:

- **Runtime (`src/`) remains 100% pure, zero-dependency Java 25 LTS launched directly via JEP 458** (`./procomp` / `procomp.cmd` → `java src/procomp/cli/Procomp.java`). End users in restricted corporate environments still clone and run offline with zero build step, zero Gradle, and zero third-party JARs.
- **Development & verification use Gradle 9+** (`build.gradle.kts` / `settings.gradle.kts`) for incremental compilation, JUnit 5 test reporting, Spotless formatting, Error Prone bug patterns, NullAway compile-time null safety, and opt-in multi-module E2E test fixtures (Spring Boot, ActiveMQ Artemis, WireMock).

## Considered Options

- **Keep zero Gradle everywhere, put E2E fixtures in a separate repo:** Preserves offline zero-Gradle testing, but splits PRs across two repos and foregoes compile-time static analysis (Error Prone, NullAway) and incremental compilation.
- **Keep zero Gradle for `procomp`, add Gradle only under `e2e/`:** Avoids two repos, but leaves the main codebase without Error Prone, NullAway, Spotless, or standard IDE/JUnit integration.
- **Gradle for dev/verification + enforced zero-dep JEP 458 runtime (chosen):** Delivers full Java static analysis, formatting, incremental builds, and first-class multi-module E2E tests while preserving the clone-and-run offline JEP 458 contract.

## Consequences

- `build.gradle.kts` must declare **zero** `implementation` or `compileOnly` dependencies for `src/`.
- The `:verifyJep458` Gradle task runs `java src/procomp/cli/Procomp.java --help` with an empty classpath as part of `gradle check`, mechanically guaranteeing zero third-party imports ever leak into `src/`.
- The zero-dependency offline runner (`java test/TestRunner.java`) continues to work alongside `gradle test` because JUnit 5 bridge code lives under `gradle-test/` rather than `test/`.
- No `gradle-wrapper.jar` binary is committed unless explicitly decided later; local dev and CI use installed Gradle 9+.
