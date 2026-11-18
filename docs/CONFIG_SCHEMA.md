# `procomp` Configuration Schema

`procomp` is configured declaratively via `services.json` in the workspace root, with runtime state persisted under `.stack_state/`.

---

## 1. `services.json`

`services.json` is a JSON array of service objects. Startup order is not significant; enabled services boot in parallel.

### Service Object Fields

| Field | Type | Required | Default (by `type`) | Description |
| :--- | :--- | :--- | :--- | :--- |
| `name` | `string` | Yes | — | Unique service identifier (used for logs, PIDs, and WireMock mapping IDs). |
| `type` | `string` | No | `"spring-boot"` | One of `"spring-boot"`, `"activemq"`, `"wiremock"`, `"exec"`. |
| `localport` | `integer` | Yes | — | Local TCP port the service listens on. |
| `gitrepo` | `string` | No | `null` | Path to the Git repository (relative to `services.json` or absolute). Multiple services may share one monorepo. |
| `gradlesubproject` | `string` | No | `null` | Gradle subproject path (e.g. `":services:order-service"`), or `null` for single-project repos. |
| `command` | `string` | No | `null` | Custom launch command for `"exec"` or standalone `"activemq"` binaries. |
| `healthPath` | `string` | No | See Type Defaults | HTTP path polled for readiness (`2xx` = `HEALTHY`). |
| `jvmArgs` | `string` | No | See Type Defaults | JVM flags (e.g. `"-Xmx384m -Dspring.profiles.active=local"`). `-Xmx` is parsed for `MAXHEAP`. |
| `proxyPath` | `string` | No | `"/<name>"` | Context prefix exposed on WireMock (`localhost:8080<proxyPath>/...`). |
| `remoteUrl` | `string` | No | `null` | Upstream URL used by WireMock when the service's routing mode is `REMOTE`. |
| `logFormat` | `string` | No | `"text"` | Hint for log viewer (`"text"` or `"json"`). JSON auto-detection still applies. |
| `enabled` | `boolean` | No | `true` | Whether `up` / TUI launch starts this service automatically. |

---

### Type Defaults

| `type` | Default `healthPath` | Default `jvmArgs` | Execution Model |
| :--- | :--- | :--- | :--- |
| `"spring-boot"` | `"/actuator/health"` | `"-Xmx384m"` | `java <jvmArgs> -Dserver.port=<localport> -jar <bootJar>` |
| `"activemq"` | `"/api/jolokia"` | `"-Xmx256m"` | `command` if set, otherwise `java -jar` |
| `"wiremock"` | `"/__admin/health"` | `"-Xmx256m"` | `java <jvmArgs> -jar <wiremockJar> --port <localport>` |
| `"exec"` | `"/health"` | `""` | Executes `command` directly |

---

### Example `services.json`

```json
[
  {
    "name": "wiremock-gateway",
    "type": "wiremock",
    "localport": 8080,
    "healthPath": "/__admin/health",
    "jvmArgs": "-Xmx256m",
    "enabled": true
  },
  {
    "name": "activemq-broker",
    "type": "activemq",
    "localport": 8161,
    "command": "C:\\tools\\apache-activemq\\bin\\activemq.bat start",
    "healthPath": "/api/jolokia",
    "remoteUrl": "https://shared-dev.mycorp/activemq",
    "enabled": true
  },
  {
    "name": "order-service",
    "type": "spring-boot",
    "localport": 8082,
    "gitrepo": "../commerce-backend",
    "gradlesubproject": ":services:order-service",
    "healthPath": "/actuator/health",
    "jvmArgs": "-Xmx384m",
    "proxyPath": "/orders",
    "remoteUrl": "https://shared-dev.mycorp/orders",
    "logFormat": "json",
    "enabled": true
  }
]
```

---

## 2. Runtime State (`.stack_state/`)

* **`.stack_state/<name>.pid`**: Stores the Windows `PID` of processes launched by `procomp`, enabling detach (`d`), re-attach, and clean `taskkill /F /T` teardown.
* **`.stack_state/routing.json`**: Persists each service's active WireMock routing mode (`"LOCAL"` or `"REMOTE"`) across TUI restarts.
