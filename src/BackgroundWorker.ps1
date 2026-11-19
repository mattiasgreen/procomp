Set-StrictMode -Version Latest

function New-SharedTuiState {
    param(
        [string]$RootDir,
        [string]$ConfigPath,
        [string]$StateDir,
        [string]$LogDir,
        [string]$ReposRoot = ""
    )
    return [Hashtable]::Synchronized(@{
        StopRequested      = $false
        RootDir            = $RootDir
        ConfigPath         = $ConfigPath
        StateDir           = $StateDir
        LogDir             = $LogDir
        ReposRoot          = $ReposRoot
        SnapshotVersion    = 0
        RawMode            = $false
        ClearLogEpoch      = 0
        RefreshNow         = $true
        LastError          = ""
        BannerNotification = ""
        GitJobQueue        = [System.Collections.Concurrent.ConcurrentQueue[object]]::new()
        ActiveGitOps       = [Hashtable]::Synchronized(@{})
        StaleServices      = [Hashtable]::Synchronized(@{})
        ServiceOverrides   = [Hashtable]::Synchronized(@{})
        ServiceMetrics     = [Hashtable]::Synchronized(@{})
        LogBuffers         = [Hashtable]::Synchronized(@{})
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
            $cfg = Load-ProcompConfig -ConfigPath $State.ConfigPath -StateDir $State.StateDir -LogDir $State.LogDir -ReposRootOverride $State.ReposRoot
            $bgServices = @($cfg.Services)
            Write-ProcompTraceLog -Category "BG" -Message "BackgroundMonitor started ($($bgServices.Count) service(s))"
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

                    # Process any queued on-demand Git fetch/pull jobs without blocking the UI thread
                    $job = $null
                    while ($State.GitJobQueue.TryDequeue([ref]$job)) {
                        $targetRepos = @()
                        $seenR = @{}
                        foreach ($svc in $bgServices) {
                            if (-not $svc.Repo) { continue }
                            if ($job.ServiceName -and ($svc.Name -ne $job.ServiceName)) { continue }
                            $rp = Resolve-RepoPath -Repo $svc.Repo -RootDir $root
                            if ($rp -and (-not $seenR.ContainsKey($rp))) {
                                $seenR[$rp] = $true
                                $targetRepos += $rp
                            }
                        }

                        $opLabel = if ($job.Action -like "Pull*") { "PULLING..." } else { "FETCHING..." }
                        foreach ($rp in $targetRepos) { $State.ActiveGitOps[$rp] = $opLabel }
                        Refresh-GitSummaries -Services $bgServices -RootDir $root -ActiveOps $State.ActiveGitOps
                        foreach ($svc in $bgServices) {
                            if ($State.ServiceMetrics.ContainsKey($svc.Name)) {
                                $State.ServiceMetrics[$svc.Name].GitSummary = $svc.GitSummary
                            }
                        }
                        $State.SnapshotVersion = [int]$State.SnapshotVersion + 1

                        $pulledTotal = 0
                        $skippedNotes = @()
                        foreach ($rp in $targetRepos) {
                            if ($job.Action -like "Pull*") {
                                $pr = Invoke-GitPullRepo -RepoPath $rp
                                if ($pr.Action -eq "PULLED" -and $pr.Commits -gt 0) {
                                    $pulledTotal += $pr.Commits
                                    foreach ($svc in $bgServices) {
                                        if ($svc.Repo -and ((Resolve-RepoPath -Repo $svc.Repo -RootDir $root) -eq $rp)) {
                                            $State.StaleServices[$svc.Name] = $true
                                        }
                                    }
                                } elseif ($pr.Action -ne "PULLED") {
                                    $skippedNotes += "$(Split-Path -Leaf $rp): $($pr.Reason)"
                                }
                            } else {
                                [void](Invoke-GitFetchRepo -RepoPath $rp)
                            }
                            $State.ActiveGitOps.Remove($rp)
                        }

                        Refresh-GitSummaries -Services $bgServices -RootDir $root -ActiveOps $State.ActiveGitOps
                        $lastGit = Get-Date
                        if ($job.Action -like "Pull*") {
                            if ($pulledTotal -gt 0) {
                                $State.BannerNotification = "Pulled $pulledTotal commit(s) - press [b] to rebuild"
                            } elseif ($skippedNotes.Count -gt 0) {
                                $State.BannerNotification = "Pull skipped: $($skippedNotes[0])"
                            } else {
                                $State.BannerNotification = "Already up to date."
                            }
                        } else {
                            $State.BannerNotification = "Git fetch complete ($($targetRepos.Count) repo(s))."
                        }
                    }

                    $now = Get-Date
                    if (($now - $lastGit).TotalSeconds -ge 10) {
                        Refresh-GitSummaries -Services $bgServices -RootDir $root -ActiveOps $State.ActiveGitOps
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

                        $entries = @(Get-ServiceLogEntries -Svc $svc -TailCount 1000 -RawMode $rawMode)
                        if (($svc.StatusText -eq "CRASHED") -and (Test-Path $svc.ErrFile)) {
                            $errLines = @(Get-Content -Path $svc.ErrFile -Tail 20 -ErrorAction SilentlyContinue)
                            if ($errLines.Count -gt 0) {
                                $entries += [PSCustomObject]@{ EntryId = "err-hdr-$($svc.Name)"; SortKey = "9999-99-99 99:99:99.998"; Level = "ERROR"; Text = "--- stderr ---"; StackLines = @(); IsJson = $false }
                                foreach ($el in $errLines) {
                                    if ($el) {
                                        $entries += [PSCustomObject]@{ EntryId = "err-$($svc.Name)"; SortKey = "9999-99-99 99:99:99.999"; Level = "ERROR"; Text = "[$($svc.Name)] $el"; StackLines = @(); IsJson = $false }
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
                    Write-ProcompTraceLog -Category "BG-ERROR" -Message "$($_.Exception.Message) ($($_.InvocationInfo.PositionMessage -replace '[\r\n]+', ' '))"
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
            $isStale = ($SharedState.StaleServices.ContainsKey($svc.Name) -and [bool]$SharedState.StaleServices[$svc.Name])
            if (-not $svc.PSObject.Properties["NeedsRebuild"]) {
                $svc | Add-Member -NotePropertyName "NeedsRebuild" -NotePropertyValue $isStale -Force
            } else {
                $svc.NeedsRebuild = $isStale
            }
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

function Get-LogViewportFromMemory {
    param(
        $Services,
        [hashtable]$LogBuffers,
        [int]$SelectedIndex,
        [bool]$AllMode,
        [bool]$ErrorsOnly,
        [int]$MaxRows,
        [string]$FilterText = "",
        [bool]$ExpandStacks = $false,
        [int]$ScrollOffset = 0,
        [hashtable]$ExpandedEntryIds = @{}
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

    return (Get-LogViewport -Entries $entries -MaxRows $MaxRows -ScrollOffset $ScrollOffset -ExpandedEntryIds $ExpandedEntryIds -ExpandLatest $ExpandStacks)
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
        [bool]$ExpandStacks = $false,
        [int]$ScrollOffset = 0,
        [hashtable]$ExpandedEntryIds = @{}
    )
    $vp = Get-LogViewportFromMemory -Services $Services -LogBuffers $LogBuffers -SelectedIndex $SelectedIndex -AllMode $AllMode -ErrorsOnly $ErrorsOnly -MaxRows $MaxRows -FilterText $FilterText -ExpandStacks $ExpandStacks -ScrollOffset $ScrollOffset -ExpandedEntryIds $ExpandedEntryIds
    return $vp.Rows
}
