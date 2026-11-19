# `procomp` Configuration Schema (`procomp.json`)

`procomp` is completely language- and framework-agnostic with **zero built-in presets**. Every build tool (`builders`), runtime (`runtimes`), gateway, routing profile, and application (`apps`) is declared transparently in a single **`procomp.json`**.

---

## 1. Top-Level Structure

```json
{
  "reposRoot": "..",
  "appsFile":  "./service-index.json",
  "defaults":  {
    "builder": "gradle-boot",
    "enabled": false,
    "remoteUrlTemplate": "https://shared-dev.mycorp/{{servicename}}",
    "vars": { "jvmArgs": "-Xmx384m" }
  },
  "builders": { ... },
  "runtimes": { ... },
  "gateway":  { ... },
  "profiles": {
    "one-local": { "local": ["service1"], "remote": ["*"] }
  },
  "apps":     [ ... ]
}
```

* **`appsFile`** *(optional)*: Path (relative to `procomp.json` or absolute) to an external service index JSON (e.g. `service-index.json` containing `{"apps": [...]}` or a bare array), merged with any inline `apps`.
* **`defaults`** *(optional)*: Default `builder`, `enabled`, `logFormat`, `proxyPathTemplate`, `remoteUrlTemplate`, and `vars` applied to every app unless overridden on the app itself.
* **`profiles.<name>.remote`**: Supports `"*"` wildcard to route all apps not listed in `local` as `REMOTE`.

---

## 2. Template Variables (`{{var}}`)

Both `builders.<name>.command` and `runtimes.<name>.command` (as well as `artifactGlob`) support `{{var}}` interpolation:

| Variable | Description |
| :--- | :--- |
| `{{root}}` | Workspace root directory containing `procomp.ps1` |
| `{{reposRoot}}` | Value of `-ReposRoot` CLI parameter (or `reposRoot` in config); usable in `appsFile`, `repo`, `builders`, and `runtimes` |
| `{{java}}` | Resolved `%JAVA_HOME%\bin\java.exe` (or `java` on `PATH`) |
| `{{servicename}}` (or `{{name}}`) | App `servicename` |
| `{{localport}}` (or `{{port}}`) | App `localport` |
| `{{type}}` | App `type` (runtime alias) |
| `{{subproject}}` (or `{{module}}`) | Raw `subproject` string (e.g. `service1` or `:services:order-service`) |
| `{{modulePath}}` | Filesystem path derived from `subproject` (`:` converted to `\`) |
| `{{repo}}` | Resolved local repository path (explicit `repo`, or derived from `reposRoot` + `servicerepositoryurl` basename) |
| `{{servicerepositoryurl}}` | Git remote URL from `servicerepositoryurl` |
| `{{swaggeruri}}` | Swagger/OpenAPI relative URI from `swaggeruri` |
| `{{artifact}}` | Newest file matching `runtimes.<type>.artifactGlob` (excluding `artifactExclude`) |
| `{{<custom>}}` | Any key defined in `app.vars` (e.g. `{{jvmArgs}}`) |

---

## 3. `apps` Entry Reference

```json
{
  "apps": [
    {
      "servicename": "service1",
      "localport": "9001",
      "subproject": "service1",
      "type": "java",
      "swaggeruri": "swagger-ui/index.html",
      "servicerepositoryurl": "ssh://git@scm.corp:7999/acme/service1.git"
    }
  ]
}
```

| Field | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `servicename` | `string` | Yes | — | Unique application identifier. |
| `localport` | `string\|integer` | Yes | — | Local TCP port the app listens on (string or int). |
| `type` | `string` | Yes | — | Key in top-level `runtimes` (e.g. `"java"`, `"quarkus"`, `"node"`). |
| `subproject` | `string` | No | `""` | Subproject / module path within the repository. |
| `swaggeruri` | `string` | No | `""` | Relative Swagger/OpenAPI path (e.g. `"swagger-ui/index.html"`). |
| `servicerepositoryurl` | `string` | No | `""` | Git remote URL; if `repo` is omitted, local path is `<reposRoot>/<basename>`. |
| `repo` | `string` | No | Derived | Explicit local Git repository path override. |
| `builder` | `string` | No | `null` | Key in top-level `builders`. |
| `vars` | `object` | No | `{}` | Custom template variables (e.g. `{"jvmArgs": "-Xmx384m"}`). |
| `healthPath` | `string` | No | From runtime | Overrides runtime `healthPath`. |
| `proxyPath` | `string` | No | `"/<servicename>"` | Context prefix on the gateway. |
| `remoteUrl` | `string` | No | `null` | Upstream URL used by the gateway when routing mode is `REMOTE`. |
| `logFormat` | `string` | No | `"text"` | Display hint (`"text"` or `"json"`). |
| `logMapping` | `object` | No | `{}` | Custom JSON log field overrides. |
| `env` | `object` | No | `{}` | Inline environment variables (precedence: `.env` < `env` < `.env.<servicename>`). |
| `enabled` | `boolean` | No | `true` | Whether `up` / TUI launch starts this app automatically. |

---

## 4. Runtime State (`.stack_state/`)
* **`.stack_state/<servicename>.pid`**: Windows PID of `procomp`-managed processes.
* **`.stack_state/routing.json`**: Persisted `LOCAL` / `REMOTE` routing choices.
