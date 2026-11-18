<#
.SYNOPSIS
    procomp (Process Composer) - Lightweight TUI & orchestrator for local Java/Spring Boot microservices.
.DESCRIPTION
    Compatible with Windows PowerShell 5.1+ and PowerShell 7+.
#>
param(
    [ValidateSet("tui", "up", "down", "status")]
    [string]$Action = "tui",
    [string]$ConfigPath = (Join-Path $PSScriptRoot "services.json")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RootDir     = $PSScriptRoot
$StateDir    = Join-Path $RootDir ".stack_state"
$LogDir      = Join-Path $RootDir "logs"
$RoutingFile = Join-Path $StateDir "routing.json"
$MockJar     = Join-Path $RootDir "mock-service\mock-service.jar"

New-Item -ItemType Directory -Force -Path $StateDir, $LogDir | Out-Null

. (Join-Path $RootDir "src\Config.ps1")
. (Join-Path $RootDir "src\IdeDetector.ps1")
. (Join-Path $RootDir "src\ProcessManager.ps1")
. (Join-Path $RootDir "src\HealthMonitor.ps1")
. (Join-Path $RootDir "src\LogViewer.ps1")
. (Join-Path $RootDir "src\Tui.ps1")

$HttpClient = New-HealthHttpClient -TimeoutMs 400
$services = @(Load-Services -ConfigPath $ConfigPath -StateDir $StateDir -LogDir $LogDir)

if ($Action -eq "up") {
    try {
        foreach ($svc in $services) {
            if ($svc.Enabled) {
                Start-ServiceProcess -Svc $svc -MockJarPath $MockJar
                Write-Host "Started $($svc.Name) (port $($svc.Port))"
            } else {
                Write-Host "Skipped $($svc.Name) (disabled)"
            }
        }
    } finally {
        $HttpClient.Dispose()
    }
    exit 0
}

if ($Action -eq "down") {
    try {
        foreach ($svc in $services) {
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
        Refresh-ProcessSnapshot -Services $services -DetectIde
        foreach ($svc in $services) {
            if ($svc.PortConflict) {
                $svc.StatusText = "PORT-CONFLICT"
            } elseif ($svc.CachedProc) {
                $url = "http://127.0.0.1:$($svc.Port)$($svc.HealthPath)"
                try {
                    $resp = $HttpClient.GetAsync($url).GetAwaiter().GetResult()
                    $svc.Healthy = ([int]$resp.StatusCode -ge 200 -and [int]$resp.StatusCode -lt 300)
                    $resp.Dispose()
                } catch {
                    $svc.Healthy = $false
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
        $rows = foreach ($svc in $services) {
            [PSCustomObject]@{
                Service = $svc.Name
                Type    = $svc.Type
                Port    = $svc.Port
                PID     = if ($svc.CachedProc) { $svc.CachedProc.Id } else { "-" }
                State   = $svc.StatusText
                Routing = if ($svc.RoutingMode -eq "REMOTE") { "REMOTE" } else { "LOCAL :$($svc.Port)" }
                MaxHeap = $svc.MaxHeap
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
$selected = 0
$allLogs = $false
$errorsOnly = $false
$rawLogs = $false
$confirmQuit = $false
$detachOnExit = $false
$banner = "Booting enabled services..."

foreach ($svc in $services) {
    if ($svc.Enabled) {
        try {
            Start-ServiceProcess -Svc $svc -MockJarPath $MockJar
        } catch {
            $banner = $_.Exception.Message
        }
    }
}

$origCtrlC = [Console]::TreatControlCAsInput
[Console]::TreatControlCAsInput = $true
[Console]::CursorVisible = $false
Clear-Host

try {
    $running = $true
    $lastRefresh = [datetime]::MinValue
    $needsRender = $true

    while ($running) {
        $now = Get-Date
        if (($now - $lastRefresh).TotalMilliseconds -ge 750) {
            Refresh-ProcessSnapshot -Services $services -DetectIde
            Poll-AsyncHealth -Services $services -HttpClient $HttpClient
            $lastRefresh = $now
            $needsRender = $true
        } else {
            foreach ($svc in $services) {
                if ($svc.HealthTask -and $svc.HealthTask.IsCompleted) {
                    Poll-AsyncHealth -Services $services -HttpClient $HttpClient
                    $needsRender = $true
                    break
                }
            }
        }

        if ($needsRender) {
            Render-Tui -Services $services -SelectedIndex $selected -BannerMsg $banner -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $confirmQuit
            $needsRender = $false
        }

        if ([Console]::KeyAvailable) {
            while ([Console]::KeyAvailable -and $running) {
                $key = [Console]::ReadKey($true)
                $isCtrlC = ($key.Key -eq [ConsoleKey]::C -and ($key.Modifiers -band [ConsoleModifiers]::Control))

                if ($confirmQuit) {
                    if ($key.KeyChar -eq 'y' -or $key.KeyChar -eq 'Y') {
                        $confirmQuit = $false
                        $detachOnExit = $false
                        $running = $false
                        continue
                    } else {
                        $confirmQuit = $false
                        $banner = "Shutdown canceled."
                        $needsRender = $true
                        continue
                    }
                }

                if ($isCtrlC -or $key.KeyChar -eq 'q') {
                    $confirmQuit = $true
                    $needsRender = $true
                    continue
                }

                switch ($key.Key) {
                    "UpArrow"   { $selected = [Math]::Max(0, $selected - 1); $banner = "Selected $($services[$selected].Name)"; $needsRender = $true }
                    "DownArrow" { $selected = [Math]::Min($services.Count - 1, $selected + 1); $banner = "Selected $($services[$selected].Name)"; $needsRender = $true }
                    "Tab"       { $allLogs = -not $allLogs; $banner = if ($allLogs) { "Showing unified logs for ALL services" } else { "Showing logs for $($services[$selected].Name)" }; $needsRender = $true }
                    default {
                        switch ($key.KeyChar) {
                            'k' { $selected = [Math]::Max(0, $selected - 1); $banner = "Selected $($services[$selected].Name)"; $needsRender = $true }
                            'j' { $selected = [Math]::Min($services.Count - 1, $selected + 1); $banner = "Selected $($services[$selected].Name)"; $needsRender = $true }
                            'a' { $allLogs = -not $allLogs; $banner = if ($allLogs) { "Showing unified logs for ALL services" } else { "Showing logs for $($services[$selected].Name)" }; $needsRender = $true }
                            'e' { $errorsOnly = -not $errorsOnly; $banner = if ($errorsOnly) { "Filter: WARN/ERROR only" } else { "Filter: All log levels" }; $needsRender = $true }
                            'v' { $rawLogs = -not $rawLogs; $banner = if ($rawLogs) { "Log format: Raw" } else { "Log format: Unified/Normalized" }; $needsRender = $true }
                            't' {
                                $svc = $services[$selected]
                                $svc.RoutingMode = if ($svc.RoutingMode -eq "LOCAL") { "REMOTE" } else { "LOCAL" }
                                $rMap = @{}
                                foreach ($s in $services) { $rMap[$s.Name] = $s.RoutingMode }
                                Save-RoutingState -RoutingMap $rMap -RoutingFile $RoutingFile
                                $banner = "Routing for $($svc.Name) set to $($svc.RoutingMode)"
                                $needsRender = $true
                            }
                            's' {
                                $svc = $services[$selected]
                                $svc.Enabled = $true
                                $svc.RoutingMode = "LOCAL"
                                try {
                                    Start-ServiceProcess -Svc $svc -MockJarPath $MockJar
                                    $banner = "Started $($svc.Name)"
                                } catch {
                                    $banner = $_.Exception.Message
                                }
                                $needsRender = $true
                            }
                            'x' {
                                $svc = $services[$selected]
                                if ($svc.ExternalIde) {
                                    $banner = "$($svc.Name) is IDE-managed; stop it from your IDE."
                                } else {
                                    $svc.StatusText = "STOPPING"
                                    Render-Tui -Services $services -SelectedIndex $selected -BannerMsg "Stopping $($svc.Name)..." -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $false
                                    Stop-ServiceProcess -Svc $svc
                                    $banner = "Stopped $($svc.Name)"
                                }
                                $needsRender = $true
                            }
                            'r' {
                                $svc = $services[$selected]
                                if ($svc.ExternalIde) {
                                    $banner = "$($svc.Name) is IDE-managed; restart it from your IDE."
                                } else {
                                    $svc.StatusText = "STOPPING"
                                    Render-Tui -Services $services -SelectedIndex $selected -BannerMsg "Restarting $($svc.Name)..." -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $false
                                    Stop-ServiceProcess -Svc $svc
                                    $svc.Enabled = $true
                                    $svc.RoutingMode = "LOCAL"
                                    Start-ServiceProcess -Svc $svc -MockJarPath $MockJar
                                    $banner = "Restarted $($svc.Name)"
                                }
                                $needsRender = $true
                            }
                            'd' {
                                $detachOnExit = $true
                                $running = $false
                            }
                        }
                    }
                }
            }
        } else {
            Start-Sleep -Milliseconds 25
        }
    }

    if (-not $detachOnExit) {
        foreach ($svc in $services) {
            if (($svc.Managed -or (Test-Path $svc.PidFile)) -and (-not $svc.ExternalIde)) {
                $svc.StatusText = "STOPPING"
                Render-Tui -Services $services -SelectedIndex $selected -BannerMsg "Stopping $($svc.Name)..." -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $false
                Stop-ServiceProcess -Svc $svc
                Render-Tui -Services $services -SelectedIndex $selected -BannerMsg "Stopped $($svc.Name)." -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $false
            }
        }
        Render-Tui -Services $services -SelectedIndex $selected -BannerMsg "All services stopped. Exiting..." -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $false
        Start-Sleep -Milliseconds 350
    }
} finally {
    [Console]::CursorVisible = $true
    [Console]::TreatControlCAsInput = $origCtrlC
    $HttpClient.Dispose()
    Clear-Host
    if ($detachOnExit) {
        Write-Host "Detached. Services remain running in the background. Re-run .\procomp.cmd to re-attach, or .\procomp.cmd -Action down to stop all."
    } else {
        foreach ($svc in $services) {
            if (($svc.Managed -or (Test-Path $svc.PidFile)) -and (-not $svc.ExternalIde)) {
                Stop-ServiceProcess -Svc $svc
            }
        }
        Write-Host "All services stopped."
    }
}
