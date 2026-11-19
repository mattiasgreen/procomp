Set-StrictMode -Version Latest

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
    param($Services, [switch]$DetectIde)

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

    $hierarchy = $null
    $portMap = $null
    $unmanagedLocalPorts = @()
    if ($DetectIde) {
        foreach ($s in $Services) {
            if ((-not $s.CachedPid) -and ($s.RoutingMode -eq "LOCAL")) {
                $unmanagedLocalPorts += $s.Port
            }
        }
    }

    foreach ($svc in $Services) {
        $svc.ExternalIde = $false
        $svc.PortConflict = $false

        if ($svc.CachedPid -and $procMap.ContainsKey($svc.CachedPid)) {
            $svc.CachedProc = $procMap[$svc.CachedPid]
            continue
        }

        $svc.CachedProc = $null
        if ($DetectIde -and (-not $svc.CachedPid) -and ($svc.RoutingMode -eq "LOCAL")) {
            if ($null -eq $portMap) {
                $portMap = Get-ListeningPortMap -TargetPorts $unmanagedLocalPorts
            }
            $listeningPid = if ($portMap.ContainsKey($svc.Port)) { $portMap[$svc.Port] } else { $null }
            if ($listeningPid) {
                if ($null -eq $hierarchy) {
                    $hierarchy = Get-ProcessHierarchyMap
                }
                $extProc = Get-Process -Id $listeningPid -ErrorAction SilentlyContinue
                if ($extProc -and -not $extProc.HasExited) {
                    $svc.CachedProc = $extProc
                }
                if (Test-IsIdeDescendant -ProcessId $listeningPid -HierarchyMap $hierarchy) {
                    $svc.ExternalIde = $true
                } else {
                    $svc.PortConflict = $true
                }
            }
        }
    }
}

function Split-CommandLine {
    param([string]$CommandLine)
    $trimmed = $CommandLine.Trim()
    if ($trimmed -match '[|&><]') {
        return @{ FilePath = "cmd.exe"; ArgumentList = @("/c", $trimmed) }
    }
    $tokens = @()
    $matches = [regex]::Matches($trimmed, '("[^"]*"|\S+)')
    foreach ($m in $matches) {
        $tok = $m.Value
        if ($tok.Length -ge 2 -and $tok.StartsWith('"') -and $tok.EndsWith('"')) {
            $tok = $tok.Substring(1, $tok.Length - 2)
        }
        $tokens += $tok
    }
    if ($tokens.Count -eq 0) {
        throw "Empty command line."
    }
    $args = if ($tokens.Count -gt 1) { @($tokens[1..($tokens.Count - 1)]) } else { @() }
    return @{ FilePath = $tokens[0]; ArgumentList = $args }
}

function Resolve-ServiceCommand {
    param($Svc, [string]$RootDir, [switch]$AsyncBuild)
    $rt = $Svc.RuntimeDef
    if (-not $rt -or -not $rt.Command) {
        throw "Service '$($Svc.Name)' has no runtime command."
    }

    $workDir = if ($Svc.Repo) { Resolve-RepoPath -Repo $Svc.Repo -RootDir $RootDir } else { $RootDir }
    $baseVars = Build-ServiceTemplateVars -Svc $Svc -RootDir $RootDir
    $artifact = ""

    if ($rt.ArtifactGlob) {
        $expandedGlob = Expand-Template -Template $rt.ArtifactGlob -Vars $baseVars
        $artifact = Resolve-Artifact -BaseDir $workDir -Glob $expandedGlob -Exclude $rt.ArtifactExclude
        if (-not $artifact -and $Svc.BuilderDef) {
            if ($AsyncBuild) {
                Write-ProcompTraceLog -Category "BUILD-ASYNC" -ServiceName $Svc.Name -WorkingDirectory $workDir -Message "Artifact missing ($expandedGlob); starting async build"
                Start-ServiceBuildAsync -Svc $Svc -RootDir $RootDir
                return $null
            }
            Write-ProcompTraceLog -Category "BUILD" -ServiceName $Svc.Name -WorkingDirectory $workDir -Message "Artifact missing ($expandedGlob); starting sync build"
            $ok = Invoke-ServiceBuildSync -Svc $Svc -RootDir $RootDir
            if ($ok) {
                $artifact = Resolve-Artifact -BaseDir $workDir -Glob $expandedGlob -Exclude $rt.ArtifactExclude
            }
        }
        if (-not $artifact) {
            Write-ProcompTraceLog -Category "ARTIFACT" -ServiceName $Svc.Name -WorkingDirectory $workDir -Message "Artifact not found (glob: $expandedGlob)"
            throw "Artifact not found for $($Svc.Name) (glob: $expandedGlob in $workDir)"
        }
        Write-ProcompTraceLog -Category "ARTIFACT" -ServiceName $Svc.Name -Message "Resolved artifact='$artifact'"
    }

    $fullVars = Build-ServiceTemplateVars -Svc $Svc -RootDir $RootDir -Artifact $artifact
    $cmdStr = Expand-Template -Template $rt.Command -Vars $fullVars
    return @{ CommandLine = $cmdStr; WorkingDirectory = $workDir }
}

function Start-ServiceProcess {
    param($Svc, [string]$RootDir, [switch]$AsyncBuild)

    $existingPid = Read-PidFromFile -PidFile $Svc.PidFile
    if ($existingPid) {
        $existing = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
        if ($existing -and -not $existing.HasExited) {
            Write-ProcompTraceLog -Category "EXEC" -ServiceName $Svc.Name -Message "Already running PID=$($existing.Id)"
            $Svc.Managed = $true
            $Svc.CachedPid = $existing.Id
            $Svc.CachedProc = $existing
            return
        }
    }

    $listeningPid = Get-ListeningPidByPort -Port $Svc.Port
    if ($listeningPid) {
        $hierarchy = Get-ProcessHierarchyMap
        if (Test-IsIdeDescendant -ProcessId $listeningPid -HierarchyMap $hierarchy) {
            Write-ProcompTraceLog -Category "IDE" -ServiceName $Svc.Name -Message "Port $($Svc.Port) owned by IDE descendant PID=$listeningPid -> IDE-UP"
            $Svc.ExternalIde = $true
            $Svc.StatusText = "IDE-UP"
            return
        } else {
            Write-ProcompTraceLog -Category "PORT" -ServiceName $Svc.Name -Message "Port $($Svc.Port) conflict with PID=$listeningPid -> PORT-CONFLICT"
            $Svc.PortConflict = $true
            $Svc.StatusText = "PORT-CONFLICT"
            throw "Port $($Svc.Port) for $($Svc.Name) is already in use by PID $listeningPid."
        }
    }

    if ($Svc.BuildProc) {
        try {
            if (-not $Svc.BuildProc.HasExited) { return }
        } catch { }
        $Svc.BuildProc = $null
    }

    $resolved = Resolve-ServiceCommand -Svc $Svc -RootDir $RootDir -AsyncBuild:$AsyncBuild
    if ($null -eq $resolved) {
        return
    }
    $parsed = Split-CommandLine -CommandLine $resolved.CommandLine
    if (($parsed.FilePath -eq "java" -or $parsed.FilePath -eq "java.exe") -and (Get-Command Resolve-JavaTool -ErrorAction SilentlyContinue)) {
        $parsed.FilePath = Resolve-JavaTool -Tool "java"
    }

    $envToInject = @{}
    if ($RootDir -and (Get-Command Resolve-ServiceEnvironment -ErrorAction SilentlyContinue)) {
        $envToInject = Resolve-ServiceEnvironment -Svc $Svc -RootDir $RootDir
    }
    $savedEnv = @{}
    foreach ($k in $envToInject.Keys) {
        $savedEnv[$k] = [System.Environment]::GetEnvironmentVariable($k, "Process")
        [System.Environment]::SetEnvironmentVariable($k, $envToInject[$k], "Process")
    }

    try {
        $argText = if ($parsed.ArgumentList.Count -gt 0) { " " + ($parsed.ArgumentList -join " ") } else { "" }
        Write-ProcompDebugLog -Category "EXEC" -ServiceName $Svc.Name -WorkingDirectory $resolved.WorkingDirectory -Message "`"$($parsed.FilePath)`"$argText"
        $startParams = @{
            FilePath               = $parsed.FilePath
            WorkingDirectory       = $resolved.WorkingDirectory
            RedirectStandardOutput = $Svc.LogFile
            RedirectStandardError  = $Svc.ErrFile
            WindowStyle            = "Hidden"
            PassThru               = $true
        }
        if ($parsed.ArgumentList.Count -gt 0) {
            $startParams["ArgumentList"] = $parsed.ArgumentList
        }
        $proc = Start-Process @startParams
        Write-ProcompDebugLog -Category "EXEC" -ServiceName $Svc.Name -Message "Started PID=$($proc.Id)"
    } finally {
        foreach ($k in $savedEnv.Keys) {
            [System.Environment]::SetEnvironmentVariable($k, $savedEnv[$k], "Process")
        }
    }

    Set-Content -Path $Svc.PidFile -Value $proc.Id -Encoding ASCII
    $Svc.Managed = $true
    $Svc.ExternalIde = $false
    $Svc.PortConflict = $false
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
    if ($Svc.ExternalIde) { return }
    $Svc.Stopping = $true
    $Svc.HealthTask = $null
    if ($Svc.BuildProc) {
        try {
            if (-not $Svc.BuildProc.HasExited) {
                $bPid = $Svc.BuildProc.Id
                $prevEap = $ErrorActionPreference
                $ErrorActionPreference = "Continue"
                try {
                    Write-ProcompDebugLog -Category "KILL" -ServiceName $Svc.Name -Message "taskkill.exe /F /T /PID $bPid (build)"
                    & taskkill.exe /F /T /PID $bPid >$null 2>&1
                } finally {
                    $ErrorActionPreference = $prevEap
                }
            }
        } catch { }
        $Svc.BuildProc = $null
    }
    if (Test-Path $Svc.PidFile) {
        $pidText = (Get-Content -Path $Svc.PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($pidText -and $pidText.Trim() -match '^\d+$') {
            $procId = [int]$pidText.Trim()
            $prevEap = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            try {
                Write-ProcompDebugLog -Category "KILL" -ServiceName $Svc.Name -Message "taskkill.exe /F /T /PID $procId"
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
