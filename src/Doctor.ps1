Set-StrictMode -Version Latest

function Invoke-ProcompDoctor {
    param($Config, [string]$RootDir, $TargetServices = $null)

    $checks = @()
    $svcList = if ($null -ne $TargetServices) { @($TargetServices) } else { @($Config.Services) }

    # 1. Check unique runtime executables on PATH
    $checkedExes = @{}
    foreach ($rtName in $Config.Runtimes.Keys) {
        $rt = $Config.Runtimes[$rtName]
        $parsed = Split-CommandLine -CommandLine $rt.Command
        $exe = $parsed.FilePath
        if ($checkedExes.ContainsKey($exe)) { continue }
        $checkedExes[$exe] = $true

        $resolvedExe = $null
        if (($exe -eq "java" -or $exe -eq "java.exe" -or $exe -eq "{{java}}") -and (Get-Command Resolve-JavaTool -ErrorAction SilentlyContinue)) {
            $cand = Resolve-JavaTool -Tool "java"
            if (Test-Path $cand) { $resolvedExe = $cand }
        }
        if (-not $resolvedExe) {
            $cmd = Get-Command $exe -ErrorAction SilentlyContinue
            if ($cmd) { $resolvedExe = $cmd.Source }
        }

        if ($resolvedExe) {
            $srcTag = if ($env:JAVA_HOME -and $resolvedExe.StartsWith($env:JAVA_HOME, [System.StringComparison]::OrdinalIgnoreCase)) { " (JAVA_HOME)" } else { "" }
            $checks += [PSCustomObject]@{ Check = "runtime:$exe"; Status = "OK"; Detail = "$resolvedExe$srcTag" }
        } else {
            $checks += [PSCustomObject]@{ Check = "runtime:$exe"; Status = "FAIL"; Detail = "'$exe' not found in JAVA_HOME or PATH" }
        }
    }

    # 2. Git
    $gitCmd = Get-Command "git" -ErrorAction SilentlyContinue
    if ($gitCmd) {
        $checks += [PSCustomObject]@{ Check = "git"; Status = "OK"; Detail = $gitCmd.Source }
    } else {
        $checks += [PSCustomObject]@{ Check = "git"; Status = "WARN"; Detail = "git not found on PATH (sync disabled)" }
    }

    # 3. .env
    $envPath = Join-Path $RootDir ".env"
    if (Test-Path $envPath) {
        $checks += [PSCustomObject]@{ Check = ".env"; Status = "OK"; Detail = $envPath }
    } else {
        $checks += [PSCustomObject]@{ Check = ".env"; Status = "INFO"; Detail = "No global .env file (optional)" }
    }

    # 4. Per-service checks
    $hierarchy = $null
    foreach ($svc in $svcList) {
        $portPid = Get-ListeningPidByPort -Port $svc.Port
        $portNote = "port $($svc.Port) free"
        $status = "OK"
        if ($portPid) {
            if ($null -eq $hierarchy) { $hierarchy = Get-ProcessHierarchyMap }
            if (Test-IsIdeDescendant -ProcessId $portPid -HierarchyMap $hierarchy) {
                $portNote = "port $($svc.Port) IDE-UP (PID $portPid)"
            } else {
                $portNote = "port $($svc.Port) IN USE (PID $portPid)"
                $status = "WARN"
            }
        }

        $repoNote = "workspace"
        if ($svc.Repo) {
            $rp = Resolve-RepoPath -Repo $svc.Repo -RootDir $RootDir
            $st = Get-GitRepoStatus -RepoPath $rp
            if ($st.Exists) {
                $repoNote = "git:$($st.Summary)"
            } else {
                $repoNote = "repo missing ($($svc.Repo))"
                $status = "WARN"
            }
        }

        $checks += [PSCustomObject]@{
            Check  = "svc:$($svc.Name)"
            Status = $status
            Detail = "rt=$($svc.Runtime) | $portNote | $repoNote | route=$($svc.RoutingMode)"
        }
    }

    # 5. Gateway / WireMock drift check (if reachable)
    if ($Config.Gateway -and $Config.Gateway.AdminUrl -and (Get-Command Get-WireMockDrift -ErrorAction SilentlyContinue)) {
        $drift = @(Get-WireMockDrift -Services $svcList -WireMockBaseUrl $Config.Gateway.AdminUrl -GatewayServiceName $Config.Gateway.ServiceName)
        if ($drift.Count -eq 0) {
            $checks += [PSCustomObject]@{ Check = "gateway"; Status = "OK"; Detail = "WireMock routes in sync ($($Config.Gateway.AdminUrl))" }
        } elseif ($drift.Count -eq 1 -and $drift[0].Status -eq "UNREACHABLE") {
            $checks += [PSCustomObject]@{ Check = "gateway"; Status = "INFO"; Detail = "$($drift[0].Detail) (not running yet)" }
        } else {
            foreach ($d in $drift) {
                $checks += [PSCustomObject]@{ Check = "gateway:$($d.Service)"; Status = "WARN"; Detail = "$($d.Status): $($d.Detail)" }
            }
        }
    }

    return $checks
}
