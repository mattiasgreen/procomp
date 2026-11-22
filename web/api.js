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
  let response;
  try {
    response = await fetch(url, {
      cache: 'no-store',
      ...options,
      headers: {
        Accept: 'application/json',
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

export function reconcileGateway() {
  return requestJson('/api/gateway/reconcile', {
    method: 'POST',
  });
}

export function fetchServiceLogs(serviceName, { lines = 100, err = false } = {}) {
  const params = new URLSearchParams({
    lines: String(lines),
    err: err ? 'true' : 'false',
  });
  return requestJson(`/api/services/${encodeURIComponent(serviceName)}/logs?${params.toString()}`);
}

/**
 * Opens an SSE stream for `GET /api/services/start/stream`.
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
  const params = new URLSearchParams();
  if (services.length > 0) {
    params.set('services', services.join(','));
  }
  const timeoutSeconds = handlers.timeoutSeconds ?? 30;
  params.set('timeout', String(timeoutSeconds));

  const url = `/api/services/start/stream?${params.toString()}`;
  const eventSource = new EventSource(url);
  let closed = false;

  const close = () => {
    if (!closed) {
      closed = true;
      eventSource.close();
    }
  };

  eventSource.addEventListener('meta', (event) => {
    const payload = parseJsonSafe(event.data);
    if (payload && handlers.onMeta) {
      handlers.onMeta(payload);
    }
  });

  eventSource.addEventListener('progress', (event) => {
    const payload = parseJsonSafe(event.data);
    if (payload && handlers.onProgress) {
      handlers.onProgress(payload);
    }
  });

  eventSource.addEventListener('done', (event) => {
    const payload = parseJsonSafe(event.data);
    close();
    if (handlers.onDone) {
      handlers.onDone(payload || {});
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
  });

  return { eventSource, close };
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
  });
  const url = `/api/services/${encodeURIComponent(serviceName)}/logs/stream?${params.toString()}`;
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
