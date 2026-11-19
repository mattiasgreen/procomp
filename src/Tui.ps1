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
    $clean = $Text.Replace("`t", "  ").Replace("`r", "")
    $plain = $clean -replace "$script:ESC\[[0-9;]*m", ""
    if ($plain.Length -gt $Width) {
        return $plain.Substring(0, $Width)
    }
    $pad = $Width - $plain.Length
    if ($pad -gt 0) {
        return $clean + (" " * $pad)
    }
    return $clean
}

function Build-ToolbarLine {
    param([array]$Items)
    $buttons = @()
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append(" ")
    $cursorX = 1
    foreach ($it in $Items) {
        $label = $it.Label
        $startX = $cursorX
        $endX = $cursorX + $label.Length - 1
        $buttons += [PSCustomObject]@{ StartX = $startX; EndX = $endX; Command = $it.Command; Label = $label }
        if ($it.Active) {
            [void]$sb.Append("$script:C_BOLD$script:C_CYAN$label$script:C_RESET  ")
        } else {
            [void]$sb.Append("$script:C_DIM$label$script:C_RESET  ")
        }
        $cursorX += $label.Length + 2
    }
    return [PSCustomObject]@{ Text = $sb.ToString(); Buttons = $buttons }
}

function Resolve-UiClick {
    param(
        $Layout,
        [int]$X,
        [int]$Y,
        [bool]$IsDoubleClick = $false
    )
    if (-not $Layout) { return $null }

    # 1. Toolbar row (Y == 1)
    if ($Y -eq 1 -and $Layout.ToolbarButtons) {
        foreach ($btn in $Layout.ToolbarButtons) {
            if ($X -ge $btn.StartX -and $X -le $btn.EndX) {
                return [PSCustomObject]@{ Action = "Toolbar"; Command = $btn.Command }
            }
        }
        return $null
    }

    # 2. Service table rows (SERVICES mode)
    if ($Layout.ViewMode -eq "SERVICES" -and $Y -ge $Layout.ServiceStartY -and $Y -le $Layout.ServiceEndY) {
        $idx = $Y - $Layout.ServiceStartY
        if ($IsDoubleClick) {
            return [PSCustomObject]@{ Action = "OpenLogs"; Index = $idx }
        }
        $onRouting = ($X -ge $Layout.RoutingStartX -and $X -le $Layout.RoutingEndX)
        return [PSCustomObject]@{ Action = "SelectService"; Index = $idx; ToggleRoute = $onRouting }
    }

    # 3. Log pane rows (both SERVICES preview and LOGS full-screen)
    if ($Y -ge $Layout.LogStartY -and $Layout.LogRows) {
        $rel = $Y - $Layout.LogStartY
        if ($rel -ge 0 -and $rel -lt $Layout.LogRows.Count) {
            $row = $Layout.LogRows[$rel]
            if ($row.HasStack -and $row.EntryId) {
                return [PSCustomObject]@{ Action = "ToggleStack"; EntryId = $row.EntryId }
            }
        }
    }

    return $null
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
        [string]$ViewMode = "SERVICES",
        [string]$FilterText = "",
        [bool]$FilterInputMode = $false,
        [bool]$ExpandStacks = $false,
        [int]$ScrollOffset = 0,
        [hashtable]$ExpandedEntryIds = @{},
        [hashtable]$LogBuffers = $null,
        $LayoutOut = $null,
        [switch]$DryRun
    )

    $width = 120
    $height = 30
    try {
        $width = [Math]::Max(40, [Console]::WindowWidth - 1)
        $height = [Math]::Max(15, [Console]::WindowHeight)
    } catch { }

    $sb = New-Object System.Text.StringBuilder
    $sel = $Services[$SelectedIndex]
    $modeTag = if ($AllLogs) { "ALL" } else { $sel.Name }
    $errTag  = if ($ErrorsOnly) { " | ${script:C_YELLOW}WARN/ERR${script:C_RESET}" } else { "" }
    $rawTag  = if ($RawLogs) { " | ${script:C_MAGENTA}RAW${script:C_RESET}" } else { " | ${script:C_GREEN}UNIFIED${script:C_RESET}" }
    $filtTag = if ($FilterText) { " | ${script:C_CYAN}Filter:'$FilterText'${script:C_RESET}" } else { "" }

    $serviceStartY = -1
    $serviceEndY = -1
    $logStartY = 3
    $logRows = 4

    if ($ViewMode -eq "LOGS") {
        $toolbar = Build-ToolbarLine -Items @(
            @{ Label = "[Esc]Services"; Command = "back";   Active = $false },
            @{ Label = "[a]All";        Command = "all";    Active = $AllLogs },
            @{ Label = "[e]Warn/Err";   Command = "err";    Active = $ErrorsOnly },
            @{ Label = "[v]Raw";        Command = "raw";    Active = $RawLogs },
            @{ Label = "[/]Find";       Command = "find";   Active = ($FilterText -ne "") },
            @{ Label = "[c]Clear";      Command = "clear";  Active = $false },
            @{ Label = "[End]Follow";   Command = "follow"; Active = ($ScrollOffset -eq 0) }
        )
        $logStartY = 3
        $logRows = [Math]::Max(4, $height - 4)
    } else {
        $toolbar = Build-ToolbarLine -Items @(
            @{ Label = "[s]Start";  Command = "start";   Active = $false },
            @{ Label = "[x]Stop";   Command = "stop";    Active = $false },
            @{ Label = "[r]Rst";    Command = "restart"; Active = $false },
            @{ Label = "[b]Build";  Command = "build";   Active = $false },
            @{ Label = "[t]Route";  Command = "route";   Active = $false },
            @{ Label = "[l]Logs";   Command = "logs";    Active = $false },
            @{ Label = "[a]All";    Command = "all";     Active = $AllLogs },
            @{ Label = "[e]Err";    Command = "err";     Active = $ErrorsOnly },
            @{ Label = "[q]Quit";   Command = "quit";    Active = $false }
        )
        $serviceStartY = 4
        $serviceEndY = 4 + $Services.Count - 1
        $logStartY = 6 + $Services.Count
        $logRows = [Math]::Max(4, $height - $logStartY - 1)
    }

    # Fetch log viewport first so header can display FOLLOW / PAUSED scroll position accurately
    if ($null -ne $LogBuffers) {
        $vp = Get-LogViewportFromMemory -Services $Services -LogBuffers $LogBuffers -SelectedIndex $SelectedIndex -AllMode $AllLogs -ErrorsOnly $ErrorsOnly -MaxRows $logRows -FilterText $FilterText -ExpandStacks $ExpandStacks -ScrollOffset $ScrollOffset -ExpandedEntryIds $ExpandedEntryIds
    } else {
        $rowsDirect = @(Get-UnifiedLogs -Services $Services -SelectedIndex $SelectedIndex -AllMode $AllLogs -ErrorsOnly $ErrorsOnly -RawMode $RawLogs -MaxRows $logRows -FilterText $FilterText -ExpandStacks $ExpandStacks -ScrollOffset $ScrollOffset -ExpandedEntryIds $ExpandedEntryIds)
        $vp = [PSCustomObject]@{ TotalRows = $rowsDirect.Count; ClampedOffset = 0; IsFollowing = $true; Rows = $rowsDirect }
    }

    $followTag = if ($vp.IsFollowing) { " | ${script:C_GREEN}FOLLOW${script:C_RESET}" } else { " | ${script:C_YELLOW}PAUSED ^$($vp.ClampedOffset)/$($vp.TotalRows)${script:C_RESET}" }

    if ($ViewMode -eq "LOGS") {
        [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_CYAN procomp LOGS$script:C_RESET $script:C_DIM| Scope: $modeTag (Left/Right: switch, Wheel/PgUp/PgDn: scroll)$script:C_RESET$rawTag$errTag$followTag$filtTag" $width) + "`n")
        [void]$sb.Append((Pad-Line $toolbar.Text $width) + "`n")
        [void]$sb.Append(("-" * $width) + "`n")
    } else {
        [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_CYAN procomp$script:C_RESET $script:C_DIM| Services: $($Services.Count) | Log: $modeTag$script:C_RESET$rawTag$errTag$followTag$filtTag" $width) + "`n")
        [void]$sb.Append((Pad-Line $toolbar.Text $width) + "`n")
        [void]$sb.Append(("-" * $width) + "`n")

        $hdr = "{0,-2} {1,-21} {2,-13} {3,-6} {4,-7} {5,-13} {6,-13} {7,-12} {8,-7} {9,-7} {10,-9}" -f "", "SERVICE", "RUNTIME", "PORT", "PID", "STATE", "ROUTING", "GIT", "UPTIME", "LIMIT", "RAM USED"
        [void]$sb.Append((Pad-Line "$script:C_BOLD$hdr$script:C_RESET" $width) + "`n")

        for ($i = 0; $i -lt $Services.Count; $i++) {
            $svc = $Services[$i]
            $proc = $svc.CachedProc
            $pidStr = if ($svc.PSObject.Properties["UiPidStr"]) { $svc.UiPidStr } elseif ($proc) { [string]$proc.Id } else { "-" }
            $uptime = if ($svc.PSObject.Properties["UiUptime"]) { $svc.UiUptime } else { Format-Uptime -Proc $proc }
            $ram    = if ($svc.PSObject.Properties["UiRamUsed"]) { $svc.UiRamUsed } else { Format-RamMb -Proc $proc }

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

        [void]$sb.Append(("-" * $width) + "`n")
        $logTitle = if ($AllLogs) { "Logs Preview: ALL SERVICES (press [l] or double-click service for full-screen)" } else { "Logs Preview: $($sel.Name) ($($sel.LogFormat)) — click stack line to expand, [l] full-screen" }
        [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_CYAN $logTitle$script:C_RESET" $width) + "`n")
    }

    $entries = @($vp.Rows)
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

    if ($null -ne $LayoutOut) {
        $LayoutOut.Value = [PSCustomObject]@{
            ViewMode       = $ViewMode
            ToolbarButtons = $toolbar.Buttons
            ServiceStartY  = $serviceStartY
            ServiceEndY    = $serviceEndY
            RoutingStartX  = 67
            RoutingEndX    = 80
            LogStartY      = $logStartY
            LogRows        = $entries
            TotalLogRows   = $vp.TotalRows
            ClampedOffset  = $vp.ClampedOffset
            PageHeight     = $logRows
        }
    }

    if ($DryRun) {
        return $sb.ToString()
    }
    [Console]::Write("$script:ESC[H" + $sb.ToString() + "$script:ESC[J")
}
