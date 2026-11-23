# `procomp` Configuration Schema (`procomp.json`)

`procomp` is language- and framework-agnostic with **zero built-in presets**. Every `Builder`, `Runtime`, `Gateway`, `Profile`, `Catalog`, and `Service` is declared explicitly in **`procomp.json`** using canonical terms from [GLOSSARY.md](../GLOSSARY.md).

**Strict validation:** `procomp.json` fails fast on unrecognized fields, invalid types, undefined `{{var}}` templates, missing required `envFiles`, unset `${env:VAR}` references, and unknown `Service`, `Builder`, or `Runtime` names.

---

## 1. Config Discovery

`procomp` discovers `procomp.json` by walking upward from the current working directory. Override with `--config <path>` or `PROCOMP_CONFIG`.

---

## 2. Top-Level Structure

```json
{
  "project": "acme",
  "repositoriesRoot": "..",
  "envFiles": [
    ".env",
    { "path": ".env.local", "optional": true }
  ],
  "defaults": {
    "proxyPathTemplate": "/{{service}}",
    "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}",
    "vars": {
      "jvmArgs": ["-Xmx384m"]
    },
    "env": {
      "SPRING_PROFILES_ACTIVE": "local",
      "DB_PASSWORD": "${env:ACME_DB_PASSWORD}"
    }
  },
  "runtimes": {
    "java": {
      "command": ["java", "{{jvmArgs}}", "-Dserver.port={{port}}", "-jar", "{{artifact}}"],
      "healthPath": "/actuator/health",
      "artifactGlob": "{{subproject}}/build/libs/*.jar",
      "artifactExclude": "*-plain.jar"
    }
  },
  "gateway": {
    "type": "wiremock",
    "port": 8080,
    "healthPath": "/__admin/health",
    "command": ["java", "-Xmx128m", "-jar", "{{repositoriesRoot}}/wiremock/wiremock-standalone.jar", "--port", "8080"],
    "workingDir": "{{repositoriesRoot}}/wiremock"
  },
  "catalog": {
    "path": "../my-gitops/service-index.json",
    "itemsPath": "apps",
    "map": {
      "name": "servicename",
      "port": "localport",
      "runtime": "type",
      "repositoryUrl": "servicerepositoryurl",
      "vars.subproject": "subproject"
    }
  },
  "services": {
    "service1": {
      "vars": {
        "jvmArgs": ["-Xmx512m"]
      }
    }
  },
  "profiles": {
    "one-local": {
      "local": ["service1"]
    }
  }
}
```

### Top-Level Fields

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `project` | `string` | Yes | Explicit `Project` identifier used to scope local state and logs (`~/.local/state/procomp/<project>/` or `%LOCALAPPDATA%\procomp\<project>\`). |
| `repositoriesRoot` | `string` | No | Base folder for local `Repository` checkouts (resolved relative to `configDir` if relative). Exposed as `{{repositoriesRoot}}`. |
| `envFiles` | `(string \| { path, optional? })[]` | No | Explicit `.env` files loaded into `Process` environments. Missing files fail fast unless `optional: true`. |
| `defaults` | `object` | No | Default `builder`, `proxyPathTemplate`, `remoteUrlTemplate`, `workingDir`, `vars`, and `env` applied to all `Service`s unless overridden. |
| `builders` | `map<string, Builder>` | No | Named `Builder` templates referenced by `Service`s or `defaults.builder`. |
| `runtimes` | `map<string, Runtime>` | Yes | Named `Runtime` execution templates referenced by `Service`s. |
| `gateway` | `object` | No | Local `Gateway` definition (`type`, `port`, optional `healthPath`, `command`, `workingDir`, `env`). |
| `catalog` | `object` | No | External `Catalog` JSON importer with explicit field mapping. |
| `services` | `map<string, Service>` | No | Inline `Service` definitions (or overrides for `Service`s imported via `catalog`). Map key is the unique `Service` name. |
| `profiles` | `map<string, Profile>` | No | Named routing `Profile`s. Each declares `{ "local": ["<service>", ...] }`; all other `Service`s route `REMOTE`. Fails fast on unknown `Service` names. |

---

## 3. Commands (`argv` Arrays) & Template Variables

All launch commands (`runtimes.<name>.command` and `gateway.command`) are **structured `argv` arrays** executed directly without a shell wrapper.

### Built-In Template Variables (`{{var}}`)

Supported in `command` elements, `workingDir`, `artifactGlob`, `proxyPathTemplate`, `remoteUrlTemplate`, and `env` values. Undefined `{{var}}` references fail fast at validation time.

| Variable | Description |
| :--- | :--- |
| `{{configDir}}` | Directory containing `procomp.json`. |
| `{{repositoriesRoot}}` | Resolved `repositoriesRoot` directory (requires `repositoriesRoot` to be configured). |
| `{{user}}` | Current OS username (cross-platform, from `user.name`). |
| `{{service}}` | `Service` name (map key). |
| `{{port}}` | `Service` local port (integer). |
| `{{repository}}` | Resolved local `Repository` Git root (explicit `repository` or `<repositoriesRoot>/<basename>` derived from `repositoryUrl`). Checked lazily when a local action requires the checkout; missing checkouts never block `REMOTE` `Service`s. |
| `{{artifact}}` | Newest file matching `runtime.artifactGlob` (excluding `artifactExclude`), resolved at `start` time. |
| `{{<custom>}}` | Any key defined in `defaults.vars` or `service.vars`. |

### Array Splicing in `argv`

Custom `vars` values may be either a `string` or a `string[]` (e.g., `"jvmArgs": ["-Xmx384m", "-XX:+UseG1GC"]`). When a standalone `argv` element is a `string[]` variable (`"{{jvmArgs}}"`), its elements are **spliced** directly into `argv` (an empty array `[]` splices zero arguments).

---

## 4. Environment Variables & Secrets

1. **Precedence (lowest to highest):**
   - **Runtime `Process`:** host process environment < `envFiles` (in listed order) < `defaults.env` < `runtime.env` < `service.env`.
   - **Build `Process`:** host process environment < `envFiles` (in listed order) < `defaults.env` < `builder.env` < `service.env` (`runtime.env` is deliberately excluded).
2. **Host-env pass-through:** `env` values, `command` elements, `workingDir`, and `vars` (scalar or array) may reference host variables via `"${env:VAR_NAME}"` (for example, `"${env:JAVA_HOME}/bin/java"` in `runtimes.<name>.command`). Unset host variables fail fast rather than silently expanding to empty strings.
3. **Secret hygiene:** `procomp` never persists resolved `env` values in state files and never prints them in `inspect`, `status --json`, or error logs.

---

## 5. `builders`, `runtimes`, `gateway`, `catalog`, and `services` Reference

### `builders.<name>`

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `command` | `string[]` | Yes | Build `argv` array (executed in-place without a shell wrapper). Relative executable paths resolve against the build working directory. |
| `workingDir` | `string` | No | Build working directory template override (overrides `service.workingDir` when set; defaults to `service.workingDir`). |
| `env` | `map<string, string>` | No | Builder-level environment variables. |

### `runtimes.<name>`

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `command` | `string[]` | Yes | Launch `argv` array. Relative executable paths resolve against `workingDir`. |
| `healthPath` | `string` | No | HTTP health path (e.g., `/actuator/health`). Empty/omitted falls back to TCP listen (if `port` set) or process-alive. |
| `artifactGlob` | `string` | No | Glob (relative to `workingDir`/`repository`) for newest runnable `Artifact`. |
| `artifactExclude` | `string` | No | Filename glob to exclude (e.g., `*-plain.jar`). |
| `workingDir` | `string` | No | Working directory template override. |
| `env` | `map<string, string>` | No | Runtime-level environment variables. |

### `gateway`

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `type` | `"wiremock"` | Yes | Gateway adapter type (v1 supports `"wiremock"`). |
| `port` | `integer` | Yes | Local TCP port (`adminUrl` is derived as `http://127.0.0.1:<port>`). |
| `healthPath` | `string` | No | HTTP health check path (e.g., `/__admin/health`). |
| `command` | `string[]` | No | Launch `argv` array (required only if `procomp gateway start` launches a `Managed` `Gateway`). |
| `workingDir` | `string` | No | Working directory for a `Managed` `Gateway`. |
| `env` | `map<string, string>` | No | Environment variables for a `Managed` `Gateway`. |
| `proxyHeaders` | `map<string, string>` | No | Extra HTTP request headers injected onto all proxied requests (`LOCAL` and `REMOTE`, via WireMock `additionalProxyRequestHeaders`). Supports `{{var}}` (including `{{user}}`) and `${env:VAR}` expansion. |

### `catalog`

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `path` | `string` | Yes | Path to external JSON catalog (relative to `configDir`, absolute, or using `{{repositoriesRoot}}`). |
| `itemsPath` | `string` | No | Dot-path to the array of entries inside the catalog JSON (omit if the root JSON is an array). |
| `map` | `map<string, string>` | Yes | Explicit mapping from `procomp` `Service` fields (`name`, `port`, `runtime`, `builder`, `repository`, `repositoryUrl`, `workingDir`, `healthPath`, `proxyPath`, `remoteUrl`, `vars.<key>`) to source field names in each catalog item. Unmapped source fields are ignored; numeric string ports (e.g., `"9001"`) are strictly parsed as integers. |

### `services.<name>`

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `port` | `integer` | No* | Local TCP port the `Service` listens on (*required if `proxyPath` is routed `LOCAL`). |
| `runtime` | `string` | Yes* | Key in top-level `runtimes` (validated strictly; *optional when overriding a `Service` already imported with a `runtime` from `catalog`). |
| `builder` | `string` | No | Key in top-level `builders` (defaults to `defaults.builder` if omitted). |
| `repository` | `string` | No | Explicit local `Repository` Git root path override. Multiple `Service`s may share the same `Repository` (monorepo / multi-module). |
| `repositoryUrl` | `string` | No | Remote Git URL used to derive `<repositoriesRoot>/<basename>` when `repository` is omitted. |
| `workingDir` | `string` | No* | Working directory for the `Service`'s `Process`. Defaults to `{{repository}}` when a `Repository` is configured (*required if the `Service` has no `Repository`). |
| `healthPath` | `string` | No | Overrides `runtime.healthPath`. |
| `proxyPath` | `string` | No | Gateway path prefix (defaults to `defaults.proxyPathTemplate` or `"/{{service}}"`). |
| `remoteUrl` | `string` | No | Upstream URL when routed `REMOTE` (defaults to `defaults.remoteUrlTemplate`). |
| `vars` | `map<string, string \| string[]>` | No | Custom template variables (merged over `defaults.vars`). |
| `env` | `map<string, string>` | No | Service-level environment variables (merged over `runtime.env`). |
| `envFiles` | `(string \| { path, optional? })[]` | No | Additional service-specific `.env` files. |
