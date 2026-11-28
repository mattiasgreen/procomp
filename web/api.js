/**
 * Thin ES6 HTTP & SSE client for the procomp Web UI API.
 * Contains zero domain logic: dispatches intents and returns raw server payloads.
 */

export class ApiError extends Error {
  /**
   * @param {number} status
   * @param {{ code?: string, message?: string, details?: Record<string, string> }} error
   * @param {string | null} cliCommand
   */
  constructor(status, error, cliCommand = null) {
    super(error?.message || `Request failed with HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = error?.code || `HTTP_${status}`;
    this.details = error?.details || {};
    this.cliCommand = cliCommand;
  }
}

async function requestJson(url, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  const isMutating = method !== 'GET' && method !== 'HEAD';
  let response;
  try {
    response = await fetch(url, {
      cache: 'no-store',
      ...options,
      headers: {
        Accept: 'application/json',
        ...(isMutating ? { 'X-Procomp-Request': '1' } : {}),
        ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(options.headers || {}),
      },
    });
  } catch (err) {
    throw new ApiError(
      0,
      {
        code: 'NETWORK_ERROR',
        message: 'Unable to reach local procomp Web UI server.',
        details: { cause: err instanceof Error ? err.message : String(err) },
      },
      null,
    );
  }

  let payload = null;
  const text = await response.text();
  if (text.length > 0) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = null;
    }
  }

  if (!response.ok || (payload && payload.error)) {
    const errObj = payload?.error || {
      code: `HTTP_${response.status}`,
      message: response.statusText || `Request failed with HTTP ${response.status}`,
      details: {},
    };
    throw new ApiError(response.status, errObj, payload?.cliCommand ?? null);
  }

  return payload;
}

function parseJsonSafe(raw) {
  if (typeof raw !== 'string' || raw.length === 0) {
    return null;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function fetchStatus() {
  return requestJson('/api/status');
}

export function fetchInspect(serviceName) {
  return requestJson(`/api/services/${encodeURIComponent(serviceName)}/inspect`);
}

export function updateRoutes(services, route) {
  return requestJson('/api/routes', {
    method: 'PUT',
    body: JSON.stringify({ services, route }),
  });
}

export function useProfile(profileName) {
  return requestJson(`/api/profiles/${encodeURIComponent(profileName)}/use`, {
    method: 'POST',
  });
}

export function startServices(services = [], { detach = false, timeoutSeconds = 30 } = {}) {
  return requestJson('/api/services/start', {
    method: 'POST',
    body: JSON.stringify({ services, detach, timeoutSeconds }),
  });
}

export function stopServices(services = [], { all = false, killConflict = false } = {}) {
  return requestJson('/api/services/stop', {
    method: 'POST',
    body: JSON.stringify({ services, all, killConflict }),
  });
}

export function buildService(serviceName, { force = false } = {}) {
  return requestJson(`/api/services/${encodeURIComponent(serviceName)}/build`, {
    method: 'POST',
    body: JSON.stringify({ force }),
  });
}

export function cancelBuild(serviceName) {
  return requestJson(`/api/services/${encodeURIComponent(serviceName)}/build/cancel`, {
    method: 'POST',
  });
}

export function startGateway({ detach = false, timeoutSeconds = 30 } = {}) {
  return requestJson('/api/gateway/start', {
    method: 'POST',
    body: JSON.stringify({ detach, timeoutSeconds }),
  });
}

export function stopGateway() {
  return requestJson('/api/gateway/stop', {
    method: 'POST',
  });
}

export function restartGateway({ detach = false, timeoutSeconds = 30 } = {}) {
  return requestJson('/api/gateway/restart', {
    method: 'POST',
    body: JSON.stringify({ detach, timeoutSeconds }),
  });
}

export function reconcileGateway() {
  return requestJson('/api/gateway/reconcile', {
    method: 'POST',
  });
}

export function fetchServiceLogs(serviceName, { lines = 100, err = false, build = false } = {}) {
  const params = new URLSearchParams({
    lines: String(lines),
    err: err ? 'true' : 'false',
    build: build ? 'true' : 'false',
  });
  return requestJson(`/api/services/${encodeURIComponent(serviceName)}/logs?${params.toString()}`);
}

export function fetchGatewayLogs({ lines = 100, err = false } = {}) {
  const params = new URLSearchParams({
    lines: String(lines),
    err: err ? 'true' : 'false',
  });
  return requestJson(`/api/gateway/logs?${params.toString()}`);
}

/**
 * Opens an SSE stream via `POST /api/services/start/stream` using `fetch()` + `ReadableStream`.
 *
 * @param {string[]} services Empty array starts all LOCAL-routed Services.
 * @param {{
 *   timeoutSeconds?: number,
 *   onMeta?: (payload: { cliCommand?: string }) => void,
 *   onProgress?: (payload: { type: string, target: string, message: string }) => void,
 *   onDone?: (payload: { data: unknown, cliCommand?: string }) => void,
 *   onError?: (err: ApiError) => void,
 * }} handlers
 */
export function streamStartServices(services = [], handlers = {}) {
  return streamLifecycleEndpoint('/api/services/start/stream', 'Start', services, handlers);
}

export function streamRestartServices(services = [], handlers = {}) {
  return streamLifecycleEndpoint('/api/services/restart/stream', 'Restart', services, handlers);
}

function streamLifecycleEndpoint(endpoint, label, services = [], handlers = {}) {
  const timeoutSeconds = handlers.timeoutSeconds ?? 30;
  const controller = new AbortController();
  let closed = false;

  const close = () => {
    if (!closed) {
      closed = true;
      controller.abort();
    }
  };

  const dispatchSseEvent = (eventName, dataText) => {
    const payload = parseJsonSafe(dataText);
    if (eventName === 'meta') {
      if (payload && handlers.onMeta) {
        handlers.onMeta(payload);
      }
      return;
    }
    if (eventName === 'progress') {
      if (payload && handlers.onProgress) {
        handlers.onProgress(payload);
      }
      return;
    }
    if (eventName === 'done') {
      close();
      if (handlers.onDone) {
        handlers.onDone(payload || {});
      }
      return;
    }
    if (eventName === 'error') {
      close();
      if (handlers.onError) {
        if (payload?.error) {
          handlers.onError(new ApiError(500, payload.error, payload.cliCommand ?? null));
        } else {
          handlers.onError(
            new ApiError(
              0,
              {
                code: 'STREAM_DISCONNECTED',
                message: `${label} progress stream disconnected unexpectedly.`,
                details: {},
              },
              null,
            ),
          );
        }
      }
    }
  };

  (async () => {
    let response;
    try {
      response = await fetch(endpoint, {
        method: 'POST',
        cache: 'no-store',
        signal: controller.signal,
        headers: {
          Accept: 'text/event-stream',
          'Content-Type': 'application/json',
          'X-Procomp-Request': '1',
        },
        body: JSON.stringify({ services, detach: false, timeoutSeconds }),
      });
    } catch (err) {
      if (closed) {
        return;
      }
      close();
      if (handlers.onError) {
        handlers.onError(
          new ApiError(
            0,
            {
              code: 'NETWORK_ERROR',
              message: 'Unable to reach local procomp Web UI server.',
              details: { cause: err instanceof Error ? err.message : String(err) },
            },
            null,
          ),
        );
      }
      return;
    }

    if (!response.ok || !response.body) {
      const text = await response.text().catch(() => '');
      const payload = parseJsonSafe(text);
      close();
      if (handlers.onError) {
        const errObj = payload?.error || {
          code: `HTTP_${response.status}`,
          message: response.statusText || `Request failed with HTTP ${response.status}`,
          details: {},
        };
        handlers.onError(new ApiError(response.status, errObj, payload?.cliCommand ?? null));
      }
      return;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let currentEvent = 'message';
    let dataLines = [];

    const flushEvent = () => {
      if (dataLines.length === 0) {
        currentEvent = 'message';
        return;
      }
      const dataText = dataLines.join('\n');
      const evt = currentEvent;
      currentEvent = 'message';
      dataLines = [];
      dispatchSseEvent(evt, dataText);
    };

    try {
      while (!closed) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        let nlIdx;
        while ((nlIdx = buffer.indexOf('\n')) >= 0) {
          let line = buffer.slice(0, nlIdx);
          buffer = buffer.slice(nlIdx + 1);
          if (line.endsWith('\r')) {
            line = line.slice(0, -1);
          }
          if (line === '') {
            flushEvent();
          } else if (line.startsWith(':')) {
            // heartbeat comment
          } else if (line.startsWith('event:')) {
            currentEvent = line.slice('event:'.length).trim();
          } else if (line.startsWith('data:')) {
            let val = line.slice('data:'.length);
            if (val.startsWith(' ')) {
              val = val.slice(1);
            }
            dataLines.push(val);
          }
        }
      }
      if (!closed) {
        flushEvent();
      }
      if (!closed) {
        close();
        if (handlers.onError) {
          handlers.onError(
            new ApiError(
              0,
              {
                code: 'STREAM_DISCONNECTED',
                message: 'Start progress stream disconnected unexpectedly.',
                details: {},
              },
              null,
            ),
          );
        }
      }
    } catch {
      if (!closed) {
        close();
        if (handlers.onError) {
          handlers.onError(
            new ApiError(
              0,
              {
                code: 'STREAM_DISCONNECTED',
                message: 'Start progress stream disconnected unexpectedly.',
                details: {},
              },
              null,
            ),
          );
        }
      }
    }
  })();

  return { close };
}

/**
 * Opens an SSE stream for `GET /api/services/{name}/logs/stream`.
 *
 * @param {string} serviceName
 * @param {{
 *   lines?: number,
 *   err?: boolean,
 *   onOpen?: () => void,
 *   onMeta?: (payload: { cliCommand?: string }) => void,
 *   onLog?: (payload: { type: string, service: string, stderr: boolean, line: string }) => void,
 *   onError?: (err: ApiError | null) => void,
 * }} handlers
 */
export function streamServiceLogs(serviceName, handlers = {}) {
  const params = new URLSearchParams({
    lines: String(handlers.lines ?? 100),
    err: handlers.err ? 'true' : 'false',
    build: handlers.build ? 'true' : 'false',
  });
  return openLogEventSource(
    `/api/services/${encodeURIComponent(serviceName)}/logs/stream?${params.toString()}`,
    handlers,
  );
}

export function streamGatewayLogs(handlers = {}) {
  const params = new URLSearchParams({
    lines: String(handlers.lines ?? 100),
    err: handlers.err ? 'true' : 'false',
  });
  return openLogEventSource(`/api/gateway/logs/stream?${params.toString()}`, handlers);
}

function openLogEventSource(url, handlers = {}) {
  const eventSource = new EventSource(url);
  let closed = false;

  const close = () => {
    if (!closed) {
      closed = true;
      eventSource.close();
    }
  };

  eventSource.addEventListener('open', () => {
    if (!closed && handlers.onOpen) {
      handlers.onOpen();
    }
  });

  eventSource.addEventListener('meta', (event) => {
    const payload = parseJsonSafe(event.data);
    if (payload && handlers.onMeta) {
      handlers.onMeta(payload);
    }
  });

  eventSource.addEventListener('log', (event) => {
    const payload = parseJsonSafe(event.data);
    if (payload && handlers.onLog) {
      handlers.onLog(payload);
    }
  });

  eventSource.addEventListener('error', (event) => {
    if (closed) {
      return;
    }
    const payload = parseJsonSafe(event.data);
    close();
    if (handlers.onError) {
      if (payload?.error) {
        handlers.onError(new ApiError(500, payload.error, payload.cliCommand ?? null));
      } else {
        handlers.onError(null);
      }
    }
  });

  return { eventSource, close };
}
