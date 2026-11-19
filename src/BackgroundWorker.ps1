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
        CommandJobQueue    = [System.Collections.Concurrent.ConcurrentQueue[object]]::new()
        CurrentActivity    = ""
        ActiveGitOps       = [Hashtable]::Synchronized(@{})
        StaleServices      = [Hashtable]::Synchronized(@{})
        ServiceOverrides   = [Hashtable]::Synchronized(@{})
        ServiceMetrics     = [Hashtable]::Synchronized(@{})
        LogBuffers         = [Hashtable]::Synchronized(@{})
        ProcompLogEntries  = @()
        ActiveViewMode     = "SERVICES"
        WireMockSubMode    = "JOURNAL"
        WireMockEntries    = @()
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
        . (Join-Path $root "src\WireMockBridge.ps1")
        . (Join-Path $root "src\LogViewer.ps1")

        Set-ProcompActivityCallback -Callback {
            param($msg)
            $State.CurrentActivity = $msg
            $State.ProcompLogEntries = @(Read-ProcompTraceEntries -TailCount 400)
            $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
        }

        $httpClient = New-HealthHttpClient -TimeoutMs 400
        try {
            $State.CurrentActivity = "Loading config..."
            $cfg = Load-ProcompConfig -ConfigPath $State.ConfigPath -StateDir $State.StateDir -LogDir $State.LogDir -ReposRootOverride $State.ReposRoot
            $bgServices = @($cfg.Services)
            $gwAdminUrl = Get-GatewayAdminUrl -Config $cfg
            $gwSvcName = if ($cfg.Gateway) { $cfg.Gateway.ServiceName } else { "" }
            $routingFile = Join-Path $State.StateDir "routing.json"
            $traceOffsets = @{}
            Write-ProcompTraceLog -Category "BG" -Message "BackgroundMonitor started ($($bgServices.Count) service(s))"
            $State.CurrentActivity = ""
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

                    # Process queued lifecycle/WireMock commands off the UI thread
                    $cmdJob = $null
                    while ($State.CommandJobQueue.TryDequeue([ref]$cmdJob)) {
                        try {
                            switch ($cmdJob.Action) {
                                "Startup" {
                                    $buildingNames = @()
                                    $lastErr = ""
                                    foreach ($s in $bgServices) {
                                        if ($s.Enabled) {
                                            $s.StatusText = "PROBING"
                                            if ($State.ServiceMetrics.ContainsKey($s.Name)) {
                                                $State.ServiceMetrics[$s.Name].StatusText = "PROBING"
                                            }
                                        }
                                    }
                                    $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                                    $externalGwReused = $false
                                    if ($gwAdminUrl -and $gwSvcName -and (Test-WireMockReachable -WireMockBaseUrl $gwAdminUrl)) {
                                        $gwTarget = $bgServices | Where-Object { $_.Name -eq $gwSvcName } | Select-Object -First 1
                                        if ($gwTarget -and (-not (Read-PidFromFile -PidFile $gwTarget.PidFile))) {
                                            $externalGwReused = $true
                                            $gwTarget.ExternalIde = $true
                                            $gwTarget.Healthy = $true
                                            $gwTarget.StatusText = "IDE-UP"
                                            Write-ProcompTraceLog -Category "WIREMOCK" -ServiceName $gwSvcName -Message "WireMock already reachable at $gwAdminUrl; reusing external instance"
                                        }
                                    }
                                    foreach ($svc in $bgServices) {
                                        if ($svc.Enabled -and (-not ($externalGwReused -and $svc.Name -eq $gwSvcName))) {
                                            $State.CurrentActivity = "Starting $($svc.Name) (port $($svc.Port))..."
                                            $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                                            try {
                                                Start-ServiceProcess -Svc $svc -RootDir $root -AsyncBuild
                                                if ($svc.BuildProc) { $buildingNames += $svc.Name }
                                            } catch {
                                                $lastErr = $_.Exception.Message
                                                Write-ProcompTraceLog -Category "START-ERR" -ServiceName $svc.Name -Message $lastErr
                                            }
                                        }
                                    }
                                    if ($gwAdminUrl -and ($externalGwReused -or (-not $gwSvcName))) {
                                        $State.CurrentActivity = "Syncing WireMock routes..."
                                        $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                                        $cnt = Sync-WireMockMappings -Services $bgServices -WireMockBaseUrl $gwAdminUrl -GatewayServiceName $gwSvcName
                                        if ($cnt -gt 0 -and (-not $lastErr)) { $State.BannerNotification = "WireMock synced ($cnt routes)" }
                                    }
                                    if ($lastErr) {
                                        $State.BannerNotification = $lastErr
                                    } elseif ($buildingNames.Count -gt 0) {
                                        $State.BannerNotification = "Building $($buildingNames -join ', ') in background..."
                                    } elseif (-not $State.BannerNotification) {
                                        $State.BannerNotification = "Ready"
                                    }
                                }
                                "StartOne" {
                                    $target = $bgServices | Where-Object { $_.Name -eq $cmdJob.ServiceName } | Select-Object -First 1
                                    if ($target) {
                                        $target.Enabled = $true
                                        $target.RoutingMode = "LOCAL"
                                        if ($target.StatusText -eq "BUILD-FAIL") { $target.StatusText = "STOPPED" }
                                        $State.CurrentActivity = "Starting $($target.Name)..."
                                        $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                                        Start-ServiceProcess -Svc $target -RootDir $root -AsyncBuild
                                        $State.BannerNotification = if ($target.BuildProc) { "Building $($target.Name) (will auto-start)..." } else { "Started $($target.Name)" }
                                    }
                                }
                                "StartAll" {
                                    foreach ($s in $bgServices) {
                                        if ($s.Enabled -and ($s.RoutingMode -eq "LOCAL")) {
                                            $State.CurrentActivity = "Starting $($s.Name)..."
                                            $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                                            try { Start-ServiceProcess -Svc $s -RootDir $root -AsyncBuild } catch { }
                                        }
                                    }
                                    $State.BannerNotification = "Started all enabled LOCAL services"
                                }
                                "StopOne" {
                                    $target = $bgServices | Where-Object { $_.Name -eq $cmdJob.ServiceName } | Select-Object -First 1
                                    if ($target) {
                                        if ($target.ExternalIde) {
                                            $State.BannerNotification = "$($target.Name) is IDE-managed; stop it from your IDE."
                                        } else {
                                            $State.CurrentActivity = "Stopping $($target.Name)..."
                                            $target.StatusText = "STOPPING"
                                            $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                                            Stop-ServiceProcess -Svc $target
                                            $State.BannerNotification = "Stopped $($target.Name)"
                                        }
                                    }
                                }
                                "StopAll" {
                                    foreach ($s in $bgServices) {
                                        if (($s.Managed -or $s.BuildProc -or (Test-Path $s.PidFile)) -and (-not $s.ExternalIde)) {
                                            $State.CurrentActivity = "Stopping $($s.Name)..."
                                            $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                                            Stop-ServiceProcess -Svc $s
                                        }
                                    }
                                    $State.BannerNotification = "Stopped all managed services"
                                }
                                "RestartOne" {
                                    $target = $bgServices | Where-Object { $_.Name -eq $cmdJob.ServiceName } | Select-Object -First 1
                                    if ($target) {
                                        if ($target.ExternalIde) {
                                            $State.BannerNotification = "$($target.Name) is IDE-managed; restart it from your IDE."
                                        } else {
                                            $State.CurrentActivity = "Restarting $($target.Name)..."
                                            $target.StatusText = "STOPPING"
                                            $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                                            Stop-ServiceProcess -Svc $target
                                            $target.Enabled = $true
                                            $target.RoutingMode = "LOCAL"
                                            Start-ServiceProcess -Svc $target -RootDir $root -AsyncBuild
                                            $State.BannerNotification = if ($target.BuildProc) { "Building $($target.Name) (will auto-start)..." } else { "Restarted $($target.Name)" }
                                        }
                                    }
                                }
                                "BuildOne" {
                                    $target = $bgServices | Where-Object { $_.Name -eq $cmdJob.ServiceName } | Select-Object -First 1
                                    if ($target) {
                                        $State.CurrentActivity = "Starting build for $($target.Name)..."
                                        $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                                        Stop-ServiceProcess -Svc $target
                                        Start-ServiceBuildAsync -Svc $target -RootDir $root
                                        $State.BannerNotification = "Building $($target.Name) ($($target.Builder))..."
                                    }
                                }
                                "RouteOne" {
                                    $target = $bgServices | Where-Object { $_.Name -eq $cmdJob.ServiceName } | Select-Object -First 1
                                    if ($target) {
                                        $State.CurrentActivity = "Updating WireMock route for $($target.Name)..."
                                        $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                                        $rMap = @{}
                                        foreach ($s in $bgServices) { $rMap[$s.Name] = $s.RoutingMode }
                                        Save-RoutingState -RoutingMap $rMap -RoutingFile $routingFile
                                        $gwSynced = Set-ServiceWireMockRoute -Svc $target -WireMockBaseUrl $gwAdminUrl -GatewayServiceName $gwSvcName
                                        $gwNote = if ($gwSynced) { " (Gateway updated)" } else { "" }
                                        $State.BannerNotification = "Routing for $($target.Name) set to $($target.RoutingMode)$gwNote"
                                    }
                                }
                                "SyncGateway" {
                                    if ($gwAdminUrl) {
                                        $State.CurrentActivity = "Syncing WireMock routes..."
                                        $State.SnapshotVersion = [int]$State.SnapshotVersion + 1
                                        $cnt = Sync-WireMockMappings -Services $bgServices -WireMockBaseUrl $gwAdminUrl -GatewayServiceName $gwSvcName
                                        $State.BannerNotification = "Gateway synced ($cnt routes)"
                                    }
                                }
                            }
                        } catch {
                            $State.BannerNotification = $_.Exception.Message
                            Write-ProcompTraceLog -Category "CMD-ERR" -Message "$($cmdJob.Action): $($_.Exception.Message)"
                        } finally {
                            $State.CurrentActivity = ""
                        }
                    }

                    # Check completed async builds inside BackgroundWorker
                    foreach ($svc in $bgServices) {
                        if ($svc.BuildProc -and $svc.BuildProc.HasExited) {
                            try { $svc.BuildProc.WaitForExit() } catch { }
                            $exitCode = $svc.BuildProc.ExitCode
                            $svc.BuildProc = $null
                            Write-ProcompTraceLog -Category "BUILD-DONE" -ServiceName $svc.Name -Message "exitCode=$exitCode"
                            if ($exitCode -eq 0) {
                                try {
                                    $State.StaleServices[$svc.Name] = $false
                                    $State.CurrentActivity = "Starting $($svc.Name)..."
                                    Start-ServiceProcess -Svc $svc -RootDir $root
                                    $State.BannerNotification = "Build succeeded; started $($svc.Name)"
                                } catch {
                                    $State.BannerNotification = $_.Exception.Message
                                } finally {
                                    $State.CurrentActivity = ""
                                }
                            } else {
                                $svc.StatusText = "BUILD-FAIL"
                                $State.BannerNotification = "Build failed for $($svc.Name) (exit $exitCode)"
                                foreach ($bf in @($svc.LogFile, $svc.ErrFile)) {
                                    if ($bf -and (Test-Path $bf)) {
                                        $bTail = @(Get-Content -Path $bf -Tail 20 -ErrorAction SilentlyContinue)
                                        foreach ($bl in $bTail) {
                                            if ($bl) { Write-ProcompTraceLog -Category "BUILD-FAIL" -ServiceName $svc.Name -Message $bl }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    # Process any queued on-demand Git fetch/pull jobs without blocking the UI thread
                    $job = $null
                    while ($State.GitJobQueue.TryDequeue([ref]$job)) {
                        $State.CurrentActivity = if ($job.Action -like "Pull*") { "Git pulling..." } else { "Git fetching..." }
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
                        $isPull = ($job.Action -like "Pull*")
                        $gitItems = @($targetRepos | ForEach-Object { [PSCustomObject]@{ RepoPath = $_; IsPull = $isPull } })
                        $gitInit = @((Join-Path $root "src\Config.ps1"), (Join-Path $root "src\BuildRunner.ps1"))
                        $gitResults = @(Invoke-ParallelWork -Items $gitItems -MaxThreads 4 -InitScripts $gitInit -ScriptBlock {
                            param($gi)
                            if ($gi.IsPull) {
                                return (Invoke-GitPullRepo -RepoPath $gi.RepoPath)
                            } else {
                                return (Invoke-GitFetchRepo -RepoPath $gi.RepoPath)
                            }
                        })
                        foreach ($rp in $targetRepos) { $State.ActiveGitOps.Remove($rp) }
                        foreach ($gr in $gitResults) {
                            if (-not $gr) { continue }
                            if ($isPull) {
                                if ($gr.Action -eq "PULLED" -and $gr.Commits -gt 0) {
                                    $pulledTotal += $gr.Commits
                                    foreach ($svc in $bgServices) {
                                        if ($svc.Repo -and ((Resolve-RepoPath -Repo $svc.Repo -RootDir $root) -eq $gr.Repo)) {
                                            $State.StaleServices[$svc.Name] = $true
                                        }
                                    }
                                } elseif ($gr.Action -ne "PULLED") {
                                    $skippedNotes += "$(Split-Path -Leaf $gr.Repo): $($gr.Reason)"
                                }
                            }
                        }

                        Refresh-GitSummaries -Services $bgServices -RootDir $root -ActiveOps $State.ActiveGitOps -Quiet
                        $lastGit = Get-Date
                        if ($isPull) {
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
                        $State.CurrentActivity = ""
                    }

                    $now = Get-Date
                    if (($now - $lastGit).TotalMinutes -ge 30) {
                        Refresh-GitSummaries -Services $bgServices -RootDir $root -ActiveOps $State.ActiveGitOps -Quiet
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
                        if ($svc.ErrFile -and (Test-Path $svc.ErrFile)) {
                            $errLines = @(Get-Content -Path $svc.ErrFile -Tail 40 -ErrorAction SilentlyContinue | Where-Object { $_ -and $_.Trim() -ne "" })
                            if ($errLines.Count -gt 0) {
                                $pinBottom = ($svc.StatusText -in @("CRASHED", "BUILD-FAIL", "BUILDING")) -or ($entries.Count -eq 0)
                                $errEntries = @()
                                $errIdx = 0
                                foreach ($el in $errLines) {
                                    $errIdx++
                                    $normErr = Normalize-LogEntry -RawLine $el -DefaultService $svc.Name -RawMode $rawMode -LogMapping $svc.LogMapping
                                    if (-not $normErr.SortKey) {
                                        $normErr.SortKey = if ($pinBottom) { "9999-99-99 99:99:99.$($errIdx.ToString('D3'))" } else { "0000-00-00 00:00:00.$($errIdx.ToString('D3'))" }
                                    }
                                    $normErr.Text = "[stderr] $($normErr.Text)"
                                    $errEntries += $normErr
                                }
                                if ($pinBottom) {
                                    $entries = @($entries) + @([PSCustomObject]@{ EntryId = "err-hdr-$($svc.Name)"; SortKey = "9999-99-99 99:99:99.000"; Level = "WARN"; Text = "--- stderr ---"; StackLines = @(); IsJson = $false }) + $errEntries
                                } else {
                                    $entries = $errEntries + @($entries)
                                }
                            }
                        }
                        if ([System.Environment]::GetEnvironmentVariable("PROCOMP_TRACE_LOG", "Process")) {
                            foreach ($streamPair in @(@("stdout", $svc.LogFile), @("stderr", $svc.ErrFile))) {
                                $stName = $streamPair[0]
                                $stPath = $streamPair[1]
                                if ($stPath -and (Test-Path -LiteralPath $stPath)) {
                                    try {
                                        $fi = Get-Item -LiteralPath $stPath -ErrorAction SilentlyContinue
                                        if ($fi) {
                                            $prevOff = if ($traceOffsets.ContainsKey($stPath)) { [long]$traceOffsets[$stPath] } else { 0L }
                                            if ($fi.Length -lt $prevOff) { $prevOff = 0L }
                                            if ($fi.Length -gt $prevOff) {
                                                $fs = [System.IO.File]::Open($stPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
                                                try {
                                                    [void]$fs.Seek($prevOff, [System.IO.SeekOrigin]::Begin)
                                                    $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8, $true)
                                                    while ($null -ne ($tLine = $sr.ReadLine())) {
                                                        if ($tLine.Trim() -ne "") {
                                                            Write-ProcompTraceLog -Category "SUBPROC" -ServiceName $svc.Name -Message "[$stName] $tLine"
                                                        }
                                                    }
                                                    $traceOffsets[$stPath] = $fs.Position
                                                } finally {
                                                    $fs.Dispose()
                                                }
                                            }
                                        }
                                    } catch { }
                                }
                            }
                        }
                        $State.LogBuffers[$svc.Name] = $entries
                    }

                    $State.ProcompLogEntries = @(Read-ProcompTraceEntries -TailCount 400)
                    if ($State.ActiveViewMode -eq "WIREMOCK") {
                        $State.WireMockEntries = @(Get-WireMockViewerEntries -WireMockBaseUrl $gwAdminUrl -SubMode ([string]$State.WireMockSubMode))
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
