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
    foreach ($svc in $Services) {
        $svc.ExternalIde = $false
        $svc.PortConflict = $false

        if ($svc.CachedPid -and $procMap.ContainsKey($svc.CachedPid)) {
            $svc.CachedProc = $procMap[$svc.CachedPid]
            continue
        }

        $svc.CachedProc = $null
        if ($DetectIde -and (-not $svc.CachedPid) -and ($svc.RoutingMode -eq "LOCAL")) {
            $listeningPid = Get-ListeningPidByPort -Port $svc.Port
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

function Start-ServiceProcess {
    param($Svc, [string]$MockJarPath, [string]$RootDir = "")

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

    # Check if an IDE or another process already owns the port
    $listeningPid = Get-ListeningPidByPort -Port $Svc.Port
    if ($listeningPid) {
        $hierarchy = Get-ProcessHierarchyMap
        if (Test-IsIdeDescendant -ProcessId $listeningPid -HierarchyMap $hierarchy) {
            $Svc.ExternalIde = $true
            $Svc.StatusText = "IDE-UP"
            return
        } else {
            $Svc.PortConflict = $true
            $Svc.StatusText = "PORT-CONFLICT"
            throw "Port $($Svc.Port) for $($Svc.Name) is already in use by PID $listeningPid."
        }
    }

    $exePath = "java"
    $argList = @()

    if ($Svc.PSObject.Properties["Command"] -and $Svc.Command) {
        $exePath = "cmd.exe"
        $argList = @("/c", $Svc.Command)
    } else {
        $realJar = $null
        if ($RootDir -and (Get-Command Resolve-RepoPath -ErrorAction SilentlyContinue)) {
            $repoPath = Resolve-RepoPath -GitRepo $Svc.GitRepo -RootDir $RootDir
            if ($repoPath -and (Test-Path $repoPath)) {
                $realJar = Find-ServiceBootJar -RepoPath $repoPath -GradleSubproj $Svc.GradleSubproj
                if (-not $realJar) {
                    $ok = Invoke-GradleBuildSync -Svc $Svc -RootDir $RootDir
                    if ($ok) { $realJar = Find-ServiceBootJar -RepoPath $repoPath -GradleSubproj $Svc.GradleSubproj }
                }
            }
        }

        if ($realJar) {
            if ($Svc.JvmArgs) { $argList += ($Svc.JvmArgs -split '\s+' | Where-Object { $_ -ne "" }) }
            $argList += "-Dserver.port=$($Svc.Port)"
            $argList += "-Dspring.profiles.active=local"
            $argList += "-jar"
            $argList += $realJar
        } else {
            if (-not (Test-Path $MockJarPath)) {
                throw "No bootJar or Mock JAR found at $MockJarPath."
            }
            if ($Svc.JvmArgs) { $argList += ($Svc.JvmArgs -split '\s+' | Where-Object { $_ -ne "" }) }
            $argList += "-Dservice.name=$($Svc.Name)"
            $argList += "-Dservice.type=$($Svc.Type)"
            $argList += "-Dserver.port=$($Svc.Port)"
            $argList += "-Dhealth.path=$($Svc.HealthPath)"
            $argList += "-Dstartup.delay.ms=$($Svc.StartupDelayMs)"
            $argList += "-Dlog.format=$($Svc.LogFormat)"
            $argList += "-jar"
            $argList += $MockJarPath
        }
    }

    # Inject .env / service env vars temporarily so Start-Process child inherits them (PS 5.1+ compatible)
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
        $proc = Start-Process -FilePath $exePath `
            -ArgumentList $argList `
            -RedirectStandardOutput $Svc.LogFile `
            -RedirectStandardError $Svc.ErrFile `
            -WindowStyle Hidden `
            -PassThru
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
    if ($Svc.ExternalIde) {
        return
    }
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
