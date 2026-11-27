import {
  ApiError,
  buildService,
  cancelBuild,
  fetchGatewayLogs,
  fetchInspect,
  fetchServiceLogs,
  fetchStatus,
  reconcileGateway,
  startGateway,
  stopGateway,
  stopServices,
  streamGatewayLogs,
  streamServiceLogs,
  streamStartServices,
  updateRoutes,
  useProfile,
} from './api.js';

const POLL_INTERVAL_MS = 2500;
const INITIAL_LOG_LINES = 200;
const OLDER_LOG_PAGE_LINES = 200;
const MAX_LOG_LINES = 5000;
const MAX_PAUSED_LOG_LINES = 50000;
const AUTOSCROLL_REENGAGE_THRESHOLD_PX = 8;
const LOAD_OLDER_TOP_THRESHOLD_PX = 48;
const MAX_CLI_HISTORY = 500;
const STORAGE_KEY_AUTO_RECONCILE = 'procomp.gatewayAutoReconcile';

const state = {
  estate: null,
  profiles: [],
  configPath: '',
  gatewayAutoReconcile: true,
  pollTimer: null,
  statusInFlightCount: 0,
  statusSeq: 0,
  hasExplicitCliCommand: false,
  hasRecordedInitialCliCommand: false,
  cliHistory: [],
  cliHistoryOpen: false,
  cliHistorySeq: 0,
  pollErrorActive: false,
  pendingRoutes: new Set(),
  pendingStops: new Set(),
  pendingProfiles: new Set(),
  drawer: {
    open: false,
    targetType: 'service',
    service: null,
    tab: 'inspect',
  },
  logs: {
    err: false,
    build: false,
    autoScroll: true,
    lineCount: 0,
    stream: null,
    streamGen: 0,
    startOffset: 0,
    hasOlder: false,
    loadingOlder: false,
    truncated: false,
    programmaticScroll: false,
    pointerDown: false,
    lastTouchY: null,
  },
  activeStarts: new Map(),
  startSeq: 0,
  conflictService: null,
};

const dom = {
  projectName: document.getElementById('project-name'),
  configPath: document.getElementById('config-path'),
  pollIndicator: document.getElementById('poll-indicator'),
  pollLabel: document.getElementById('poll-label'),
  lastUpdated: document.getElementById('last-updated'),
  btnRefresh: document.getElementById('btn-refresh'),

  errorBanner: document.getElementById('error-banner'),
  errorCode: document.getElementById('error-code'),
  errorMessage: document.getElementById('error-message'),
  errorDetails: document.getElementById('error-details'),
  btnDismissError: document.getElementById('btn-dismiss-error'),

  progressStrip: document.getElementById('progress-strip'),
  progressList: document.getElementById('progress-list'),

  gatewayType: document.getElementById('gateway-type'),
  gatewayEndpoint: document.getElementById('gateway-endpoint'),
  gatewayTelemetry: document.getElementById('gateway-telemetry'),
  gatewayOwner: document.getElementById('gateway-owner'),
  gatewayHealth: document.getElementById('gateway-health'),
  gatewaySync: document.getElementById('gateway-sync'),
  gatewayPid: document.getElementById('gateway-pid'),
  gatewayUptime: document.getElementById('gateway-uptime'),
  gatewayActions: document.getElementById('gateway-actions'),
  gatewayUnconfigured: document.getElementById('gateway-unconfigured'),
  chkGatewayAutoReconcile: document.getElementById('chk-gateway-auto-reconcile'),
  btnGatewayReconcile: document.getElementById('btn-gateway-reconcile'),
  btnGatewayStart: document.getElementById('btn-gateway-start'),
  btnGatewayStop: document.getElementById('btn-gateway-stop'),
  btnGatewayLogs: document.getElementById('btn-gateway-logs'),
  gatewayDriftAlert: document.getElementById('gateway-drift-alert'),
  gatewayDriftList: document.getElementById('gateway-drift-list'),
  btnDriftReconcile: document.getElementById('btn-drift-reconcile'),

  profileList: document.getElementById('profile-list'),
  btnStartLocal: document.getElementById('btn-start-local'),
  btnStopManaged: document.getElementById('btn-stop-managed'),

  servicesSummary: document.getElementById('services-summary'),
  servicesTbody: document.getElementById('services-tbody'),

  detailDrawer: document.getElementById('detail-drawer'),
  drawerTargetLabel: document.getElementById('drawer-target-label'),
  drawerServiceName: document.getElementById('drawer-service-name'),
  tabInspect: document.getElementById('tab-inspect'),
  tabLogs: document.getElementById('tab-logs'),
  btnDrawerClose: document.getElementById('btn-drawer-close'),
  panelInspect: document.getElementById('panel-inspect'),
  inspectStatusNote: document.getElementById('inspect-status-note'),
  btnInspectRefresh: document.getElementById('btn-inspect-refresh'),
  inspectContent: document.getElementById('inspect-content'),

  panelLogs: document.getElementById('panel-logs'),
  logsSourceGroup: document.getElementById('logs-source-group'),
  btnLogsSourceService: document.getElementById('btn-logs-source-service'),
  btnLogsSourceBuild: document.getElementById('btn-logs-source-build'),
  btnLogsStdout: document.getElementById('btn-logs-stdout'),
  btnLogsStderr: document.getElementById('btn-logs-stderr'),
  logsStreamStatus: document.getElementById('logs-stream-status'),
  logsLineCount: document.getElementById('logs-line-count'),
  chkLogsAutoscroll: document.getElementById('chk-logs-autoscroll'),
  btnLogsClear: document.getElementById('btn-logs-clear'),
  btnLogsReconnect: document.getElementById('btn-logs-reconnect'),
  logsOutput: document.getElementById('logs-output'),

  cliHistoryPanel: document.getElementById('cli-history-panel'),
  cliHistorySummary: document.getElementById('cli-history-summary'),
  cliHistoryEmpty: document.getElementById('cli-history-empty'),
  cliHistoryList: document.getElementById('cli-history-list'),
  btnCopyCliHistory: document.getElementById('btn-copy-cli-history'),
  btnClearCliHistory: document.getElementById('btn-clear-cli-history'),
  btnCloseCliHistory: document.getElementById('btn-close-cli-history'),

  cliCommandEcho: document.getElementById('cli-command-echo'),
  btnCopyCli: document.getElementById('btn-copy-cli'),
  btnCliHistory: document.getElementById('btn-cli-history'),
  cliHistoryCount: document.getElementById('cli-history-count'),

  conflictDialog: document.getElementById('conflict-dialog'),
  conflictServiceName: document.getElementById('conflict-service-name'),
  conflictLoading: document.getElementById('conflict-loading'),
  conflictError: document.getElementById('conflict-error'),
  conflictDetails: document.getElementById('conflict-details'),
  conflictPort: document.getElementById('conflict-port'),
  conflictPid: document.getElementById('conflict-pid'),
  conflictCmdline: document.getElementById('conflict-cmdline'),
  conflictParents: document.getElementById('conflict-parents'),
  conflictCliPreview: document.getElementById('conflict-cli-preview'),
  btnConflictCancel: document.getElementById('btn-conflict-cancel'),
  btnConflictConfirm: document.getElementById('btn-conflict-confirm'),
};

/* ---------- Pure Display Formatters ---------- */

function el(tag, className = '', text = null) {
  const node = document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (text !== null && text !== undefined) {
    node.textContent = String(text);
  }
  return node;
}

function formatUptime(seconds) {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) {
    return '—';
  }
  const total = Math.max(0, Math.floor(seconds));
  if (total < 60) {
    return `${total}s`;
  }
  const minutes = Math.floor(total / 60);
  const remSec = total % 60;
  if (minutes < 60) {
    return `${minutes}m ${remSec}s`;
  }
  const hours = Math.floor(minutes / 60);
  const remMin = minutes % 60;
  if (hours < 24) {
    return `${hours}h ${remMin}m`;
  }
  const days = Math.floor(hours / 24);
  const remHours = hours % 24;
  return `${days}d ${remHours}h`;
}

function formatBytes(bytes) {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) {
    return '—';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const kb = bytes / 1024;
  if (kb < 1024) {
    return `${kb.toFixed(1)} KB`;
  }
  const mb = kb / 1024;
  if (mb < 1024) {
    return `${mb.toFixed(1)} MB`;
  }
  return `${(mb / 1024).toFixed(2)} GB`;
}

function healthBadgeClass(health) {
  switch (health) {
    case 'HEALTHY':
      return 'badge-healthy';
    case 'STARTING':
      return 'badge-starting';
    case 'CRASHED':
      return 'badge-crashed';
    case 'STOPPED':
      return 'badge-stopped';
    default:
      return 'badge-neutral';
  }
}

function ownerBadgeClass(owner) {
  switch (owner) {
    case 'MANAGED':
      return 'badge-managed';
    case 'UNMANAGED':
      return 'badge-unmanaged';
    case 'CONFLICT':
      return 'badge-conflict';
    case 'NONE':
      return 'badge-none';
    default:
      return 'badge-neutral';
  }
}

function syncBadgeClass(syncStatus) {
  switch (syncStatus) {
    case 'IN_SYNC':
      return 'badge-in-sync';
    case 'DRIFTED':
      return 'badge-drifted';
    case 'OFFLINE':
      return 'badge-offline';
    default:
      return 'badge-neutral';
  }
}

function formatClockTime(date) {
  return date.toLocaleTimeString([], { hour12: false });
}

/* ---------- CLI Command Echo, History & Error Banner ---------- */

function setCliCommand(command, { explicit = true, recordHistory = true, errorCode = null } = {}) {
  if (!command) {
    return null;
  }
  if (!explicit && state.hasExplicitCliCommand) {
    return null;
  }
  if (explicit) {
    state.hasExplicitCliCommand = true;
  }
  dom.cliCommandEcho.textContent = command;
  dom.cliCommandEcho.title = command;

  const shouldRecord = recordHistory && (explicit || !state.hasRecordedInitialCliCommand);
  if (!shouldRecord) {
    return null;
  }

  state.hasRecordedInitialCliCommand = true;
  const entry = {
    id: ++state.cliHistorySeq,
    command,
    timestamp: formatClockTime(new Date()),
    errorCode,
  };
  state.cliHistory.push(entry);
  if (state.cliHistory.length > MAX_CLI_HISTORY) {
    state.cliHistory.splice(0, state.cliHistory.length - MAX_CLI_HISTORY);
  }
  renderCliHistory();
  return entry;
}

function renderCliHistory() {
  const count = state.cliHistory.length;
  dom.cliHistoryCount.textContent = String(count);
  dom.cliHistorySummary.textContent = `${count} ${count === 1 ? 'command' : 'commands'}`;
  dom.btnCopyCliHistory.disabled = count === 0;
  dom.btnClearCliHistory.disabled = count === 0;

  if (!state.cliHistoryOpen) {
    return;
  }

  dom.cliHistoryList.replaceChildren();
  if (count === 0) {
    dom.cliHistoryEmpty.hidden = false;
    dom.cliHistoryList.hidden = true;
    return;
  }

  dom.cliHistoryEmpty.hidden = true;
  dom.cliHistoryList.hidden = false;

  for (let i = count - 1; i >= 0; i -= 1) {
    const item = state.cliHistory[i];
    const li = el('li', 'cli-history-item');
    li.append(
      el('span', 'cli-history-index mono muted', `#${i + 1}`),
      el('span', 'cli-history-time mono muted', item.timestamp),
    );
    const cmdEl = el('code', 'cli-history-cmd', item.command);
    cmdEl.title = item.command;
    li.append(cmdEl);

    if (item.errorCode) {
      const errBadge = el('span', 'badge badge-danger', 'ERR');
      errBadge.title = item.errorCode;
      li.append(errBadge);
    }

    const copyBtn = el('button', 'btn btn-secondary btn-xs cli-history-copy', 'Copy');
    copyBtn.type = 'button';
    copyBtn.title = 'Copy command';
    copyBtn.addEventListener('click', () => copyTextToClipboard(item.command, copyBtn, 'Copy'));
    li.append(copyBtn);

    dom.cliHistoryList.append(li);
  }
}

function setCliHistoryOpen(open) {
  state.cliHistoryOpen = open;
  dom.cliHistoryPanel.hidden = !open;
  dom.btnCliHistory.setAttribute('aria-expanded', String(open));
  dom.btnCliHistory.classList.toggle('active', open);
  if (open) {
    renderCliHistory();
  }
}

function clearCliHistory() {
  state.cliHistory = [];
  state.hasRecordedInitialCliCommand = true;
  renderCliHistory();
}

function showError(err, { fromPoll = false, recordCliHistory = true } = {}) {
  state.pollErrorActive = fromPoll;
  const code = err instanceof ApiError ? err.code : 'ERROR';
  const message = err instanceof Error ? err.message : String(err);
  const details = err instanceof ApiError && err.details ? err.details : {};

  if (err instanceof ApiError && err.cliCommand) {
    setCliCommand(err.cliCommand, {
      explicit: !fromPoll,
      recordHistory: recordCliHistory,
      errorCode: code,
    });
  }

  dom.errorCode.textContent = code;
  dom.errorMessage.textContent = message;

  dom.errorDetails.replaceChildren();
  const entries = Object.entries(details);
  if (entries.length > 0) {
    for (const [key, val] of entries) {
      dom.errorDetails.append(el('dt', '', key), el('dd', '', val));
    }
    dom.errorDetails.hidden = false;
  } else {
    dom.errorDetails.hidden = true;
  }

  dom.errorBanner.hidden = false;
}

function clearError() {
  state.pollErrorActive = false;
  dom.errorBanner.hidden = true;
}

/* ---------- Poll Status Indicator ---------- */

function updatePollIndicator(mode, labelText) {
  dom.pollIndicator.dataset.state = mode;
  dom.pollLabel.textContent = labelText;
}

/* ---------- Rendering: Header, Gateway, Profiles, Services ---------- */

function renderHeader() {
  const project = state.estate?.project || 'Project';
  dom.projectName.textContent = project;
  document.title = `${project} — procomp`;
  dom.configPath.textContent = state.configPath || '';
  dom.configPath.title = state.configPath || '';
}

function renderGateway() {
  const gw = state.estate?.gateway;
  if (!gw || !gw.configured) {
    dom.gatewayType.textContent = '';
    dom.gatewayEndpoint.textContent = '';
    dom.gatewayTelemetry.hidden = true;
    dom.gatewayActions.hidden = true;
    dom.gatewayUnconfigured.hidden = false;
    dom.gatewayDriftAlert.hidden = true;
    return;
  }

  dom.gatewayTelemetry.hidden = false;
  dom.gatewayActions.hidden = false;
  dom.gatewayUnconfigured.hidden = true;

  dom.gatewayType.textContent = gw.type ? `[${gw.type}]` : '';
  const endpointParts = [];
  if (gw.port !== null && gw.port !== undefined) {
    endpointParts.push(`:${gw.port}`);
  }
  if (gw.adminUrl) {
    endpointParts.push(gw.adminUrl);
  }
  dom.gatewayEndpoint.textContent = endpointParts.join(' · ');

  const ownerLabel = gw.provenance ? `${gw.owner || 'NONE'} (${gw.provenance})` : (gw.owner || 'NONE');
  dom.gatewayOwner.textContent = ownerLabel;
  dom.gatewayOwner.className = `badge ${ownerBadgeClass(gw.owner)}`;

  dom.gatewayHealth.textContent = gw.health || 'STOPPED';
  dom.gatewayHealth.className = `badge ${healthBadgeClass(gw.health)}`;
  dom.gatewayHealth.title = gw.healthResponse || gw.healthEndpoint || '';

  dom.gatewaySync.textContent = gw.syncStatus || 'OFFLINE';
  dom.gatewaySync.className = `badge ${syncBadgeClass(gw.syncStatus)}`;

  dom.gatewayPid.textContent = gw.pid !== null && gw.pid !== undefined ? `PID ${gw.pid}` : '';
  dom.gatewayUptime.textContent =
    gw.uptimeSeconds !== null && gw.uptimeSeconds !== undefined
      ? `up ${formatUptime(gw.uptimeSeconds)}`
      : '';

  const isDrifted =
    gw.syncStatus === 'DRIFTED' ||
    (Array.isArray(gw.drifted) && gw.drifted.length > 0) ||
    (Array.isArray(gw.staleMappings) && gw.staleMappings.length > 0);

  dom.btnGatewayReconcile.className = isDrifted ? 'btn btn-warning' : 'btn btn-secondary';

  if (gw.owner === 'UNMANAGED') {
    dom.btnGatewayStop.disabled = true;
    dom.btnGatewayStop.title = 'Unmanaged Gateway Process — never killed by procomp';
  } else if (gw.owner === 'NONE' || !gw.owner) {
    dom.btnGatewayStop.disabled = true;
    dom.btnGatewayStop.title = 'Gateway Process is not running';
  } else {
    dom.btnGatewayStop.disabled = false;
    dom.btnGatewayStop.title = 'Stop Managed Gateway Process';
  }

  if (isDrifted) {
    dom.gatewayDriftList.replaceChildren();
    for (const item of gw.drifted || []) {
      const li = el('li');
      li.textContent = `${item.service}: desired ${item.desired}, live ${item.live}`;
      dom.gatewayDriftList.append(li);
    }
    for (const stale of gw.staleMappings || []) {
      const li = el('li');
      const label = stale.service || stale.id || 'unknown';
      const target = stale.targetUrl ? ` -> ${stale.targetUrl}` : '';
      li.textContent = `Stale mapping (${label})${target}`;
      dom.gatewayDriftList.append(li);
    }
    dom.gatewayDriftAlert.hidden = false;
  } else {
    dom.gatewayDriftAlert.hidden = true;
  }
}

function renderProfiles() {
  dom.profileList.replaceChildren();
  if (!state.profiles || state.profiles.length === 0) {
    dom.profileList.append(el('span', 'muted', 'No Profiles configured'));
    return;
  }

  for (const profile of state.profiles) {
    const locals = Array.isArray(profile.local) ? profile.local : [];
    const btn = el('button', 'profile-pill');
    btn.type = 'button';
    btn.title = locals.length > 0 ? `LOCAL: ${locals.join(', ')}` : 'All Services REMOTE';
    btn.append(
      el('span', '', profile.name),
      el('span', 'profile-pill-count', `${locals.length} LOCAL`),
    );
    btn.addEventListener('click', () => handleUseProfile(profile.name, btn));
    dom.profileList.append(btn);
  }
}

function isServiceStarting(serviceName) {
  for (const entry of state.activeStarts.values()) {
    if (entry.services.length === 0 || entry.services.includes(serviceName)) {
      return true;
    }
  }
  return false;
}

function renderServices() {
  const services = state.estate?.services || [];
  dom.servicesTbody.replaceChildren();

  const localCount = services.filter((s) => s.route === 'LOCAL').length;
  const healthyCount = services.filter((s) => s.health === 'HEALTHY').length;
  dom.servicesSummary.textContent = `${services.length} total · ${localCount} LOCAL · ${healthyCount} HEALTHY`;

  if (services.length === 0) {
    const tr = el('tr');
    const td = el('td', 'empty-cell', 'No Services defined in this Project.');
    td.colSpan = 9;
    tr.append(td);
    dom.servicesTbody.append(tr);
    return;
  }

  for (const row of services) {
    const tr = el('tr');
    if (state.drawer.open && state.drawer.service === row.service) {
      tr.classList.add('row-selected');
    }
    if (row.owner === 'CONFLICT') {
      tr.classList.add('row-conflict');
    }

    // SERVICE
    const tdName = el('td', 'service-name-cell', row.service);

    // ROUTE toggle (LOCAL | REMOTE)
    const tdRoute = el('td');
    const routeToggle = el('div', 'segmented-control');
    routeToggle.setAttribute('role', 'group');
    routeToggle.setAttribute('aria-label', `Route for ${row.service}`);

    const routePending = state.pendingRoutes.has(row.service);
    const btnLocal = el('button', `seg-btn route-local${row.route === 'LOCAL' ? ' active' : ''}`, 'LOCAL');
    btnLocal.type = 'button';
    btnLocal.disabled = routePending;
    btnLocal.dataset.service = row.service;
    btnLocal.dataset.action = 'route-local';
    btnLocal.setAttribute('aria-pressed', String(row.route === 'LOCAL'));
    btnLocal.addEventListener('click', () => {
      if (row.route !== 'LOCAL') {
        handleRouteToggle(row.service, 'LOCAL', routeToggle);
      }
    });

    const btnRemote = el('button', `seg-btn route-remote${row.route === 'REMOTE' ? ' active' : ''}`, 'REMOTE');
    btnRemote.type = 'button';
    btnRemote.disabled = routePending;
    btnRemote.dataset.service = row.service;
    btnRemote.dataset.action = 'route-remote';
    btnRemote.setAttribute('aria-pressed', String(row.route === 'REMOTE'));
    btnRemote.addEventListener('click', () => {
      if (row.route !== 'REMOTE') {
        handleRouteToggle(row.service, 'REMOTE', routeToggle);
      }
    });

    routeToggle.append(btnLocal, btnRemote);
    tdRoute.append(routeToggle);

    // OWNER + provenance
    const tdOwner = el('td');
    tdOwner.append(el('span', `badge ${ownerBadgeClass(row.owner)}`, row.owner));
    if (row.provenance) {
      tdOwner.append(el('span', 'provenance-tag', row.provenance));
    }

    // HEALTH
    const tdHealth = el('td');
    const healthBadge = el('span', `badge ${healthBadgeClass(row.health)}`, row.health);
    const healthTip = [row.healthEndpoint, row.healthNote].filter(Boolean).join(' — ');
    if (healthTip) {
      healthBadge.title = healthTip;
    }
    tdHealth.append(healthBadge);

    // BUILD
    const tdBuild = el('td');
    const buildState = row.buildRun?.displayState || 'NO_BUILDER';
    const buildBadge = el('span', `badge ${buildBadgeClass(buildState)}`, buildState);
    if (row.buildRun?.reason) {
      buildBadge.title = row.buildRun.reason;
    }
    tdBuild.append(buildBadge);

    // PORT, PID, UPTIME
    const tdPort = el('td', 'mono', row.port ?? '—');
    const tdPid = el('td', 'mono', row.pid ?? '—');
    const tdUptime = el('td', 'mono', formatUptime(row.uptimeSeconds));

    // STALE
    const tdStale = el('td');
    if (row.stale) {
      const staleBadge = el('span', 'badge badge-stale', 'STALE');
      staleBadge.title = 'Managed Process was started before the latest Artifact was built';
      tdStale.append(staleBadge);
    } else {
      tdStale.append(el('span', 'muted', '—'));
    }

    // ACTIONS
    const tdActions = el('td', 'col-actions');
    const actionsWrap = el('div', 'row-actions');

    if (buildState === 'RUNNING') {
      const btnCancelBuild = el('button', 'btn btn-danger btn-xs', 'Cancel Build');
      btnCancelBuild.type = 'button';
      btnCancelBuild.dataset.service = row.service;
      btnCancelBuild.dataset.action = 'cancel-build';
      btnCancelBuild.title = `Cancel active Build Run for ${row.service}`;
      btnCancelBuild.addEventListener('click', () => handleCancelBuild(row.service, btnCancelBuild));
      actionsWrap.append(btnCancelBuild);
    } else if (buildState === 'UNKNOWN') {
      const btnForceBuild = el('button', 'btn btn-secondary btn-xs', 'Force Build');
      btnForceBuild.type = 'button';
      btnForceBuild.dataset.service = row.service;
      btnForceBuild.dataset.action = 'build';
      btnForceBuild.title = `Clear UNKNOWN Build Run and rebuild ${row.service}`;
      btnForceBuild.addEventListener('click', () => handleBuildService(row.service, true, btnForceBuild));
      actionsWrap.append(btnForceBuild);
    } else if (buildState !== 'NO_BUILDER') {
      const btnBuild = el('button', 'btn btn-secondary btn-xs', 'Build');
      btnBuild.type = 'button';
      btnBuild.dataset.service = row.service;
      btnBuild.dataset.action = 'build';
      btnBuild.title = `Build ${row.service}`;
      btnBuild.addEventListener('click', () => handleBuildService(row.service, false, btnBuild));
      actionsWrap.append(btnBuild);
    }

    const starting = isServiceStarting(row.service);
    const btnStart = el('button', 'btn btn-secondary btn-xs', starting ? 'Starting…' : 'Start');
    btnStart.type = 'button';
    btnStart.disabled = starting;
    btnStart.dataset.service = row.service;
    btnStart.dataset.action = 'start';
    btnStart.title = `Start ${row.service}`;
    btnStart.addEventListener('click', () => handleStartServices([row.service]));
    actionsWrap.append(btnStart);

    if (row.owner === 'CONFLICT') {
      const btnKillConflict = el('button', 'btn btn-danger btn-xs', 'Kill Conflict…');
      btnKillConflict.type = 'button';
      btnKillConflict.dataset.service = row.service;
      btnKillConflict.dataset.action = 'kill-conflict';
      btnKillConflict.title = `Inspect and kill conflicting Process on port ${row.port ?? ''}`;
      btnKillConflict.addEventListener('click', () => openConflictDialog(row.service));
      actionsWrap.append(btnKillConflict);
    } else {
      const stopPending = state.pendingStops.has(row.service);
      const btnStop = el('button', 'btn btn-secondary btn-xs', 'Stop');
      btnStop.type = 'button';
      btnStop.dataset.service = row.service;
      btnStop.dataset.action = 'stop';
      if (row.owner === 'MANAGED') {
        btnStop.disabled = stopPending;
        btnStop.title = `Stop Managed Process for ${row.service}`;
        btnStop.addEventListener('click', () => handleStopServices([row.service], btnStop));
      } else if (row.owner === 'UNMANAGED') {
        btnStop.disabled = true;
        btnStop.title = 'Unmanaged Process — never killed by procomp';
      } else {
        btnStop.disabled = true;
        btnStop.title = 'No running Process';
      }
      actionsWrap.append(btnStop);
    }

    const btnInspect = el('button', 'btn btn-secondary btn-xs', 'Inspect');
    btnInspect.type = 'button';
    btnInspect.dataset.service = row.service;
    btnInspect.dataset.action = 'inspect';
    btnInspect.title = `Inspect ${row.service}`;
    btnInspect.addEventListener('click', () => openDrawer(row.service, 'inspect'));
    actionsWrap.append(btnInspect);

    const btnLogs = el('button', 'btn btn-secondary btn-xs', 'Logs');
    btnLogs.type = 'button';
    btnLogs.dataset.service = row.service;
    btnLogs.dataset.action = 'logs';
    btnLogs.title = `Stream live Logs for ${row.service}`;
    btnLogs.addEventListener('click', () => openDrawer(row.service, 'logs'));
    actionsWrap.append(btnLogs);

    tdActions.append(actionsWrap);

    tr.append(tdName, tdRoute, tdOwner, tdHealth, tdBuild, tdPort, tdPid, tdUptime, tdStale, tdActions);
    dom.servicesTbody.append(tr);
  }
}

function renderProgressStrip() {
  if (state.activeStarts.size === 0) {
    dom.progressStrip.hidden = true;
    dom.progressList.replaceChildren();
    return;
  }

  dom.progressList.replaceChildren();
  for (const entry of state.activeStarts.values()) {
    const li = el('li');
    li.append(
      el('span', 'progress-target', `[${entry.target || entry.label}]`),
      el('span', '', entry.message),
    );
    dom.progressList.append(li);
  }
  dom.progressStrip.hidden = false;
}

/* ---------- Polling & Discovery ---------- */

async function refreshStatus({ explicit = false } = {}) {
  if (state.statusInFlightCount > 0 && !explicit) {
    return;
  }
  const reqSeq = ++state.statusSeq;
  state.statusInFlightCount += 1;
  if (explicit) {
    dom.btnRefresh.disabled = true;
  }

  try {
    const payload = await fetchStatus();
    if (reqSeq !== state.statusSeq) {
      return;
    }
    state.estate = payload.data;
    state.profiles = payload.profiles || [];
    state.configPath = payload.configPath || '';

    if (payload.cliCommand) {
      setCliCommand(payload.cliCommand, { explicit });
    }

    if (state.pollErrorActive) {
      clearError();
    }

    const activeEl = document.activeElement;
    const focusKey =
      activeEl instanceof HTMLElement &&
      dom.servicesTbody.contains(activeEl) &&
      activeEl.dataset.service &&
      activeEl.dataset.action
        ? { service: activeEl.dataset.service, action: activeEl.dataset.action }
        : null;

    renderHeader();
    renderGateway();
    renderProfiles();
    renderServices();

    if (focusKey) {
      const targetBtn = Array.from(dom.servicesTbody.querySelectorAll('button')).find(
        (b) => b.dataset.service === focusKey.service && b.dataset.action === focusKey.action,
      );
      if (targetBtn && !targetBtn.disabled) {
        targetBtn.focus({ preventScroll: true });
      }
    }

    dom.lastUpdated.textContent = formatClockTime(new Date());
    if (document.visibilityState === 'visible') {
      updatePollIndicator('live', 'Live');
    } else {
      updatePollIndicator('paused', 'Paused');
    }
  } catch (err) {
    if (reqSeq !== state.statusSeq) {
      return;
    }
    updatePollIndicator('error', 'Error');
    showError(err, { fromPoll: !explicit });
  } finally {
    state.statusInFlightCount = Math.max(0, state.statusInFlightCount - 1);
    if (explicit) {
      dom.btnRefresh.disabled = false;
    }
  }
}

function startPolling() {
  stopPolling();
  if (document.visibilityState !== 'visible') {
    updatePollIndicator('paused', 'Paused (tab hidden)');
    return;
  }
  updatePollIndicator('live', 'Live');
  state.pollTimer = window.setInterval(() => {
    if (document.visibilityState === 'visible') {
      refreshStatus({ explicit: false });
    }
  }, POLL_INTERVAL_MS);
}

function stopPolling() {
  if (state.pollTimer !== null) {
    window.clearInterval(state.pollTimer);
    state.pollTimer = null;
  }
}

/* ---------- Gateway Auto-Reconcile Preference ---------- */

function loadAutoReconcilePreference() {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY_AUTO_RECONCILE);
    if (stored === 'false') {
      return false;
    }
    if (stored === 'true') {
      return true;
    }
  } catch {
    // Ignore storage access failures and fall back to default.
  }
  return true;
}

function saveAutoReconcilePreference(enabled) {
  try {
    window.localStorage.setItem(STORAGE_KEY_AUTO_RECONCILE, String(enabled));
  } catch {
    // Ignore storage access failures.
  }
}

function canAutoReconcileGateway() {
  const gw = state.estate?.gateway;
  return Boolean(
    state.gatewayAutoReconcile &&
      gw &&
      gw.configured &&
      gw.health === 'HEALTHY',
  );
}

/* ---------- Action Handlers ---------- */

async function runAction(controls, actionFn, { onSettled = null, autoReconcile = false } = {}) {
  const list = Array.from(controls || []).filter(Boolean);
  for (const control of list) {
    control.disabled = true;
  }
  let actionSucceeded = false;
  try {
    const payload = await actionFn();
    actionSucceeded = true;
    if (payload && payload.cliCommand) {
      setCliCommand(payload.cliCommand, { explicit: true });
    }
    if (autoReconcile && canAutoReconcileGateway()) {
      dom.btnGatewayReconcile.disabled = true;
      try {
        const reconcilePayload = await reconcileGateway();
        if (reconcilePayload && reconcilePayload.cliCommand) {
          setCliCommand(reconcilePayload.cliCommand, { explicit: true });
        }
      } finally {
        dom.btnGatewayReconcile.disabled = false;
      }
    }
    if (onSettled) {
      onSettled();
    }
    clearError();
    await refreshStatus({ explicit: false });
  } catch (err) {
    if (onSettled) {
      onSettled();
    }
    showError(err);
    if (actionSucceeded) {
      await refreshStatus({ explicit: false });
    }
  } finally {
    for (const control of list) {
      control.disabled = false;
    }
    renderGateway();
  }
}

function handleRouteToggle(serviceName, targetRoute, toggleGroup) {
  state.pendingRoutes.add(serviceName);
  return runAction(toggleGroup.querySelectorAll('button'), () => updateRoutes([serviceName], targetRoute), {
    onSettled: () => state.pendingRoutes.delete(serviceName),
    autoReconcile: true,
  });
}

function handleUseProfile(profileName, btn) {
  state.pendingProfiles.add(profileName);
  return runAction([btn], () => useProfile(profileName), {
    onSettled: () => state.pendingProfiles.delete(profileName),
    autoReconcile: true,
  });
}

function handleStartServices(services = []) {
  const id = `start-${++state.startSeq}`;
  const label = services.length > 0 ? services.join(', ') : 'LOCAL';
  const entry = {
    label,
    services,
    target: label,
    message: 'Starting…',
    stream: null,
    historyEntry: null,
  };

  entry.stream = streamStartServices(services, {
    timeoutSeconds: 30,
    onMeta(payload) {
      if (payload.cliCommand) {
        entry.historyEntry = setCliCommand(payload.cliCommand, { explicit: true });
      }
    },
    onProgress(payload) {
      entry.target = payload.target || label;
      entry.message = payload.message || 'Starting…';
      renderProgressStrip();
    },
    onDone(payload) {
      if (payload.cliCommand) {
        setCliCommand(payload.cliCommand, {
          explicit: true,
          recordHistory: !entry.historyEntry,
        });
      }
      state.activeStarts.delete(id);
      renderProgressStrip();
      clearError();
      refreshStatus({ explicit: false });
    },
    onError(err) {
      state.activeStarts.delete(id);
      renderProgressStrip();
      if (entry.historyEntry) {
        entry.historyEntry.errorCode = err instanceof ApiError ? err.code : 'ERROR';
        renderCliHistory();
        showError(err, { recordCliHistory: false });
      } else {
        showError(err);
      }
      refreshStatus({ explicit: false });
    },
  });

  state.activeStarts.set(id, entry);
  renderProgressStrip();
  renderServices();
}

function handleStopServices(services = [], triggerBtn = null) {
  for (const s of services) {
    state.pendingStops.add(s);
  }
  return runAction([triggerBtn], () => stopServices(services, { all: false, killConflict: false }), {
    onSettled: () => {
      for (const s of services) {
        state.pendingStops.delete(s);
      }
    },
  });
}

function handleGatewayAction(actionFn, triggerBtn) {
  return runAction([triggerBtn], actionFn);
}

/* ---------- Detail Drawer: Inspect & Live SSE Logs ---------- */

function openDrawer(serviceName, tab = 'inspect') {
  const targetChanged = state.drawer.targetType !== 'service' || state.drawer.service !== serviceName;
  state.drawer.open = true;
  state.drawer.targetType = 'service';
  state.drawer.service = serviceName;
  state.drawer.tab = tab;

  dom.drawerTargetLabel.textContent = 'Service';
  dom.drawerServiceName.textContent = serviceName;
  dom.tabInspect.hidden = false;
  dom.logsSourceGroup.hidden = false;
  dom.detailDrawer.hidden = false;

  updateDrawerTabs();
  renderServices();

  if (tab === 'inspect') {
    closeLogStream();
    loadInspection(serviceName);
  } else {
    if (targetChanged || !state.logs.stream) {
      startActiveLogStream();
    }
  }

  dom.detailDrawer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function openGatewayLogsDrawer() {
  const targetChanged = state.drawer.targetType !== 'gateway';
  state.drawer.open = true;
  state.drawer.targetType = 'gateway';
  state.drawer.service = null;
  state.drawer.tab = 'logs';

  dom.drawerTargetLabel.textContent = 'Gateway';
  dom.drawerServiceName.textContent = 'gateway';
  dom.tabInspect.hidden = true;
  dom.logsSourceGroup.hidden = true;
  dom.detailDrawer.hidden = false;

  updateDrawerTabs();
  renderServices();

  if (targetChanged || !state.logs.stream) {
    startActiveLogStream();
  }

  dom.detailDrawer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function closeDrawer() {
  closeLogStream();
  state.drawer.open = false;
  state.drawer.targetType = 'service';
  state.drawer.service = null;
  dom.detailDrawer.hidden = true;
  renderServices();
}

function switchDrawerTab(tab) {
  if (!state.drawer.open) {
    return;
  }
  if (state.drawer.targetType === 'gateway' && tab === 'inspect') {
    return;
  }
  if (state.drawer.targetType === 'service' && !state.drawer.service) {
    return;
  }
  state.drawer.tab = tab;
  updateDrawerTabs();
  if (tab === 'inspect') {
    closeLogStream();
    loadInspection(state.drawer.service);
  } else {
    startActiveLogStream();
  }
}

function updateDrawerTabs() {
  const isInspect = state.drawer.tab === 'inspect';
  dom.tabInspect.classList.toggle('active', isInspect);
  dom.tabInspect.setAttribute('aria-selected', String(isInspect));
  dom.tabLogs.classList.toggle('active', !isInspect);
  dom.tabLogs.setAttribute('aria-selected', String(!isInspect));
  dom.panelInspect.hidden = !isInspect;
  dom.panelLogs.hidden = isInspect;
}

async function loadInspection(serviceName) {
  dom.inspectStatusNote.textContent = 'Running Inspection…';
  dom.btnInspectRefresh.disabled = true;
  try {
    const payload = await fetchInspect(serviceName);
    if (state.drawer.service !== serviceName) {
      return;
    }
    setCliCommand(payload.cliCommand, { explicit: true });
    renderInspection(payload.data);
    dom.inspectStatusNote.textContent = `Inspected at ${formatClockTime(new Date())}`;
  } catch (err) {
    if (state.drawer.service !== serviceName) {
      return;
    }
    dom.inspectStatusNote.textContent = 'Inspection failed';
    showError(err);
  } finally {
    dom.btnInspectRefresh.disabled = false;
  }
}

function renderInspection(report) {
  dom.inspectContent.replaceChildren();

  const grid = el('dl', 'inspect-grid');
  const ownerText = report.provenance ? `${report.owner} (${report.provenance})` : report.owner;
  const items = [
    ['Port', report.port ?? '—'],
    ['Desired Route', report.desiredRoute ?? '—'],
    ['Live Gateway Route', report.liveGatewayRoute ?? '—'],
    ['Owner', ownerText ?? '—'],
    ['Health State', report.health ?? '—'],
    ['PID', report.pid ?? '—'],
    ['Uptime', formatUptime(report.uptimeSeconds)],
    ['Stale', report.stale ? 'YES' : 'NO'],
    ['RAM (RSS)', formatBytes(report.rssBytes)],
    ['Health Endpoint', report.healthEndpoint ?? '—'],
    ['Health Response', report.healthResponse || '—'],
    ['Stdout Log Path', report.outLogPath || '—'],
    ['Stderr Log Path', report.errLogPath || '—'],
  ];
  if (report.buildRun) {
    items.push(['Build Run', report.buildRun.displayState]);
    if (report.buildRun.pid !== null && report.buildRun.pid !== undefined) {
      items.push(['Build PID', report.buildRun.pid]);
    }
    if (report.buildRun.exitCode !== null && report.buildRun.exitCode !== undefined) {
      items.push(['Build Exit Code', report.buildRun.exitCode]);
    }
    if (report.buildRun.reason) {
      items.push(['Build Reason', report.buildRun.reason]);
    }
    if (report.buildRun.stdoutLog) {
      items.push(['Build Stdout Log', report.buildRun.stdoutLog]);
    }
    if (report.buildRun.stderrLog) {
      items.push(['Build Stderr Log', report.buildRun.stderrLog]);
    }
  }

  for (const [label, value] of items) {
    const box = el('div', 'inspect-item');
    box.append(el('dt', '', label), el('dd', '', value));
    grid.append(box);
  }
  dom.inspectContent.append(grid);

  // Command line
  const cmdBlock = el('div', 'inspect-block');
  cmdBlock.append(
    el('span', 'inspect-block-title', 'Command Line'),
    el('pre', 'code-block', report.commandLine || '—'),
  );
  dom.inspectContent.append(cmdBlock);

  // Arguments
  if (Array.isArray(report.arguments) && report.arguments.length > 0) {
    const argsBlock = el('div', 'inspect-block');
    argsBlock.append(
      el('span', 'inspect-block-title', `Arguments (${report.arguments.length})`),
      el('pre', 'code-block', report.arguments.join('\n')),
    );
    dom.inspectContent.append(argsBlock);
  }

  // Parent process chain
  const parentsBlock = el('div', 'inspect-block');
  const chain = Array.isArray(report.parentChain) ? report.parentChain : [];
  parentsBlock.append(el('span', 'inspect-block-title', `Parent Process Chain (${chain.length})`));

  if (chain.length === 0) {
    parentsBlock.append(el('div', 'muted', 'No parent Process information available.'));
  } else {
    const table = el('table', 'parent-table');
    const thead = el('thead');
    const headRow = el('tr');
    headRow.append(el('th', '', 'PID'), el('th', '', 'EXECUTABLE'), el('th', '', 'COMMAND LINE'));
    thead.append(headRow);
    table.append(thead);

    const tbody = el('tbody');
    for (const parent of chain) {
      const tr = el('tr');
      tr.append(
        el('td', '', parent.pid ?? '—'),
        el('td', '', parent.executable || '—'),
        el('td', 'parent-cmd', parent.commandLine || '—'),
      );
      tbody.append(tr);
    }
    table.append(tbody);
    parentsBlock.append(table);
  }
  dom.inspectContent.append(parentsBlock);
}

/* ---------- Live SSE Logs ---------- */

let logsHistoryBannerEl = null;
let logsLinesEl = null;
let pendingLogLines = [];
let pendingLogFlushRaf = null;

function ensureLogsContainers() {
  if (!logsHistoryBannerEl || !logsLinesEl || !dom.logsOutput.contains(logsLinesEl)) {
    logsHistoryBannerEl = el('div', 'logs-history-banner');
    logsHistoryBannerEl.id = 'logs-history-banner';
    logsHistoryBannerEl.hidden = true;
    logsLinesEl = el('div', 'logs-lines');
    logsLinesEl.id = 'logs-lines';
    dom.logsOutput.replaceChildren(logsHistoryBannerEl, logsLinesEl);
  }
}

function updateLogsLineCountDisplay() {
  ensureLogsContainers();
  const count = logsLinesEl.querySelectorAll('.log-line').length;
  state.logs.lineCount = count;
  dom.logsLineCount.textContent = `${count} ${count === 1 ? 'line' : 'lines'}`;
}

function renderLogsHistoryBanner() {
  ensureLogsContainers();
  logsHistoryBannerEl.replaceChildren();

  if (state.logs.lineCount === 0 && !state.logs.hasOlder && !state.logs.truncated) {
    logsHistoryBannerEl.hidden = true;
    return;
  }

  if (state.logs.hasOlder) {
    const btn = el(
      'button',
      'btn btn-secondary btn-xs',
      state.logs.loadingOlder ? 'Loading…' : 'Load older',
    );
    btn.type = 'button';
    btn.id = 'btn-logs-load-older';
    btn.disabled = state.logs.loadingOlder;
    btn.addEventListener('click', () => {
      if (state.logs.autoScroll) {
        setLogsAutoScroll(false);
      }
      loadOlderLogs();
    });
    logsHistoryBannerEl.append(btn);
    logsHistoryBannerEl.hidden = false;
    return;
  }

  if (state.logs.truncated) {
    logsHistoryBannerEl.append(el('span', 'logs-history-marker', 'Log file was reset'));
    logsHistoryBannerEl.hidden = false;
    return;
  }

  if (state.logs.lineCount > 0) {
    logsHistoryBannerEl.append(el('span', 'logs-history-marker', 'Start of log'));
    logsHistoryBannerEl.hidden = false;
    return;
  }

  logsHistoryBannerEl.hidden = true;
}

function runProgrammaticScroll(scrollFn) {
  state.logs.programmaticScroll = true;
  scrollFn();
  window.requestAnimationFrame(() => {
    state.logs.programmaticScroll = false;
  });
}

function scrollLogsToBottom() {
  runProgrammaticScroll(() => {
    dom.logsOutput.scrollTop = dom.logsOutput.scrollHeight;
  });
}

function trimLogsToCap(cap) {
  ensureLogsContainers();
  let removed = false;
  while (logsLinesEl.childElementCount > cap) {
    logsLinesEl.firstElementChild.remove();
    removed = true;
  }
  if (removed) {
    updateLogsLineCountDisplay();
  }
}

function setLogsAutoScroll(enabled, { scrollToBottom = true } = {}) {
  state.logs.autoScroll = enabled;
  dom.chkLogsAutoscroll.checked = enabled;
  if (enabled) {
    trimLogsToCap(MAX_LOG_LINES);
    if (scrollToBottom) {
      scrollLogsToBottom();
    }
  }
}

function splitRawLogLines(text) {
  if (!text) {
    return [];
  }
  const lines = [];
  let start = 0;
  for (let i = 0; i < text.length; i += 1) {
    if (text.charCodeAt(i) === 10) {
      lines.push(text.slice(start, i + 1));
      start = i + 1;
    }
  }
  if (start < text.length) {
    lines.push(text.slice(start));
  }
  return lines;
}

function setLogsStreamBadge(text, badgeClass) {
  dom.logsStreamStatus.textContent = text;
  dom.logsStreamStatus.className = `badge ${badgeClass}`;
}

function clearLogsOutput({ resetHistory = false } = {}) {
  ensureLogsContainers();
  cancelPendingLogFlush();
  state.logs.lineCount = 0;
  if (resetHistory) {
    state.logs.startOffset = 0;
    state.logs.hasOlder = false;
    state.logs.loadingOlder = false;
    state.logs.truncated = false;
  }
  dom.logsLineCount.textContent = '0 lines';
  logsLinesEl.replaceChildren(el('div', 'log-empty', 'Waiting for log output…'));
  renderLogsHistoryBanner();
}

function cancelPendingLogFlush() {
  pendingLogLines = [];
  if (pendingLogFlushRaf !== null) {
    window.cancelAnimationFrame(pendingLogFlushRaf);
    pendingLogFlushRaf = null;
  }
}

function flushPendingLogLines() {
  pendingLogFlushRaf = null;
  if (pendingLogLines.length === 0) {
    return;
  }
  ensureLogsContainers();
  const batch = pendingLogLines;
  pendingLogLines = [];

  const prevCount = state.logs.lineCount;
  if (prevCount === 0) {
    logsLinesEl.replaceChildren();
  }

  const frag = document.createDocumentFragment();
  for (const item of batch) {
    frag.append(el('div', item.isStderr ? 'log-line log-line-err' : 'log-line', item.line));
  }
  logsLinesEl.append(frag);

  const activeCap = state.logs.autoScroll ? MAX_LOG_LINES : MAX_PAUSED_LOG_LINES;
  while (logsLinesEl.childElementCount > activeCap) {
    logsLinesEl.firstElementChild.remove();
  }

  state.logs.lineCount = logsLinesEl.childElementCount;
  dom.logsLineCount.textContent = `${state.logs.lineCount} ${state.logs.lineCount === 1 ? 'line' : 'lines'}`;
  if (prevCount === 0) {
    renderLogsHistoryBanner();
  }

  if (state.logs.autoScroll) {
    scrollLogsToBottom();
  }
}

function appendLogLine(line, isStderr) {
  pendingLogLines.push({ line, isStderr });
  if (pendingLogFlushRaf === null) {
    pendingLogFlushRaf = window.requestAnimationFrame(flushPendingLogLines);
  }
}

async function loadOlderLogs() {
  if (!state.drawer.open || state.drawer.tab !== 'logs') {
    return;
  }
  if (!state.logs.hasOlder || state.logs.loadingOlder || state.logs.startOffset <= 0) {
    return;
  }
  const gen = state.logs.streamGen;
  const beforeOffset = state.logs.startOffset;
  const isStderr = state.logs.err;

  state.logs.loadingOlder = true;
  renderLogsHistoryBanner();

  try {
    const payload =
      state.drawer.targetType === 'gateway'
        ? await fetchGatewayLogs({
            lines: OLDER_LOG_PAGE_LINES,
            err: isStderr,
            beforeOffset,
          })
        : await fetchServiceLogs(state.drawer.service, {
            lines: OLDER_LOG_PAGE_LINES,
            err: isStderr,
            build: state.logs.build,
            beforeOffset,
          });

    if (gen !== state.logs.streamGen) {
      return;
    }

    const data = payload?.data || {};
    state.logs.startOffset = typeof data.startOffset === 'number' ? data.startOffset : 0;
    state.logs.hasOlder = Boolean(data.hasOlder);
    state.logs.truncated = Boolean(data.truncated);
    state.logs.loadingOlder = false;

    const olderLines = splitRawLogLines(data.text || '');
    const prevScrollHeight = dom.logsOutput.scrollHeight;
    const prevScrollTop = dom.logsOutput.scrollTop;

    ensureLogsContainers();
    if (olderLines.length > 0) {
      if (state.logs.lineCount === 0) {
        logsLinesEl.replaceChildren();
      }
      const frag = document.createDocumentFragment();
      for (const line of olderLines) {
        frag.append(el('div', isStderr ? 'log-line log-line-err' : 'log-line', line));
      }
      logsLinesEl.prepend(frag);
      state.logs.lineCount = logsLinesEl.childElementCount;
      dom.logsLineCount.textContent = `${state.logs.lineCount} ${state.logs.lineCount === 1 ? 'line' : 'lines'}`;
    }

    renderLogsHistoryBanner();

    const heightDelta = dom.logsOutput.scrollHeight - prevScrollHeight;
    if (heightDelta !== 0) {
      runProgrammaticScroll(() => {
        dom.logsOutput.scrollTop = Math.max(0, prevScrollTop + heightDelta);
      });
    }
  } catch (err) {
    if (gen !== state.logs.streamGen) {
      return;
    }
    state.logs.loadingOlder = false;
    renderLogsHistoryBanner();
    showError(err);
  }
}

function closeLogStream() {
  state.logs.streamGen += 1;
  state.logs.loadingOlder = false;
  cancelPendingLogFlush();
  if (state.logs.stream) {
    state.logs.stream.close();
    state.logs.stream = null;
  }
  setLogsStreamBadge('Disconnected', 'badge-neutral');
}

function buildBadgeClass(displayState) {
  switch (displayState) {
    case 'SUCCEEDED':
    case 'ARTIFACT_PRESENT':
      return 'badge-healthy';
    case 'RUNNING':
      return 'badge-starting';
    case 'FAILED':
    case 'UNKNOWN':
      return 'badge-danger';
    default:
      return 'badge-neutral';
  }
}

function handleBuildService(serviceName, force, button) {
  return runAction([button], () => buildService(serviceName, { force }));
}

function handleCancelBuild(serviceName, button) {
  return runAction([button], () => cancelBuild(serviceName));
}

function startActiveLogStream() {
  if (!state.drawer.open || state.drawer.tab !== 'logs') {
    return;
  }
  closeLogStream();
  clearLogsOutput({ resetHistory: true });
  setLogsAutoScroll(true, { scrollToBottom: false });
  setLogsStreamBadge('Connecting…', 'badge-starting');

  dom.btnLogsSourceService.classList.toggle('active', !state.logs.build);
  dom.btnLogsSourceBuild.classList.toggle('active', state.logs.build);
  dom.btnLogsStdout.classList.toggle('active', !state.logs.err);
  dom.btnLogsStderr.classList.toggle('active', state.logs.err);

  const gen = state.logs.streamGen;
  let logHistoryEntry = null;
  const handlers = {
    lines: INITIAL_LOG_LINES,
    err: state.logs.err,
    build: state.logs.build,
    onOpen() {
      if (gen !== state.logs.streamGen) {
        return;
      }
      dom.btnLogsReconnect.hidden = true;
      setLogsStreamBadge('Streaming', 'badge-healthy');
    },
    onMeta(payload) {
      if (gen !== state.logs.streamGen) {
        return;
      }
      if (payload.cliCommand) {
        logHistoryEntry = setCliCommand(payload.cliCommand, { explicit: true });
      }
      if (typeof payload.startOffset === 'number') {
        state.logs.startOffset = payload.startOffset;
      }
      if (typeof payload.hasOlder === 'boolean') {
        state.logs.hasOlder = payload.hasOlder;
      }
      state.logs.truncated = false;
      renderLogsHistoryBanner();
      dom.btnLogsReconnect.hidden = true;
      setLogsStreamBadge('Streaming', 'badge-healthy');
    },
    onLog(payload) {
      if (gen !== state.logs.streamGen) {
        return;
      }
      appendLogLine(payload.line ?? '', Boolean(payload.stderr));
    },
    onError(err) {
      if (gen !== state.logs.streamGen) {
        return;
      }
      state.logs.stream = null;
      dom.btnLogsReconnect.hidden = false;
      if (err) {
        setLogsStreamBadge('Error', 'badge-danger');
        if (logHistoryEntry) {
          logHistoryEntry.errorCode = err instanceof ApiError ? err.code : 'ERROR';
          renderCliHistory();
          showError(err, { recordCliHistory: false });
        } else {
          showError(err);
        }
      } else {
        setLogsStreamBadge('Disconnected', 'badge-neutral');
      }
    },
  };

  if (state.drawer.targetType === 'gateway') {
    state.logs.stream = streamGatewayLogs(handlers);
  } else if (state.drawer.service) {
    state.logs.stream = streamServiceLogs(state.drawer.service, handlers);
  }
}

/* ---------- Conflict Kill Confirmation <dialog> ---------- */

async function openConflictDialog(serviceName) {
  state.conflictService = serviceName;
  dom.conflictServiceName.textContent = serviceName;
  dom.conflictCliPreview.textContent = 'Loading CLI command…';
  dom.conflictLoading.hidden = false;
  dom.conflictError.hidden = true;
  dom.conflictDetails.hidden = true;
  dom.btnConflictConfirm.disabled = true;

  if (typeof dom.conflictDialog.showModal === 'function') {
    dom.conflictDialog.showModal();
  } else {
    dom.conflictDialog.setAttribute('open', '');
  }
  dom.btnConflictCancel.focus();

  try {
    const payload = await fetchInspect(serviceName);
    if (state.conflictService !== serviceName) {
      return;
    }
    const report = payload.data;
    dom.conflictPort.textContent = report.port ?? '—';
    dom.conflictPid.textContent = report.pid ?? '—';
    dom.conflictCmdline.textContent = report.commandLine || '—';
    const parents = Array.isArray(report.parentChain) ? report.parentChain : [];
    dom.conflictParents.textContent =
      parents.length > 0
        ? parents.map((p) => `${p.pid} (${p.executable || p.commandLine || 'unknown'})`).join(' ← ')
        : '—';
    dom.conflictCliPreview.textContent = payload.killConflictCliCommand || '—';

    dom.conflictLoading.hidden = true;
    dom.conflictDetails.hidden = false;
    dom.btnConflictConfirm.disabled = report.owner !== 'CONFLICT';
  } catch (err) {
    if (state.conflictService !== serviceName) {
      return;
    }
    dom.conflictLoading.hidden = true;
    dom.conflictCliPreview.textContent = '(unavailable — inspection failed)';
    dom.conflictError.textContent = err instanceof Error ? err.message : String(err);
    dom.conflictError.hidden = false;
    dom.btnConflictConfirm.disabled = true;
  }
}

function closeConflictDialog() {
  state.conflictService = null;
  if (dom.conflictDialog.open) {
    dom.conflictDialog.close();
  }
}

function confirmConflictKill() {
  const serviceName = state.conflictService;
  if (!serviceName) {
    return;
  }
  return runAction(
    [dom.btnConflictConfirm],
    () => stopServices([serviceName], { all: false, killConflict: true }),
    { onSettled: closeConflictDialog },
  );
}

/* ---------- Clipboard Copy ---------- */

async function copyTextToClipboard(text, buttonEl, defaultLabel = 'Copy') {
  if (!text) {
    return;
  }
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      const textarea = document.createElement('textarea');
      textarea.value = text;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.append(textarea);
      textarea.select();
      document.execCommand('copy');
      textarea.remove();
    }
    if (buttonEl) {
      buttonEl.textContent = 'Copied';
      window.setTimeout(() => {
        buttonEl.textContent = defaultLabel;
      }, 1500);
    }
  } catch {
    if (buttonEl) {
      buttonEl.textContent = 'Copy failed';
      window.setTimeout(() => {
        buttonEl.textContent = defaultLabel;
      }, 1500);
    }
  }
}

function copyCliCommand() {
  const text = dom.cliCommandEcho.textContent || '';
  return copyTextToClipboard(text, dom.btnCopyCli, 'Copy');
}

function copyCliHistory() {
  if (state.cliHistory.length === 0) {
    return;
  }
  const text = state.cliHistory.map((item) => item.command).join('\n');
  return copyTextToClipboard(text, dom.btnCopyCliHistory, 'Copy All');
}

/* ---------- Event Wiring & Lifecycle ---------- */

function closeAllStreams() {
  closeLogStream();
  for (const entry of state.activeStarts.values()) {
    if (entry.stream) {
      entry.stream.close();
    }
  }
  state.activeStarts.clear();
}

function init() {
  dom.btnRefresh.addEventListener('click', () => refreshStatus({ explicit: true }));
  dom.btnDismissError.addEventListener('click', clearError);

  state.gatewayAutoReconcile = loadAutoReconcilePreference();
  dom.chkGatewayAutoReconcile.checked = state.gatewayAutoReconcile;
  dom.chkGatewayAutoReconcile.addEventListener('change', () => {
    state.gatewayAutoReconcile = dom.chkGatewayAutoReconcile.checked;
    saveAutoReconcilePreference(state.gatewayAutoReconcile);
  });

  dom.btnGatewayReconcile.addEventListener('click', () =>
    handleGatewayAction(() => reconcileGateway(), dom.btnGatewayReconcile),
  );
  dom.btnDriftReconcile.addEventListener('click', () =>
    handleGatewayAction(() => reconcileGateway(), dom.btnDriftReconcile),
  );
  dom.btnGatewayStart.addEventListener('click', () =>
    handleGatewayAction(() => startGateway({ detach: false, timeoutSeconds: 30 }), dom.btnGatewayStart),
  );
  dom.btnGatewayStop.addEventListener('click', () =>
    handleGatewayAction(() => stopGateway(), dom.btnGatewayStop),
  );
  dom.btnGatewayLogs.addEventListener('click', () => openGatewayLogsDrawer());

  dom.btnStartLocal.addEventListener('click', () => handleStartServices([]));
  dom.btnStopManaged.addEventListener('click', () => handleStopServices([], dom.btnStopManaged));

  dom.tabInspect.addEventListener('click', () => switchDrawerTab('inspect'));
  dom.tabLogs.addEventListener('click', () => switchDrawerTab('logs'));
  dom.btnDrawerClose.addEventListener('click', closeDrawer);
  dom.btnInspectRefresh.addEventListener('click', () => {
    if (state.drawer.service) {
      loadInspection(state.drawer.service);
    }
  });

  dom.btnLogsSourceService.addEventListener('click', () => {
    if (state.logs.build) {
      state.logs.build = false;
      startActiveLogStream();
    }
  });

  dom.btnLogsSourceBuild.addEventListener('click', () => {
    if (!state.logs.build) {
      state.logs.build = true;
      startActiveLogStream();
    }
  });

  dom.btnLogsStdout.addEventListener('click', () => {
    if (state.logs.err) {
      state.logs.err = false;
      startActiveLogStream();
    }
  });

  dom.btnLogsStderr.addEventListener('click', () => {
    if (!state.logs.err) {
      state.logs.err = true;
      startActiveLogStream();
    }
  });

  dom.chkLogsAutoscroll.addEventListener('change', () => {
    setLogsAutoScroll(dom.chkLogsAutoscroll.checked);
  });

  dom.logsOutput.addEventListener(
    'wheel',
    (event) => {
      if (event.deltaY < 0 && state.logs.autoScroll) {
        setLogsAutoScroll(false);
      }
    },
    { passive: true },
  );

  dom.logsOutput.addEventListener(
    'touchstart',
    (event) => {
      if (event.touches.length === 1) {
        state.logs.lastTouchY = event.touches[0].clientY;
      }
    },
    { passive: true },
  );

  dom.logsOutput.addEventListener(
    'touchmove',
    (event) => {
      if (event.touches.length === 1 && state.logs.lastTouchY !== null) {
        const currentY = event.touches[0].clientY;
        if (currentY > state.logs.lastTouchY && state.logs.autoScroll) {
          setLogsAutoScroll(false);
        }
        state.logs.lastTouchY = currentY;
      }
    },
    { passive: true },
  );

  dom.logsOutput.addEventListener(
    'touchend',
    () => {
      state.logs.lastTouchY = null;
    },
    { passive: true },
  );

  dom.logsOutput.addEventListener('keydown', (event) => {
    if (
      (event.key === 'ArrowUp' || event.key === 'PageUp' || event.key === 'Home') &&
      state.logs.autoScroll
    ) {
      setLogsAutoScroll(false);
    }
  });

  dom.logsOutput.addEventListener('pointerdown', () => {
    state.logs.pointerDown = true;
  });

  window.addEventListener('pointerup', () => {
    state.logs.pointerDown = false;
  });

  dom.logsOutput.addEventListener('scroll', () => {
    const distanceFromBottom =
      dom.logsOutput.scrollHeight - dom.logsOutput.scrollTop - dom.logsOutput.clientHeight;

    if (!state.logs.programmaticScroll || state.logs.pointerDown) {
      if (distanceFromBottom > AUTOSCROLL_REENGAGE_THRESHOLD_PX) {
        if (state.logs.autoScroll) {
          setLogsAutoScroll(false);
        }
      } else if (!state.logs.autoScroll) {
        setLogsAutoScroll(true, { scrollToBottom: false });
      }
    }

    if (
      !state.logs.programmaticScroll &&
      !state.logs.autoScroll &&
      dom.logsOutput.scrollTop <= LOAD_OLDER_TOP_THRESHOLD_PX &&
      state.logs.hasOlder &&
      !state.logs.loadingOlder &&
      state.logs.lineCount > 0
    ) {
      loadOlderLogs();
    }
  });

  dom.btnLogsClear.addEventListener('click', () => clearLogsOutput({ resetHistory: true }));
  dom.btnLogsReconnect.addEventListener('click', () => {
    startActiveLogStream();
  });

  dom.btnCopyCli.addEventListener('click', copyCliCommand);
  dom.btnCliHistory.addEventListener('click', () => setCliHistoryOpen(!state.cliHistoryOpen));
  dom.btnCloseCliHistory.addEventListener('click', () => setCliHistoryOpen(false));
  dom.btnCopyCliHistory.addEventListener('click', copyCliHistory);
  dom.btnClearCliHistory.addEventListener('click', clearCliHistory);

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && state.cliHistoryOpen && !dom.conflictDialog.open) {
      setCliHistoryOpen(false);
      dom.btnCliHistory.focus();
    }
  });

  dom.btnConflictCancel.addEventListener('click', closeConflictDialog);
  dom.btnConflictConfirm.addEventListener('click', confirmConflictKill);
  dom.conflictDialog.addEventListener('cancel', () => {
    state.conflictService = null;
  });

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      refreshStatus({ explicit: false });
      startPolling();
    } else {
      stopPolling();
      updatePollIndicator('paused', 'Paused (tab hidden)');
    }
  });

  window.addEventListener('pagehide', closeAllStreams);
  window.addEventListener('beforeunload', closeAllStreams);

  renderCliHistory();
  refreshStatus({ explicit: false });
  startPolling();
}

init();
