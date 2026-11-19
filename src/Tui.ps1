Set-StrictMode -Version Latest

$script:ESC       = [char]27
$script:C_RESET   = "$script:ESC[0m"
$script:C_BOLD    = "$script:ESC[1m"
$script:C_DIM     = "$script:ESC[2m"
$script:C_RED     = "$script:ESC[31m"
$script:C_GREEN   = "$script:ESC[32m"
$script:C_YELLOW  = "$script:ESC[33m"
$script:C_CYAN    = "$script:ESC[36m"
$script:C_MAGENTA = "$script:ESC[35m"
$script:C_REV     = "$script:ESC[7m"

function Pad-Line {
    param([string]$Text, [int]$Width)
    $plain = $Text -replace "$script:ESC\[[0-9;]*m", ""
    if ($plain.Length -gt $Width) {
        # Strip ANSI and truncate cleanly so the terminal never wraps and scrolls the viewport
        return $plain.Substring(0, $Width)
    }
    $pad = $Width - $plain.Length
    if ($pad -gt 0) {
        return $Text + (" " * $pad)
    }
    return $Text
}

function Render-Tui {
    param(
        $Services,
        [int]$SelectedIndex,
        [string]$BannerMsg,
        [bool]$AllLogs,
        [bool]$ErrorsOnly,
        [bool]$RawLogs,
        [bool]$ConfirmQuit,
        [string]$FilterText = "",
        [bool]$FilterInputMode = $false,
        [bool]$ExpandStacks = $false,
        [bool]$PausedLogs = $false,
        $FrozenEntries = $null,
        [hashtable]$LogBuffers = $null,
        [switch]$DryRun
    )

    $width = 120
    $height = 30
    try {
        $width = [Math]::Max(40, [Console]::WindowWidth - 1)
        $height = [Math]::Max(15, [Console]::WindowHeight)
    } catch { }

    $sb = New-Object System.Text.StringBuilder
    $modeTag = if ($AllLogs) { "ALL" } else { $Services[$SelectedIndex].Name }
    $errTag = if ($ErrorsOnly) { " | ${script:C_YELLOW}WARN/ERR${script:C_RESET}" } else { "" }
    $rawTag = if ($RawLogs) { " | ${script:C_MAGENTA}RAW${script:C_RESET}" } else { " | ${script:C_GREEN}UNIFIED${script:C_RESET}" }
    $pauseTag = if ($PausedLogs) { " | ${script:C_YELLOW}PAUSED${script:C_RESET}" } else { "" }
    $stackTag = if ($ExpandStacks) { " | ${script:C_CYAN}STACKS:ON${script:C_RESET}" } else { "" }
    $filtTag = if ($FilterText) { " | ${script:C_CYAN}Filter:'$FilterText'${script:C_RESET}" } else { "" }

    [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_CYAN procomp$script:C_RESET $script:C_DIM| Services: $($Services.Count) | Log: $modeTag$script:C_RESET$rawTag$errTag$stackTag$pauseTag$filtTag" $width) + "`n")
    [void]$sb.Append((Pad-Line "$script:C_DIM Keys: [Up/Down] Sel [s/S] Start [x/X] Stop [r] Rst [b] Build [t] Route [a] All [e] Err [z] Stack [/] Find [Space] Pause [c] Clr [q] Quit$script:C_RESET" $width) + "`n")
    [void]$sb.Append(("-" * $width) + "`n")

    $hdr = "{0,-2} {1,-21} {2,-13} {3,-6} {4,-7} {5,-13} {6,-13} {7,-12} {8,-7} {9,-7} {10,-9}" -f "", "SERVICE", "RUNTIME", "PORT", "PID", "STATE", "ROUTING", "GIT", "UPTIME", "LIMIT", "RAM USED"
    [void]$sb.Append((Pad-Line "$script:C_BOLD$hdr$script:C_RESET" $width) + "`n")

    for ($i = 0; $i -lt $Services.Count; $i++) {
        $svc = $Services[$i]
        $proc = $svc.CachedProc
        $pidStr = if ($svc.PSObject.Properties["UiPidStr"]) { $svc.UiPidStr } elseif ($proc) { [string]$proc.Id } else { "-" }
        $uptime = if ($svc.PSObject.Properties["UiUptime"]) { $svc.UiUptime } else { Format-Uptime -Proc $proc }
        $ram = if ($svc.PSObject.Properties["UiRamUsed"]) { $svc.UiRamUsed } else { Format-RamMb -Proc $proc }

        $stateColor = switch ($svc.StatusText) {
            "HEALTHY"       { $script:C_GREEN }
            "IDE-UP"        { $script:C_CYAN }
            "IDE-INIT"      { $script:C_YELLOW }
            "STARTING"      { $script:C_YELLOW }
            "BUILDING"      { $script:C_YELLOW }
            "STOPPING"      { $script:C_YELLOW }
            "CRASHED"       { $script:C_RED }
            "BUILD-FAIL"    { $script:C_RED }
            "PORT-CONFLICT" { $script:C_RED }
            default         { $script:C_DIM }
        }

        $routingStr = if ($svc.RoutingMode -eq "REMOTE") { "REMOTE" } else { "LOCAL :$($svc.Port)" }
        $gitStr = if ($svc.PSObject.Properties["GitSummary"] -and $svc.GitSummary) { [string]$svc.GitSummary } else { "-" }
        if ($gitStr.Length -gt 12) { $gitStr = $gitStr.Substring(0, 11) + "~" }
        $rtStr = if ($svc.PSObject.Properties["Runtime"] -and $svc.Runtime) { [string]$svc.Runtime } else { "-" }
        $limStr = if ($svc.PSObject.Properties["MemLimit"] -and $svc.MemLimit) { [string]$svc.MemLimit } else { "-" }

        $marker = if ($i -eq $SelectedIndex) { ">" } else { " " }
        $rowPrefix = if ($i -eq $SelectedIndex) { "$script:C_REV" } else { "" }
        $rowSuffix = if ($i -eq $SelectedIndex) { "$script:C_RESET" } else { "" }

        $line = "{0,-2} {1,-21} {2,-13} {3,-6} {4,-7} {5}{6,-13}{7} {8,-13} {9,-12} {10,-7} {11,-7} {12,-9}" -f `
            $marker, $svc.Name, $rtStr, $svc.Port, $pidStr, $stateColor, $svc.StatusText, "$script:C_RESET$rowPrefix", $routingStr, $gitStr, $uptime, $limStr, $ram

        [void]$sb.Append((Pad-Line "$rowPrefix$line$rowSuffix" $width) + "`n")
    }

    $sel = $Services[$SelectedIndex]
    [void]$sb.Append(("-" * $width) + "`n")
    $logTitle = if ($AllLogs) { "Unified Logs: ALL SERVICES (JSON + Text normalized)" } else { "Logs: $($sel.Name) ($($sel.LogFormat))" }
    [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_CYAN $logTitle$script:C_RESET" $width) + "`n")

    $usedRows = 4 + $Services.Count + 2 + 1
    $logRows = [Math]::Max(4, $height - $usedRows - 1)
    if ($PausedLogs -and $null -ne $FrozenEntries) {
        $entries = @($FrozenEntries)
    } elseif ($null -ne $LogBuffers) {
        $entries = @(Get-UnifiedLogsFromMemory -Services $Services -LogBuffers $LogBuffers -SelectedIndex $SelectedIndex -AllMode $AllLogs -ErrorsOnly $ErrorsOnly -MaxRows $logRows -FilterText $FilterText -ExpandStacks $ExpandStacks)
    } else {
        $entries = @(Get-UnifiedLogs -Services $Services -SelectedIndex $SelectedIndex -AllMode $AllLogs -ErrorsOnly $ErrorsOnly -RawMode $RawLogs -MaxRows $logRows -FilterText $FilterText -ExpandStacks $ExpandStacks)
    }

    for ($r = 0; $r -lt $logRows; $r++) {
        if ($r -lt $entries.Count) {
            $e = $entries[$r]
            $text = $e.Text
            if ($text.Length -gt $width) {
                $text = $text.Substring(0, $width)
            }
            $colored = switch ($e.Level) {
                "ERROR" { "$script:C_RED$text$script:C_RESET" }
                "WARN"  { "$script:C_YELLOW$text$script:C_RESET" }
                default { $text }
            }
            [void]$sb.Append((Pad-Line $colored $width) + "`n")
        } else {
            [void]$sb.Append((Pad-Line "" $width) + "`n")
        }
    }

    if ($ConfirmQuit) {
        [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_YELLOW [CONFIRM] Stop all running services and exit? Press [y] to confirm, any other key to cancel.$script:C_RESET" $width))
    } elseif ($FilterInputMode) {
        [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_CYAN Filter (Enter=apply, Esc=clear): /$FilterText$script:C_RESET" $width))
    } else {
        [void]$sb.Append((Pad-Line "$script:C_DIM $BannerMsg$script:C_RESET" $width))
    }

    if ($DryRun) {
        return $sb.ToString()
    }
    [Console]::Write("$script:ESC[H" + $sb.ToString() + "$script:ESC[J")
}
