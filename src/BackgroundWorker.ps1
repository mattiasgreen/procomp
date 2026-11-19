Set-StrictMode -Version Latest

function New-SharedTuiState {
    param(
        [string]$RootDir,
        [string]$ConfigPath,
        [string]$StateDir,
        [string]$LogDir
    )
    return [Hashtable]::Synchronized(@{
        StopRequested    = $false
        RootDir          = $RootDir
        ConfigPath       = $ConfigPath
        StateDir         = $StateDir
        LogDir           = $LogDir
        SnapshotVersion  = 0
        RawMode          = $false
        ClearLogEpoch    = 0
        RefreshNow       = $true
        LastError        = ""
        ServiceOverrides = [Hashtable]::Synchronized(@{})
        ServiceMetrics   = [Hashtable]::Synchronized(@{})
        LogBuffers       = [Hashtable]::Synchronized(@{})
    })
}

function Start-BackgroundMonitor {
    param([hashtable]$SharedState)

    $rs = [RunspaceFactory]::CreateRunspace()
    $rs.Open()

    $ps = [PowerShell]::Create()
    $ps.Runspace = $rs

    [void]$ps.AddScript({
        param($State)
        Set-StrictMode -Version Latest
        $ErrorActionPreference = "Continue"

        $root = $State.RootDir
        . (Join-Path $root "src\Config.ps1")
        . (Join-Path $root "src\EnvLoader.ps1")
        . (Join-Path $root "src\BuildRunner.ps1")
        . (Join-Path $root "src\IdeDetector.ps1")
        . (Join-Path $root "src\ProcessManager.ps1")
        . (Join-Path $root "src\HealthMonitor.ps1")
        . (Join-Path $root "src\LogViewer.ps1")

        $httpClient = New-HealthHttpClient -TimeoutMs 400
        try {
            $cfg = Load-ProcompConfig -ConfigPath $State.ConfigPath -StateDir $State.StateDir -LogDir $State.LogDir
            $bgServices = @($cfg.Services)
            $lastGit = [datetime]::MinValue
            $lastClearEpoch = 0

            while (-not $State.StopRequested) {
                try {
                    # Apply any UI-side overrides (RoutingMode, Enabled)
                    foreach ($svc in $bgServices) {
                        if ($State.ServiceOverrides.ContainsKey($svc.Name)) {
                            $ov = $State.ServiceOverrides[$svc.Name]
                            if ($ov.ContainsKey("RoutingMode")) { $svc.RoutingMode = $ov["RoutingMode"] }
                            if ($ov.ContainsKey("Enabled"))     { $svc.Enabled = $ov["Enabled"] }
                        }
                    }

                    if ($State.ClearLogEpoch -ne $lastClearEpoch) {
                        Clear-ServiceLogView -Services $bgServices
                        $lastClearEpoch = $State.ClearLogEpoch
                    }

                    $now = Get-Date
                    if (($now - $lastGit).TotalSeconds -ge 10) {
                        Refresh-GitSummaries -Services $bgServices -RootDir $root
                        $lastGit = $now
                    }

                    Refresh-ProcessSnapshot -Services $bgServices -DetectIde
                    Poll-AsyncHealth -Services $bgServices -HttpClient $httpClient

                    $rawMode = [bool]$State.RawMode
                    foreach ($svc in $bgServices) {
                        $proc = $svc.CachedProc
                        $State.ServiceMetrics[$svc.Name] = @{
                            PidStr       = if ($proc) { [string]$proc.Id } else { "-" }
                            StatusText   = $svc.StatusText
                            RamUsed      = (Format-RamMb -Proc $proc)
                            Uptime       = (Format-Uptime -Proc $proc)
                            ExternalIde  = $svc.ExternalIde
                            PortConflict = $svc.PortConflict
                            GitSummary   = $svc.GitSummary
                            Healthy      = $svc.Healthy
                        }

                        $entries = @(Get-ServiceLogEntries -Svc $svc -TailCount 200 -RawMode $rawMode)
                        if (($svc.StatusText -eq "CRASHED") -and (Test-Path $svc.ErrFile)) {
                            $errLines = @(Get-Content -Path $svc.ErrFile -Tail 20 -ErrorAction SilentlyContinue)
                            if ($errLines.Count -gt 0) {
                                $entries += [PSCustomObject]@{ SortKey = "9999-99-99 99:99:99.998"; Level = "ERROR"; Text = "--- stderr ---"; StackLines = @(); IsJson = $false }
                                foreach ($el in $errLines) {
                                    if ($el) {
                                        $entries += [PSCustomObject]@{ SortKey = "9999-99-99 99:99:99.999"; Level = "ERROR"; Text = "[$($svc.Name)] $el"; StackLines = @(); IsJson = $false }
                                    }
                                }
                            }
                        }
                        $State.LogBuffers[$svc.Name] = $entries
                    }

                    $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                    $State.RefreshNow = $false
                } catch {
                    $State.LastError = $_.Exception.Message
                }

                # Sleep in short slices so RefreshNow or StopRequested wakes up quickly
                for ($s = 0; $s -lt 10; $s++) {
                    if ($State.StopRequested -or $State.RefreshNow) { break }
                    Start-Sleep -Milliseconds 50
                }
            }
        } finally {
            $httpClient.Dispose()
        }
    }).AddArgument($SharedState)

    $handle = $ps.BeginInvoke()
    return [PSCustomObject]@{
        Runspace    = $rs
        PowerShell  = $ps
        AsyncHandle = $handle
        SharedState = $SharedState
    }
}

function Stop-BackgroundMonitor {
    param($Worker)
    if (-not $Worker) { return }
    try {
        $Worker.SharedState.StopRequested = $true
        if ($Worker.AsyncHandle -and $Worker.AsyncHandle.AsyncWaitHandle) {
            [void]$Worker.AsyncHandle.AsyncWaitHandle.WaitOne(1500)
        }
    } catch { }
    try { $Worker.PowerShell.Dispose() } catch { }
    try { $Worker.Runspace.Close(); $Worker.Runspace.Dispose() } catch { }
}

function Apply-SharedSnapshot {
    param($Services, [hashtable]$SharedState)
    foreach ($svc in $Services) {
        if ($SharedState.ServiceMetrics.ContainsKey($svc.Name)) {
            $m = $SharedState.ServiceMetrics[$svc.Name]
            if (-not $svc.Stopping -and -not $svc.BuildProc) {
                $svc.StatusText   = $m.StatusText
                $svc.Healthy      = $m.Healthy
                $svc.ExternalIde  = $m.ExternalIde
                $svc.PortConflict = $m.PortConflict
            }
            $svc.GitSummary = $m.GitSummary
            if (-not $svc.PSObject.Properties["UiPidStr"]) {
                $svc | Add-Member -NotePropertyName "UiPidStr"  -NotePropertyValue $m.PidStr  -Force
                $svc | Add-Member -NotePropertyName "UiUptime"  -NotePropertyValue $m.Uptime  -Force
                $svc | Add-Member -NotePropertyName "UiRamUsed" -NotePropertyValue $m.RamUsed -Force
            } else {
                $svc.UiPidStr  = $m.PidStr
                $svc.UiUptime  = $m.Uptime
                $svc.UiRamUsed = $m.RamUsed
            }
        }
    }
}

function Get-UnifiedLogsFromMemory {
    param(
        $Services,
        [hashtable]$LogBuffers,
        [int]$SelectedIndex,
        [bool]$AllMode,
        [bool]$ErrorsOnly,
        [int]$MaxRows,
        [string]$FilterText = "",
        [bool]$ExpandStacks = $false
    )

    $entries = @()
    if ($AllMode) {
        foreach ($svc in $Services) {
            if ($LogBuffers.ContainsKey($svc.Name)) {
                $entries += @($LogBuffers[$svc.Name])
            }
        }
        $entries = @($entries | Sort-Object -Property SortKey)
    } else {
        $sel = $Services[$SelectedIndex]
        if ($LogBuffers.ContainsKey($sel.Name)) {
            $entries = @($LogBuffers[$sel.Name])
        }
    }

    if ($ErrorsOnly) {
        $entries = @($entries | Where-Object { $_.Level -eq "ERROR" -or $_.Level -eq "WARN" })
    }
    if ($FilterText) {
        $entries = @($entries | Where-Object { $_.Text.IndexOf($FilterText, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 })
    }

    $latestStackIdx = -1
    if ($ExpandStacks) {
        for ($i = $entries.Count - 1; $i -ge 0; $i--) {
            $e = $entries[$i]
            if ($e.PSObject.Properties["StackLines"] -and $e.StackLines -and ($e.StackLines.Count -gt 0)) {
                $latestStackIdx = $i
                break
            }
        }
    }

    $displayRows = @()
    for ($i = 0; $i -lt $entries.Count; $i++) {
        $e = $entries[$i]
        $hasStack = ($e.PSObject.Properties["StackLines"] -and $e.StackLines -and ($e.StackLines.Count -gt 0))
        if ($hasStack -and ($i -ne $latestStackIdx)) {
            $displayRows += [PSCustomObject]@{ SortKey = $e.SortKey; Level = $e.Level; Text = "$($e.Text) [+$($e.StackLines.Count) stack lines]" }
        } else {
            $displayRows += [PSCustomObject]@{ SortKey = $e.SortKey; Level = $e.Level; Text = $e.Text }
            if ($hasStack) {
                foreach ($sl in $e.StackLines) {
                    $displayRows += [PSCustomObject]@{ SortKey = $e.SortKey; Level = $e.Level; Text = "    $sl" }
                }
            }
        }
    }

    if ($displayRows.Count -gt $MaxRows) {
        $displayRows = @($displayRows[($displayRows.Count - $MaxRows)..($displayRows.Count - 1)])
    }
    return $displayRows
}
