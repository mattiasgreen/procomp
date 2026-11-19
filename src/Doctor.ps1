Set-StrictMode -Version Latest

function Invoke-ProcompDoctor {
    param($Config, [string]$RootDir)

    $checks = @()

    # 1. Check unique runtime executables on PATH
    $checkedExes = @{}
    foreach ($rtName in $Config.Runtimes.Keys) {
        $rt = $Config.Runtimes[$rtName]
        $parsed = Split-CommandLine -CommandLine $rt.Command
        $exe = $parsed.FilePath
        if ($checkedExes.ContainsKey($exe)) { continue }
        $checkedExes[$exe] = $true

        $cmd = Get-Command $exe -ErrorAction SilentlyContinue
        if ($cmd) {
            $checks += [PSCustomObject]@{ Check = "runtime:$exe"; Status = "OK"; Detail = $cmd.Source }
        } else {
            $checks += [PSCustomObject]@{ Check = "runtime:$exe"; Status = "FAIL"; Detail = "'$exe' not found on PATH" }
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
    foreach ($svc in $Config.Services) {
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

    return $checks
}
