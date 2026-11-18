Set-StrictMode -Version Latest

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
