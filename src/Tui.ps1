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
        $scrollTop = if ($Layout.PSObject.Properties["ServiceScrollTop"]) { [int]$Layout.ServiceScrollTop } else { 0 }
        $idx = $scrollTop + ($Y - $Layout.ServiceStartY)
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
        [string]$ProjectName = "",
        [bool]$LocalOnly = $false,
        [bool]$ShowHelp = $false,
        [string]$FilterText = "",
        [bool]$FilterInputMode = $false,
        [bool]$ExpandStacks = $false,
        [int]$ScrollOffset = 0,
        [hashtable]$ExpandedEntryIds = @{},
        [hashtable]$LogBuffers = $null,
        [array]$ProcompLogEntries = @(),
        [string]$ProcompLogLevel = "TRACE",
        [string]$WireMockSubMode = "JOURNAL",
        [array]$WireMockEntries = @(),
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
    $projTag = if ($ProjectName) { " [$ProjectName]" } else { "" }

    if ($ShowHelp) {
        $helpLines = @(
            "$script:C_BOLD$script:C_CYAN procomp$projTag — Keyboard & Mouse Cheat Sheet$script:C_RESET",
            ("-" * $width),
            "$script:C_BOLD SERVICES MODE (Split View)$script:C_RESET",
            "  Up/Down or j/k    Select service         Enter / l / Dbl-Click  Full-screen Log Viewer",
            "  s / S             Start one / all LOCAL  x / X                  Stop one / all managed",
            "  r                 Restart selected       b                      Rebuild selected",
            "  f / F             Git fetch one / all    p / P                  Git pull (--ff-only) one / all",
            "  t / Click ROUTING Toggle LOCAL <-> REMOTE  o                    Open Swagger URI in browser",
            "  L / 4             procomp Log (INFO/DBG/TRC) w / 3              WireMock Journal / Mappings",
            "  1 / 2 / 3 / 4     Switch main views          g                    Toggle LOCAL-only view",
            "  q / Ctrl+C        Confirm & stop all",
            "",
            "$script:C_BOLD LOG VIEWER MODE (Full-Screen)$script:C_RESET",
            "  Esc / l           Return to Services     Left / Right           Switch service logs",
            "  Wheel / Up / Down Scroll logs (auto-pauses FOLLOW)              PgUp / PgDn / Home  Page / Top",
            "  End / G           Jump to bottom & resume FOLLOW                Click stack line    Expand/collapse trace",
            "  a / Tab           All services merged    e                      Warn / Error only",
            "  v                 Unified vs Raw JSON    z                      Toggle latest stack trace",
            "  /                 Filter text / traceId  c                      Clear log display buffer",
            "",
            "$script:C_BOLD MOUSE & GENERAL$script:C_RESET",
            "  Click toolbar buttons on Row 1, service rows, ROUTING column, or [+N stack lines] rows.",
            "  Hold Shift + Drag in Windows Terminal to highlight and copy text.",
            "  q / Ctrl+C        Confirm & stop all managed services           ?  Close this help"
        )
        for ($r = 0; $r -lt ($height - 1); $r++) {
            $ln = if ($r -lt $helpLines.Count) { $helpLines[$r] } else { "" }
            [void]$sb.Append((Pad-Line $ln $width) + "`n")
        }
        [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_YELLOW Press any key or click anywhere to close help...$script:C_RESET" $width))
        if ($DryRun) { return $sb.ToString() }
        [Console]::Write("$script:ESC[H" + $sb.ToString() + "$script:ESC[J")
        return
    }

    $sel = $Services[$SelectedIndex]
    $modeTag = if ($AllLogs) { "ALL" } else { $sel.Name }
    $errTag  = if ($ErrorsOnly) { " | ${script:C_YELLOW}WARN/ERR${script:C_RESET}" } else { "" }
    $rawTag  = if ($RawLogs) { " | ${script:C_MAGENTA}RAW${script:C_RESET}" } else { " | ${script:C_GREEN}UNIFIED${script:C_RESET}" }
    $locTag  = if ($LocalOnly) { " | ${script:C_CYAN}LOCAL-ONLY${script:C_RESET}" } else { "" }
    $filtTag = if ($FilterText) { " | ${script:C_CYAN}Filter:'$FilterText'${script:C_RESET}" } else { "" }

    $serviceStartY = -1
    $serviceEndY = -1
    $serviceScrollTop = 0
    $visibleSvcCount = $Services.Count
    $logStartY = 3
    $logRows = 4

    if ($ViewMode -eq "WIREMOCK") {
        $wmToggleLabel = if ($WireMockSubMode -eq "MAPPINGS") { "[m]Journal" } else { "[m]Mappings" }
        $toolbar = Build-ToolbarLine -Items @(
            @{ Label = "[Esc]Services"; Command = "back";      Active = $false },
            @{ Label = $wmToggleLabel;   Command = "wm-toggle"; Active = $true },
            @{ Label = "[e]Warn/Err";    Command = "err";       Active = $ErrorsOnly },
            @{ Label = "[/]Find";        Command = "find";      Active = ($FilterText -ne "") },
            @{ Label = "[End]Follow";    Command = "follow";    Active = ($ScrollOffset -eq 0) },
            @{ Label = "[?]Help";        Command = "help";      Active = $false }
        )
        $logStartY = 3
        $logRows = [Math]::Max(4, $height - 4)
    } elseif ($ViewMode -eq "PROCOMP") {
        $toolbar = Build-ToolbarLine -Items @(
            @{ Label = "[Esc]Services";            Command = "back";          Active = $false },
            @{ Label = "[v]Level:$ProcompLogLevel"; Command = "procomp-level"; Active = $true },
            @{ Label = "[e]Warn/Err";               Command = "err";           Active = $ErrorsOnly },
            @{ Label = "[/]Find";                   Command = "find";          Active = ($FilterText -ne "") },
            @{ Label = "[End]Follow";               Command = "follow";        Active = ($ScrollOffset -eq 0) },
            @{ Label = "[?]Help";                   Command = "help";          Active = $false }
        )
        $logStartY = 3
        $logRows = [Math]::Max(4, $height - 4)
    } elseif ($ViewMode -eq "LOGS") {
        $toolbar = Build-ToolbarLine -Items @(
            @{ Label = "[Esc]Services"; Command = "back";   Active = $false },
            @{ Label = "[a]All";        Command = "all";    Active = $AllLogs },
            @{ Label = "[e]Warn/Err";   Command = "err";    Active = $ErrorsOnly },
            @{ Label = "[v]Raw";        Command = "raw";    Active = $RawLogs },
            @{ Label = "[/]Find";       Command = "find";   Active = ($FilterText -ne "") },
            @{ Label = "[c]Clear";      Command = "clear";  Active = $false },
            @{ Label = "[End]Follow";   Command = "follow"; Active = ($ScrollOffset -eq 0) },
            @{ Label = "[?]Help";       Command = "help";   Active = $false }
        )
        $logStartY = 3
        $logRows = [Math]::Max(4, $height - 4)
    } else {
        $toolbar = Build-ToolbarLine -Items @(
            @{ Label = "[s]Start";  Command = "start";        Active = $false },
            @{ Label = "[x]Stop";   Command = "stop";         Active = $false },
            @{ Label = "[r]Rst";    Command = "restart";      Active = $false },
            @{ Label = "[b]Build";  Command = "build";        Active = $false },
            @{ Label = "[f]Fetch";  Command = "fetch";        Active = $false },
            @{ Label = "[p]Pull";   Command = "pull";         Active = $false },
            @{ Label = "[t]Route";  Command = "route";        Active = $false },
            @{ Label = "[l]Logs";   Command = "logs";         Active = $false },
            @{ Label = "[L]Procomp"; Command = "procomp-logs"; Active = $false },
            @{ Label = "[w]WM";     Command = "wiremock";     Active = $false },
            @{ Label = "[o]Docs";   Command = "swagger";      Active = $false },
            @{ Label = "[?]Help";   Command = "help";         Active = $false },
            @{ Label = "[q]Quit";   Command = "quit";         Active = $false }
        )
        $maxSvcRows = [Math]::Max(4, [int][Math]::Floor($height * 0.45))
        $visibleSvcCount = [Math]::Min($Services.Count, $maxSvcRows)
        if ($Services.Count -gt $visibleSvcCount) {
            $serviceScrollTop = [Math]::Max(0, [Math]::Min($SelectedIndex - [int][Math]::Floor($visibleSvcCount / 2), $Services.Count - $visibleSvcCount))
        }
        $serviceStartY = 4
        $serviceEndY = 4 + $visibleSvcCount - 1
        $logStartY = 6 + $visibleSvcCount
        $logRows = [Math]::Max(4, $height - $logStartY - 1)
    }

    # Fetch log viewport first so header can display FOLLOW / PAUSED scroll position accurately
    if ($ViewMode -eq "PROCOMP" -or $ViewMode -eq "WIREMOCK") {
        $pEntries = if ($ViewMode -eq "WIREMOCK") { @($WireMockEntries) } else { @($ProcompLogEntries) }
        if ($ViewMode -eq "PROCOMP" -and $ProcompLogLevel -ne "TRACE") {
            $allowedVerbs = if ($ProcompLogLevel -eq "INFO") { @("INFO") } else { @("INFO", "DEBUG") }
            $pEntries = @($pEntries | Where-Object { (-not $_.PSObject.Properties["Verbosity"]) -or ($_.Verbosity -in $allowedVerbs) })
        }
        if ($ErrorsOnly) {
            $pEntries = @($pEntries | Where-Object { $_.Level -eq "ERROR" -or $_.Level -eq "WARN" })
        }
        if ($FilterText) {
            $pEntries = @($pEntries | Where-Object { $_.Text.IndexOf($FilterText, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 })
        }
        $vp = Get-LogViewport -Entries $pEntries -MaxRows $logRows -ScrollOffset $ScrollOffset -ExpandedEntryIds @{} -ExpandLatest $false
    } elseif ($null -ne $LogBuffers) {
        $vp = Get-LogViewportFromMemory -Services $Services -LogBuffers $LogBuffers -SelectedIndex $SelectedIndex -AllMode $AllLogs -ErrorsOnly $ErrorsOnly -MaxRows $logRows -FilterText $FilterText -ExpandStacks $ExpandStacks -ScrollOffset $ScrollOffset -ExpandedEntryIds $ExpandedEntryIds
    } else {
        $rowsDirect = @(Get-UnifiedLogs -Services $Services -SelectedIndex $SelectedIndex -AllMode $AllLogs -ErrorsOnly $ErrorsOnly -RawMode $RawLogs -MaxRows $logRows -FilterText $FilterText -ExpandStacks $ExpandStacks -ScrollOffset $ScrollOffset -ExpandedEntryIds $ExpandedEntryIds)
        $vp = [PSCustomObject]@{ TotalRows = $rowsDirect.Count; ClampedOffset = 0; IsFollowing = $true; Rows = $rowsDirect }
    }

    $followTag = if ($vp.IsFollowing) { " | ${script:C_GREEN}FOLLOW${script:C_RESET}" } else { " | ${script:C_YELLOW}PAUSED ^$($vp.ClampedOffset)/$($vp.TotalRows)${script:C_RESET}" }

    if ($ViewMode -eq "WIREMOCK") {
        [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_CYAN procomp$projTag WIREMOCK ($WireMockSubMode)$script:C_RESET $script:C_DIM| (m/Tab: Journal/Mappings, Wheel/PgUp/PgDn: scroll, Esc: services)$script:C_RESET$errTag$followTag$filtTag" $width) + "`n")
        [void]$sb.Append((Pad-Line $toolbar.Text $width) + "`n")
        [void]$sb.Append(("-" * $width) + "`n")
    } elseif ($ViewMode -eq "PROCOMP") {
        [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_CYAN procomp$projTag PROCOMP ($ProcompLogLevel)$script:C_RESET $script:C_DIM| (v: INFO/DEBUG/TRACE, 1-4: views, Esc: services)$script:C_RESET$errTag$followTag$filtTag" $width) + "`n")
        [void]$sb.Append((Pad-Line $toolbar.Text $width) + "`n")
        [void]$sb.Append(("-" * $width) + "`n")
    } elseif ($ViewMode -eq "LOGS") {
        [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_CYAN procomp$projTag LOGS$script:C_RESET $script:C_DIM| Scope: $modeTag (Left/Right: switch, Wheel/PgUp/PgDn: scroll)$script:C_RESET$rawTag$errTag$followTag$filtTag" $width) + "`n")
        [void]$sb.Append((Pad-Line $toolbar.Text $width) + "`n")
        [void]$sb.Append(("-" * $width) + "`n")
    } else {
        $svcColWidth = 21
        foreach ($s in $Services) {
            if ($s.Name -and ($s.Name.Length + 1) -gt $svcColWidth) {
                $svcColWidth = $s.Name.Length + 1
            }
        }
        $svcColWidth = [Math]::Min(36, [Math]::Max(21, $svcColWidth))

        $svcRangeTag = if ($Services.Count -gt $visibleSvcCount) { " ($($serviceScrollTop + 1)-$($serviceScrollTop + $visibleSvcCount)/$($Services.Count))" } else { "" }
        [void]$sb.Append((Pad-Line "$script:C_BOLD$script:C_CYAN procomp$projTag$script:C_RESET $script:C_DIM| Services: $($Services.Count)$svcRangeTag | Log: $modeTag$script:C_RESET$locTag$rawTag$errTag$followTag$filtTag" $width) + "`n")
        [void]$sb.Append((Pad-Line $toolbar.Text $width) + "`n")
        [void]$sb.Append(("-" * $width) + "`n")

        $rowFmtHdr = "{0,-2} {1,-$svcColWidth} {2,-13} {3,-6} {4,-7} {5,-13} {6,-13} {7,-12} {8,-7} {9,-7} {10,-9}"
        $hdr = $rowFmtHdr -f "", "SERVICE", "RUNTIME", "PORT", "PID", "STATE", "ROUTING", "GIT", "UPTIME", "LIMIT", "RAM USED"
        [void]$sb.Append((Pad-Line "$script:C_BOLD$hdr$script:C_RESET" $width) + "`n")

        for ($i = $serviceScrollTop; $i -lt ($serviceScrollTop + $visibleSvcCount); $i++) {
            $svc = $Services[$i]
            $proc = $svc.CachedProc
            $pidStr = if ($svc.PSObject.Properties["UiPidStr"]) { $svc.UiPidStr } elseif ($proc) { [string]$proc.Id } else { "-" }
            $uptime = if ($svc.PSObject.Properties["UiUptime"]) { $svc.UiUptime } else { Format-Uptime -Proc $proc }
            $ram    = if ($svc.PSObject.Properties["UiRamUsed"]) { $svc.UiRamUsed } else { Format-RamMb -Proc $proc }

            $stateColor = switch ($svc.StatusText) {
                "HEALTHY"       { $script:C_GREEN }
                "IDE-UP"        { $script:C_CYAN }
                "IDE-INIT"      { $script:C_YELLOW }
                "PROBING"       { $script:C_YELLOW }
                "STARTING"      { $script:C_YELLOW }
                "BUILDING"      { $script:C_YELLOW }
                "STOPPING"      { $script:C_YELLOW }
                "CRASHED"       { $script:C_RED }
                "BUILD-FAIL"    { $script:C_RED }
                "PORT-CONFLICT" { $script:C_RED }
                default         { $script:C_DIM }
            }

            $marker = if ($i -eq $SelectedIndex) { ">" } else { " " }
            $rowPrefix = if ($i -eq $SelectedIndex) { "$script:C_REV" } else { "" }
            $rowSuffix = if ($i -eq $SelectedIndex) { "$script:C_RESET" } else { "" }

            $routingStr = if ($svc.RoutingMode -eq "REMOTE") { "REMOTE" } else { "LOCAL :$($svc.Port)" }
            $gitStr = if ($svc.PSObject.Properties["GitSummary"] -and $svc.GitSummary) { [string]$svc.GitSummary } else { "-" }
            if ($svc.PSObject.Properties["NeedsRebuild"] -and $svc.NeedsRebuild -and $gitStr -notlike "*...") {
                $gitStr = "$gitStr[!]"
            }
            if ($gitStr.Length -gt 12) { $gitStr = $gitStr.Substring(0, 11) + "~" }
            $gitColor = if ($gitStr -eq "MISSING") { $script:C_RED }
                        elseif ($gitStr -match 'FETCH|PULL|\*|v\d+|\[!\]') { $script:C_YELLOW }
                        else { "" }
            $gitReset = if ($gitColor) { "$script:C_RESET$rowPrefix" } else { "" }

            $rtStr = if ($svc.PSObject.Properties["Runtime"] -and $svc.Runtime) { [string]$svc.Runtime } else { "-" }
            $limStr = if ($svc.PSObject.Properties["MemLimit"] -and $svc.MemLimit) { [string]$svc.MemLimit } else { "-" }
            $svcDisplay = [string]$svc.Name
            if ($svcDisplay.Length -gt $svcColWidth) {
                $svcDisplay = $svcDisplay.Substring(0, $svcColWidth - 1) + "~"
            }

            $rowFmt = "{0,-2} {1,-$svcColWidth} {2,-13} {3,-6} {4,-7} {5}{6,-13}{7} {8,-13} {9}{10,-12}{11} {12,-7} {13,-7} {14,-9}"
            $line = $rowFmt -f `
                $marker, $svcDisplay, $rtStr, $svc.Port, $pidStr, $stateColor, $svc.StatusText, "$script:C_RESET$rowPrefix", $routingStr, $gitColor, $gitStr, $gitReset, $uptime, $limStr, $ram

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

    $routingStartX = if ($ViewMode -eq "LOGS" -or $ViewMode -eq "PROCOMP" -or $ViewMode -eq "WIREMOCK") { 67 } else { 46 + $svcColWidth }
    if ($null -ne $LayoutOut) {
        $LayoutOut.Value = [PSCustomObject]@{
            ViewMode         = $ViewMode
            ToolbarButtons   = $toolbar.Buttons
            ServiceStartY    = $serviceStartY
            ServiceEndY      = $serviceEndY
            ServiceScrollTop = $serviceScrollTop
            RoutingStartX    = $routingStartX
            RoutingEndX      = ($routingStartX + 13)
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
