# `procomp` Configuration Schema (`procomp.json`)

`procomp` is completely language- and framework-agnostic with **zero built-in presets**. Every build tool (`builders`), runtime (`runtimes`), gateway, routing profile, and service is declared transparently in a single **`procomp.json`**.

---

## 1. Top-Level Structure

```json
{
  "builders": { ... },
  "runtimes": { ... },
  "gateway":  { ... },
  "profiles": { ... },
  "services": [ ... ]
}
```

---

## 2. Template Variables (`{{var}}`)

Both `builders.<name>.command` and `runtimes.<name>.command` (as well as `artifactGlob`) support `{{var}}` interpolation:

| Variable | Description |
| :--- | :--- |
| `{{name}}` | Service `name` |
| `{{port}}` | Service `port` |
| `{{repo}}` | Resolved path to `repo` |
| `{{module}}` | Raw `module` string (e.g. `:services:order-service` or `billing-api`) |
| `{{modulePath}}` | Filesystem path derived from `module` (`:` converted to `\`) |
| `{{artifact}}` | Newest file matching `runtimes.<name>.artifactGlob` (excluding `artifactExclude`) |
| `{{<custom>}}` | Any key defined in `service.vars` (e.g. `{{jvmArgs}}`, `{{profile}}`) |

---

## 3. Section Reference

### `builders` *(optional)*
Map of named build aliases to command templates executed inside the service's `repo`:
```json
"builders": {
  "gradle-boot":   { "command": "gradlew.bat {{module}}:bootJar -q" },
  "maven-quarkus": { "command": "mvnw.cmd -pl {{module}} package -DskipTests -q" },
  "npm":           { "command": "npm run build" }
}
```

### `runtimes` *(required)*
Map of named runtime aliases:

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `command` | `string` | Yes | Command template to launch the process. |
| `healthPath` | `string` | No | Default HTTP readiness path (`2xx` = `HEALTHY`). |
| `artifactGlob` | `string` | No | Glob pattern (relative to `repo` or workspace) used to resolve `{{artifact}}`. |
| `artifactExclude` | `string` | No | Wildcard pattern excluded from `artifactGlob` (e.g. `"*-plain.jar"`). |
| `memoryArgPattern` | `string` | No | Optional regex to extract a memory limit for the `LIMIT` column (e.g. `"-Xmx(\\d+[kmgKMG]?)"`). |

### `gateway` *(optional)*
WireMock admin bridge configuration for `LOCAL` ↔ `REMOTE` routing:
```json
"gateway": {
  "type": "wiremock",
  "adminUrl": "http://127.0.0.1:8080",
  "serviceName": "wiremock-gateway"
}
```

### `profiles` *(optional)*
Named routing presets activated via `.\procomp.cmd -Profile <name>`:
```json
"profiles": {
  "checkout": {
    "local":  ["order-service", "billing-quarkus"],
    "remote": ["frontend-bff"]
  }
}
```

### `services` *(required)*
Array of application definitions:

| Field | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `name` | `string` | Yes | — | Unique service name. |
| `runtime` | `string` | Yes | — | Key in top-level `runtimes`. |
| `builder` | `string` | No | `null` | Key in top-level `builders`. |
| `port` | `integer` | Yes | — | Local TCP port the service listens on. |
| `repo` | `string` | No | `null` | Path to the Git repository (relative to `procomp.json` or absolute). |
| `module` | `string` | No | `""` | Subproject / module path within `repo`. |
| `vars` | `object` | No | `{}` | Custom template variables (e.g. `{"jvmArgs": "-Xmx384m"}`). |
| `healthPath` | `string` | No | From runtime | Overrides runtime `healthPath`. |
| `proxyPath` | `string` | No | `"/<name>"` | Context prefix on the gateway (`localhost:8080<proxyPath>/...`). |
| `remoteUrl` | `string` | No | `null` | Upstream URL used by the gateway when routing mode is `REMOTE`. |
| `logFormat` | `string` | No | `"text"` | Display hint (`"text"` or `"json"`). |
| `logMapping` | `object` | No | `{}` | Custom JSON log field overrides. |
| `env` | `object` | No | `{}` | Inline environment variables (precedence: `.env` < `env` < `.env.<name>`). |
| `enabled` | `boolean` | No | `true` | Whether `up` / TUI launch starts this service automatically. |

---

## 4. Runtime State (`.stack_state/`)
* **`.stack_state/<name>.pid`**: Windows PID of `procomp`-managed processes.
* **`.stack_state/routing.json`**: Persisted `LOCAL` / `REMOTE` routing choices.
