<#
.SYNOPSIS
    procomp (Process Composer) - Language-agnostic TUI & process orchestrator for Windows.
.DESCRIPTION
    Compatible with Windows PowerShell 5.1+ and PowerShell 7+.
#>
param(
    [ValidateSet("tui", "up", "down", "status", "sync", "fetch", "clone", "build", "doctor")]
    [string]$Action = "tui",
    [string]$ConfigPath = (Join-Path $PSScriptRoot "procomp.json"),
    [string]$ReposRoot = "",
    [string]$Project = "",
    [string]$Service = "",
    [string]$Profile = "",
    [switch]$NoTrace
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RootDir     = $PSScriptRoot
$BaseLogDir  = Join-Path $RootDir "logs"
$CrashLog    = Join-Path $BaseLogDir "procomp-error.log"
$DebugLog    = Join-Path $BaseLogDir "procomp-debug.log"
$TraceLog    = Join-Path $BaseLogDir "procomp-trace.log"
$script:LastResolvedAppsFile = ""

New-Item -ItemType Directory -Force -Path $BaseLogDir | Out-Null

function Write-ProcompCrashLog {
    param($ErrRecord)
    $psVer = $PSVersionTable.PSVersion.ToString()
    $appsFileInfo = ""
    $appsVar = Get-Variable -Name LastResolvedAppsFile -Scope Script -ErrorAction SilentlyContinue
    if ($appsVar -and $appsVar.Value) {
        $appsFileInfo = ", AppsFile=$($appsVar.Value)"
    }
    $msg = @"
=== $(Get-Date -Format "yyyy-MM-dd HH:mm:ss") [procomp.ps1] (PS $psVer, Action=$Action, Config=$ConfigPath$appsFileInfo) ===
Message: $($ErrRecord.Exception.Message)
Position: $($ErrRecord.InvocationInfo.PositionMessage)
StackTrace:
$($ErrRecord.ScriptStackTrace)

"@
    try { Add-Content -Path $CrashLog -Value $msg -Encoding UTF8 } catch { }
    Write-Host ""
    Write-Host "[procomp ERROR] $($ErrRecord.Exception.Message)" -ForegroundColor Red
    Write-Host "Full details written to: $CrashLog" -ForegroundColor Yellow
}

trap {
    try { [Console]::Write("$([char]27)[?1049l") } catch { }
    try { [Console]::CursorVisible = $true } catch { }
    Write-ProcompCrashLog -ErrRecord $_
    exit 1
}

. (Join-Path $RootDir "src\Config.ps1")
. (Join-Path $RootDir "src\EnvLoader.ps1")
. (Join-Path $RootDir "src\BuildRunner.ps1")
. (Join-Path $RootDir "src\IdeDetector.ps1")
. (Join-Path $RootDir "src\ProcessManager.ps1")
. (Join-Path $RootDir "src\HealthMonitor.ps1")
. (Join-Path $RootDir "src\WireMockBridge.ps1")
. (Join-Path $RootDir "src\LogViewer.ps1")
. (Join-Path $RootDir "src\BackgroundWorker.ps1")
. (Join-Path $RootDir "src\Doctor.ps1")
. (Join-Path $RootDir "src\Tui.ps1")

Set-ProcompDebugLogPath -Path $DebugLog
if (-not $NoTrace -and ([System.Environment]::GetEnvironmentVariable("PROCOMP_TRACE", "Process") -ne "0")) {
    Set-ProcompTraceLogPath -Path $TraceLog
}
Write-ProcompDebugLog -Category "START" -WorkingDirectory (Get-Location).Path -Message "Action=$Action Config=$ConfigPath ReposRoot=$ReposRoot Project=$Project Service=$Service Profile=$Profile"

$ProjectName = Resolve-ProjectName -ConfigPath $ConfigPath -ProjectOverride $Project
$StateDir    = Join-Path (Join-Path $RootDir ".stack_state") $ProjectName
$LogDir      = Join-Path (Join-Path $RootDir "logs") $ProjectName
$RoutingFile = Join-Path $StateDir "routing.json"
New-Item -ItemType Directory -Force -Path $StateDir, $LogDir | Out-Null

$HttpClient = New-HealthHttpClient -TimeoutMs 400
$config = Load-ProcompConfig -ConfigPath $ConfigPath -StateDir $StateDir -LogDir $LogDir -ReposRootOverride $ReposRoot
if ($Profile) {
    Apply-RoutingProfile -Config $config -ProfileName $Profile -RoutingFile $RoutingFile
}
$services = @($config.Services)
$cliServices = if ($Service) {
    $matched = @($services | Where-Object { $_.Name -eq $Service })
    if ($matched.Count -eq 0) { throw "Service '$Service' not found in config." }
    $matched
} else {
    $services
}
$gatewayUrl = Get-GatewayAdminUrl -Config $config
$gatewaySvcName = if ($config.Gateway) { $config.Gateway.ServiceName } else { "" }

if ($Action -eq "doctor") {
    try {
        $checks = @(Invoke-ProcompDoctor -Config $config -RootDir $RootDir -TargetServices $cliServices)
        $checks | Format-Table -AutoSize
        $fails = @($checks | Where-Object { $_.Status -eq "FAIL" }).Count
        if ($fails -gt 0) { exit 1 }
    } finally {
        $HttpClient.Dispose()
    }
    exit 0
}

if ($Action -eq "sync") {
    try {
        $syncRes = @(Sync-GitRepos -Services $services -RootDir $RootDir)
        $syncRes | Format-Table -AutoSize
    } finally {
        $HttpClient.Dispose()
    }
    exit 0
}

if ($Action -eq "fetch") {
    try {
        $fetchRes = @(Fetch-AllGitRepos -Services $services -RootDir $RootDir)
        $fetchRes | Format-Table -AutoSize
    } finally {
        $HttpClient.Dispose()
    }
    exit 0
}

if ($Action -eq "clone") {
    try {
        $cloneRes = @(Invoke-GitCloneMissingRepos -Services $services -RootDir $RootDir)
        $cloneRes | Format-Table -AutoSize
    } finally {
        $HttpClient.Dispose()
    }
    exit 0
}

if ($Action -eq "build") {
    try {
        foreach ($svc in $cliServices) {
            if ($svc.BuilderDef) {
                Write-Host "Building $($svc.Name) ($($svc.Builder))..."
                $ok = Invoke-ServiceBuildSync -Svc $svc -RootDir $RootDir
                Write-Host "  $($svc.Name): $(if ($ok) { 'OK' } else { 'SKIPPED/FAILED' })"
            }
        }
    } finally {
        $HttpClient.Dispose()
    }
    exit 0
}

if ($Action -eq "up") {
    try {
        foreach ($svc in $cliServices) {
            if ($svc.Enabled -or $Service) {
                Start-ServiceProcess -Svc $svc -RootDir $RootDir
                Write-Host "Started $($svc.Name) (port $($svc.Port))"
            } else {
                Write-Host "Skipped $($svc.Name) (disabled)"
            }
        }
        if ($gatewayUrl -and (-not $gatewaySvcName)) {
            $cnt = Sync-WireMockMappings -Services $services -WireMockBaseUrl $gatewayUrl
            if ($cnt -gt 0) { Write-Host "Synced $cnt route(s) to external WireMock ($gatewayUrl)" }
        }
    } finally {
        $HttpClient.Dispose()
    }
    exit 0
}

if ($Action -eq "down") {
    try {
        foreach ($svc in $cliServices) {
            Stop-ServiceProcess -Svc $svc
            Write-Host "Stopped $($svc.Name)"
        }
    } finally {
        $HttpClient.Dispose()
    }
    exit 0
}

if ($Action -eq "status") {
    try {
        Refresh-ProcessSnapshot -Services $cliServices -DetectIde
        Refresh-GitSummaries -Services $cliServices -RootDir $RootDir
        foreach ($svc in $cliServices) {
            if ($svc.PortConflict) {
                $svc.StatusText = "PORT-CONFLICT"
            } elseif ($svc.CachedProc) {
                if ($svc.HealthPath) {
                    $url = "http://127.0.0.1:$($svc.Port)$($svc.HealthPath)"
                    try {
                        $resp = $HttpClient.GetAsync($url).GetAwaiter().GetResult()
                        $svc.Healthy = ([int]$resp.StatusCode -ge 200 -and [int]$resp.StatusCode -lt 300)
                        $resp.Dispose()
                    } catch {
                        $svc.Healthy = $false
                    }
                } else {
                    $svc.Healthy = $true
                }
                if ($svc.ExternalIde) {
                    $svc.StatusText = if ($svc.Healthy) { "IDE-UP" } else { "IDE-INIT" }
                } else {
                    $svc.StatusText = if ($svc.Healthy) { "HEALTHY" } else { "STARTING" }
                }
            } else {
                $svc.StatusText = if ($svc.CachedPid) { "CRASHED" } elseif ($svc.RoutingMode -eq "REMOTE") { "REMOTE" } elseif ($svc.Enabled) { "STOPPED" } else { "DISABLED" }
            }
        }
        $rows = foreach ($svc in $cliServices) {
            [PSCustomObject]@{
                Service = $svc.Name
                Runtime = $svc.Runtime
                Port    = $svc.Port
                PID     = if ($svc.CachedProc) { $svc.CachedProc.Id } else { "-" }
                State   = $svc.StatusText
                Routing = if ($svc.RoutingMode -eq "REMOTE") { "REMOTE" } else { "LOCAL :$($svc.Port)" }
                Git     = $svc.GitSummary
                Limit   = $svc.MemLimit
                RamUsed = (Format-RamMb -Proc $svc.CachedProc)
            }
        }
        $rows | Format-Table -AutoSize
    } finally {
        $HttpClient.Dispose()
    }
    exit 0
}

# --- TUI Mode ---
Write-Host "[procomp] Loading TUI & background monitor ($($services.Count) apps)..." -ForegroundColor Cyan
. (Join-Path $RootDir "src\ConsoleInput.ps1")

$selected = 0
$allLogs = $false
$errorsOnly = $false
$rawLogs = $false
$confirmQuit = $false
$detachOnExit = $false
$banner = ""

$sharedState = New-SharedTuiState -RootDir $RootDir -ConfigPath $ConfigPath -StateDir $StateDir -LogDir $LogDir -ReposRoot $ReposRoot
foreach ($svc in $services) {
    $sharedState.ServiceOverrides[$svc.Name] = @{ RoutingMode = $svc.RoutingMode; Enabled = $svc.Enabled }
}
$bgWorker = Start-BackgroundMonitor -SharedState $sharedState

$origCtrlC = [Console]::TreatControlCAsInput
[Console]::TreatControlCAsInput = $true
[Console]::CursorVisible = $false
$mouseEnabled = Enable-ConsoleMouseInput
[Console]::Write("$([char]27)[?1049h$([char]27)[2J$([char]27)[H")

$tuiError = $null
try {
    $running = $true
    $lastSnapVer = -1
    $gatewaySynced = $false
    $needsRender = $true
    $viewMode = "SERVICES"
    $wireMockSubMode = "JOURNAL"
    $procompLogLevel = "TRACE"
    $localOnly = $false
    $showHelp = $false
    $filterText = ""
    $filterInputMode = $false
    $expandStacks = $false
    $scrollOffset = 0
    $expandedEntryIds = @{}
    $uiLayout = $null

    function Get-VisibleServices {
        if ($script:localOnly) {
            $subset = @($services | Where-Object { $_.RoutingMode -eq "LOCAL" -or $_.Managed -or $_.ExternalIde })
            if ($subset.Count -gt 0) { return $subset }
        }
        return $services
    }

    # Queue startup in BackgroundWorker so the TUI event loop is responsive immediately
    foreach ($s in $services) {
        if ($s.Enabled) { $s.StatusText = "PROBING" }
    }
    $sharedState.CommandJobQueue.Enqueue([PSCustomObject]@{ Action = "Startup"; ServiceName = "" })
    $sharedState.RefreshNow = $true
    $banner = "Starting enabled services..."
    $lastBannerText = $banner
    $lastActivity = ""
    $bannerSetAt = Get-Date

    function Invoke-UiAction {
        param([string]$Cmd)
        $vis = @(Get-VisibleServices)
        if ($script:selected -ge $vis.Count) { $script:selected = [Math]::Max(0, $vis.Count - 1) }
        $curSvc = $vis[$script:selected]
        Write-ProcompTraceLog -Category "UI" -ServiceName $curSvc.Name -Message "action=$Cmd"
        switch ($Cmd) {
            "help" {
                $script:showHelp = -not $script:showHelp
            }
            "local-only" {
                $script:localOnly = -not $script:localOnly
                $script:selected = 0
                $script:scrollOffset = 0
                $script:banner = if ($script:localOnly) { "Showing LOCAL / active services only (press [g] for all)" } else { "Showing all $($services.Count) services" }
            }
            "swagger" {
                $url = Get-ServiceSwaggerUrl -Svc $curSvc
                if (-not $url) {
                    $script:banner = "No swaggeruri configured for $($curSvc.Name)"
                } else {
                    try {
                        Write-ProcompDebugLog -Category "OPEN" -ServiceName $curSvc.Name -Message $url
                        Start-Process $url
                        $script:banner = "Opened Swagger: $url"
                    } catch {
                        $script:banner = "Failed to open $url : $($_.Exception.Message)"
                    }
                }
            }
            "logs" {
                $script:viewMode = "LOGS"
                $sharedState.ActiveViewMode = "LOGS"
                $script:scrollOffset = 0
                $script:banner = "Log Viewer: $($curSvc.Name) (Wheel/PgUp/PgDn to scroll, Esc to return)"
            }
            "procomp-logs" {
                $script:viewMode = "PROCOMP"
                $sharedState.ActiveViewMode = "PROCOMP"
                $script:scrollOffset = 0
                $script:banner = "procomp Log ($($script:procompLogLevel)) - [v] INFO/DEBUG/TRACE, Esc to return"
            }
            "procomp-level" {
                $script:procompLogLevel = switch ($script:procompLogLevel) {
                    "TRACE" { "INFO" }
                    "INFO"  { "DEBUG" }
                    default { "TRACE" }
                }
                $script:scrollOffset = 0
                $script:banner = "procomp log level: $($script:procompLogLevel)"
            }
            "wiremock" {
                $script:viewMode = "WIREMOCK"
                $sharedState.ActiveViewMode = "WIREMOCK"
                $sharedState.WireMockSubMode = $script:wireMockSubMode
                $sharedState.RefreshNow = $true
                $script:scrollOffset = 0
                $script:banner = "WireMock Viewer ($($script:wireMockSubMode)) - [m]/Tab to switch Journal/Mappings, Esc to return"
            }
            "wm-toggle" {
                $script:wireMockSubMode = if ($script:wireMockSubMode -eq "JOURNAL") { "MAPPINGS" } else { "JOURNAL" }
                $sharedState.WireMockSubMode = $script:wireMockSubMode
                $sharedState.RefreshNow = $true
                $script:scrollOffset = 0
                $script:banner = "WireMock Viewer: $($script:wireMockSubMode)"
            }
            "back" {
                $script:viewMode = "SERVICES"
                $sharedState.ActiveViewMode = "SERVICES"
                $script:scrollOffset = 0
                $script:banner = "Services view"
            }
            "follow" {
                $script:scrollOffset = 0
                $script:banner = "Auto-tail FOLLOW resumed"
            }
            "all" {
                $script:allLogs = -not $script:allLogs
                $script:scrollOffset = 0
                $script:banner = if ($script:allLogs) { "Showing unified logs for ALL services" } else { "Showing logs for $($services[$script:selected].Name)" }
            }
            "err" {
                $script:errorsOnly = -not $script:errorsOnly
                $script:scrollOffset = 0
                $script:banner = if ($script:errorsOnly) { "Filter: WARN/ERROR only" } else { "Filter: All log levels" }
            }
            "raw" {
                $script:rawLogs = -not $script:rawLogs
                $sharedState.RawMode = $script:rawLogs
                $sharedState.RefreshNow = $true
                $script:scrollOffset = 0
                $script:banner = if ($script:rawLogs) { "Log format: Raw" } else { "Log format: Unified/Normalized" }
            }
            "find" {
                $script:filterInputMode = $true
            }
            "clear" {
                $sharedState.ClearLogEpoch = [int]$sharedState.ClearLogEpoch + 1
                $sharedState.RefreshNow = $true
                $script:scrollOffset = 0
                $script:expandedEntryIds = @{}
                $script:banner = "Cleared log display buffer"
            }
            "stack" {
                if ($script:expandedEntryIds.Count -gt 0) {
                    $script:expandedEntryIds = @{}
                    $script:expandStacks = $false
                    $script:banner = "Collapsed all stack traces"
                } else {
                    $script:expandStacks = -not $script:expandStacks
                    $script:banner = if ($script:expandStacks) { "Expanded latest stack trace (click any trace to toggle)" } else { "Stack traces folded" }
                }
            }
            "route" {
                $svc = $curSvc
                $svc.RoutingMode = if ($svc.RoutingMode -eq "LOCAL") { "REMOTE" } else { "LOCAL" }
                $sharedState.ServiceOverrides[$svc.Name] = @{ RoutingMode = $svc.RoutingMode; Enabled = $svc.Enabled }
                $sharedState.CommandJobQueue.Enqueue([PSCustomObject]@{ Action = "RouteOne"; ServiceName = $svc.Name })
                $sharedState.RefreshNow = $true
                $script:banner = "Updating routing for $($svc.Name) -> $($svc.RoutingMode)..."
            }
            "start" {
                $svc = $curSvc
                $svc.Enabled = $true
                $svc.RoutingMode = "LOCAL"
                $sharedState.ServiceOverrides[$svc.Name] = @{ RoutingMode = $svc.RoutingMode; Enabled = $svc.Enabled }
                $sharedState.CommandJobQueue.Enqueue([PSCustomObject]@{ Action = "StartOne"; ServiceName = $svc.Name })
                $sharedState.RefreshNow = $true
                $script:banner = "Starting $($svc.Name)..."
            }
            "start-all" {
                $sharedState.CommandJobQueue.Enqueue([PSCustomObject]@{ Action = "StartAll"; ServiceName = "" })
                $sharedState.RefreshNow = $true
                $script:banner = "Starting all enabled LOCAL services..."
            }
            "stop" {
                $svc = $curSvc
                if ($svc.ExternalIde) {
                    $script:banner = "$($svc.Name) is IDE-managed; stop it from your IDE."
                } else {
                    $svc.StatusText = "STOPPING"
                    $sharedState.CommandJobQueue.Enqueue([PSCustomObject]@{ Action = "StopOne"; ServiceName = $svc.Name })
                    $sharedState.RefreshNow = $true
                    $script:banner = "Stopping $($svc.Name)..."
                }
            }
            "stop-all" {
                $sharedState.CommandJobQueue.Enqueue([PSCustomObject]@{ Action = "StopAll"; ServiceName = "" })
                $sharedState.RefreshNow = $true
                $script:banner = "Stopping all managed services..."
            }
            "restart" {
                $svc = $curSvc
                if ($svc.ExternalIde) {
                    $script:banner = "$($svc.Name) is IDE-managed; restart it from your IDE."
                } else {
                    $svc.StatusText = "STOPPING"
                    $svc.Enabled = $true
                    $svc.RoutingMode = "LOCAL"
                    $sharedState.ServiceOverrides[$svc.Name] = @{ RoutingMode = $svc.RoutingMode; Enabled = $svc.Enabled }
                    $sharedState.CommandJobQueue.Enqueue([PSCustomObject]@{ Action = "RestartOne"; ServiceName = $svc.Name })
                    $sharedState.RefreshNow = $true
                    $script:banner = "Restarting $($svc.Name)..."
                }
            }
            "build" {
                $svc = $curSvc
                $sharedState.CommandJobQueue.Enqueue([PSCustomObject]@{ Action = "BuildOne"; ServiceName = $svc.Name })
                $sharedState.RefreshNow = $true
                $script:banner = "Queueing build for $($svc.Name)..."
            }
            "fetch" {
                $svc = $curSvc
                $sharedState.GitJobQueue.Enqueue([PSCustomObject]@{ Action = "FetchOne"; ServiceName = $svc.Name })
                $sharedState.RefreshNow = $true
                $script:banner = "Fetching $($svc.Name)..."
            }
            "fetch-all" {
                $sharedState.GitJobQueue.Enqueue([PSCustomObject]@{ Action = "FetchAll"; ServiceName = "" })
                $sharedState.RefreshNow = $true
                $script:banner = "Fetching all repos..."
            }
            "pull" {
                $svc = $curSvc
                $sharedState.GitJobQueue.Enqueue([PSCustomObject]@{ Action = "PullOne"; ServiceName = $svc.Name })
                $sharedState.RefreshNow = $true
                $script:banner = "Pulling $($svc.Name) (--ff-only)..."
            }
            "pull-all" {
                $sharedState.GitJobQueue.Enqueue([PSCustomObject]@{ Action = "PullAll"; ServiceName = "" })
                $sharedState.RefreshNow = $true
                $script:banner = "Pulling all clean repos (--ff-only)..."
            }
            "detach" {
                $script:detachOnExit = $true
                $script:running = $false
            }
            "quit" {
                $script:confirmQuit = $true
            }
        }
    }

    while ($running) {
        if ($sharedState.SnapshotVersion -ne $lastSnapVer) {
            Apply-SharedSnapshot -Services $services -SharedState $sharedState
            $lastSnapVer = $sharedState.SnapshotVersion
            if ($sharedState.BannerNotification) {
                $banner = $sharedState.BannerNotification
                $sharedState.BannerNotification = ""
            }

            if (-not $gatewaySynced -and (-not $sharedState.GatewaySynced) -and $gatewayUrl -and $gatewaySvcName) {
                $gwSvc = $services | Where-Object { $_.Name -eq $gatewaySvcName -and $_.StatusText -eq "HEALTHY" } | Select-Object -First 1
                if ($gwSvc) {
                    $gatewaySynced = $true
                    $sharedState.CommandJobQueue.Enqueue([PSCustomObject]@{ Action = "SyncGateway"; ServiceName = "" })
                    $sharedState.RefreshNow = $true
                }
            }
            $needsRender = $true
        }

        $visServices = @(Get-VisibleServices)
        if ($selected -ge $visServices.Count) { $selected = [Math]::Max(0, $visServices.Count - 1) }

        $curActivity = [string]$sharedState.CurrentActivity
        if ($curActivity -ne $lastActivity) {
            $lastActivity = $curActivity
            $needsRender = $true
        }

        if ($banner -ne $lastBannerText) {
            $lastBannerText = $banner
            $bannerSetAt = Get-Date
        } elseif ($banner -and $banner -ne "Ready" -and (((Get-Date) - $bannerSetAt).TotalSeconds -ge 6)) {
            $hasActiveBuild = (@($services | Where-Object { $_.StatusText -eq "BUILDING" }).Count -gt 0)
            if (-not $curActivity -and (-not $hasActiveBuild) -and ($sharedState.ActiveGitOps.Count -eq 0) -and ($banner -notmatch 'fail|error|conflict|cannot|not found|skipped')) {
                $banner = "Ready"
                $lastBannerText = "Ready"
                $needsRender = $true
            }
        }

        if ($needsRender) {
            $layoutRef = [ref]$uiLayout
            $effectiveBanner = if ($curActivity) { $curActivity } else { $banner }
            Render-Tui -Services $visServices -SelectedIndex $selected -BannerMsg $effectiveBanner -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $confirmQuit -ViewMode $viewMode -ProjectName $ProjectName -LocalOnly $localOnly -ShowHelp $showHelp -FilterText $filterText -FilterInputMode $filterInputMode -ExpandStacks $expandStacks -ScrollOffset $scrollOffset -ExpandedEntryIds $expandedEntryIds -LogBuffers $sharedState.LogBuffers -ProcompLogEntries $sharedState.ProcompLogEntries -ProcompLogLevel $procompLogLevel -WireMockSubMode $wireMockSubMode -WireMockEntries $sharedState.WireMockEntries -LayoutOut $layoutRef
            if ($uiLayout) { $scrollOffset = $uiLayout.ClampedOffset }
            $needsRender = $false
        }

        $events = @(Read-ConsoleEvents)
        if ($events.Count -gt 0) {
            foreach ($ev in $events) {
                if (-not $running) { break }

                if ($showHelp) {
                    $showHelp = $false
                    $needsRender = $true
                    continue
                }

                if ($ev.Kind -eq "WheelUp" -or $ev.Kind -eq "WheelDown") {
                    $delta = if ($ev.Kind -eq "WheelUp") { 3 } else { -3 }
                    $inLogArea = ($viewMode -eq "LOGS") -or ($uiLayout -and $ev.Y -ge $uiLayout.LogStartY)
                    if ($inLogArea) {
                        $scrollOffset = [Math]::Max(0, $scrollOffset + $delta)
                    } else {
                        $step = if ($ev.Kind -eq "WheelUp") { -1 } else { 1 }
                        $selected = [Math]::Max(0, [Math]::Min($visServices.Count - 1, $selected + $step))
                        $scrollOffset = 0
                        $banner = "Selected $($visServices[$selected].Name)"
                    }
                    $needsRender = $true
                    continue
                }

                if ($ev.Kind -eq "MouseClick" -or $ev.Kind -eq "MouseDoubleClick") {
                    if ($confirmQuit) { $confirmQuit = $false; $needsRender = $true; continue }
                    $hit = Resolve-UiClick -Layout $uiLayout -X $ev.X -Y $ev.Y -IsDoubleClick ($ev.Kind -eq "MouseDoubleClick")
                    if ($hit) {
                        switch ($hit.Action) {
                            "Toolbar" { Invoke-UiAction -Cmd $hit.Command }
                            "OpenLogs" {
                                $selected = [Math]::Max(0, [Math]::Min($visServices.Count - 1, $hit.Index))
                                Invoke-UiAction -Cmd "logs"
                            }
                            "SelectService" {
                                $selected = [Math]::Max(0, [Math]::Min($visServices.Count - 1, $hit.Index))
                                $scrollOffset = 0
                                if ($hit.ToggleRoute) {
                                    Invoke-UiAction -Cmd "route"
                                } else {
                                    $banner = "Selected $($visServices[$selected].Name)"
                                }
                            }
                            "ToggleStack" {
                                $eid = $hit.EntryId
                                if ($expandedEntryIds.ContainsKey($eid)) {
                                    $expandedEntryIds.Remove($eid)
                                    $banner = "Collapsed stack trace"
                                } else {
                                    $expandedEntryIds[$eid] = $true
                                    $banner = "Expanded stack trace (click again to collapse)"
                                }
                            }
                        }
                        $needsRender = $true
                    }
                    continue
                }

                # Keyboard event
                $vk = $ev.VirtualKeyCode
                $ch = $ev.UnicodeChar
                $isCtrlC = ($ev.CtrlPressed -and ($vk -eq 0x43 -or $ch -eq [char]3))

                if ($confirmQuit) {
                    if ($ch -eq 'y' -or $ch -eq 'Y') {
                        $confirmQuit = $false
                        $detachOnExit = $false
                        $running = $false
                    } else {
                        $confirmQuit = $false
                        $banner = "Shutdown canceled."
                        $needsRender = $true
                    }
                    continue
                }

                if ($filterInputMode) {
                    if ($vk -eq 0x0D) {
                        $filterInputMode = $false
                        $scrollOffset = 0
                        $banner = if ($filterText) { "Filter applied: '$filterText'" } else { "Filter cleared" }
                    } elseif ($vk -eq 0x1B) {
                        $filterInputMode = $false
                        $filterText = ""
                        $scrollOffset = 0
                        $banner = "Filter cleared"
                    } elseif ($vk -eq 0x08) {
                        if ($filterText.Length -gt 0) { $filterText = $filterText.Substring(0, $filterText.Length - 1) }
                    } elseif ($ch -ne [char]0 -and (-not [char]::IsControl($ch))) {
                        $filterText += $ch
                    }
                    $needsRender = $true
                    continue
                }

                if ($isCtrlC -or $ch -eq 'q') {
                    Invoke-UiAction -Cmd "quit"
                    $needsRender = $true
                    continue
                }

                if ($ch -eq '?') {
                    Invoke-UiAction -Cmd "help"
                    $needsRender = $true
                    continue
                }

                $pageStep = if ($uiLayout -and $uiLayout.PageHeight -gt 2) { $uiLayout.PageHeight - 2 } else { 10 }

                if ($viewMode -eq "LOGS" -or $viewMode -eq "PROCOMP" -or $viewMode -eq "WIREMOCK") {
                    switch ($vk) {
                        0x1B { Invoke-UiAction -Cmd "back"; $needsRender = $true; continue }   # Esc
                        0x26 { $scrollOffset++; $needsRender = $true; continue }              # Up
                        0x28 { $scrollOffset = [Math]::Max(0, $scrollOffset - 1); $needsRender = $true; continue } # Down
                        0x21 { $scrollOffset += $pageStep; $needsRender = $true; continue }   # PgUp
                        0x22 { $scrollOffset = [Math]::Max(0, $scrollOffset - $pageStep); $needsRender = $true; continue } # PgDn
                        0x24 { $scrollOffset = 999999; $needsRender = $true; continue }       # Home
                        0x23 { Invoke-UiAction -Cmd "follow"; $needsRender = $true; continue } # End
                        0x25 { $selected = ($selected - 1 + $visServices.Count) % $visServices.Count; $scrollOffset = 0; $allLogs = $false; $banner = "Logs: $($visServices[$selected].Name)"; $needsRender = $true; continue } # Left
                        0x27 { $selected = ($selected + 1) % $visServices.Count; $scrollOffset = 0; $allLogs = $false; $banner = "Logs: $($visServices[$selected].Name)"; $needsRender = $true; continue } # Right
                        0x09 { if ($viewMode -eq "WIREMOCK") { Invoke-UiAction -Cmd "wm-toggle" } else { Invoke-UiAction -Cmd "all" }; $needsRender = $true; continue }   # Tab
                    }
                    switch -CaseSensitive ($ch) {
                        '1' { Invoke-UiAction -Cmd "back"; $needsRender = $true }
                        '2' { Invoke-UiAction -Cmd "logs"; $needsRender = $true }
                        '3' { Invoke-UiAction -Cmd "wiremock"; $needsRender = $true }
                        '4' { Invoke-UiAction -Cmd "procomp-logs"; $needsRender = $true }
                        'l' { Invoke-UiAction -Cmd "back"; $needsRender = $true }
                        'L' { if ($viewMode -eq "PROCOMP") { Invoke-UiAction -Cmd "back" } else { Invoke-UiAction -Cmd "procomp-logs" }; $needsRender = $true }
                        'w' { if ($viewMode -eq "WIREMOCK") { Invoke-UiAction -Cmd "back" } else { Invoke-UiAction -Cmd "wiremock" }; $needsRender = $true }
                        'm' { if ($viewMode -eq "WIREMOCK") { Invoke-UiAction -Cmd "wm-toggle"; $needsRender = $true } }
                        'k' { $scrollOffset++; $needsRender = $true }
                        'j' { $scrollOffset = [Math]::Max(0, $scrollOffset - 1); $needsRender = $true }
                        'G' { Invoke-UiAction -Cmd "follow"; $needsRender = $true }
                        'a' { Invoke-UiAction -Cmd "all"; $needsRender = $true }
                        'e' { Invoke-UiAction -Cmd "err"; $needsRender = $true }
                        'v' { if ($viewMode -eq "PROCOMP") { Invoke-UiAction -Cmd "procomp-level" } else { Invoke-UiAction -Cmd "raw" }; $needsRender = $true }
                        'z' { Invoke-UiAction -Cmd "stack"; $needsRender = $true }
                        '/' { Invoke-UiAction -Cmd "find"; $needsRender = $true }
                        'c' { Invoke-UiAction -Cmd "clear"; $needsRender = $true }
                        'd' { Invoke-UiAction -Cmd "detach" }
                    }
                } else {
                    switch ($vk) {
                        0x26 { $selected = [Math]::Max(0, $selected - 1); $scrollOffset = 0; $banner = "Selected $($visServices[$selected].Name)"; $needsRender = $true; continue } # Up
                        0x28 { $selected = [Math]::Min($visServices.Count - 1, $selected + 1); $scrollOffset = 0; $banner = "Selected $($visServices[$selected].Name)"; $needsRender = $true; continue } # Down
                        0x0D { Invoke-UiAction -Cmd "logs"; $needsRender = $true; continue }  # Enter
                        0x09 { Invoke-UiAction -Cmd "all"; $needsRender = $true; continue }   # Tab
                    }
                    switch -CaseSensitive ($ch) {
                        '1' { Invoke-UiAction -Cmd "back"; $needsRender = $true }
                        '2' { Invoke-UiAction -Cmd "logs"; $needsRender = $true }
                        '3' { Invoke-UiAction -Cmd "wiremock"; $needsRender = $true }
                        '4' { Invoke-UiAction -Cmd "procomp-logs"; $needsRender = $true }
                        'k' { $selected = [Math]::Max(0, $selected - 1); $scrollOffset = 0; $banner = "Selected $($visServices[$selected].Name)"; $needsRender = $true }
                        'j' { $selected = [Math]::Min($visServices.Count - 1, $selected + 1); $scrollOffset = 0; $banner = "Selected $($visServices[$selected].Name)"; $needsRender = $true }
                        'g' { Invoke-UiAction -Cmd "local-only"; $needsRender = $true }
                        'o' { Invoke-UiAction -Cmd "swagger"; $needsRender = $true }
                        'l' { Invoke-UiAction -Cmd "logs"; $needsRender = $true }
                        'L' { Invoke-UiAction -Cmd "procomp-logs"; $needsRender = $true }
                        'w' { Invoke-UiAction -Cmd "wiremock"; $needsRender = $true }
                        'a' { Invoke-UiAction -Cmd "all"; $needsRender = $true }
                        'e' { Invoke-UiAction -Cmd "err"; $needsRender = $true }
                        'v' { Invoke-UiAction -Cmd "raw"; $needsRender = $true }
                        'z' { Invoke-UiAction -Cmd "stack"; $needsRender = $true }
                        '/' { Invoke-UiAction -Cmd "find"; $needsRender = $true }
                        'c' { Invoke-UiAction -Cmd "clear"; $needsRender = $true }
                        't' { Invoke-UiAction -Cmd "route"; $needsRender = $true }
                        's' { Invoke-UiAction -Cmd "start"; $needsRender = $true }
                        'S' { Invoke-UiAction -Cmd "start-all"; $needsRender = $true }
                        'x' { Invoke-UiAction -Cmd "stop"; $needsRender = $true }
                        'X' { Invoke-UiAction -Cmd "stop-all"; $needsRender = $true }
                        'r' { Invoke-UiAction -Cmd "restart"; $needsRender = $true }
                        'b' { Invoke-UiAction -Cmd "build"; $needsRender = $true }
                        'f' { Invoke-UiAction -Cmd "fetch"; $needsRender = $true }
                        'F' { Invoke-UiAction -Cmd "fetch-all"; $needsRender = $true }
                        'p' { Invoke-UiAction -Cmd "pull"; $needsRender = $true }
                        'P' { Invoke-UiAction -Cmd "pull-all"; $needsRender = $true }
                        'd' { Invoke-UiAction -Cmd "detach" }
                    }
                }
            }
        } else {
            Start-Sleep -Milliseconds 15
        }
    }

    $tuiError = $null
    if (-not $detachOnExit) {
        foreach ($svc in $services) {
            if (($svc.Managed -or $svc.BuildProc -or (Test-Path $svc.PidFile)) -and (-not $svc.ExternalIde)) {
                $svc.StatusText = "STOPPING"
                Render-Tui -Services $services -SelectedIndex $selected -BannerMsg "Stopping $($svc.Name)..." -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $false -ViewMode "SERVICES" -LogBuffers $sharedState.LogBuffers
                Stop-ServiceProcess -Svc $svc
                Render-Tui -Services $services -SelectedIndex $selected -BannerMsg "Stopped $($svc.Name)." -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $false -ViewMode "SERVICES" -LogBuffers $sharedState.LogBuffers
            }
        }
        Render-Tui -Services $services -SelectedIndex $selected -BannerMsg "All services stopped. Exiting..." -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $false -ViewMode "SERVICES" -LogBuffers $sharedState.LogBuffers
        Start-Sleep -Milliseconds 350
    }
} catch {
    $tuiError = $_
} finally {
    Stop-BackgroundMonitor -Worker $bgWorker
    Restore-ConsoleInputMode
    [Console]::Write("$([char]27)[?1049l")
    [Console]::CursorVisible = $true
    [Console]::TreatControlCAsInput = $origCtrlC
    $HttpClient.Dispose()
    if ($detachOnExit -and (-not $tuiError)) {
        Write-Host "Detached. Services remain running in the background. Re-run .\procomp.cmd to re-attach, or .\procomp.cmd -Action down to stop all."
    } else {
        foreach ($svc in $services) {
            if (($svc.Managed -or $svc.BuildProc -or (Test-Path $svc.PidFile)) -and (-not $svc.ExternalIde)) {
                Stop-ServiceProcess -Svc $svc
            }
        }
        if ($tuiError) {
            Write-ProcompTraceLog -Category "EXIT" -Message "detach=$detachOnExit error=$($tuiError.Exception.Message)"
            Write-ProcompCrashLog -ErrRecord $tuiError
        } else {
            Write-ProcompTraceLog -Category "EXIT" -Message "detach=$detachOnExit ok"
            Write-Host "All services stopped."
        }
    }
}
if ($tuiError) { exit 1 }
