# `procomp` Configuration Schema (`procomp.json`)

`procomp` is completely language- and framework-agnostic with **zero built-in presets**. Every build tool (`builders`), runtime (`runtimes`), gateway (`gateway`), routing profile (`profiles`), and application (`apps` / `appsFile`) is declared transparently in **`procomp.json`**.

---

## 1. Top-Level Structure

```json
{
  "project": "my-project",
  "reposRoot": "..",
  "appsFile": "{{reposRoot}}\\my-gitops\\service-index.json",
  "defaults": {
    "builder": "gradle",
    "enabled": false,
    "logFormat": "json",
    "proxyPathTemplate": "/{{servicename}}",
    "remoteUrlTemplate": "https://shared-dev.mycorp/{{servicename}}",
    "workingDir": "",
    "vars": { "jvmArgs": "-Xmx384m" },
    "env":  { "SPRING_PROFILES_ACTIVE": "local" }
  },
  "builders": {
    "gradle": { "command": "gradlew.bat {{gradleTaskPrefix}}assemble -q" }
  },
  "runtimes": {
    "java": {
      "command": "java {{jvmArgs}} -Dserver.port={{localport}} -jar {{artifact}}",
      "healthPath": "/actuator/health",
      "artifactGlob": "{{modulePath}}\\build\\libs\\*.jar",
      "artifactExclude": "*-plain.jar",
      "memoryArgPattern": "-Xmx(\\d+[kmgKMG]?)"
    },
    "autotest": {
      "command": "java -jar {{artifact}}",
      "healthPath": "",
      "env": { "local_port": "{{localport}}" }
    }
  },
  "gateway": {
    "type": "wiremock",
    "port": 8080,
    "adminUrl": "http://127.0.0.1:8080",
    "command": "java -Xmx128m -jar {{reposRoot}}\\wiremock\\wiremock-standalone.jar --port {{port}}",
    "workingDir": "{{reposRoot}}\\wiremock",
    "healthPath": "/__admin/health"
  },
  "profiles": {
    "one-local": { "local": ["service1"], "remote": ["*"] }
  },
  "apps": []
}
```

* **`project`** *(optional)*: Scopes runtime state (`.stack_state/<project>/`) and service logs (`logs/<project>/`). Overridable via `-Project`. Defaults to config filename stem or `"default"`.
* **`reposRoot`** *(optional)*: Base folder for cloned repositories; overridable via `-ReposRoot`. Exposed as `{{reposRoot}}`.
* **`appsFile`** *(optional)*: Path (relative to `procomp.json`, absolute, or using `{{reposRoot}}`) to an external service index JSON (`{"apps": [...]}` or a bare array), merged with inline `apps`. Read as UTF-8 (BOM-safe).
* **`defaults`** *(optional)*: Default `builder`, `enabled`, `logFormat`, `proxyPathTemplate`, `remoteUrlTemplate`, `workingDir`, `vars`, and `env` applied to all apps unless overridden.
* **`gateway`** *(optional)*:
  * `port` / `adminUrl`: Either may be omitted (the other is derived as `http://127.0.0.1:<port>` or from `adminUrl`'s port).
  * `command` / `workingDir` / `healthPath` / `serviceName`: When `command` is set, `procomp` automatically synthesizes a managed gateway service (`wiremock-gateway` by default, kept `LOCAL` even under `"remote": ["*"]` profiles). If WireMock is already reachable on `port`, `procomp` reuses the running instance (`IDE-UP`) instead of launching a second copy.
* **`profiles.<name>.remote`**: Supports `"*"` wildcard to route all non-gateway apps not listed in `local` as `REMOTE`.

---

## 2. Template Variables (`{{var}}`)

Supported across `builders.<name>.command`, `runtimes.<name>.command`, `artifactGlob`, `workingDir`, `gateway.command`/`workingDir`, and `env` values:

| Variable | Description |
| :--- | :--- |
| `{{root}}` | Directory containing `procomp.ps1` (or config dir in `workingDir`/`gateway.workingDir`) |
| `{{reposRoot}}` | Value of `-ReposRoot` CLI parameter (or `reposRoot` in config) |
| `{{java}}` | Resolved `%JAVA_HOME%\bin\java.exe` (or `java` on `PATH`) |
| `{{servicename}}` (or `{{name}}`) | App `servicename` |
| `{{localport}}` (or `{{port}}`) | App `localport` |
| `{{type}}` | App `type` (runtime alias) |
| `{{subproject}}` (or `{{module}}`) | Raw `subproject` string (e.g., `service1` or `:services:order-service`) |
| `{{modulePath}}` | Filesystem path derived from `subproject` (`:` converted to `\`) |
| `{{gradleTaskPrefix}}` | `:<subproject>:` when `subproject` is set, or `""` for single-project repos |
| `{{repo}}` | Resolved local repository path (explicit `repo`, or `<reposRoot>/<basename>` from `servicerepositoryurl`) |
| `{{servicerepositoryurl}}` | Git remote URL from `servicerepositoryurl` |
| `{{swaggeruri}}` | Swagger/OpenAPI relative URI from `swaggeruri` |
| `{{artifact}}` | Newest file matching `runtimes.<type>.artifactGlob` (excluding `artifactExclude`) |
| `{{<custom>}}` | Any key defined in `defaults.vars` or `app.vars` (e.g., `{{jvmArgs}}`) |

---

## 3. `runtimes` & `apps` Entry Reference

### `runtimes.<name>`
| Field | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `command` | `string` | Yes | — | Launch command template. Relative executables (e.g., `gradlew.bat`) are resolved against `workingDir`. |
| `healthPath` | `string` | No | `""` | HTTP health endpoint path. Empty skips HTTP polling (process alive = `HEALTHY`). |
| `artifactGlob` | `string` | No | `""` | Glob for newest runnable artifact; triggers builder if missing. |
| `artifactExclude` | `string` | No | `""` | Filename wildcard to exclude (e.g., `*-plain.jar`). |
| `memoryArgPattern` | `string` | No | `""` | Regex to extract `LIMIT` column (e.g., `-Xmx(\d+[kmgKMG]?)`). |
| `workingDir` | `string` | No | `""` | Optional working directory override (supports `{{repo}}`, `{{modulePath}}`, `{{reposRoot}}`). |
| `env` | `object` | No | `{}` | Runtime-level environment variables (supports `{{var}}` templates). |

### `apps[]`
| Field | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `servicename` | `string` | Yes | — | Unique application identifier. |
| `localport` | `string\|integer` | Yes | — | Local TCP port the app listens on. |
| `type` | `string` | Yes | — | Key in top-level `runtimes` (must exist for every app in `apps`/`appsFile`). |
| `subproject` | `string` | No | `""` | Subproject / module path within the repository. |
| `swaggeruri` | `string` | No | `""` | Relative Swagger/OpenAPI path (opened via `o`). |
| `servicerepositoryurl` | `string` | No | `""` | Git remote URL; derives `<reposRoot>/<basename>` when `repo` is omitted. |
| `repo` | `string` | No | Derived | Explicit local Git repository path override. |
| `workingDir` | `string` | No | Derived | Explicit working directory override (keeps `repo` intact for Git). |
| `builder` | `string` | No | From `defaults` | Key in top-level `builders`. |
| `vars` | `object` | No | From `defaults` | Custom template variables (merged over `defaults.vars`). |
| `healthPath` | `string` | No | From runtime | Overrides runtime `healthPath`. |
| `proxyPath` | `string` | No | `"/<servicename>"` | Context prefix on the gateway (or `defaults.proxyPathTemplate`). |
| `remoteUrl` | `string` | No | `""` | Upstream URL when `REMOTE` (or `defaults.remoteUrlTemplate`). |
| `logFormat` | `string` | No | `"text"` | Display hint (`"text"` or `"json"`). |
| `logMapping` | `object` | No | `{}` | Custom JSON log field overrides. |
| `env` | `object` | No | `{}` | Environment variables (precedence: `.env` < `defaults.env` < `runtime.env` < `app.env` < `.env.<servicename>`). |
| `enabled` | `boolean` | No | `true` | Whether `up` / TUI startup launches this app automatically. |

---

## 4. Runtime State & Logs

* **`.stack_state/<project>/<servicename>.pid`**: PID of `procomp`-managed processes.
* **`.stack_state/<project>/routing.json`**: Persisted `LOCAL` / `REMOTE` routing choices.
* **`logs/<project>/<servicename>.log` & `.err.log`**: Raw subprocess `stdout` and `stderr`.
* **`logs/procomp-error.log`**: Unhandled crashes (including resolved `Config` and `AppsFile`).
* **`logs/procomp-debug.log`**: All external commands executed by `procomp`.
* **`logs/procomp-trace.log`**: Full trace log (default ON; disable with `-NoTrace` or `PROCOMP_TRACE=0`), viewable live inside the TUI via **`4` / `L`**.
