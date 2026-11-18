# `procomp` Unified Log Viewer Design

Most services in a modern Spring Boot stack emit structured JSON logs (Logstash Logback Encoder, ECS, etc.), while infrastructure tools (ActiveMQ, WireMock, or legacy services) often emit plain text. `procomp` keeps raw `.log` files untouched on disk and normalizes both formats at read time.

---

## 1. Ingestion & Performance

* **Incremental Byte-Offset Tailer (`FileStream`):**
  Each service tracks its last-read byte offset and a bounded in-memory ring buffer (e.g., last 500 parsed entries). On each refresh tick, only newly appended bytes are read and parsed. If `FileInfo.Length < LastOffset` (log truncation on restart), the offset resets to `0` and the ring buffer clears.
* **Fast .NET JSON Parsing:**
  Avoids `ConvertFrom-Json` in the hot path; uses `[System.Web.Script.Serialization.JavaScriptSerializer]` (PowerShell 5.1+) or `[System.Text.Json.JsonDocument]` (PowerShell 7+).

---

## 2. Field Normalization & `logMapping`

### Built-in Smart Alias Chain
1. **Timestamp:** `@timestamp` → `timestamp` → `time` (ISO-8601 UTC/offset converted to `LocalDateTime` so merged logs sort accurately against local plain-text logs)
2. **Level:** `level` → `log.level` → `severity`
3. **Logger:** `logger_name` → `log.logger` → `logger` (compact package abbreviation, e.g. `c.m.o.OrderController`)
4. **Message:** `message` → `msg`
5. **Trace / Correlation ID:** `traceId` → `trace.id` → `correlationId` → `mdc.traceId`
6. **Stack Trace:** `stack_trace` → `error.stack_trace` → `exception`

### Optional `logMapping` Override
Services with custom JSON schemas can define `logMapping` in `services.json` (either globally or per service):

```json
"logMapping": {
  "timestamp": "ts",
  "level": "lvl",
  "message": "event",
  "traceId": "reqId"
}
```

---

## 3. Stack Trace Folding & Display Format

* **Compact 1-Line Default:**
  `HH:mm:ss.fff  LEVEL [service] [traceId] --- message [+14 stack lines]`
* **Plain-Text Folding:** Consecutive `\tat ...` and `Caused by: ...` lines in plain-text logs are folded onto the preceding log entry just like JSON `stack_trace` fields.
* **Expand/Collapse (`z` or `Enter`):** Toggles inline stack-trace expansion in the log pane.

---

## 4. Interactive Controls

| Key | Action |
| :--- | :--- |
| `a` / `Tab` | Toggle selected service vs. `[ALL]` merged chronological stream |
| `e` | Toggle `WARN` / `ERROR` only filter |
| `v` | Toggle Unified/Normalized vs. Raw log lines |
| `z` / `Enter` | Expand / collapse multi-line stack traces |
| `/` | Live text / `traceId` filter |
| `Space` | Pause / resume auto-scroll |
| `c` | Clear display buffer (resets view without deleting the `.log` file) |
