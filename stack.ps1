<#
.SYNOPSIS
    Lightweight Process-Compose-style TUI & orchestrator for local Java/Spring Boot microservices.
.DESCRIPTION
    Compatible with Windows PowerShell 5.1+ and PowerShell 7+.
    Features async non-blocking health checks, batched process/RAM metrics,
    in-TUI shutdown confirmation, and unified JSON + plain-text log tailing.
#>
param(
    [ValidateSet("tui", "up", "down", "status", "selftest")]
    [string]$Action = "tui",
    [string]$ConfigPath = (Join-Path $PSScriptRoot "services.json")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Net.Http

$RootDir = $PSScriptRoot
$StateDir = Join-Path $RootDir ".stack_state"
$LogDir = Join-Path $RootDir "logs"
$MockJar = Join-Path $RootDir "mock-service\mock-service.jar"

New-Item -ItemType Directory -Force -Path $StateDir, $LogDir | Out-Null

$ESC = [char]27
$C_RESET  = "$ESC[0m"
$C_BOLD   = "$ESC[1m"
$C_DIM    = "$ESC[2m"
$C_RED    = "$ESC[31m"
$C_GREEN  = "$ESC[32m"
$C_YELLOW = "$ESC[33m"
$C_CYAN   = "$ESC[36m"
$C_MAGENTA = "$ESC[35m"
$C_REV    = "$ESC[7m"

# Shared HttpClient with short timeout for async health probes
$HttpClient = New-Object System.Net.Http.HttpClient
$HttpClient.Timeout = [TimeSpan]::FromMilliseconds(400)

function Get-TypeDefaults {
    param([string]$Type)
    switch ($Type) {
        "activemq"    { return @{ HealthPath = "/api/jolokia";     JvmArgs = "-Xmx256m" } }
        "spring-boot" { return @{ HealthPath = "/actuator/health"; JvmArgs = "-Xmx384m" } }
        default       { return @{ HealthPath = "/health";          JvmArgs = "-Xmx256m" } }
    }
}

function Parse-MaxHeap {
    param([string]$JvmArgs)
    if ($JvmArgs -match '-Xmx(\d+[kmgKMG]?)') {
        return $Matches[1].ToUpper()
    }
    return "-"
}

function Load-Services {
    if (-not (Test-Path $ConfigPath)) {
        throw "Config file not found: $ConfigPath"
    }
    $raw = Get-Content -Path $ConfigPath -Raw | ConvertFrom-Json
    $list = @()
    foreach ($item in $raw) {
        $type = if ($item.PSObject.Properties["type"] -and $item.type) { [string]$item.type } else { "spring-boot" }
        $defaults = Get-TypeDefaults -Type $type
        $healthPath = if ($item.PSObject.Properties["healthPath"] -and $item.healthPath) { [string]$item.healthPath } else { $defaults.HealthPath }
        $jvmArgs = if ($item.PSObject.Properties["jvmArgs"] -and $item.jvmArgs) { [string]$item.jvmArgs } else { $defaults.JvmArgs }
        $enabled = if ($item.PSObject.Properties["enabled"]) { [bool]$item.enabled } else { $true }
        $delayMs = if ($item.PSObject.Properties["startupDelayMs"] -and $item.startupDelayMs) { [int]$item.startupDelayMs } else { 2500 }
        $logFmt = if ($item.PSObject.Properties["logFormat"] -and $item.logFormat) { [string]$item.logFormat } else { "text" }

        $list += [PSCustomObject]@{
            Name           = [string]$item.name
            Type           = $type
            Port           = [int]$item.localport
            GitRepo        = [string]$item.gitrepo
            GradleSubproj  = [string]$item.gradlesubproject
            HealthPath     = $healthPath
            JvmArgs        = $jvmArgs
            MaxHeap        = (Parse-MaxHeap -JvmArgs $jvmArgs)
            StartupDelayMs = $delayMs
            LogFormat      = $logFmt
            Enabled        = $enabled
            PidFile        = (Join-Path $StateDir "$($item.name).pid")
            LogFile        = (Join-Path $LogDir "$($item.name).log")
            ErrFile        = (Join-Path $LogDir "$($item.name).err.log")
            Managed        = $false
            Healthy        = $false
            Stopping       = $false
            LastHealthAt   = [datetime]::MinValue
            HealthTask     = $null
            CachedPid        = $null
            CachedProc       = $null
            CachedLogStamp   = ""
            CachedLogRawMode = $null
            CachedLogTail    = 0
            CachedLogEntries = @()
            StatusText       = if ($enabled) { "STOPPED" } else { "DISABLED" }
        }
    }
    return $list
}

function Read-PidFromFile {
    param([string]$PidFile)
    if (-not (Test-Path $PidFile)) { return $null }
    $pidText = (Get-Content -Path $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $pidText) { return $null }
    $procId = 0
    if ([int]::TryParse($pidText.Trim(), [ref]$procId)) {
        return $procId
    }
    return $null
}

function Refresh-ProcessSnapshot {
    param($Services)
    $pidList = @()
    foreach ($svc in $Services) {
        $p = Read-PidFromFile -PidFile $svc.PidFile
        $svc.CachedPid = $p
        if ($p) { $pidList += $p }
    }

    $procMap = @{}
    if ($pidList.Count -gt 0) {
        $procs = @(Get-Process -Id $pidList -ErrorAction SilentlyContinue)
        foreach ($pr in $procs) {
            if (-not $pr.HasExited) {
                $procMap[$pr.Id] = $pr
            }
        }
    }

    foreach ($svc in $Services) {
        if ($svc.CachedPid -and $procMap.ContainsKey($svc.CachedPid)) {
            $svc.CachedProc = $procMap[$svc.CachedPid]
        } else {
            $svc.CachedProc = $null
        }
    }
}

function Poll-AsyncHealth {
    param($Services)
    $now = Get-Date
    foreach ($svc in $Services) {
        if ($svc.Stopping) {
            $svc.StatusText = "STOPPING"
            continue
        }

        if (-not $svc.CachedProc) {
            if ($svc.HealthTask) { $svc.HealthTask = $null }
            if ($svc.CachedPid) {
                $svc.StatusText = "CRASHED"
                $svc.Healthy = $false
            } else {
                $svc.StatusText = if ($svc.Enabled) { "STOPPED" } else { "DISABLED" }
                $svc.Healthy = $false
            }
            continue
        }

        # Check if an in-flight async health task has completed
        if ($svc.HealthTask -and $svc.HealthTask.IsCompleted) {
            try {
                if ($svc.HealthTask.Status -eq [System.Threading.Tasks.TaskStatus]::RanToCompletion) {
                    $resp = $svc.HealthTask.Result
                    $code = [int]$resp.StatusCode
                    $resp.Dispose()
                    $svc.Healthy = ($code -ge 200 -and $code -lt 300)
                } else {
                    $svc.Healthy = $false
                }
            } catch {
                $svc.Healthy = $false
            }
            $svc.HealthTask = $null
            $svc.LastHealthAt = $now
        }

        # Launch a new non-blocking health probe if none in flight and interval elapsed
        if ((-not $svc.HealthTask) -and (($now - $svc.LastHealthAt).TotalMilliseconds -ge 1200)) {
            $url = "http://127.0.0.1:$($svc.Port)$($svc.HealthPath)"
            try {
                $svc.HealthTask = $HttpClient.GetAsync($url)
            } catch {
                $svc.Healthy = $false
                $svc.LastHealthAt = $now
            }
        }

        $svc.StatusText = if ($svc.Healthy) { "HEALTHY" } else { "STARTING" }
    }
}

function Start-ServiceProcess {
    param($Svc)
    $existingPid = Read-PidFromFile -PidFile $Svc.PidFile
    if ($existingPid) {
        $existing = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
        if ($existing -and -not $existing.HasExited) {
            $Svc.Managed = $true
            $Svc.CachedPid = $existing.Id
            $Svc.CachedProc = $existing
            return
        }
    }

    if (-not (Test-Path $MockJar)) {
        throw "Mock JAR not found at $MockJar. Compile MockService.java first."
    }

    $argList = @()
    if ($Svc.JvmArgs) {
        $argList += ($Svc.JvmArgs -split '\s+' | Where-Object { $_ -ne "" })
    }
    $argList += "-Dservice.name=$($Svc.Name)"
    $argList += "-Dservice.type=$($Svc.Type)"
    $argList += "-Dserver.port=$($Svc.Port)"
    $argList += "-Dhealth.path=$($Svc.HealthPath)"
    $argList += "-Dstartup.delay.ms=$($Svc.StartupDelayMs)"
    $argList += "-Dlog.format=$($Svc.LogFormat)"
    $argList += "-jar"
    $argList += $MockJar

    $proc = Start-Process -FilePath "java" `
        -ArgumentList $argList `
        -RedirectStandardOutput $Svc.LogFile `
        -RedirectStandardError $Svc.ErrFile `
        -WindowStyle Hidden `
        -PassThru

    Set-Content -Path $Svc.PidFile -Value $proc.Id -Encoding ASCII
    $Svc.Managed = $true
    $Svc.Stopping = $false
    $Svc.Healthy = $false
    $Svc.HealthTask = $null
    $Svc.LastHealthAt = [datetime]::MinValue
    $Svc.CachedPid = $proc.Id
    $Svc.CachedProc = $proc
    $Svc.StatusText = "STARTING"
}

function Stop-ServiceProcess {
    param($Svc)
    $Svc.Stopping = $true
    $Svc.HealthTask = $null
    if (Test-Path $Svc.PidFile) {
        $pidText = (Get-Content -Path $Svc.PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($pidText -and $pidText.Trim() -match '^\d+$') {
            $procId = [int]$pidText.Trim()
            $prevEap = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            try {
                & taskkill.exe /F /T /PID $procId >$null 2>&1
            } finally {
                $ErrorActionPreference = $prevEap
            }
        }
        Remove-Item -Path $Svc.PidFile -Force -ErrorAction SilentlyContinue
    }
    $Svc.Stopping = $false
    $Svc.Healthy = $false
    $Svc.Managed = $false
    $Svc.CachedPid = $null
    $Svc.CachedProc = $null
    $Svc.StatusText = if ($Svc.Enabled) { "STOPPED" } else { "DISABLED" }
}

function Format-Uptime {
    param($Proc)
    if (-not $Proc) { return "-" }
    try {
        $span = (Get-Date) - $Proc.StartTime
        if ($span.TotalHours -ge 1) {
            return "{0:00}:{1:00}:{2:00}" -f [int]$span.TotalHours, $span.Minutes, $span.Seconds
        }
        return "{0:00}:{1:00}" -f $span.Minutes, $span.Seconds
    } catch {
        return "-"
    }
}

function Format-RamMb {
    param($Proc)
    if (-not $Proc) { return "-" }
    try {
        $mb = [math]::Round($Proc.WorkingSet64 / 1MB, 1)
        return "$mb MB"
    } catch {
        return "-"
    }
}

function Normalize-LogEntry {
    param([string]$RawLine, [string]$DefaultService, [bool]$RawMode)
    $sortKey = ""
    $level = "INFO"
    $display = $RawLine

    if (-not $RawMode -and $RawLine.TrimStart().StartsWith("{")) {
        try {
            $obj = $RawLine | ConvertFrom-Json -ErrorAction Stop
            $tsRaw = if ($obj.PSObject.Properties["@timestamp"]) { [string]$obj.'@timestamp' }
                     elseif ($obj.PSObject.Properties["timestamp"]) { [string]$obj.timestamp }
                     elseif ($obj.PSObject.Properties["time"]) { [string]$obj.time }
                     else { "" }

            $tsDisplay = $tsRaw
            $dto = [DateTimeOffset]::MinValue
            if ($tsRaw -and [DateTimeOffset]::TryParse($tsRaw, [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::AssumeUniversal, [ref]$dto)) {
                $localDt = $dto.LocalDateTime
                $sortKey = $localDt.ToString("yyyy-MM-dd HH:mm:ss.fff")
                $tsDisplay = $localDt.ToString("yyyy-MM-dd HH:mm:ss.fff")
            } elseif ($tsRaw -match '(\d{2}:\d{2}:\d{2}\.\d{3})') {
                $sortKey = $Matches[1]
                $tsDisplay = $Matches[1]
            }

            $lvl = if ($obj.PSObject.Properties["level"]) { [string]$obj.level }
                   elseif ($obj.PSObject.Properties["severity"]) { [string]$obj.severity }
                   else { "INFO" }
            $level = $lvl.ToUpper()

            $svc = if ($obj.PSObject.Properties["service"]) { [string]$obj.service } else { $DefaultService }
            $msg = if ($obj.PSObject.Properties["message"]) { [string]$obj.message }
                   elseif ($obj.PSObject.Properties["msg"]) { [string]$obj.msg }
                   else { $RawLine }

            $trace = if ($obj.PSObject.Properties["traceId"] -and $obj.traceId) { " [trace=$($obj.traceId)]" } else { "" }
            $display = "{0}  {1,-5} [{2}] --- {3}{4}" -f $tsDisplay, $level, $svc, $msg, $trace
            return [PSCustomObject]@{ SortKey = $sortKey; Level = $level; Text = $display; IsJson = $true }
        } catch {
            # Fallback to plain text if malformed JSON
        }
    }

    if ($RawLine -match '\b(ERROR|FATAL)\b') { $level = "ERROR" }
    elseif ($RawLine -match '\bWARN\b') { $level = "WARN" }

    if ($RawLine -match '(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})') {
        $sortKey = $Matches[1]
    } elseif ($RawLine -match '(\d{2}:\d{2}:\d{2}\.\d{3})') {
        $sortKey = $Matches[1]
    }
    if ($RawMode -or $RawLine -match "\[$DefaultService\]") {
        $display = $RawLine
    } else {
        $display = "[$DefaultService] $RawLine"
    }

    return [PSCustomObject]@{ SortKey = $sortKey; Level = $level; Text = $display; IsJson = $false }
}

function Get-ServiceLogEntries {
    param($Svc, [int]$TailCount, [bool]$RawMode)
    if (-not (Test-Path $Svc.LogFile)) {
        $Svc.CachedLogStamp = ""
        $Svc.CachedLogEntries = @()
        return @()
    }

    $fi = [System.IO.FileInfo]::new($Svc.LogFile)
    $stamp = "$($fi.Length):$($fi.LastWriteTimeUtc.Ticks)"
    if (($Svc.CachedLogStamp -eq $stamp) -and ($Svc.CachedLogRawMode -eq $RawMode) -and ($Svc.CachedLogTail -ge $TailCount)) {
        return $Svc.CachedLogEntries
    }

    $parsed = @()
    $lines = @(Get-Content -Path $Svc.LogFile -Tail $TailCount -ErrorAction SilentlyContinue)
    foreach ($l in $lines) {
        if ($l) { $parsed += (Normalize-LogEntry -RawLine $l -DefaultService $Svc.Name -RawMode $RawMode) }
    }
    $Svc.CachedLogStamp = $stamp
    $Svc.CachedLogRawMode = $RawMode
    $Svc.CachedLogTail = $TailCount
    $Svc.CachedLogEntries = $parsed
    return $parsed
}

function Get-UnifiedLogs {
    param($Services, [int]$SelectedIndex, [bool]$AllMode, [bool]$ErrorsOnly, [bool]$RawMode, [int]$MaxRows)

    $entries = @()
    if ($AllMode) {
        $perSvc = [Math]::Max(10, $MaxRows)
        foreach ($svc in $Services) {
            $entries += @(Get-ServiceLogEntries -Svc $svc -TailCount $perSvc -RawMode $RawMode)
            if (($svc.StatusText -eq "CRASHED") -and (Test-Path $svc.ErrFile)) {
                $errLines = @(Get-Content -Path $svc.ErrFile -Tail 4 -ErrorAction SilentlyContinue)
                foreach ($l in $errLines) {
                    if ($l) { $entries += [PSCustomObject]@{ SortKey = "9999-99-99 99:99:99.999"; Level = "ERROR"; Text = "[$($svc.Name)] $l"; IsJson = $false } }
                }
            }
        }
        $entries = @($entries | Sort-Object -Property SortKey)
    } else {
        $sel = $Services[$SelectedIndex]
        $entries += @(Get-ServiceLogEntries -Svc $sel -TailCount ($MaxRows * 2) -RawMode $RawMode)
        if (($sel.StatusText -eq "CRASHED") -and (Test-Path $sel.ErrFile)) {
            $errLines = @(Get-Content -Path $sel.ErrFile -Tail $MaxRows -ErrorAction SilentlyContinue)
            if ($errLines.Count -gt 0) {
                $entries += [PSCustomObject]@{ SortKey = ""; Level = "ERROR"; Text = "--- stderr ---"; IsJson = $false }
                foreach ($l in $errLines) {
                    if ($l) { $entries += [PSCustomObject]@{ SortKey = ""; Level = "ERROR"; Text = $l; IsJson = $false } }
                }
            }
        }
    }

    if ($ErrorsOnly) {
        $entries = @($entries | Where-Object { $_.Level -eq "ERROR" -or $_.Level -eq "WARN" })
    }

    if ($entries.Count -gt $MaxRows) {
        $entries = @($entries[($entries.Count - $MaxRows)..($entries.Count - 1)])
    }
    return $entries
}

function Pad-Line {
    param([string]$Text, [int]$Width)
    $plain = $Text -replace "$ESC\[[0-9;]*m", ""
    $pad = $Width - $plain.Length
    if ($pad -gt 0) {
        return $Text + (" " * $pad)
    }
    return $Text
}

function Render-Tui {
    param($Services, [int]$SelectedIndex, [string]$BannerMsg, [bool]$AllLogs, [bool]$ErrorsOnly, [bool]$RawLogs, [bool]$ConfirmQuit, [switch]$DryRun)

    $width = 120
    $height = 30
    try {
        $width = [Math]::Max(80, [Console]::WindowWidth - 1)
        $height = [Math]::Max(20, [Console]::WindowHeight)
    } catch { }

    $sb = New-Object System.Text.StringBuilder
    $modeTag = if ($AllLogs) { "ALL" } else { $Services[$SelectedIndex].Name }
    $errTag = if ($ErrorsOnly) { " | ${C_YELLOW}WARN/ERR ONLY${C_RESET}" } else { "" }
    $rawTag = if ($RawLogs) { " | ${C_MAGENTA}RAW${C_RESET}" } else { " | ${C_GREEN}UNIFIED${C_RESET}" }

    [void]$sb.Append((Pad-Line "$C_BOLD$C_CYAN Process Compose PoC$C_RESET $C_DIM| Services: $($Services.Count) | Log: $modeTag$C_RESET$rawTag$errTag" $width) + "`n")
    [void]$sb.Append((Pad-Line "$C_DIM Keys: [Up/Down] Select  [s] Start  [x] Stop  [r] Restart  [a/Tab] All Logs  [e] Warn/Err  [v] Raw  [d] Detach  [q] Quit$C_RESET" $width) + "`n")
    [void]$sb.Append(("-" * $width) + "`n")

    $hdr = "{0,-2} {1,-21} {2,-12} {3,-6} {4,-7} {5,-10} {6,-9} {7,-8} {8,-10}" -f "", "SERVICE", "TYPE", "PORT", "PID", "STATE", "UPTIME", "MAXHEAP", "RAM USED"
    [void]$sb.Append((Pad-Line "$C_BOLD$hdr$C_RESET" $width) + "`n")

    for ($i = 0; $i -lt $Services.Count; $i++) {
        $svc = $Services[$i]
        $proc = $svc.CachedProc
        $pidStr = if ($proc) { [string]$proc.Id } else { "-" }
        $uptime = Format-Uptime -Proc $proc
        $ram = Format-RamMb -Proc $proc

        $stateColor = switch ($svc.StatusText) {
            "HEALTHY"  { $C_GREEN }
            "STARTING" { $C_YELLOW }
            "STOPPING" { $C_YELLOW }
            "CRASHED"  { $C_RED }
            default    { $C_DIM }
        }

        $marker = if ($i -eq $SelectedIndex) { ">" } else { " " }
        $rowPrefix = if ($i -eq $SelectedIndex) { "$C_REV" } else { "" }
        $rowSuffix = if ($i -eq $SelectedIndex) { "$C_RESET" } else { "" }

        $line = "{0,-2} {1,-21} {2,-12} {3,-6} {4,-7} {5}{6,-10}{7} {8,-9} {9,-8} {10,-10}" -f `
            $marker, $svc.Name, $svc.Type, $svc.Port, $pidStr, $stateColor, $svc.StatusText, "$C_RESET$rowPrefix", $uptime, $svc.MaxHeap, $ram

        [void]$sb.Append((Pad-Line "$rowPrefix$line$rowSuffix" $width) + "`n")
    }

    $sel = $Services[$SelectedIndex]
    [void]$sb.Append(("-" * $width) + "`n")
    $logTitle = if ($AllLogs) { "Unified Logs: ALL SERVICES (JSON + Text normalized)" } else { "Logs: $($sel.Name) ($($sel.LogFormat))" }
    [void]$sb.Append((Pad-Line "$C_BOLD$C_CYAN $logTitle$C_RESET" $width) + "`n")

    $usedRows = 4 + $Services.Count + 2 + 1
    $logRows = [Math]::Max(4, $height - $usedRows - 1)
    $entries = @(Get-UnifiedLogs -Services $Services -SelectedIndex $SelectedIndex -AllMode $AllLogs -ErrorsOnly $ErrorsOnly -RawMode $RawLogs -MaxRows $logRows)

    for ($r = 0; $r -lt $logRows; $r++) {
        if ($r -lt $entries.Count) {
            $e = $entries[$r]
            $text = $e.Text
            if ($text.Length -gt $width) {
                $text = $text.Substring(0, $width)
            }
            $colored = switch ($e.Level) {
                "ERROR" { "$C_RED$text$C_RESET" }
                "WARN"  { "$C_YELLOW$text$C_RESET" }
                default { $text }
            }
            [void]$sb.Append((Pad-Line $colored $width) + "`n")
        } else {
            [void]$sb.Append((Pad-Line "" $width) + "`n")
        }
    }

    if ($ConfirmQuit) {
        [void]$sb.Append((Pad-Line "$C_BOLD$C_YELLOW [CONFIRM] Stop all running services and exit? Press [y] to confirm, any other key to cancel.$C_RESET" $width))
    } else {
        [void]$sb.Append((Pad-Line "$C_DIM $BannerMsg$C_RESET" $width))
    }

    if ($DryRun) {
        return $sb.ToString()
    }
    [Console]::SetCursorPosition(0, 0)
    [Console]::Write($sb.ToString())
}

# --- Entrypoint ---
$services = @(Load-Services)

if ($Action -eq "up") {
    foreach ($svc in $services) {
        if ($svc.Enabled) {
            Start-ServiceProcess -Svc $svc
            Write-Host "Started $($svc.Name) (port $($svc.Port))"
        } else {
            Write-Host "Skipped $($svc.Name) (disabled)"
        }
    }
    exit 0
}

if ($Action -eq "down") {
    foreach ($svc in $services) {
        Stop-ServiceProcess -Svc $svc
        Write-Host "Stopped $($svc.Name)"
    }
    exit 0
}

if ($Action -eq "status") {
    Refresh-ProcessSnapshot -Services $services
    foreach ($svc in $services) {
        if ($svc.CachedProc) {
            $url = "http://127.0.0.1:$($svc.Port)$($svc.HealthPath)"
            try {
                $resp = $HttpClient.GetAsync($url).GetAwaiter().GetResult()
                $svc.Healthy = ([int]$resp.StatusCode -ge 200 -and [int]$resp.StatusCode -lt 300)
                $resp.Dispose()
            } catch {
                $svc.Healthy = $false
            }
            $svc.StatusText = if ($svc.Healthy) { "HEALTHY" } else { "STARTING" }
        } else {
            $svc.StatusText = if ($svc.CachedPid) { "CRASHED" } elseif ($svc.Enabled) { "STOPPED" } else { "DISABLED" }
        }
    }
    $rows = foreach ($svc in $services) {
        [PSCustomObject]@{
            Service = $svc.Name
            Type    = $svc.Type
            Port    = $svc.Port
            PID     = if ($svc.CachedProc) { $svc.CachedProc.Id } else { "-" }
            State   = $svc.StatusText
            MaxHeap = $svc.MaxHeap
            RamUsed = (Format-RamMb -Proc $svc.CachedProc)
        }
    }
    $rows | Format-Table -AutoSize
    exit 0
}

if ($Action -eq "selftest") {
    Write-Host "[1/5] Starting enabled services..."
    foreach ($svc in $services) {
        if ($svc.Enabled) { Start-ServiceProcess -Svc $svc }
    }

    Write-Host "[2/5] Polling async health for up to 6s..."
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt 6000) {
        Refresh-ProcessSnapshot -Services $services
        Poll-AsyncHealth -Services $services
        $allHealthy = $true
        foreach ($svc in $services) {
            if ($svc.Enabled -and $svc.StatusText -ne "HEALTHY") { $allHealthy = $false }
        }
        if ($allHealthy) { break }
        Start-Sleep -Milliseconds 100
    }
    $healthyCount = @($services | Where-Object { $_.StatusText -eq "HEALTHY" }).Count
    Write-Host "  Healthy services: $healthyCount / $(@($services | Where-Object { $_.Enabled }).Count)"
    if ($healthyCount -ne @($services | Where-Object { $_.Enabled }).Count) {
        throw "Expected all enabled services to become HEALTHY"
    }

    Write-Host "[3/5] Testing Render-Tui across all rows (including empty-log disabled service) & modes..."
    for ($idx = 0; $idx -lt $services.Count; $idx++) {
        foreach ($allMode in @($false, $true)) {
            foreach ($errMode in @($false, $true)) {
                foreach ($rawMode in @($false, $true)) {
                    $frame = Render-Tui -Services $services -SelectedIndex $idx -BannerMsg "selftest" -AllLogs $allMode -ErrorsOnly $errMode -RawLogs $rawMode -ConfirmQuit $false -DryRun
                    if (-not $frame) { throw "Empty frame rendered at idx=$idx" }
                }
            }
        }
    }
    $confirmFrame = Render-Tui -Services $services -SelectedIndex 0 -BannerMsg "" -AllLogs $false -ErrorsOnly $false -RawLogs $false -ConfirmQuit $true -DryRun
    if ($confirmFrame -notmatch '\[CONFIRM\]') { throw "ConfirmQuit prompt missing" }

    Write-Host "[4/5] Verifying chronological sort of unified JSON (UTC) + plain-text (local) logs..."
    $merged = @(Get-UnifiedLogs -Services $services -SelectedIndex 0 -AllMode $true -ErrorsOnly $false -RawMode $false -MaxRows 30)
    if ($merged.Count -lt 5) { throw "Expected >= 5 merged log entries, got $($merged.Count)" }
    for ($k = 1; $k -lt $merged.Count; $k++) {
        if ($merged[$k].SortKey -lt $merged[$k - 1].SortKey) {
            throw "Out-of-order log sort at index ${k}: '$($merged[$k-1].SortKey)' > '$($merged[$k].SortKey)'"
        }
    }
    Write-Host "  Merged $($merged.Count) lines in strict chronological order (sample: $($merged[0].Text))"

    Write-Host "[5/5] Stopping all services..."
    foreach ($svc in $services) {
        Stop-ServiceProcess -Svc $svc
    }
    Refresh-ProcessSnapshot -Services $services
    $stillRunning = @($services | Where-Object { $_.CachedProc -ne $null }).Count
    if ($stillRunning -ne 0) { throw "$stillRunning services still running after stop" }
    Write-Host "SELFTEST PASSED!"
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
        Start-ServiceProcess -Svc $svc
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
            Refresh-ProcessSnapshot -Services $services
            Poll-AsyncHealth -Services $services
            $lastRefresh = $now
            $needsRender = $true
        } else {
            # Pick up any just-completed async health checks immediately without re-querying OS processes
            foreach ($svc in $services) {
                if ($svc.HealthTask -and $svc.HealthTask.IsCompleted) {
                    Poll-AsyncHealth -Services $services
                    $needsRender = $true
                    break
                }
            }
        }

        if ($needsRender) {
            Render-Tui -Services $services -SelectedIndex $selected -BannerMsg $banner -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $confirmQuit
            $needsRender = $false
        }

        # Fast 25ms keyboard poll; drain all queued keys before rendering so rapid arrows never lag
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
                        's' {
                            $svc = $services[$selected]
                            $svc.Enabled = $true
                            Start-ServiceProcess -Svc $svc
                            $banner = "Started $($svc.Name)"
                            $needsRender = $true
                        }
                        'x' {
                            $svc = $services[$selected]
                            $svc.StatusText = "STOPPING"
                            Render-Tui -Services $services -SelectedIndex $selected -BannerMsg "Stopping $($svc.Name)..." -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $false
                            Stop-ServiceProcess -Svc $svc
                            $banner = "Stopped $($svc.Name)"
                            $needsRender = $true
                        }
                        'r' {
                            $svc = $services[$selected]
                            $svc.StatusText = "STOPPING"
                            Render-Tui -Services $services -SelectedIndex $selected -BannerMsg "Restarting $($svc.Name)..." -AllLogs $allLogs -ErrorsOnly $errorsOnly -RawLogs $rawLogs -ConfirmQuit $false
                            Stop-ServiceProcess -Svc $svc
                            $svc.Enabled = $true
                            Start-ServiceProcess -Svc $svc
                            $banner = "Restarted $($svc.Name)"
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

    # Live in-TUI shutdown sequence when quitting (not detaching)
    if (-not $detachOnExit) {
        foreach ($svc in $services) {
            if ($svc.Managed -or (Test-Path $svc.PidFile)) {
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
        Write-Host "Detached. Services remain running in the background. Re-run .\stack.cmd to re-attach, or .\stack.cmd -Action down to stop all."
    } else {
        # Safety net in case of unexpected exception
        foreach ($svc in $services) {
            if ($svc.Managed -or (Test-Path $svc.PidFile)) {
                Stop-ServiceProcess -Svc $svc
            }
        }
        Write-Host "All services stopped."
    }
}
