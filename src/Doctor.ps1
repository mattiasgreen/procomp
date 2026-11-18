Set-StrictMode -Version Latest

function Invoke-ProcompDoctor {
    param($Services, [string]$RootDir, [string]$MockJarPath)

    $checks = @()

    # 1. Java
    $javaCmd = Get-Command "java" -ErrorAction SilentlyContinue
    if ($javaCmd) {
        $checks += [PSCustomObject]@{ Check = "java"; Status = "OK"; Detail = $javaCmd.Source }
    } else {
        $checks += [PSCustomObject]@{ Check = "java"; Status = "FAIL"; Detail = "java not found on PATH" }
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

    # 4. Per-service repo / JAR / port checks
    $hierarchy = $null
    foreach ($svc in $Services) {
        $portPid = Get-ListeningPidByPort -Port $svc.Port
        $portNote = "port $($svc.Port) free"
        $portStatus = "OK"
        if ($portPid) {
            if ($null -eq $hierarchy) { $hierarchy = Get-ProcessHierarchyMap }
            if (Test-IsIdeDescendant -ProcessId $portPid -HierarchyMap $hierarchy) {
                $portNote = "port $($svc.Port) IDE-UP (PID $portPid)"
            } else {
                $portNote = "port $($svc.Port) IN USE (PID $portPid)"
                $portStatus = "WARN"
            }
        }

        $repoNote = "mock-jar"
        if ($svc.GitRepo) {
            $rp = Resolve-RepoPath -GitRepo $svc.GitRepo -RootDir $RootDir
            $st = Get-GitRepoStatus -RepoPath $rp
            if ($st.Exists) {
                $repoNote = "git:$($st.Summary)"
            } elseif (Test-Path $MockJarPath) {
                $repoNote = "repo missing (using mock-jar)"
            } else {
                $repoNote = "repo missing & no mock-jar"
                $portStatus = "FAIL"
            }
        }

        $checks += [PSCustomObject]@{
            Check  = "svc:$($svc.Name)"
            Status = $portStatus
            Detail = "$portNote | $repoNote | route=$($svc.RoutingMode)"
        }
    }

    return $checks
}
