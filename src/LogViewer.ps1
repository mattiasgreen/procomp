Set-StrictMode -Version Latest

try {
    Add-Type -AssemblyName System.Web.Extensions -ErrorAction Stop
    $script:JsonSerializer = New-Object System.Web.Script.Serialization.JavaScriptSerializer
    $script:JsonSerializer.MaxJsonLength = 1048576
} catch {
    $script:JsonSerializer = $null
}

function Parse-JsonFast {
    param([string]$Line)
    if ($script:JsonSerializer) {
        return $script:JsonSerializer.DeserializeObject($Line)
    }
    $obj = $Line | ConvertFrom-Json -ErrorAction Stop
    $ht = @{}
    foreach ($p in $obj.PSObject.Properties) { $ht[$p.Name] = $p.Value }
    return $ht
}

function Get-MappedField {
    param($Dict, [hashtable]$LogMapping, [string]$FieldName, [string[]]$Aliases)
    if ($LogMapping -and $LogMapping.ContainsKey($FieldName)) {
        $customKey = $LogMapping[$FieldName]
        if ($Dict.ContainsKey($customKey) -and $null -ne $Dict[$customKey]) {
            return $Dict[$customKey]
        }
    }
    foreach ($alias in $Aliases) {
        if ($alias.Contains(".")) {
            $parts = $alias -split '\.', 2
            if ($Dict.ContainsKey($parts[0]) -and ($Dict[$parts[0]] -is [System.Collections.IDictionary])) {
                $sub = $Dict[$parts[0]]
                if ($sub.ContainsKey($parts[1]) -and $null -ne $sub[$parts[1]]) {
                    return $sub[$parts[1]]
                }
            }
        } elseif ($Dict.ContainsKey($alias) -and $null -ne $Dict[$alias]) {
            return $Dict[$alias]
        }
    }
    return $null
}

function Normalize-LogEntry {
    param(
        [string]$RawLine,
        [string]$DefaultService,
        [bool]$RawMode,
        [hashtable]$LogMapping = @{}
    )
    $sortKey = ""
    $level = "INFO"
    $display = $RawLine
    $stackLines = @()

    if (-not $RawMode -and $RawLine.TrimStart().StartsWith("{")) {
        try {
            $dict = Parse-JsonFast -Line $RawLine
            if ($dict -is [System.Collections.IDictionary]) {
                $tsRaw = [string](Get-MappedField -Dict $dict -LogMapping $LogMapping -FieldName "timestamp" -Aliases @("@timestamp", "timestamp", "time"))
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

                $lvl = [string](Get-MappedField -Dict $dict -LogMapping $LogMapping -FieldName "level" -Aliases @("level", "log.level", "severity"))
                if (-not $lvl) { $lvl = "INFO" }
                $level = $lvl.ToUpper()

                $svc = [string](Get-MappedField -Dict $dict -LogMapping $LogMapping -FieldName "service" -Aliases @("service", "service.name"))
                if (-not $svc) { $svc = $DefaultService }

                $msg = [string](Get-MappedField -Dict $dict -LogMapping $LogMapping -FieldName "message" -Aliases @("message", "msg"))
                if (-not $msg) { $msg = $RawLine }

                $trace = [string](Get-MappedField -Dict $dict -LogMapping $LogMapping -FieldName "traceId" -Aliases @("traceId", "trace.id", "correlationId", "mdc.traceId"))
                $traceTag = if ($trace) { " [trace=$trace]" } else { "" }

                $stackRaw = [string](Get-MappedField -Dict $dict -LogMapping $LogMapping -FieldName "stack_trace" -Aliases @("stack_trace", "error.stack_trace", "exception"))
                if ($stackRaw) {
                    $stackLines = @($stackRaw -split "`r?`n" | Where-Object { $_ -ne "" })
                }

                $display = "{0}  {1,-5} [{2}]{3} --- {4}" -f $tsDisplay, $level, $svc, $traceTag, $msg
                return [PSCustomObject]@{
                    SortKey    = $sortKey
                    Level      = $level
                    Text       = $display
                    StackLines = $stackLines
                    IsJson     = $true
                }
            }
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

    return [PSCustomObject]@{
        SortKey    = $sortKey
        Level      = $level
        Text       = $display
        StackLines = @()
        IsJson     = $false
    }
}

function Ensure-LogBufferState {
    param($Svc)
    if (-not $Svc.PSObject.Properties["LastLogOffset"]) {
        $Svc | Add-Member -NotePropertyName "LastLogOffset" -NotePropertyValue ([long]0) -Force
    }
    if (-not $Svc.PSObject.Properties["LogRingBuffer"]) {
        $Svc | Add-Member -NotePropertyName "LogRingBuffer" -NotePropertyValue (New-Object System.Collections.Generic.List[object]) -Force
    }
    if (-not $Svc.PSObject.Properties["LogMapping"]) {
        $Svc | Add-Member -NotePropertyName "LogMapping" -NotePropertyValue @{} -Force
    }
}

function Clear-ServiceLogView {
    param($Services)
    foreach ($svc in $Services) {
        Ensure-LogBufferState -Svc $svc
        $svc.LogRingBuffer.Clear()
    }
}

function Get-ServiceLogEntries {
    param($Svc, [int]$TailCount, [bool]$RawMode)
    Ensure-LogBufferState -Svc $Svc

    if (-not (Test-Path $Svc.LogFile)) {
        $Svc.LastLogOffset = [long]0
        $Svc.LogRingBuffer.Clear()
        return @()
    }

    if ($Svc.CachedLogRawMode -ne $RawMode) {
        $Svc.LastLogOffset = [long]0
        $Svc.LogRingBuffer.Clear()
        $Svc.CachedLogRawMode = $RawMode
    }

    $fi = [System.IO.FileInfo]::new($Svc.LogFile)
    if ($fi.Length -lt $Svc.LastLogOffset) {
        $Svc.LastLogOffset = [long]0
        $Svc.LogRingBuffer.Clear()
    }

    if ($fi.Length -gt $Svc.LastLogOffset) {
        $fs = $null
        $sr = $null
        try {
            $fs = [System.IO.FileStream]::new($Svc.LogFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
            [void]$fs.Seek($Svc.LastLogOffset, [System.IO.SeekOrigin]::Begin)
            $sr = [System.IO.StreamReader]::new($fs, [System.Text.Encoding]::UTF8, $true)
            while (-not $sr.EndOfStream) {
                $line = $sr.ReadLine()
                if (-not $line) { continue }

                # Fold plain-text Java stack trace lines onto the preceding log entry
                if ((-not $RawMode) -and ($Svc.LogRingBuffer.Count -gt 0) -and ($line -match '^\s+at\s+|^Caused by:|^\s+\.\.\.\s+\d+\s+more')) {
                    $prev = $Svc.LogRingBuffer[$Svc.LogRingBuffer.Count - 1]
                    $prev.StackLines = @($prev.StackLines) + @($line)
                    continue
                }

                $entry = Normalize-LogEntry -RawLine $line -DefaultService $Svc.Name -RawMode $RawMode -LogMapping $Svc.LogMapping
                $Svc.LogRingBuffer.Add($entry)
                if ($Svc.LogRingBuffer.Count -gt 500) {
                    $Svc.LogRingBuffer.RemoveAt(0)
                }
            }
            $Svc.LastLogOffset = $fs.Position
        } finally {
            if ($sr) { $sr.Dispose() }
            if ($fs) { $fs.Dispose() }
        }
    }

    $cnt = $Svc.LogRingBuffer.Count
    if ($cnt -eq 0) { return @() }
    $start = [Math]::Max(0, $cnt - $TailCount)
    $slice = @()
    for ($i = $start; $i -lt $cnt; $i++) {
        $slice += $Svc.LogRingBuffer[$i]
    }
    return $slice
}

function Get-UnifiedLogs {
    param(
        $Services,
        [int]$SelectedIndex,
        [bool]$AllMode,
        [bool]$ErrorsOnly,
        [bool]$RawMode,
        [int]$MaxRows,
        [string]$FilterText = "",
        [bool]$ExpandStacks = $false
    )

    $entries = @()
    if ($AllMode) {
        $perSvc = [Math]::Max(15, $MaxRows)
        foreach ($svc in $Services) {
            $entries += @(Get-ServiceLogEntries -Svc $svc -TailCount $perSvc -RawMode $RawMode)
            if (($svc.StatusText -eq "CRASHED") -and (Test-Path $svc.ErrFile)) {
                $errLines = @(Get-Content -Path $svc.ErrFile -Tail 4 -ErrorAction SilentlyContinue)
                foreach ($l in $errLines) {
                    if ($l) { $entries += [PSCustomObject]@{ SortKey = "9999-99-99 99:99:99.999"; Level = "ERROR"; Text = "[$($svc.Name)] $l"; StackLines = @(); IsJson = $false } }
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
                $entries += [PSCustomObject]@{ SortKey = ""; Level = "ERROR"; Text = "--- stderr ---"; StackLines = @(); IsJson = $false }
                foreach ($l in $errLines) {
                    if ($l) { $entries += [PSCustomObject]@{ SortKey = ""; Level = "ERROR"; Text = $l; StackLines = @(); IsJson = $false } }
                }
            }
        }
    }

    if ($ErrorsOnly) {
        $entries = @($entries | Where-Object { $_.Level -eq "ERROR" -or $_.Level -eq "WARN" })
    }

    if ($FilterText) {
        $entries = @($entries | Where-Object { $_.Text.IndexOf($FilterText, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 })
    }

    # Expand or fold stack traces into display rows
    $displayRows = @()
    foreach ($e in $entries) {
        $hasStack = ($e.PSObject.Properties["StackLines"] -and $e.StackLines -and ($e.StackLines.Count -gt 0))
        if ($hasStack -and (-not $ExpandStacks)) {
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
