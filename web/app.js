import {
  ApiError,
  buildService,
  cancelBuild,
  deleteProfile,
  fetchGatewayLogs,
  fetchGitBranches,
  fetchInspect,
  fetchServiceLogs,
  fetchStatus,
  gitCancel,
  gitClone,
  gitFetch,
  gitPull,
  gitStash,
  gitSwitch,
  installGitHooks,
  reconcileGateway,
  saveProfile,
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
  view: 'services',
  servicesFilter: {
    query: '',
    localOnly: false,
  },
  pollTimer: null,
  statusInFlightCount: 0,
  statusSeq: 0,
  hasExplicitCliCommand: false,
  hasRecordedInitialCliCommand: false,
  cliHistory: [],
  cliHistoryOpen: false,
  cliHistorySeq: 0,
  gitHooksCollapsed: false,
  pollErrorActive: false,
  pendingRoutes: new Set(),
  pendingStops: new Set(),
  pendingProfiles: new Set(),
  drawer: {
    open: false,
    service: null,
  },
  logs: {
    targetType: null,
    targetService: null,
    err: false,
    build: false,
    git: false,
    autoScroll: true,
    wrap: false,
    sidebarQuery: '',
    filterQuery: '',
    entries: [],
    matchedCount: 0,
    stream: null,
    streamSeq: 0,
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
  activeActions: new Map(),
  actionSeq: 0,
  conflictService: null,
  profileDialog: {
    mode: 'create',
    originalName: null,
    submitting: false,
  },
  gitSwitch: {
    repository: null,
    services: [],
    dirty: false,
    branches: [],
    selectedBranch: null,
    filter: '',
  },
  pillMenu: {
    open: false,
    kind: null,
    service: null,
  },
};

const dom = {
  appShell: document.getElementById('app-shell'),
  projectName: document.getElementById('project-name'),
  configPath: document.getElementById('config-path'),
  navTabServices: document.getElementById('nav-tab-services'),
  navTabLogs: document.getElementById('nav-tab-logs'),
  navTabLogsTarget: document.getElementById('nav-tab-logs-target'),
  pollIndicator: document.getElementById('poll-indicator'),
  pollLabel: document.getElementById('poll-label'),
  lastUpdated: document.getElementById('last-updated'),
  btnRefresh: document.getElementById('btn-refresh'),

  viewServices: document.getElementById('view-services'),
  viewLogs: document.getElementById('view-logs'),

  errorBanner: document.getElementById('error-banner'),
  errorCode: document.getElementById('error-code'),
  errorMessage: document.getElementById('error-message'),
  errorDetails: document.getElementById('error-details'),
  btnDismissError: document.getElementById('btn-dismiss-error'),

  progressStrip: document.getElementById('progress-strip'),
  progressStripTitle: document.getElementById('progress-strip-title'),
  progressList: document.getElementById('progress-list'),

  gitHooksBanner: document.getElementById('git-hooks-banner'),
  gitHooksSummary: document.getElementById('git-hooks-summary'),
  gitHooksList: document.getElementById('git-hooks-list'),
  btnGitHooksToggle: document.getElementById('btn-git-hooks-toggle'),
  btnGitHooksInstallAll: document.getElementById('btn-git-hooks-install-all'),

  gatewayType: document.getElementById('gateway-type'),
  gatewayEndpoint: document.getElementById('gateway-endpoint'),
  gatewayTelemetry: document.getElementById('gateway-telemetry'),
  gatewayOwner: document.getElementById('gateway-owner'),
  gatewayHealth: document.getElementById('gateway-health'),
  gatewaySync: document.getElementById('gateway-sync'),
  gatewayPid: document.getElementById('gateway-pid'),
  gatewayUptime: document.getElementById('gateway-uptime'),
  gatewayRss: document.getElementById('gateway-rss'),
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
  btnSaveProfile: document.getElementById('btn-save-profile'),
  btnGitFetchLocal: document.getElementById('btn-git-fetch-local'),
  btnGitPullLocal: document.getElementById('btn-git-pull-local'),
  btnStartLocal: document.getElementById('btn-start-local'),
  btnStopManaged: document.getElementById('btn-stop-managed'),

  servicesSummary: document.getElementById('services-summary'),
  servicesFilterInput: document.getElementById('services-filter-input'),
  btnServicesLocalOnly: document.getElementById('btn-services-local-only'),
  servicesTbody: document.getElementById('services-tbody'),

  detailDrawer: document.getElementById('detail-drawer'),
  drawerTargetLabel: document.getElementById('drawer-target-label'),
  drawerServiceName: document.getElementById('drawer-service-name'),
  btnDrawerClose: document.getElementById('btn-drawer-close'),
  panelInspect: document.getElementById('panel-inspect'),
  inspectStatusNote: document.getElementById('inspect-status-note'),
  btnInspectRefresh: document.getElementById('btn-inspect-refresh'),
  inspectContent: document.getElementById('inspect-content'),

  logsSidebarFilter: document.getElementById('logs-sidebar-filter'),
  logsTargetList: document.getElementById('logs-target-list'),
  logsTargetKind: document.getElementById('logs-target-kind'),
  logsTargetName: document.getElementById('logs-target-name'),
  logsSourceGroup: document.getElementById('logs-source-group'),
  btnLogsSourceService: document.getElementById('btn-logs-source-service'),
  btnLogsSourceBuild: document.getElementById('btn-logs-source-build'),
  btnLogsSourceGit: document.getElementById('btn-logs-source-git'),
  btnLogsStdout: document.getElementById('btn-logs-stdout'),
  btnLogsStderr: document.getElementById('btn-logs-stderr'),
  logsStreamStatus: document.getElementById('logs-stream-status'),
  logsLineCount: document.getElementById('logs-line-count'),
  logsFilterInput: document.getElementById('logs-filter-input'),
  chkLogsWrap: document.getElementById('chk-logs-wrap'),
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
  gitSwitchDialog: document.getElementById('git-switch-dialog'),
  gitSwitchRepoName: document.getElementById('git-switch-repo-name'),
  gitSwitchServices: document.getElementById('git-switch-services'),
  gitSwitchFetched: document.getElementById('git-switch-fetched'),
  btnGitSwitchFetch: document.getElementById('btn-git-switch-fetch'),
  btnGitSwitchDefault: document.getElementById('btn-git-switch-default'),
  gitSwitchDirtyBanner: document.getElementById('git-switch-dirty-banner'),
  gitSwitchDirtySummary: document.getElementById('git-switch-dirty-summary'),
  btnGitSwitchStash: document.getElementById('btn-git-switch-stash'),
  gitSwitchError: document.getElementById('git-switch-error'),
  gitSwitchFilter: document.getElementById('git-switch-filter'),
  gitSwitchLoading: document.getElementById('git-switch-loading'),
  gitSwitchBranchList: document.getElementById('git-switch-branch-list'),
  btnGitSwitchCancel: document.getElementById('btn-git-switch-cancel'),
  btnGitSwitchConfirm: document.getElementById('btn-git-switch-confirm'),
  pillActionMenu: document.getElementById('pill-action-menu'),
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

  profileDialog: document.getElementById('profile-dialog'),
  profileDialogTitle: document.getElementById('profile-dialog-title'),
  profileNameInput: document.getElementById('profile-name-input'),
  profileOverwriteWarning: document.getElementById('profile-overwrite-warning'),
  profileDialogError: document.getElementById('profile-dialog-error'),
  profileSelectedCount: document.getElementById('profile-selected-count'),
  btnProfileSelectAll: document.getElementById('btn-profile-select-all'),
  btnProfileClearAll: document.getElementById('btn-profile-clear-all'),
  profileServicesList: document.getElementById('profile-services-list'),
  profileCliPreview: document.getElementById('profile-cli-preview'),
  profileDeleteConfirm: document.getElementById('profile-delete-confirm'),
  profileDeleteConfirmText: document.getElementById('profile-delete-confirm-text'),
  btnProfileDeleteCancel: document.getElementById('btn-profile-delete-cancel'),
  btnProfileDeleteConfirm: document.getElementById('btn-profile-delete-confirm'),
  btnProfileDelete: document.getElementById('btn-profile-delete'),
  btnProfileCancel: document.getElementById('btn-profile-cancel'),
  btnProfileSave: document.getElementById('btn-profile-save'),
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

function parseOrTerms(query) {
  const raw = (query || '').trim();
  if (!raw) {
    return [];
  }
  return raw
    .split('|')
    .map((term) => term.trim().toLowerCase())
    .filter(Boolean);
}

function matchesOrQuery(text, query) {
  const terms = parseOrTerms(query);
  if (terms.length === 0) {
    return true;
  }
  const haystack = String(text ?? '').toLowerCase();
  return terms.some((term) => haystack.includes(term));
}

function isPriorityService(row) {
  return (
    row.route === 'LOCAL' ||
    (Boolean(row.owner) && row.owner !== 'NONE') ||
    row.buildRun?.displayState === 'RUNNING'
  );
}

function sortServicesLocalFirst(services) {
  return services
    .map((row, idx) => ({ row, idx, priority: isPriorityService(row) ? 0 : 1 }))
    .sort((a, b) => a.priority - b.priority || a.idx - b.idx)
    .map((item) => item.row);
}

function targetDotClass(health) {
  switch (health) {
    case 'HEALTHY':
      return 'target-dot-healthy';
    case 'STARTING':
      return 'target-dot-starting';
    case 'CRASHED':
      return 'target-dot-crashed';
    default:
      return 'target-dot-stopped';
  }
}

function hasBuildRunLogs(displayState) {
  return (
    displayState === 'RUNNING' ||
    displayState === 'SUCCEEDED' ||
    displayState === 'FAILED' ||
    displayState === 'UNKNOWN'
  );
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

function renderGitHooks() {
  const gitHooks = state.estate?.gitHooks;
  if (!gitHooks || !gitHooks.configured || !gitHooks.hasWarnings) {
    dom.gitHooksBanner.hidden = true;
    dom.gitHooksList.replaceChildren();
    return;
  }

  if (gitHooks.sourceError) {
    dom.gitHooksSummary.textContent = `Mandatory Git hooks could not be checked: ${gitHooks.sourceError}`;
    dom.btnGitHooksInstallAll.hidden = true;
    dom.btnGitHooksToggle.hidden = true;
    dom.gitHooksList.hidden = true;
    dom.gitHooksList.replaceChildren();
    dom.gitHooksBanner.hidden = false;
    return;
  }

  dom.btnGitHooksInstallAll.hidden = false;
  dom.btnGitHooksToggle.hidden = false;

  const allRepos = gitHooks.repositories || [];
  const nameCounts = new Map();
  for (const repo of allRepos) {
    nameCounts.set(repo.repository, (nameCounts.get(repo.repository) || 0) + 1);
  }

  const issueRepos = allRepos.filter(
    (repo) => repo.status === 'MISSING_HOOKS' || repo.status === 'OUTDATED_HOOKS',
  );
  const parts = [];
  if (gitHooks.missingHookCount > 0) {
    parts.push(`${gitHooks.missingHookCount} missing`);
  }
  if (gitHooks.outdatedHookCount > 0) {
    parts.push(`${gitHooks.outdatedHookCount} outdated`);
  }
  const detail = parts.length > 0 ? ` (${parts.join(', ')})` : '';
  dom.gitHooksSummary.textContent =
    `Mandatory Git hooks need attention in ${gitHooks.repositoriesWithIssuesCount}/${gitHooks.checkedRepositoryCount} Repositories${detail}.`;

  dom.gitHooksList.replaceChildren();
  for (const repo of issueRepos) {
    const li = el('li', 'git-hooks-item');
    const info = el('div', 'git-hooks-item-info');
    const isAmbiguous = (nameCounts.get(repo.repository) || 0) > 1;
    const repoLabel = isAmbiguous ? repo.path : repo.repository;
    const targetFilter = isAmbiguous ? repo.path : repo.repository;
    info.append(el('span', 'mono git-hooks-repo', repoLabel));

    const missing = (repo.hooks || []).filter((h) => h.state === 'MISSING').map((h) => h.name);
    const outdated = (repo.hooks || [])
      .filter((h) => h.state === 'OUTDATED' || h.state === 'NOT_EXECUTABLE')
      .map((h) => h.name);
    const repoParts = [];
    if (missing.length > 0) {
      repoParts.push(`missing: ${missing.join(', ')}`);
    }
    if (outdated.length > 0) {
      repoParts.push(`outdated: ${outdated.join(', ')}`);
    }
    info.append(el('span', 'muted mono', repoParts.join(' · ')));

    const btn = el(
      'button',
      'btn btn-secondary btn-sm',
      missing.length > 0 && outdated.length > 0
        ? 'Install / Upgrade'
        : missing.length > 0
          ? 'Install'
          : 'Upgrade',
    );
    btn.type = 'button';
    btn.addEventListener('click', () => handleInstallGitHooks([targetFilter], btn));

    li.append(info, btn);
    dom.gitHooksList.append(li);
  }

  dom.gitHooksList.hidden = state.gitHooksCollapsed;
  dom.btnGitHooksToggle.textContent = state.gitHooksCollapsed ? 'Show Details' : 'Hide Details';
  dom.btnGitHooksToggle.setAttribute('aria-expanded', String(!state.gitHooksCollapsed));
  dom.gitHooksBanner.hidden = false;
}

async function handleInstallGitHooks(repositories, triggerBtn) {
  clearError();
  if (triggerBtn) {
    triggerBtn.disabled = true;
  }
  try {
    const payload = await installGitHooks(repositories);
    setCliCommand(payload.cliCommand, { explicit: true });
    await refreshStatus({ explicit: false });
  } catch (err) {
    showError(err);
  } finally {
    if (triggerBtn) {
      triggerBtn.disabled = false;
    }
  }
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
  dom.gatewayRss.textContent =
    gw.rssBytes !== null && gw.rssBytes !== undefined
      ? `${formatBytes(gw.rssBytes)} RSS`
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

function getCurrentLocalServices() {
  const services = state.estate?.services || [];
  return services.filter((s) => s.route === 'LOCAL').map((s) => s.service);
}

function sameServiceSet(a, b) {
  if (a.length !== b.length) {
    return false;
  }
  const setA = new Set(a);
  if (setA.size !== b.length) {
    return false;
  }
  for (const item of b) {
    if (!setA.has(item)) {
      return false;
    }
  }
  return true;
}

function renderProfiles() {
  dom.profileList.replaceChildren();
  const currentLocals = getCurrentLocalServices();
  if (!state.profiles || state.profiles.length === 0) {
    dom.profileList.append(el('span', 'muted', 'No Profiles configured'));
    return;
  }

  for (const profile of state.profiles) {
    const locals = Array.isArray(profile.local) ? profile.local : [];
    const isActive = state.estate !== null && sameServiceSet(locals, currentLocals);
    const group = el('div', isActive ? 'profile-pill-group active' : 'profile-pill-group');
    group.dataset.profile = profile.name;

    const btn = el('button', isActive ? 'profile-pill active' : 'profile-pill');
    btn.type = 'button';
    btn.setAttribute('aria-pressed', String(isActive));
    btn.title = locals.length > 0 ? `LOCAL: ${locals.join(', ')}` : 'All Services REMOTE';
    btn.append(
      el('span', '', profile.name),
      el('span', 'profile-pill-count', `${locals.length} LOCAL`),
    );
    btn.addEventListener('click', () => handleUseProfile(profile.name, btn));

    const editBtn = el('button', 'profile-pill-edit', '✎');
    editBtn.type = 'button';
    editBtn.title = `Edit Profile '${profile.name}'`;
    editBtn.setAttribute('aria-label', `Edit profile ${profile.name}`);
    editBtn.addEventListener('click', () => openEditProfileDialog(profile));

    group.append(btn, editBtn);
    dom.profileList.append(group);
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
  const totalRss = state.estate?.totalRssBytes;
  const rssPart =
    totalRss !== null && totalRss !== undefined ? ` · Total RSS: ${formatBytes(totalRss)}` : '';

  const sorted = sortServicesLocalFirst(services);
  const query = state.servicesFilter.query;
  const localOnly = state.servicesFilter.localOnly;
  const filtered = sorted.filter((row) => {
    if (localOnly && row.route !== 'LOCAL') {
      return false;
    }
    return matchesOrQuery(row.service, query);
  });

  const isFiltered = localOnly || parseOrTerms(query).length > 0;
  if (isFiltered) {
    dom.servicesSummary.textContent = `${filtered.length} of ${services.length} services · ${localCount} LOCAL · ${healthyCount} HEALTHY${rssPart}`;
  } else {
    dom.servicesSummary.textContent = `${services.length} total · ${localCount} LOCAL · ${healthyCount} HEALTHY${rssPart}`;
  }

  dom.btnServicesLocalOnly.classList.toggle('active', localOnly);
  dom.btnServicesLocalOnly.setAttribute('aria-pressed', String(localOnly));

  if (services.length === 0) {
    const tr = el('tr');
    const td = el('td', 'empty-cell', 'No Services defined in this Project.');
    td.colSpan = 12;
    tr.append(td);
    dom.servicesTbody.append(tr);
    return;
  }

  if (filtered.length === 0) {
    const tr = el('tr');
    const td = el('td', 'empty-cell', 'No Services match the current filter.');
    td.colSpan = 10;
    tr.append(td);
    dom.servicesTbody.append(tr);
    return;
  }

  for (const row of filtered) {
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

    // HEALTH (+ inline STALE tag) — clickable pill opens Process actions popover
    const tdHealth = el('td');
    const healthWrap = el('div', 'health-cell-wrap');
    const healthBtn = el('button', `badge badge-clickable badge-pill-trigger ${healthBadgeClass(row.health)}`, row.health);
    healthBtn.type = 'button';
    healthBtn.dataset.service = row.service;
    healthBtn.dataset.pill = 'health';
    healthBtn.setAttribute('aria-haspopup', 'menu');
    const healthTip = [row.healthEndpoint, row.healthNote].filter(Boolean).join(' — ');
    healthBtn.title = healthTip
      ? `${healthTip} — Click for Process actions`
      : `Click for Process actions (${row.service})`;
    healthBtn.addEventListener('click', (event) => {
      event.stopPropagation();
      openPillMenu('health', row, healthBtn);
    });
    healthWrap.append(healthBtn);
    if (row.stale) {
      const staleBadge = el('span', 'badge badge-stale', 'STALE');
      staleBadge.title = 'Managed Process was started before the latest Artifact was built — restart Process';
      healthWrap.append(staleBadge);
    }
    tdHealth.append(healthWrap);

    // BUILD — clickable pill opens Build actions popover (NO_BUILDER stays static)
    const tdBuild = el('td');
    const buildRunState = row.buildRun?.displayState || 'NO_BUILDER';
    if (buildRunState !== 'NO_BUILDER') {
      const buildBtn = el('button', `badge badge-clickable badge-pill-trigger ${buildBadgeClass(buildRunState)}`, buildRunState);
      buildBtn.type = 'button';
      buildBtn.dataset.service = row.service;
      buildBtn.dataset.pill = 'build';
      buildBtn.setAttribute('aria-haspopup', 'menu');
      buildBtn.title = row.buildRun?.reason
        ? `${row.buildRun.reason} — Click for Build actions`
        : `Click for Build actions (${row.service})`;
      buildBtn.addEventListener('click', (event) => {
        event.stopPropagation();
        openPillMenu('build', row, buildBtn);
      });
      tdBuild.append(buildBtn);
    } else {
      tdBuild.append(el('span', `badge ${buildBadgeClass(buildRunState)}`, buildRunState));
    }

    // GIT — clickable pill opens Git actions popover (NO_REPO stays static)
    const tdGit = el('td');
    const git = row.git;
    if (!git) {
      tdGit.append(el('span', 'badge badge-neutral', 'NO_REPO'));
    } else {
      const gitLabel = git.compact || git.state || 'NO_REPO';
      const gitBtn = el('button', `badge badge-clickable badge-pill-trigger ${gitBadgeClass(git)}`, gitLabel);
      gitBtn.type = 'button';
      gitBtn.dataset.service = row.service;
      gitBtn.dataset.repository = git.repository;
      gitBtn.dataset.pill = 'git';
      gitBtn.setAttribute('aria-haspopup', 'menu');
      gitBtn.title = `Repository ${git.repository} — Click for Git actions`;
      gitBtn.addEventListener('click', (event) => {
        event.stopPropagation();
        openPillMenu('git', row, gitBtn);
      });
      tdGit.append(gitBtn);
    }

    // PORT, PID, UPTIME, RSS
    const tdPort = el('td', 'mono', row.port ?? '—');
    const tdPid = el('td', 'mono', row.pid ?? '—');
    const tdUptime = el('td', 'mono', formatUptime(row.uptimeSeconds));
    const tdRss = el('td', 'mono', formatBytes(row.rssBytes));

    tr.append(tdName, tdRoute, tdOwner, tdHealth, tdBuild, tdGit, tdPort, tdPid, tdUptime, tdRss);
    dom.servicesTbody.append(tr);
  }
}

const GATEWAY_RECONCILE_ACTIVITY = Object.freeze({
  title: 'Reconciling Gateway',
  target: 'gateway',
  message: 'Reconciling Gateway…',
});

function beginActivity({
  buildService = null,
  gitRepo = null,
  title = 'Running Actions',
  target = '',
  message = 'Working…',
} = {}) {
  const id = `action-${++state.actionSeq}`;
  const entry = {
    buildService,
    gitRepo,
    title,
    target,
    message,
  };
  state.activeActions.set(id, entry);
  renderProgressStrip();

  let finished = false;
  return {
    update(patch = {}) {
      if (finished) {
        return;
      }
      if (patch.title !== undefined) {
        entry.title = patch.title;
      }
      if (patch.target !== undefined) {
        entry.target = patch.target;
      }
      if (patch.message !== undefined) {
        entry.message = patch.message;
      }
      renderProgressStrip();
    },
    finish() {
      if (finished) {
        return;
      }
      finished = true;
      state.activeActions.delete(id);
      renderProgressStrip();
    },
  };
}

function collectActiveProgressItems() {
  const items = [];
  const activeBuildServices = new Set();
  const activeGitRepos = new Set();

  for (const entry of state.activeStarts.values()) {
    items.push({
      title: 'Starting Processes',
      target: entry.target || entry.label,
      message: entry.message,
    });
  }

  for (const entry of state.activeActions.values()) {
    items.push({
      title: entry.title,
      target: entry.target,
      message: entry.message,
    });
    if (entry.buildService) {
      activeBuildServices.add(entry.buildService);
    }
    if (entry.gitRepo) {
      activeGitRepos.add(entry.gitRepo);
    }
  }

  const services = state.estate?.services || [];
  const seenRunningGitRepos = new Set();
  for (const row of services) {
    if (row.buildRun?.displayState === 'RUNNING' && !activeBuildServices.has(row.service)) {
      items.push({
        title: 'Building Services',
        target: row.service,
        message: 'Build Run in progress…',
      });
    }
    const git = row.git;
    if (
      git?.latestRun?.status === 'RUNNING' &&
      git.repository &&
      !activeGitRepos.has(git.repository) &&
      !seenRunningGitRepos.has(git.repository)
    ) {
      seenRunningGitRepos.add(git.repository);
      const op = git.latestRun.operation ? `${git.latestRun.operation} ` : '';
      items.push({
        title: 'Running Git Operations',
        target: git.repository,
        message: `Git ${op}in progress…`,
      });
    }
  }

  return items;
}

function renderProgressStrip() {
  const items = collectActiveProgressItems();
  if (items.length === 0) {
    dom.progressStrip.hidden = true;
    dom.progressList.replaceChildren();
    return;
  }

  const uniqueTitles = new Set(items.map((item) => item.title).filter(Boolean));
  dom.progressStripTitle.textContent =
    uniqueTitles.size === 1 ? [...uniqueTitles][0] : 'Running Actions';

  dom.progressList.replaceChildren();
  for (const item of items) {
    const li = el('li');
    if (item.target) {
      li.append(el('span', 'progress-target', `[${item.target}]`));
    }
    li.append(el('span', '', item.message));
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
  let activity = null;
  if (explicit) {
    dom.btnRefresh.disabled = true;
    activity = beginActivity({
      title: 'Running Discovery',
      target: state.estate?.project || 'project',
      message: 'Running Discovery…',
    });
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
    const sidebarFocusKey =
      activeEl instanceof HTMLElement &&
      dom.logsTargetList.contains(activeEl) &&
      activeEl.dataset.targetType
        ? { targetType: activeEl.dataset.targetType, service: activeEl.dataset.service || '' }
        : null;

    renderHeader();
    renderGitHooks();
    renderGateway();
    renderProfiles();
    renderServices();
    renderProgressStrip();
    renderLogsSidebar();

    if (state.view === 'logs' && !state.logs.targetType) {
      if (ensureLogsTargetSelected()) {
        renderViewTabs();
        renderLogsHeader();
        renderLogsSidebar();
        startActiveLogStream();
      }
    }

    if (focusKey) {
      const targetBtn = Array.from(dom.servicesTbody.querySelectorAll('button')).find(
        (b) => b.dataset.service === focusKey.service && b.dataset.action === focusKey.action,
      );
      if (targetBtn && !targetBtn.disabled) {
        targetBtn.focus({ preventScroll: true });
      }
    } else if (sidebarFocusKey) {
      const targetBtn = Array.from(dom.logsTargetList.querySelectorAll('button')).find(
        (b) =>
          b.dataset.targetType === sidebarFocusKey.targetType &&
          (b.dataset.service || '') === sidebarFocusKey.service,
      );
      if (targetBtn) {
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
    activity?.finish();
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

async function runAction(
  controls,
  actionFn,
  { onSettled = null, autoReconcile = false, activity = null } = {},
) {
  const list = Array.from(controls || []).filter(Boolean);
  for (const control of list) {
    control.disabled = true;
  }
  const active = activity ? beginActivity(activity) : null;
  let actionSucceeded = false;
  try {
    const payload = await actionFn();
    actionSucceeded = true;
    if (payload && payload.cliCommand) {
      setCliCommand(payload.cliCommand, { explicit: true });
    }
    if (autoReconcile && canAutoReconcileGateway()) {
      dom.btnGatewayReconcile.disabled = true;
      active?.update(GATEWAY_RECONCILE_ACTIVITY);
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
    active?.finish();
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
    activity: {
      title: 'Updating Route',
      target: serviceName,
      message: `Setting Route to ${targetRoute}…`,
    },
  });
}

function handleUseProfile(profileName, btn) {
  state.pendingProfiles.add(profileName);
  return runAction([btn], () => useProfile(profileName), {
    onSettled: () => state.pendingProfiles.delete(profileName),
    autoReconcile: true,
    activity: {
      title: 'Applying Profile',
      target: profileName,
      message: 'Applying Profile…',
    },
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
  const isBulk = services.length === 0;
  return runAction([triggerBtn], () => stopServices(services, { all: false, killConflict: false }), {
    onSettled: () => {
      for (const s of services) {
        state.pendingStops.delete(s);
      }
    },
    activity: {
      title: 'Stopping Processes',
      target: isBulk ? 'Managed' : services.join(', '),
      message: isBulk ? 'Stopping Managed Processes…' : 'Stopping Process…',
    },
  });
}

/* ---------- Top-Level Views: Services & Fullscreen Logs ---------- */

function renderViewTabs() {
  const isLogs = state.view === 'logs';
  dom.appShell.classList.toggle('mode-logs', isLogs);

  dom.navTabServices.classList.toggle('active', !isLogs);
  dom.navTabServices.setAttribute('aria-selected', String(!isLogs));
  dom.navTabLogs.classList.toggle('active', isLogs);
  dom.navTabLogs.setAttribute('aria-selected', String(isLogs));

  dom.viewServices.hidden = isLogs;
  dom.viewLogs.hidden = !isLogs;

  if (state.logs.targetType === 'gateway') {
    dom.navTabLogsTarget.textContent = '· gateway';
    dom.navTabLogsTarget.hidden = false;
  } else if (state.logs.targetType === 'service' && state.logs.targetService) {
    dom.navTabLogsTarget.textContent = `· ${state.logs.targetService}`;
    dom.navTabLogsTarget.hidden = false;
  } else {
    dom.navTabLogsTarget.textContent = '';
    dom.navTabLogsTarget.hidden = true;
  }
}

function ensureLogsTargetSelected() {
  const services = state.estate?.services || [];
  const gatewayConfigured = Boolean(state.estate?.gateway?.configured);

  if (state.logs.targetType === 'gateway' && gatewayConfigured) {
    return true;
  }
  if (state.logs.targetType === 'service' && state.logs.targetService) {
    if (services.length === 0 || services.some((s) => s.service === state.logs.targetService)) {
      return true;
    }
  }

  const sorted = sortServicesLocalFirst(services);
  const firstPriority = sorted.find(isPriorityService);
  if (firstPriority) {
    state.logs.targetType = 'service';
    state.logs.targetService = firstPriority.service;
    return true;
  }
  if (gatewayConfigured) {
    state.logs.targetType = 'gateway';
    state.logs.targetService = null;
    state.logs.build = false;
    return true;
  }
  if (sorted.length > 0) {
    state.logs.targetType = 'service';
    state.logs.targetService = sorted[0].service;
    return true;
  }
  return false;
}

function switchAppView(view) {
  if (view !== 'services' && view !== 'logs') {
    return;
  }
  if (state.view === view) {
    return;
  }

  if (state.view === 'logs') {
    closeLogStream();
  }

  state.view = view;
  if (view === 'logs') {
    ensureLogsTargetSelected();
    renderViewTabs();
    renderLogsHeader();
    renderLogsSidebar();
    startActiveLogStream();
  } else {
    renderViewTabs();
  }
}

function openLogsView(targetType, serviceName = null, { build = null, git = null, err = null } = {}) {
  const prevTargetType = state.logs.targetType;
  const prevTargetService = state.logs.targetService;
  const prevBuild = state.logs.build;
  const prevGit = state.logs.git;
  const prevErr = state.logs.err;

  state.logs.targetType = targetType;
  state.logs.targetService = targetType === 'service' ? serviceName : null;

  if (targetType === 'gateway') {
    state.logs.build = false;
    state.logs.git = false;
  } else {
    if (build !== null) {
      state.logs.build = Boolean(build);
      if (state.logs.build) state.logs.git = false;
    }
    if (git !== null) {
      state.logs.git = Boolean(git);
      if (state.logs.git) state.logs.build = false;
    }
  }
  if (err !== null) {
    state.logs.err = Boolean(err);
  }

  const wasLogs = state.view === 'logs';
  state.view = 'logs';

  renderViewTabs();
  renderLogsHeader();
  renderLogsSidebar();

  const changed =
    !wasLogs ||
    prevTargetType !== state.logs.targetType ||
    prevTargetService !== state.logs.targetService ||
    prevBuild !== state.logs.build ||
    prevGit !== state.logs.git ||
    prevErr !== state.logs.err ||
    !state.logs.stream;

  if (changed) {
    startActiveLogStream();
  }
}

function renderLogsHeader() {
  const isGateway = state.logs.targetType === 'gateway';
  dom.logsTargetKind.textContent = isGateway ? 'Gateway' : 'Service';
  dom.logsTargetName.textContent = isGateway ? 'gateway' : (state.logs.targetService || '—');
  dom.logsSourceGroup.hidden = isGateway;

  const isServiceSource = !state.logs.build && !state.logs.git;
  dom.btnLogsSourceService.classList.toggle('active', isServiceSource);
  dom.btnLogsSourceService.setAttribute('aria-pressed', String(isServiceSource));
  dom.btnLogsSourceBuild.classList.toggle('active', state.logs.build);
  dom.btnLogsSourceBuild.setAttribute('aria-pressed', String(state.logs.build));
  dom.btnLogsSourceGit.classList.toggle('active', state.logs.git);
  dom.btnLogsSourceGit.setAttribute('aria-pressed', String(state.logs.git));
  dom.btnLogsStdout.classList.toggle('active', !state.logs.err);
  dom.btnLogsStdout.setAttribute('aria-pressed', String(!state.logs.err));
  dom.btnLogsStderr.classList.toggle('active', state.logs.err);
  dom.btnLogsStderr.setAttribute('aria-pressed', String(state.logs.err));
}

function renderLogsSidebar() {
  dom.logsTargetList.replaceChildren();
  const query = state.logs.sidebarQuery;
  const gw = state.estate?.gateway;
  const services = sortServicesLocalFirst(state.estate?.services || []);

  let itemCount = 0;

  if (gw && gw.configured && matchesOrQuery('gateway', query)) {
    const isActive = state.logs.targetType === 'gateway';
    const item = el('button', `logs-target-item${isActive ? ' active' : ''}`);
    item.type = 'button';
    if (isActive) {
      item.setAttribute('aria-current', 'true');
    }
    item.dataset.targetType = 'gateway';

    const left = el('span', 'logs-target-item-left');
    left.append(
      el('span', `target-dot ${targetDotClass(gw.health)}`),
      el('span', 'logs-target-item-name', 'gateway'),
    );
    item.append(left);
    item.addEventListener('click', () => openLogsView('gateway', null));
    dom.logsTargetList.append(item);
    itemCount += 1;
  }

  const filteredServices = services.filter((row) => matchesOrQuery(row.service, query));
  if (filteredServices.length > 0 && gw && gw.configured) {
    dom.logsTargetList.append(el('div', 'logs-target-group-label', 'Services'));
  }

  for (const row of filteredServices) {
    const isActive = state.logs.targetType === 'service' && state.logs.targetService === row.service;
    const item = el('button', `logs-target-item${isActive ? ' active' : ''}`);
    item.type = 'button';
    if (isActive) {
      item.setAttribute('aria-current', 'true');
    }
    item.dataset.targetType = 'service';
    item.dataset.service = row.service;

    const left = el('span', 'logs-target-item-left');
    left.append(
      el('span', `target-dot ${targetDotClass(row.health)}`),
      el('span', 'logs-target-item-name', row.service),
    );
    item.append(left);

    if (row.buildRun?.displayState === 'RUNNING') {
      item.append(el('span', 'logs-target-tag logs-target-tag-building', 'BUILD'));
    } else if (row.route === 'LOCAL') {
      item.append(el('span', 'logs-target-tag logs-target-tag-local', 'LOCAL'));
    }

    item.addEventListener('click', () => openLogsView('service', row.service));
    dom.logsTargetList.append(item);
    itemCount += 1;
  }

  if (itemCount === 0) {
    dom.logsTargetList.append(
      el('div', 'logs-sidebar-empty', query.trim() ? 'No matching targets.' : 'No targets available.'),
    );
  }
}

/* ---------- Detail Drawer: Inspect ---------- */

function openInspectDrawer(serviceName) {
  state.drawer.open = true;
  state.drawer.service = serviceName;

  dom.drawerTargetLabel.textContent = 'Inspect';
  dom.drawerServiceName.textContent = serviceName;
  dom.detailDrawer.hidden = false;

  renderServices();
  loadInspection(serviceName);

  dom.detailDrawer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function closeDrawer() {
  state.drawer.open = false;
  state.drawer.service = null;
  dom.detailDrawer.hidden = true;
  renderServices();
}

async function loadInspection(serviceName) {
  dom.inspectStatusNote.textContent = 'Running Inspection…';
  dom.btnInspectRefresh.disabled = true;
  const activity = beginActivity({
    title: 'Inspecting Service',
    target: serviceName,
    message: 'Running Inspection…',
  });
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
    activity.finish();
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
    ['RSS', formatBytes(report.rssBytes)],
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
  if (report.git) {
    items.push(['Repository', `${report.git.repository} (${report.git.root})`]);
    items.push(['Git Status', report.git.compact || report.git.state]);
    if (report.git.upstream) {
      items.push(['Git Upstream', `${report.git.upstream} (↑${report.git.ahead} ↓${report.git.behind})`]);
    }
    if (report.git.lastFetchedAt) {
      items.push(['Last Fetched', report.git.lastFetchedAt]);
    }
    if (report.git.latestRun) {
      items.push(['Latest Git Run', `${report.git.latestRun.status} (${report.git.latestRun.operation})`]);
      if (report.git.latestRun.reason) {
        items.push(['Git Run Reason', report.git.latestRun.reason]);
      }
      if (report.git.latestRun.stdoutLog) {
        items.push(['Git Stdout Log', report.git.latestRun.stdoutLog]);
      }
      if (report.git.latestRun.stderrLog) {
        items.push(['Git Stderr Log', report.git.latestRun.stderrLog]);
      }
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

  // Context-aware diagnostic commands
  if (Array.isArray(report.diagnosticCommands) && report.diagnosticCommands.length > 0) {
    const diagBlock = el('div', 'inspect-block');
    diagBlock.append(el('span', 'inspect-block-title', 'Diagnostic Commands'));
    const list = el('ul', 'diag-cmd-list');
    for (const item of report.diagnosticCommands) {
      const li = el('li', 'diag-cmd-item');
      const header = el('div', 'diag-cmd-header');
      header.append(el('span', 'diag-cmd-label', item.label || 'Command'));
      if (item.note) {
        header.append(el('span', 'diag-cmd-note muted', item.note));
      }
      const row = el('div', 'diag-cmd-row');
      row.append(el('code', 'diag-cmd-code mono', item.command));
      const copyBtn = el('button', 'btn btn-secondary btn-xs', 'Copy');
      copyBtn.type = 'button';
      copyBtn.title = `Copy ${item.label || 'command'}`;
      copyBtn.addEventListener('click', () => copyTextToClipboard(item.command, copyBtn, 'Copy'));
      row.append(copyBtn);
      li.append(header, row);
      list.append(li);
    }
    diagBlock.append(list);
    dom.inspectContent.append(diagBlock);
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

function renderLogsHistoryBanner() {
  ensureLogsContainers();
  logsHistoryBannerEl.replaceChildren();

  if (state.logs.entries.length === 0 && !state.logs.hasOlder && !state.logs.truncated) {
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

  if (state.logs.entries.length > 0) {
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
  if (state.logs.entries.length <= cap) {
    return;
  }
  const excess = state.logs.entries.length - cap;
  const query = state.logs.filterQuery;
  ensureLogsContainers();
  for (let i = 0; i < excess; i += 1) {
    const evicted = state.logs.entries[i];
    if (evicted && matchesOrQuery(evicted.line, query) && state.logs.matchedCount > 0) {
      if (logsLinesEl.firstElementChild) {
        logsLinesEl.firstElementChild.remove();
      }
      state.logs.matchedCount = Math.max(0, state.logs.matchedCount - 1);
    }
  }
  state.logs.entries.splice(0, excess);
  if (state.logs.matchedCount === 0 && state.logs.entries.length > 0) {
    logsLinesEl.replaceChildren(el('div', 'log-empty', 'No log lines match the current filter.'));
  }
  updateLogsLineCount();
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

function hasActiveLogFilter() {
  return parseOrTerms(state.logs.filterQuery).length > 0;
}

function updateLogsLineCount() {
  const total = state.logs.entries.length;
  if (hasActiveLogFilter()) {
    dom.logsLineCount.textContent = `${state.logs.matchedCount} / ${total} ${total === 1 ? 'line' : 'lines'}`;
  } else {
    dom.logsLineCount.textContent = `${total} ${total === 1 ? 'line' : 'lines'}`;
  }
}

function clearLogsOutput({ resetHistory = false } = {}) {
  ensureLogsContainers();
  cancelPendingLogFlush();
  state.logs.entries = [];
  state.logs.matchedCount = 0;
  if (resetHistory) {
    state.logs.startOffset = 0;
    state.logs.hasOlder = false;
    state.logs.loadingOlder = false;
    state.logs.truncated = false;
  }
  updateLogsLineCount();
  logsLinesEl.replaceChildren(el('div', 'log-empty', 'Waiting for log output…'));
  renderLogsHistoryBanner();
}

function renderFilteredLogs() {
  ensureLogsContainers();
  const query = state.logs.filterQuery;
  const total = state.logs.entries.length;

  if (total === 0) {
    state.logs.matchedCount = 0;
    updateLogsLineCount();
    logsLinesEl.replaceChildren(el('div', 'log-empty', 'Waiting for log output…'));
    renderLogsHistoryBanner();
    return;
  }

  const fragment = document.createDocumentFragment();
  let matched = 0;
  for (const entry of state.logs.entries) {
    if (matchesOrQuery(entry.line, query)) {
      fragment.append(el('div', entry.stderr ? 'log-line log-line-err' : 'log-line', entry.line));
      matched += 1;
    }
  }

  state.logs.matchedCount = matched;
  updateLogsLineCount();

  if (matched === 0) {
    logsLinesEl.replaceChildren(el('div', 'log-empty', 'No log lines match the current filter.'));
  } else {
    logsLinesEl.replaceChildren(fragment);
  }
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

  const prevTotal = state.logs.entries.length;
  const query = state.logs.filterQuery;
  const frag = document.createDocumentFragment();
  let addedMatched = 0;

  for (const item of batch) {
    const entry = { line: item.line, stderr: item.isStderr };
    state.logs.entries.push(entry);
    if (matchesOrQuery(entry.line, query)) {
      frag.append(el('div', entry.stderr ? 'log-line log-line-err' : 'log-line', entry.line));
      addedMatched += 1;
    }
  }

  if (addedMatched > 0) {
    if (state.logs.matchedCount === 0) {
      logsLinesEl.replaceChildren();
    }
    logsLinesEl.append(frag);
    state.logs.matchedCount += addedMatched;
  } else if (state.logs.matchedCount === 0 && state.logs.entries.length > 0) {
    logsLinesEl.replaceChildren(el('div', 'log-empty', 'No log lines match the current filter.'));
  }

  const activeCap = state.logs.autoScroll ? MAX_LOG_LINES : MAX_PAUSED_LOG_LINES;
  trimLogsToCap(activeCap);
  updateLogsLineCount();

  if (prevTotal === 0) {
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
  if (state.view !== 'logs' || !state.logs.targetType) {
    return;
  }
  if (!state.logs.hasOlder || state.logs.loadingOlder || state.logs.startOffset <= 0) {
    return;
  }
  const seq = state.logs.streamSeq;
  const beforeOffset = state.logs.startOffset;
  const isStderr = state.logs.err;

  state.logs.loadingOlder = true;
  renderLogsHistoryBanner();

  try {
    const payload =
      state.logs.targetType === 'gateway'
        ? await fetchGatewayLogs({
            lines: OLDER_LOG_PAGE_LINES,
            err: isStderr,
            beforeOffset,
          })
        : await fetchServiceLogs(state.logs.targetService, {
            lines: OLDER_LOG_PAGE_LINES,
            err: isStderr,
            build: state.logs.build,
            git: state.logs.git,
            beforeOffset,
          });

    if (seq !== state.logs.streamSeq) {
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
      const olderEntries = olderLines.map((line) => ({ line, stderr: isStderr }));
      state.logs.entries = olderEntries.concat(state.logs.entries);

      const query = state.logs.filterQuery;
      const frag = document.createDocumentFragment();
      let addedMatched = 0;
      for (const entry of olderEntries) {
        if (matchesOrQuery(entry.line, query)) {
          frag.append(el('div', entry.stderr ? 'log-line log-line-err' : 'log-line', entry.line));
          addedMatched += 1;
        }
      }
      if (addedMatched > 0) {
        if (state.logs.matchedCount === 0) {
          logsLinesEl.replaceChildren();
        }
        logsLinesEl.prepend(frag);
        state.logs.matchedCount += addedMatched;
      } else if (state.logs.matchedCount === 0) {
        logsLinesEl.replaceChildren(el('div', 'log-empty', 'No log lines match the current filter.'));
      }
      updateLogsLineCount();
    }

    renderLogsHistoryBanner();

    const heightDelta = dom.logsOutput.scrollHeight - prevScrollHeight;
    if (heightDelta !== 0) {
      runProgrammaticScroll(() => {
        dom.logsOutput.scrollTop = Math.max(0, prevScrollTop + heightDelta);
      });
    }
  } catch (err) {
    if (seq !== state.logs.streamSeq) {
      return;
    }
    state.logs.loadingOlder = false;
    renderLogsHistoryBanner();
    showError(err);
  }
}

function closeLogStream() {
  state.logs.streamSeq += 1;
  state.logs.loadingOlder = false;
  cancelPendingLogFlush();
  if (state.logs.stream) {
    state.logs.stream.close();
    state.logs.stream = null;
  }
  dom.btnLogsReconnect.hidden = true;
  setLogsStreamBadge('Disconnected', 'badge-neutral');
}

function closePillMenu() {
  if (!state.pillMenu.open) return;
  state.pillMenu.open = false;
  state.pillMenu.kind = null;
  state.pillMenu.service = null;
  dom.pillActionMenu.hidden = true;
  dom.pillActionMenu.replaceChildren();
}

function addPillMenuItem(label, { danger = false, disabled = false, title = '', action = '', onClick } = {}) {
  const btn = el('button', `pill-menu-item${danger ? ' pill-menu-item-danger' : ''}`, label);
  btn.type = 'button';
  btn.setAttribute('role', 'menuitem');
  btn.disabled = disabled;
  if (title) btn.title = title;
  if (action) btn.dataset.action = action;
  btn.addEventListener('click', (event) => {
    event.stopPropagation();
    closePillMenu();
    if (onClick) onClick(btn);
  });
  dom.pillActionMenu.append(btn);
}

function openPillMenu(kind, row, anchorEl) {
  if (state.pillMenu.open && state.pillMenu.kind === kind && state.pillMenu.service === row.service) {
    closePillMenu();
    return;
  }
  dom.pillActionMenu.replaceChildren();
  state.pillMenu.open = true;
  state.pillMenu.kind = kind;
  state.pillMenu.service = row.service;

  if (kind === 'health') {
    dom.pillActionMenu.append(el('div', 'pill-menu-header mono', `Process · ${row.service}`));
    const starting = isServiceStarting(row.service);
    addPillMenuItem(starting ? 'Starting…' : 'Start', {
      disabled: starting,
      action: 'start',
      title: `Start ${row.service}`,
      onClick: () => handleStartServices([row.service]),
    });
    if (row.owner === 'CONFLICT') {
      addPillMenuItem('Kill Conflict…', {
        danger: true,
        action: 'kill-conflict',
        title: `Inspect and kill conflicting Process on port ${row.port ?? ''}`,
        onClick: () => openConflictDialog(row.service),
      });
    } else {
      const stopPending = state.pendingStops.has(row.service);
      const canStop = row.owner === 'MANAGED' && !stopPending;
      const stopTitle =
        row.owner === 'MANAGED'
          ? `Stop Managed Process for ${row.service}`
          : row.owner === 'UNMANAGED'
            ? 'Unmanaged Process — never killed by procomp'
            : 'No running Process';
      addPillMenuItem('Stop', {
        disabled: !canStop,
        action: 'stop',
        title: stopTitle,
        onClick: (btn) => handleStopServices([row.service], btn),
      });
    }
    dom.pillActionMenu.append(el('div', 'pill-menu-divider'));
    addPillMenuItem('Inspect', {
      action: 'inspect',
      title: `Inspect ${row.service}`,
      onClick: () => openInspectDrawer(row.service),
    });
    addPillMenuItem('Logs', {
      action: 'logs',
      title: `Stream live Logs for ${row.service}`,
      onClick: () => openLogsView('service', row.service, { build: false, git: false }),
    });
  } else if (kind === 'build') {
    const buildRunState = row.buildRun?.displayState || 'NO_BUILDER';
    dom.pillActionMenu.append(el('div', 'pill-menu-header mono', `Build · ${row.service}`));
    if (buildRunState === 'RUNNING') {
      addPillMenuItem('Cancel Build', {
        danger: true,
        action: 'cancel-build',
        title: `Cancel active Build Run for ${row.service}`,
        onClick: (btn) => handleCancelBuild(row.service, btn),
      });
    } else if (buildRunState === 'UNKNOWN') {
      addPillMenuItem('Force Build', {
        action: 'build',
        title: `Clear UNKNOWN Build Run and rebuild ${row.service}`,
        onClick: (btn) => handleBuildService(row.service, true, btn),
      });
    } else {
      addPillMenuItem('Build', {
        action: 'build',
        title: `Build ${row.service}`,
        onClick: (btn) => handleBuildService(row.service, false, btn),
      });
    }
    if (hasBuildRunLogs(buildRunState)) {
      dom.pillActionMenu.append(el('div', 'pill-menu-divider'));
      addPillMenuItem('Build Logs', {
        action: 'build-logs',
        title: `View Build logs for ${row.service}`,
        onClick: () => openLogsView('service', row.service, { build: true, git: false, err: false }),
      });
    }
  } else if (kind === 'git' && row.git) {
    const git = row.git;
    const sharedSuffix = git.services && git.services.length > 1 ? ` (${git.services.length} services)` : '';
    dom.pillActionMenu.append(el('div', 'pill-menu-header mono', `Git · ${git.repository}${sharedSuffix}`));
    const gitRunning = git.latestRun?.status === 'RUNNING';
    if (gitRunning) {
      addPillMenuItem('Cancel Git', {
        danger: true,
        action: 'git-cancel',
        title: `Cancel running Git Run for ${git.repository}`,
        onClick: (btn) =>
          runAction([btn], () => gitCancel([git.repository]), {
            activity: {
              gitRepo: git.repository,
              title: 'Canceling Git Run',
              target: git.repository,
              message: 'Canceling Git Run…',
            },
          }),
      });
    } else if (git.state === 'NOT_CLONED') {
      addPillMenuItem('Clone', {
        action: 'git-clone',
        title: `Clone Repository ${git.repository}`,
        onClick: (btn) =>
          runAction([btn], () => gitClone([git.repository]), {
            activity: {
              gitRepo: git.repository,
              title: 'Cloning Repositories',
              target: git.repository,
              message: 'Cloning Repository…',
            },
          }),
      });
    } else if (git.state === 'CLONED') {
      addPillMenuItem('Fetch', {
        action: 'git-fetch',
        title: `Fetch Repository ${git.repository}`,
        onClick: (btn) =>
          runAction([btn], () => gitFetch([git.repository]), {
            activity: {
              gitRepo: git.repository,
              title: 'Fetching Repositories',
              target: git.repository,
              message: 'Fetching Repository…',
            },
          }),
      });
      addPillMenuItem('Pull', {
        disabled: Boolean(git.dirty),
        action: 'git-pull',
        title: git.dirty
          ? `Cannot pull ${git.repository}: working tree is dirty`
          : `Fast-forward pull Repository ${git.repository}`,
        onClick: (btn) =>
          runAction([btn], () => gitPull([git.repository]), {
            activity: {
              gitRepo: git.repository,
              title: 'Pulling Repositories',
              target: git.repository,
              message: 'Pulling Repository…',
            },
          }),
      });
      addPillMenuItem('Switch…', {
        action: 'git-switch',
        title: `Switch branch for Repository ${git.repository}`,
        onClick: () => openGitSwitchDialog(git.repository, row.service),
      });
      addPillMenuItem('Stash (incl. untracked)', {
        disabled: !git.dirty,
        action: 'git-stash',
        title: git.dirty
          ? `Stash uncommitted and untracked changes in Repository ${git.repository}`
          : `Working tree for ${git.repository} is clean`,
        onClick: (btn) =>
          runAction([btn], () => gitStash({ targets: [git.repository], includeUntracked: true }), {
            activity: {
              gitRepo: git.repository,
              title: 'Stashing Repositories',
              target: git.repository,
              message: 'Stashing changes…',
            },
          }),
      });
    }
    if (git.latestRun) {
      dom.pillActionMenu.append(el('div', 'pill-menu-divider'));
      addPillMenuItem('Git Logs', {
        action: 'git-logs',
        title: `View Git logs for ${git.repository}`,
        onClick: () => openLogsView('service', row.service, { build: false, git: true, err: false }),
      });
    }
  }

  dom.pillActionMenu.hidden = false;
  const rect = anchorEl.getBoundingClientRect();
  const menuRect = dom.pillActionMenu.getBoundingClientRect();
  const top = Math.min(rect.bottom + 4, window.innerHeight - menuRect.height - 8);
  const left = Math.min(rect.left, window.innerWidth - menuRect.width - 8);
  dom.pillActionMenu.style.top = `${Math.max(8, top)}px`;
  dom.pillActionMenu.style.left = `${Math.max(8, left)}px`;
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

function gitBadgeClass(git) {
  if (!git) return 'badge-neutral';
  if (git.latestRun?.status === 'RUNNING') return 'badge-starting';
  if (git.latestRun?.status === 'UNKNOWN' || git.state === 'NOT_GIT') return 'badge-danger';
  if (git.state === 'NOT_CLONED') return 'badge-neutral';
  if (git.dirty || git.fetchAgeWarning) return 'badge-stale';
  if (git.behind > 0) return 'badge-starting';
  return 'badge-healthy';
}

async function openGitSwitchDialog(repoName, serviceName) {
  state.gitSwitch.repository = repoName;
  state.gitSwitch.services = [];
  state.gitSwitch.dirty = false;
  state.gitSwitch.branches = [];
  state.gitSwitch.selectedBranch = null;
  state.gitSwitch.filter = '';

  dom.gitSwitchRepoName.textContent = repoName;
  dom.gitSwitchServices.textContent = '';
  dom.gitSwitchFetched.textContent = '';
  dom.gitSwitchFilter.value = '';
  dom.gitSwitchError.hidden = true;
  dom.gitSwitchDirtyBanner.hidden = true;
  dom.gitSwitchBranchList.replaceChildren();
  dom.gitSwitchLoading.hidden = false;
  dom.btnGitSwitchConfirm.disabled = true;

  if (!dom.gitSwitchDialog.open) {
    dom.gitSwitchDialog.showModal();
  }
  await loadGitSwitchBranches(repoName);
}

async function loadGitSwitchBranches(repoName) {
  dom.gitSwitchLoading.hidden = false;
  dom.gitSwitchError.hidden = true;
  try {
    const payload = await fetchGitBranches(repoName);
    if (payload.cliCommand) {
      setCliCommand(payload.cliCommand, { explicit: true });
    }
    const data = payload.data;
    state.gitSwitch.services = data.services || [];
    state.gitSwitch.dirty = Boolean(data.dirty);
    state.gitSwitch.branches = data.branches || [];

    dom.gitSwitchServices.textContent =
      state.gitSwitch.services.length > 0
        ? ` (shared by: ${state.gitSwitch.services.join(', ')})`
        : '';
    dom.gitSwitchFetched.textContent = data.lastFetchedAt
      ? `Fetched: ${data.lastFetchedAt.replace('T', ' ').slice(0, 16)}Z`
      : 'Never fetched';

    if (state.gitSwitch.dirty) {
      dom.gitSwitchDirtySummary.textContent =
        `Working tree has uncommitted or untracked changes (${data.stagedCount || 0} staged, ${data.unstagedCount || 0} modified, ${data.untrackedCount || 0} untracked). Stash before switching.`;
      dom.gitSwitchDirtyBanner.hidden = false;
    } else {
      dom.gitSwitchDirtyBanner.hidden = true;
    }
    dom.btnGitSwitchDefault.disabled = state.gitSwitch.dirty;
    renderGitSwitchBranchList();
  } catch (err) {
    dom.gitSwitchError.textContent = err instanceof Error ? err.message : String(err);
    dom.gitSwitchError.hidden = false;
  } finally {
    dom.gitSwitchLoading.hidden = true;
  }
}

function renderGitSwitchBranchList() {
  dom.gitSwitchBranchList.replaceChildren();
  const q = state.gitSwitch.filter.trim().toLowerCase();
  const filtered = state.gitSwitch.branches.filter((b) =>
    !q || b.name.toLowerCase().includes(q) || (b.shortSubject || '').toLowerCase().includes(q),
  );
  if (filtered.length === 0) {
    dom.gitSwitchBranchList.append(el('div', 'muted git-branch-empty', 'No matching branches.'));
  } else {
    for (const b of filtered) {
      const isSelected = state.gitSwitch.selectedBranch === b.name;
      const btn = el('button', `git-branch-item${isSelected ? ' selected' : ''}${b.current ? ' current' : ''}`);
      btn.type = 'button';
      btn.setAttribute('role', 'option');
      btn.setAttribute('aria-selected', String(isSelected));
      const top = el('div', 'git-branch-top');
      top.append(
        el('span', 'mono git-branch-name', (b.current ? '* ' : '') + b.name),
        el('span', 'badge badge-neutral git-branch-origin', b.origin),
        el('span', 'mono muted git-branch-date', (b.lastCommitAt || '').replace('T', ' ').slice(0, 16) + 'Z'),
      );
      btn.append(top);
      if (b.shortSubject) {
        btn.append(el('div', 'muted git-branch-subject', b.shortSubject));
      }
      btn.addEventListener('click', () => {
        state.gitSwitch.selectedBranch = b.name;
        renderGitSwitchBranchList();
      });
      dom.gitSwitchBranchList.append(btn);
    }
  }
  dom.btnGitSwitchConfirm.disabled = state.gitSwitch.dirty || !state.gitSwitch.selectedBranch;
}

function closeGitSwitchDialog() {
  if (dom.gitSwitchDialog.open) {
    dom.gitSwitchDialog.close();
  }
  state.gitSwitch.repository = null;
}

async function handleGitSwitchFetch() {
  const repo = state.gitSwitch.repository;
  if (!repo) return;
  dom.btnGitSwitchFetch.disabled = true;
  dom.gitSwitchError.hidden = true;
  const activity = beginActivity({
    gitRepo: repo,
    title: 'Fetching Repositories',
    target: repo,
    message: 'Fetching Repository…',
  });
  try {
    const payload = await gitFetch([repo]);
    if (payload.cliCommand) setCliCommand(payload.cliCommand, { explicit: true });
    await loadGitSwitchBranches(repo);
    await refreshStatus({ explicit: false });
  } catch (err) {
    dom.gitSwitchError.textContent = err instanceof Error ? err.message : String(err);
    dom.gitSwitchError.hidden = false;
  } finally {
    activity.finish();
    dom.btnGitSwitchFetch.disabled = false;
  }
}

async function handleGitSwitchStash() {
  const repo = state.gitSwitch.repository;
  if (!repo) return;
  dom.btnGitSwitchStash.disabled = true;
  dom.gitSwitchError.hidden = true;
  const activity = beginActivity({
    gitRepo: repo,
    title: 'Stashing Repositories',
    target: repo,
    message: 'Stashing changes…',
  });
  try {
    const payload = await gitStash({ targets: [repo], includeUntracked: true });
    if (payload.cliCommand) setCliCommand(payload.cliCommand, { explicit: true });
    await loadGitSwitchBranches(repo);
    await refreshStatus({ explicit: false });
  } catch (err) {
    dom.gitSwitchError.textContent = err instanceof Error ? err.message : String(err);
    dom.gitSwitchError.hidden = false;
  } finally {
    activity.finish();
    dom.btnGitSwitchStash.disabled = false;
  }
}

async function handleGitSwitchExecute(useDefault) {
  const repo = state.gitSwitch.repository;
  if (!repo) return;
  const branch = useDefault ? null : state.gitSwitch.selectedBranch;
  if (!useDefault && !branch) return;
  dom.btnGitSwitchConfirm.disabled = true;
  dom.btnGitSwitchDefault.disabled = true;
  dom.gitSwitchError.hidden = true;
  const activity = beginActivity({
    gitRepo: repo,
    title: 'Switching Branch',
    target: repo,
    message: useDefault ? 'Switching to default branch…' : `Switching to ${branch}…`,
  });
  try {
    const payload = await gitSwitch({ branch, useDefault, targets: [repo] });
    if (payload.cliCommand) setCliCommand(payload.cliCommand, { explicit: true });
    closeGitSwitchDialog();
    await refreshStatus({ explicit: false });
  } catch (err) {
    dom.gitSwitchError.textContent = err instanceof Error ? err.message : String(err);
    dom.gitSwitchError.hidden = false;
    await loadGitSwitchBranches(repo);
  } finally {
    activity.finish();
  }
}

function handleBuildService(serviceName, force, button) {
  return runAction([button], () => buildService(serviceName, { force }), {
    activity: {
      buildService: serviceName,
      title: 'Building Services',
      target: serviceName,
      message: force ? 'Starting forced Build Run…' : 'Starting Build Run…',
    },
  });
}

function handleCancelBuild(serviceName, button) {
  return runAction([button], () => cancelBuild(serviceName), {
    activity: {
      buildService: serviceName,
      title: 'Canceling Build',
      target: serviceName,
      message: 'Canceling Build Run…',
    },
  });
}

function startActiveLogStream() {
  if (state.view !== 'logs' || !state.logs.targetType) {
    return;
  }
  closeLogStream();
  const seq = ++state.logs.streamSeq;
  clearLogsOutput({ resetHistory: true });
  setLogsAutoScroll(true, { scrollToBottom: false });
  renderLogsHeader();
  dom.btnLogsReconnect.hidden = true;
  setLogsStreamBadge('Connecting…', 'badge-starting');

  let logHistoryEntry = null;
  const handlers = {
    lines: INITIAL_LOG_LINES,
    err: state.logs.err,
    build: state.logs.build,
    git: state.logs.git,
    onOpen() {
      if (seq !== state.logs.streamSeq) {
        return;
      }
      dom.btnLogsReconnect.hidden = true;
      setLogsStreamBadge('Streaming', 'badge-healthy');
    },
    onMeta(payload) {
      if (seq !== state.logs.streamSeq) {
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
      if (seq !== state.logs.streamSeq) {
        return;
      }
      appendLogLine(payload.line ?? '', Boolean(payload.stderr));
    },
    onError(err) {
      if (seq !== state.logs.streamSeq) {
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

  if (state.logs.targetType === 'gateway') {
    state.logs.stream = streamGatewayLogs(handlers);
  } else if (state.logs.targetService) {
    state.logs.stream = streamServiceLogs(state.logs.targetService, handlers);
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
    {
      onSettled: closeConflictDialog,
      activity: {
        title: 'Stopping Conflicting Process',
        target: serviceName,
        message: 'Terminating conflicting Process…',
      },
    },
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

/* ---------- Profile Create / Edit / Delete Dialog ---------- */

function validateProfileNameClient(name) {
  if (!name || name.trim().length === 0) {
    return 'Profile name is required.';
  }
  if (name.startsWith('-')) {
    return "Profile name must not start with '-'.";
  }
  if (/[\s/\\]/.test(name)) {
    return "Profile name must not contain whitespace, '/', or '\\'.";
  }
  return null;
}

function getCheckedProfileServices() {
  const checked = [];
  for (const input of dom.profileServicesList.querySelectorAll('input[type="checkbox"]')) {
    if (input.checked) {
      checked.push(input.value);
    }
  }
  return checked;
}

function populateProfileServiceCheckboxes(selectedServices) {
  dom.profileServicesList.replaceChildren();
  const selectedSet = new Set(selectedServices);
  const services = state.estate?.services || [];
  if (services.length === 0) {
    dom.profileServicesList.append(el('span', 'muted', 'No Services configured'));
    return;
  }
  for (const svc of services) {
    const label = el('label', 'profile-service-option');
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.value = svc.service;
    checkbox.checked = selectedSet.has(svc.service);
    checkbox.addEventListener('change', updateProfileDialogState);
    label.append(checkbox, el('span', '', svc.service));
    dom.profileServicesList.append(label);
  }
}

function setAllProfileServiceCheckboxes(checked) {
  for (const input of dom.profileServicesList.querySelectorAll('input[type="checkbox"]')) {
    input.checked = checked;
  }
  updateProfileDialogState();
}

function openCreateProfileDialog() {
  state.profileDialog = {
    mode: 'create',
    originalName: null,
    submitting: false,
  };
  dom.profileDialogTitle.textContent = 'Save Profile';
  dom.profileNameInput.value = '';
  dom.profileNameInput.disabled = false;
  dom.btnProfileDelete.hidden = true;
  hideProfileDeleteConfirm();
  dom.profileDialogError.hidden = true;
  populateProfileServiceCheckboxes(getCurrentLocalServices());
  updateProfileDialogState();
  if (!dom.profileDialog.open) {
    dom.profileDialog.showModal();
  }
  dom.profileNameInput.focus();
}

function openEditProfileDialog(profile) {
  state.profileDialog = {
    mode: 'edit',
    originalName: profile.name,
    submitting: false,
  };
  dom.profileDialogTitle.textContent = `Edit Profile: ${profile.name}`;
  dom.profileNameInput.value = profile.name;
  dom.profileNameInput.disabled = true;
  dom.btnProfileDelete.hidden = false;
  hideProfileDeleteConfirm();
  dom.profileDialogError.hidden = true;
  populateProfileServiceCheckboxes(Array.isArray(profile.local) ? profile.local : []);
  updateProfileDialogState();
  if (!dom.profileDialog.open) {
    dom.profileDialog.showModal();
  }
}

function closeProfileDialog() {
  hideProfileDeleteConfirm();
  if (dom.profileDialog.open) {
    dom.profileDialog.close();
  }
}

function showProfileDeleteConfirm() {
  const name = state.profileDialog.originalName;
  if (!name) {
    return;
  }
  dom.profileDeleteConfirmText.textContent = `Delete Profile '${name}' from procomp.json?`;
  dom.profileDeleteConfirm.hidden = false;
  dom.profileCliPreview.textContent = `procomp profile delete ${name}`;
}

function hideProfileDeleteConfirm() {
  dom.profileDeleteConfirm.hidden = true;
  if (dom.profileDialog.open) {
    updateProfileDialogState();
  }
}

function isSnapshotMode() {
  return (
    state.profileDialog.mode === 'create' &&
    sameServiceSet(getCheckedProfileServices(), getCurrentLocalServices())
  );
}

function updateProfileDialogState() {
  const rawName = dom.profileNameInput.value;
  const selected = getCheckedProfileServices();
  dom.profileSelectedCount.textContent = `(${selected.length} selected)`;

  const nameError = state.profileDialog.mode === 'create' ? validateProfileNameClient(rawName) : null;
  if (state.profileDialog.mode === 'create' && rawName.length > 0 && nameError !== null) {
    dom.profileDialogError.textContent = nameError;
    dom.profileDialogError.hidden = false;
  } else if (state.profileDialog.mode === 'create' && !state.profileDialog.submitting) {
    dom.profileDialogError.hidden = true;
  }

  const existingNames = new Set((state.profiles || []).map((p) => p.name));
  const willOverwrite =
    state.profileDialog.mode === 'create' && nameError === null && existingNames.has(rawName);
  if (willOverwrite) {
    dom.profileOverwriteWarning.textContent = `Profile '${rawName}' already exists. Saving will overwrite it in procomp.json.`;
    dom.profileOverwriteWarning.hidden = false;
  } else {
    dom.profileOverwriteWarning.hidden = true;
  }

  const displayName = rawName.length > 0 ? rawName : '<name>';
  if (!dom.profileDeleteConfirm.hidden && state.profileDialog.originalName) {
    dom.profileCliPreview.textContent = `procomp profile delete ${state.profileDialog.originalName}`;
  } else if (isSnapshotMode()) {
    dom.profileCliPreview.textContent = `procomp profile save ${displayName}`;
  } else if (selected.length === 0) {
    dom.profileCliPreview.textContent = `procomp profile save ${displayName} --none`;
  } else {
    dom.profileCliPreview.textContent = `procomp profile save ${displayName} ${selected.join(' ')}`;
  }

  dom.btnProfileSave.disabled = state.profileDialog.submitting || nameError !== null;
  dom.btnProfileSave.textContent = willOverwrite ? 'Overwrite Profile' : 'Save Profile';
}

async function runProfileDialogMutation(actionFn, disabledControls, activity = null) {
  state.profileDialog.submitting = true;
  dom.profileDialogError.hidden = true;
  for (const control of disabledControls) {
    control.disabled = true;
  }
  const active = activity ? beginActivity(activity) : null;
  try {
    const payload = await actionFn();
    if (payload && payload.cliCommand) {
      setCliCommand(payload.cliCommand, { explicit: true });
    }
    closeProfileDialog();
    clearError();
    await refreshStatus({ explicit: false });
  } catch (err) {
    dom.profileDialogError.textContent =
      err instanceof ApiError ? `[${err.code}] ${err.message}` : String(err);
    dom.profileDialogError.hidden = false;
    if (err instanceof ApiError && err.cliCommand) {
      setCliCommand(err.cliCommand, { explicit: true, errorCode: err.code });
    }
  } finally {
    active?.finish();
    state.profileDialog.submitting = false;
    for (const control of disabledControls) {
      control.disabled = false;
    }
    updateProfileDialogState();
  }
}

async function submitProfileDialog() {
  if (state.profileDialog.submitting) {
    return;
  }
  const name =
    state.profileDialog.mode === 'edit'
      ? state.profileDialog.originalName
      : dom.profileNameInput.value;

  const validationError = validateProfileNameClient(name);
  if (validationError) {
    dom.profileDialogError.textContent = validationError;
    dom.profileDialogError.hidden = false;
    return;
  }

  const useSnapshot = isSnapshotMode();
  const selected = getCheckedProfileServices();
  await runProfileDialogMutation(
    () => saveProfile(name, useSnapshot ? { fromCurrentRoutes: true } : { local: selected }),
    [dom.btnProfileSave, dom.btnProfileDelete],
    {
      title: 'Saving Profile',
      target: name,
      message: 'Saving Profile…',
    },
  );
}

async function confirmDeleteProfile() {
  const name = state.profileDialog.originalName;
  if (!name || state.profileDialog.submitting) {
    return;
  }
  await runProfileDialogMutation(
    () => deleteProfile(name),
    [dom.btnProfileDeleteConfirm, dom.btnProfileSave],
    {
      title: 'Deleting Profile',
      target: name,
      message: 'Deleting Profile…',
    },
  );
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

function isEditableTarget(target) {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  if (target.isContentEditable) {
    return true;
  }
  const tag = target.tagName;
  if (tag === 'TEXTAREA' || tag === 'SELECT') {
    return true;
  }
  if (tag === 'INPUT') {
    const type = (target.getAttribute('type') || 'text').toLowerCase();
    return type !== 'checkbox' && type !== 'radio' && type !== 'button' && type !== 'submit' && type !== 'reset';
  }
  return false;
}

function handleGlobalKeydown(event) {
  if (event.ctrlKey || event.metaKey || event.altKey) {
    return;
  }
  if (dom.conflictDialog.open) {
    return;
  }

  const target = event.target;
  if (isEditableTarget(target)) {
    if (event.key === 'Escape') {
      if (target === dom.servicesFilterInput) {
        if (dom.servicesFilterInput.value) {
          dom.servicesFilterInput.value = '';
          state.servicesFilter.query = '';
          renderServices();
        } else {
          dom.servicesFilterInput.blur();
        }
        event.preventDefault();
      } else if (target === dom.logsSidebarFilter) {
        if (dom.logsSidebarFilter.value) {
          dom.logsSidebarFilter.value = '';
          state.logs.sidebarQuery = '';
          renderLogsSidebar();
        } else {
          dom.logsSidebarFilter.blur();
        }
        event.preventDefault();
      } else if (target === dom.logsFilterInput) {
        if (dom.logsFilterInput.value) {
          dom.logsFilterInput.value = '';
          state.logs.filterQuery = '';
          renderFilteredLogs();
        } else {
          dom.logsFilterInput.blur();
        }
        event.preventDefault();
      }
    }
    return;
  }

  if (event.key === '1') {
    event.preventDefault();
    switchAppView('services');
  } else if (event.key === '2') {
    event.preventDefault();
    switchAppView('logs');
  } else if (event.key === '/') {
    event.preventDefault();
    const input = state.view === 'logs' ? dom.logsFilterInput : dom.servicesFilterInput;
    input.focus();
    input.select();
  } else if (event.key === 'Escape') {
    if (state.view === 'logs') {
      if (state.logs.filterQuery) {
        dom.logsFilterInput.value = '';
        state.logs.filterQuery = '';
        renderFilteredLogs();
      } else {
        switchAppView('services');
      }
    } else if (state.servicesFilter.query) {
      dom.servicesFilterInput.value = '';
      state.servicesFilter.query = '';
      renderServices();
    } else if (state.drawer.open) {
      closeDrawer();
    }
  }
}

function init() {
  dom.navTabServices.addEventListener('click', () => switchAppView('services'));
  dom.navTabLogs.addEventListener('click', () => switchAppView('logs'));

  dom.btnRefresh.addEventListener('click', () => refreshStatus({ explicit: true }));
  dom.btnDismissError.addEventListener('click', clearError);

  state.gatewayAutoReconcile = loadAutoReconcilePreference();
  dom.chkGatewayAutoReconcile.checked = state.gatewayAutoReconcile;
  dom.chkGatewayAutoReconcile.addEventListener('change', () => {
    state.gatewayAutoReconcile = dom.chkGatewayAutoReconcile.checked;
    saveAutoReconcilePreference(state.gatewayAutoReconcile);
  });

  dom.btnGatewayReconcile.addEventListener('click', () =>
    runAction([dom.btnGatewayReconcile], () => reconcileGateway(), {
      activity: GATEWAY_RECONCILE_ACTIVITY,
    }),
  );
  dom.btnDriftReconcile.addEventListener('click', () =>
    runAction([dom.btnDriftReconcile], () => reconcileGateway(), {
      activity: GATEWAY_RECONCILE_ACTIVITY,
    }),
  );
  dom.btnGatewayStart.addEventListener('click', () =>
    runAction([dom.btnGatewayStart], () => startGateway({ detach: false, timeoutSeconds: 30 }), {
      activity: {
        title: 'Starting Gateway',
        target: 'gateway',
        message: 'Starting Gateway Process…',
      },
    }),
  );
  dom.btnGatewayStop.addEventListener('click', () =>
    runAction([dom.btnGatewayStop], () => stopGateway(), {
      activity: {
        title: 'Stopping Gateway',
        target: 'gateway',
        message: 'Stopping Gateway Process…',
      },
    }),
  );
  dom.btnGatewayLogs.addEventListener('click', () => openLogsView('gateway', null));
  dom.btnGitHooksToggle.addEventListener('click', () => {
    state.gitHooksCollapsed = !state.gitHooksCollapsed;
    renderGitHooks();
  });
  dom.btnGitHooksInstallAll.addEventListener('click', () =>
    handleInstallGitHooks([], dom.btnGitHooksInstallAll),
  );

  dom.btnStartLocal.addEventListener('click', () => handleStartServices([]));
  dom.btnStopManaged.addEventListener('click', () => handleStopServices([], dom.btnStopManaged));

  dom.servicesFilterInput.addEventListener('input', () => {
    state.servicesFilter.query = dom.servicesFilterInput.value;
    renderServices();
  });

  dom.btnServicesLocalOnly.addEventListener('click', () => {
    state.servicesFilter.localOnly = !state.servicesFilter.localOnly;
    renderServices();
  });

  dom.btnDrawerClose.addEventListener('click', closeDrawer);
  dom.btnInspectRefresh.addEventListener('click', () => {
    if (state.drawer.service) {
      loadInspection(state.drawer.service);
    }
  });

  dom.logsSidebarFilter.addEventListener('input', () => {
    state.logs.sidebarQuery = dom.logsSidebarFilter.value;
    renderLogsSidebar();
  });

  dom.logsFilterInput.addEventListener('input', () => {
    state.logs.filterQuery = dom.logsFilterInput.value;
    renderFilteredLogs();
  });

  dom.chkLogsWrap.addEventListener('change', () => {
    state.logs.wrap = dom.chkLogsWrap.checked;
    dom.logsOutput.classList.toggle('no-wrap', !state.logs.wrap);
  });

  dom.btnLogsSourceService.addEventListener('click', () => {
    if (state.logs.build || state.logs.git) {
      state.logs.build = false;
      state.logs.git = false;
      startActiveLogStream();
    }
  });

  dom.btnLogsSourceBuild.addEventListener('click', () => {
    if (!state.logs.build) {
      state.logs.build = true;
      state.logs.git = false;
      startActiveLogStream();
    }
  });

  dom.btnLogsSourceGit.addEventListener('click', () => {
    if (!state.logs.git) {
      state.logs.git = true;
      state.logs.build = false;
      startActiveLogStream();
    }
  });

  dom.btnGitFetchLocal.addEventListener('click', () =>
    runAction([dom.btnGitFetchLocal], () => gitFetch([]), {
      activity: {
        title: 'Fetching Repositories',
        target: 'LOCAL',
        message: 'Fetching LOCAL Repositories…',
      },
    }),
  );
  dom.btnGitPullLocal.addEventListener('click', () =>
    runAction([dom.btnGitPullLocal], () => gitPull([]), {
      activity: {
        title: 'Pulling Repositories',
        target: 'LOCAL',
        message: 'Pulling LOCAL Repositories…',
      },
    }),
  );

  dom.gitSwitchFilter.addEventListener('input', () => {
    state.gitSwitch.filter = dom.gitSwitchFilter.value;
    renderGitSwitchBranchList();
  });
  dom.btnGitSwitchFetch.addEventListener('click', handleGitSwitchFetch);
  dom.btnGitSwitchStash.addEventListener('click', handleGitSwitchStash);
  dom.btnGitSwitchDefault.addEventListener('click', () => handleGitSwitchExecute(true));
  dom.btnGitSwitchConfirm.addEventListener('click', () => handleGitSwitchExecute(false));
  dom.btnGitSwitchCancel.addEventListener('click', closeGitSwitchDialog);

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

    if (distanceFromBottom <= AUTOSCROLL_REENGAGE_THRESHOLD_PX) {
      if (!state.logs.autoScroll) {
        setLogsAutoScroll(true, { scrollToBottom: false });
      }
    } else if (!state.logs.programmaticScroll || state.logs.pointerDown) {
      if (state.logs.autoScroll) {
        setLogsAutoScroll(false);
      }
    }

    if (
      !state.logs.programmaticScroll &&
      !state.logs.autoScroll &&
      dom.logsOutput.scrollTop <= LOAD_OLDER_TOP_THRESHOLD_PX &&
      state.logs.hasOlder &&
      !state.logs.loadingOlder &&
      state.logs.entries.length > 0
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

  document.addEventListener('click', (event) => {
    if (state.pillMenu.open && !dom.pillActionMenu.contains(event.target)) {
      closePillMenu();
    }
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && state.pillMenu.open) {
      closePillMenu();
      return;
    }
    if (event.key === 'Escape' && state.cliHistoryOpen && !dom.conflictDialog.open && !dom.profileDialog.open) {
      setCliHistoryOpen(false);
      dom.btnCliHistory.focus();
    }
  });

  dom.btnConflictCancel.addEventListener('click', closeConflictDialog);
  dom.btnConflictConfirm.addEventListener('click', confirmConflictKill);
  dom.conflictDialog.addEventListener('cancel', () => {
    state.conflictService = null;
  });

  dom.btnSaveProfile.addEventListener('click', openCreateProfileDialog);
  dom.profileNameInput.addEventListener('input', updateProfileDialogState);
  dom.profileNameInput.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      submitProfileDialog();
    }
  });
  dom.btnProfileSelectAll.addEventListener('click', () => setAllProfileServiceCheckboxes(true));
  dom.btnProfileClearAll.addEventListener('click', () => setAllProfileServiceCheckboxes(false));
  dom.btnProfileCancel.addEventListener('click', closeProfileDialog);
  dom.btnProfileSave.addEventListener('click', submitProfileDialog);
  dom.btnProfileDelete.addEventListener('click', showProfileDeleteConfirm);
  dom.btnProfileDeleteCancel.addEventListener('click', hideProfileDeleteConfirm);
  dom.btnProfileDeleteConfirm.addEventListener('click', confirmDeleteProfile);
  dom.profileDialog.addEventListener('cancel', () => {
    hideProfileDeleteConfirm();
  });

  document.addEventListener('keydown', handleGlobalKeydown);

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

  renderViewTabs();
  renderCliHistory();
  refreshStatus({ explicit: false });
  startPolling();
}

init();
