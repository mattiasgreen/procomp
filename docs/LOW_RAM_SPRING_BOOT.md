# Low-RAM Local Spring Boot Guide

Running 10+ Spring Boot `Service`s locally with default JVM and framework settings commonly consumes **5–7.5 GB RSS**. Because `procomp` stays language- and framework-agnostic with zero built-in presets, you can apply low-RAM tuning purely in `procomp.json`—leaving every `Service` `Repository` and `application.yaml` untouched.

See [examples/low-ram-spring-boot.procomp.json](../examples/low-ram-spring-boot.procomp.json) for a complete, ready-to-adapt configuration.

---

## 1. First Leverage: Route Only Your Working Slice `LOCAL`

Before tuning JVM flags, keep most `Service`s routed `REMOTE` via the `Gateway` and use `Profile`s (`procomp profile use <name>`) to run only the `Service`s you actually need locally.

---

## 2. JVM Flags (`defaults.vars.jvmArgs`)

JVM defaults target high-throughput production servers. For local development, trading peak throughput for a small native footprint typically cuts RSS by **40–60%** per JVM:

```json
"defaults": {
  "vars": {
    "jvmArgs": [
      "-Xms32m",
      "-Xmx256m",
      "-XX:+UseSerialGC",
      "-XX:TieredStopAtLevel=1",
      "-XX:MaxMetaspaceSize=128m",
      "-XX:ReservedCodeCacheSize=48m",
      "-Xss256k",
      "-XX:+UseCompactObjectHeaders",
      "-XX:NativeMemoryTracking=summary"
    ]
  }
}
```

What each flag does:
- **`-Xms32m -Xmx256m`**: Starts with a small heap and caps heap growth. Override heavier `Service`s individually in `services.<name>.vars.jvmArgs`.
- **`-XX:+UseSerialGC`**: Eliminates multi-threaded GC structures and worker threads (often saving 50–100 MB native memory per JVM) and returns unused heap to the OS aggressively.
- **`-XX:TieredStopAtLevel=1`**: Uses the C1 JIT compiler only (disabling C2). Cuts CodeCache usage, avoids C2 compilation spikes, and speeds up local startup.
- **`-XX:MaxMetaspaceSize=128m` / `-XX:ReservedCodeCacheSize=48m`**: Caps native class metadata and JIT code caches.
- **`-Xss256k`**: Shrinks per-thread stack size from the default 1 MB to 256 KB.
- **`-XX:+UseCompactObjectHeaders`** *(Java 24+/25 only)*: Reduces object headers from 12–16 bytes to 8 bytes (~10–20% heap reduction). Omit on JDK 21 or earlier.
- **`-XX:NativeMemoryTracking=summary`** *(optional)*: Enables `jcmd <pid> VM.native_memory summary` for inspecting Heap vs. Class/Metaspace vs. Thread vs. Code memory.

---

## 3. Spring Boot Environment Variables (`runtimes.java.env`)

Spring Boot's relaxed binding maps uppercase environment variables directly to properties and gives them **higher precedence than `application.yaml`** inside the JAR—so no `Repository` files need to be modified:

```json
"runtimes": {
  "java": {
    "command": ["java", "{{jvmArgs}}", "-Dserver.port={{port}}", "-jar", "{{artifact}}"],
    "healthPath": "/actuator/health",
    "env": {
      "SPRING_MAIN_LAZY_INITIALIZATION": "true",
      "SERVER_TOMCAT_THREADS_MAX": "10",
      "SERVER_TOMCAT_THREADS_MIN_SPARE": "2",
      "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "3",
      "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE": "1",
      "SPRING_JMX_ENABLED": "false"
    }
  }
}
```

### Caveats & Per-Service Overrides

- **`SPRING_MAIN_LAZY_INITIALIZATION=true`**: Saves significant startup time and memory by instantiating beans on demand, but can defer startup-time bean errors until first request and may delay `@Scheduled` tasks or message listeners.
- **Overriding individual `Service`s**: `procomp` applies `service.env` over `runtime.env`, and `service.vars` over `defaults.vars`. If one `Service` needs eager initialization or more heap:

```json
"services": {
  "billing": {
    "vars": {
      "jvmArgs": ["-Xms64m", "-Xmx512m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:NativeMemoryTracking=summary"]
    },
    "env": {
      "SPRING_MAIN_LAZY_INITIALIZATION": "false"
    }
  }
}
```

---

## 4. Measuring Before & After with `procomp`

1. **Estate overview:** Run `procomp status` (or `procomp status --json`) to see per-`Service` `RSS`, `Gateway` `RSS`, and `Total RSS` across your running estate.
2. **Per-Service deep dive:** Run `procomp inspect <service>` to see the `Process`'s `RSS` and copy-pasteable `jcmd <pid> ...` diagnostic commands (`GC.heap_info`, `VM.flags`, and `VM.native_memory summary`).
