Set-StrictMode -Version Latest

function Resolve-RepoPath {
    param([string]$Repo, [string]$RootDir)
    if (-not $Repo) { return $null }
    if ([System.IO.Path]::IsPathRooted($Repo)) {
        return $Repo
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RootDir $Repo))
}

function Invoke-GitNonInteractive {
    param([string[]]$GitArgs)
    $argStr = ($GitArgs | ForEach-Object {
        $a = [string]$_
        if ($a -match '[\s"]') { '"' + ($a -replace '"', '\"') + '"' } else { $a }
    }) -join " "
    Write-ProcompDebugLog -Category "GIT" -Message "git $argStr"

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "git"
    $psi.Arguments = $argStr
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.EnvironmentVariables["GIT_TERMINAL_PROMPT"] = "0"
    if (-not $psi.EnvironmentVariables["GIT_SSH_COMMAND"]) {
        $psi.EnvironmentVariables["GIT_SSH_COMMAND"] = "ssh -o BatchMode=yes -o ConnectTimeout=5"
    }

    $proc = [System.Diagnostics.Process]::Start($psi)
    try {
        $errTask = $proc.StandardError.ReadToEndAsync()
        $stdOut = $proc.StandardOutput.ReadToEnd()
        $stdErr = $errTask.GetAwaiter().GetResult()
        $proc.WaitForExit()
        $exitCode = $proc.ExitCode
        $outText = (($stdOut + " " + $stdErr) -replace '[\r\n]+', ' ').Trim()
        if ($exitCode -ne 0) {
            Write-ProcompTraceLog -Category "GIT" -Message "git exit=$exitCode output='$outText'"
        }
        return [PSCustomObject]@{ ExitCode = $exitCode; Output = $outText }
    } finally {
        $proc.Dispose()
    }
}

function Get-GitRepoStatus {
    param([string]$RepoPath, [bool]$HasRepoUrl = $false)
    $mockDelay = 0
    $delayEnv = [System.Environment]::GetEnvironmentVariable("PROCOMP_MOCK_GIT_DELAY_MS", "Process")
    if ($delayEnv -and [int]::TryParse($delayEnv, [ref]$mockDelay) -and $mockDelay -gt 0) {
        Start-Sleep -Milliseconds $mockDelay
    }
    if (-not $RepoPath -or -not (Test-Path (Join-Path $RepoPath ".git"))) {
        $sum = if ($HasRepoUrl) { "MISSING" } else { "-" }
        return [PSCustomObject]@{ Exists = $false; Branch = "-"; Dirty = $false; Ahead = 0; Behind = 0; Summary = $sum }
    }

    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        Write-ProcompDebugLog -Category "GIT" -WorkingDirectory $RepoPath -Message "git -C `"$RepoPath`" rev-parse --abbrev-ref HEAD"
        $branch = (& git -C $RepoPath rev-parse --abbrev-ref HEAD 2>$null | Select-Object -First 1)
        if (-not $branch) { $branch = "HEAD" }
        $branch = $branch.Trim()
        Write-ProcompDebugLog -Category "GIT" -WorkingDirectory $RepoPath -Message "git -C `"$RepoPath`" status --porcelain"
        $porcelain = @(& git -C $RepoPath status --porcelain 2>$null)
        $dirty = ($porcelain.Count -gt 0)

        $ahead = 0
        $behind = 0
        Write-ProcompDebugLog -Category "GIT" -WorkingDirectory $RepoPath -Message "git -C `"$RepoPath`" rev-list --left-right --count HEAD...@{u}"
        $abLines = @(& git -C $RepoPath rev-list --left-right --count 'HEAD...@{u}' 2>$null)
        if ($LASTEXITCODE -eq 0 -and $abLines.Count -gt 0 -and ($abLines[0].Trim() -match '^(\d+)\s+(\d+)$')) {
            $ahead  = [int]$Matches[1]
            $behind = [int]$Matches[2]
        }

        $summary = if ($dirty) { "$branch*" } else { $branch }
        if ($behind -gt 0) { $summary += " v$behind" }
        if ($ahead -gt 0)  { $summary += " ^$ahead" }

        return [PSCustomObject]@{ Exists = $true; Branch = $branch; Dirty = $dirty; Ahead = $ahead; Behind = $behind; Summary = $summary }
    } finally {
        $ErrorActionPreference = $prevEap
    }
}

function Refresh-GitSummaries {
    param($Services, [string]$RootDir, [hashtable]$ActiveOps = $null, [switch]$Quiet)
    $cache = @{}
    $svcList = @($Services | Where-Object { $_.RoutingMode -eq "LOCAL" }) + @($Services | Where-Object { $_.RoutingMode -ne "LOCAL" })
    $toQuery = @()
    $seenQuery = @{}

    foreach ($svc in $svcList) {
        $hasUrl = ($svc.PSObject.Properties["ServiceRepositoryUrl"] -and [bool]$svc.ServiceRepositoryUrl)
        if (-not $svc.Repo) {
            $svc.GitSummary = if ($hasUrl) { "MISSING" } else { "-" }
            continue
        }
        $rp = Resolve-RepoPath -Repo $svc.Repo -RootDir $RootDir
        if (-not $rp) {
            $svc.GitSummary = "-"
            continue
        }
        if ($ActiveOps -and $ActiveOps.ContainsKey($rp)) {
            $svc.GitSummary = $ActiveOps[$rp]
            continue
        }
        if (-not $seenQuery.ContainsKey($rp)) {
            $seenQuery[$rp] = $true
            $toQuery += [PSCustomObject]@{ RepoPath = $rp; HasRepoUrl = $hasUrl }
        }
    }

    if ($toQuery.Count -gt 0) {
        if (-not $Quiet) {
            Report-ProcompActivity -Category "GIT" -Message "Checking Git status ($($toQuery.Count) repo(s), 4 threads)..."
        }
        $initScripts = @((Join-Path $PSScriptRoot "Config.ps1"), (Join-Path $PSScriptRoot "BuildRunner.ps1"))
        $statuses = @(Invoke-ParallelWork -Items $toQuery -MaxThreads 4 -InitScripts $initScripts -ScriptBlock {
            param($q)
            $st = Get-GitRepoStatus -RepoPath $q.RepoPath -HasRepoUrl ([bool]$q.HasRepoUrl)
            return [PSCustomObject]@{ RepoPath = $q.RepoPath; Summary = $st.Summary }
        })
        foreach ($r in $statuses) {
            if ($r -and $r.RepoPath) { $cache[$r.RepoPath] = $r.Summary }
        }
    }

    foreach ($svc in $svcList) {
        if (-not $svc.Repo) { continue }
        $rp = Resolve-RepoPath -Repo $svc.Repo -RootDir $RootDir
        if ($rp -and $cache.ContainsKey($rp)) {
            $svc.GitSummary = $cache[$rp]
        }
    }
}

function Invoke-GitFetchRepo {
    param([string]$RepoPath)
    if (-not $RepoPath -or -not (Test-Path (Join-Path $RepoPath ".git"))) {
        return [PSCustomObject]@{ Repo = $RepoPath; Action = "SKIP"; Reason = "not cloned" }
    }
    $res = Invoke-GitNonInteractive -GitArgs @("-C", $RepoPath, "fetch", "--quiet")
    if ($res.ExitCode -eq 0) {
        return [PSCustomObject]@{ Repo = $RepoPath; Action = "FETCHED"; Reason = "ok" }
    }
    return [PSCustomObject]@{ Repo = $RepoPath; Action = "ERROR"; Reason = $res.Output }
}

function Invoke-GitPullRepo {
    param([string]$RepoPath)
    $st = Get-GitRepoStatus -RepoPath $RepoPath
    if (-not $st.Exists) {
        return [PSCustomObject]@{ Repo = $RepoPath; Action = "SKIP"; Commits = 0; Reason = "not cloned" }
    }
    if ($st.Dirty) {
        return [PSCustomObject]@{ Repo = $RepoPath; Action = "SKIP"; Commits = 0; Reason = "dirty ($($st.Summary)) - resolve in IDE" }
    }

    $beforeRes = Invoke-GitNonInteractive -GitArgs @("-C", $RepoPath, "rev-parse", "HEAD")
    $beforeSha = $beforeRes.Output
    $pullRes = Invoke-GitNonInteractive -GitArgs @("-C", $RepoPath, "pull", "--ff-only")
    if ($pullRes.ExitCode -ne 0) {
        return [PSCustomObject]@{ Repo = $RepoPath; Action = "ERROR"; Commits = 0; Reason = "cannot fast-forward ($($pullRes.Output))" }
    }
    $afterRes = Invoke-GitNonInteractive -GitArgs @("-C", $RepoPath, "rev-parse", "HEAD")
    $afterSha = $afterRes.Output
    $count = 0
    if ($beforeSha -and $afterSha -and ($beforeSha -ne $afterSha)) {
        $cntRes = Invoke-GitNonInteractive -GitArgs @("-C", $RepoPath, "rev-list", "--count", "$beforeSha..$afterSha")
        if ($cntRes.ExitCode -eq 0 -and $cntRes.Output -match '^\d+$') {
            $count = [int]$cntRes.Output
        } else {
            $count = 1
        }
    }
    return [PSCustomObject]@{ Repo = $RepoPath; Action = "PULLED"; Commits = $count; Reason = $pullRes.Output }
}

function Sync-GitRepos {
    param($Services, [string]$RootDir)
    $seen = @{}
    $results = @()

    foreach ($svc in $Services) {
        $repoPath = Resolve-RepoPath -Repo $svc.Repo -RootDir $RootDir
        if (-not $repoPath -or $seen.ContainsKey($repoPath)) { continue }
        $seen[$repoPath] = $true

        $st = Get-GitRepoStatus -RepoPath $repoPath
        if (-not $st.Exists) {
            $results += [PSCustomObject]@{ Repo = $repoPath; Action = "SKIP"; Reason = "not cloned" }
            continue
        }
        if ($st.Dirty) {
            $results += [PSCustomObject]@{ Repo = $repoPath; Action = "SKIP"; Reason = "dirty ($($st.Summary))" }
            continue
        }
        if (@("main", "master", "develop") -notcontains $st.Branch) {
            $results += [PSCustomObject]@{ Repo = $repoPath; Action = "SKIP"; Reason = "feature branch ($($st.Branch))" }
            continue
        }
        $pr = Invoke-GitPullRepo -RepoPath $repoPath
        $results += [PSCustomObject]@{ Repo = $repoPath; Action = $pr.Action; Reason = $pr.Reason }
    }
    return $results
}

function Fetch-AllGitRepos {
    param($Services, [string]$RootDir)
    $seen = @{}
    $results = @()
    foreach ($svc in $Services) {
        $repoPath = Resolve-RepoPath -Repo $svc.Repo -RootDir $RootDir
        if (-not $repoPath -or $seen.ContainsKey($repoPath)) { continue }
        $seen[$repoPath] = $true
        $results += (Invoke-GitFetchRepo -RepoPath $repoPath)
    }
    return $results
}

function Invoke-GitCloneMissingRepos {
    param($Services, [string]$RootDir)
    $seen = @{}
    $results = @()
    foreach ($svc in $Services) {
        $repoPath = Resolve-RepoPath -Repo $svc.Repo -RootDir $RootDir
        $repoUrl = if ($svc.PSObject.Properties["ServiceRepositoryUrl"]) { [string]$svc.ServiceRepositoryUrl } else { "" }
        if (-not $repoPath -or -not $repoUrl -or $seen.ContainsKey($repoPath)) { continue }
        $seen[$repoPath] = $true

        if (Test-Path (Join-Path $repoPath ".git")) {
            $results += [PSCustomObject]@{ Repo = $repoPath; Action = "EXISTS"; Reason = "already cloned" }
            continue
        }
        $parent = Split-Path -Parent $repoPath
        if ($parent -and (-not (Test-Path $parent))) {
            New-Item -ItemType Directory -Force -Path $parent | Out-Null
        }
        $res = Invoke-GitNonInteractive -GitArgs @("clone", $repoUrl, $repoPath)
        if ($res.ExitCode -eq 0) {
            $results += [PSCustomObject]@{ Repo = $repoPath; Action = "CLONED"; Reason = $repoUrl }
        } else {
            $results += [PSCustomObject]@{ Repo = $repoPath; Action = "ERROR"; Reason = $res.Output }
        }
    }
    return $results
}

function Expand-BuildCommand {
    param($Svc, [string]$RootDir)
    if (-not $Svc.BuilderDef -or -not $Svc.BuilderDef.Command) {
        throw "Service '$($Svc.Name)' has no builder configured."
    }
    $vars = Build-ServiceTemplateVars -Svc $Svc -RootDir $RootDir
    return (Expand-Template -Template $Svc.BuilderDef.Command -Vars $vars)
}

function Start-ServiceBuildAsync {
    param($Svc, [string]$RootDir)
    $cmdLine = Expand-BuildCommand -Svc $Svc -RootDir $RootDir
    $workDir = Resolve-ServiceWorkingDir -Svc $Svc -RootDir $RootDir
    if (-not (Test-Path $workDir)) {
        throw "Working directory not found for $($Svc.Name): $workDir"
    }

    $logDir = Split-Path -Parent $Svc.LogFile
    if ($logDir -and (-not (Test-Path -LiteralPath $logDir))) {
        New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    }
    $wrappedCmd = "`"$cmdLine 1>`"$($Svc.LogFile)`" 2>`"$($Svc.ErrFile)`"`""
    Write-ProcompDebugLog -Category "BUILD-ASYNC" -ServiceName $Svc.Name -WorkingDirectory $workDir -Message "cmd.exe /c $cmdLine (stdout=$($Svc.LogFile), stderr=$($Svc.ErrFile))"
    $proc = Start-Process -FilePath "cmd.exe" `
        -ArgumentList @("/c", $wrappedCmd) `
        -WorkingDirectory $workDir `
        -WindowStyle Hidden `
        -PassThru
    try { $null = $proc.Handle } catch { }

    Write-ProcompDebugLog -Category "BUILD-ASYNC" -ServiceName $Svc.Name -Message "Started PID=$($proc.Id)"
    $Svc.BuildProc = $proc
    $Svc.StatusText = "BUILDING"
}

function Invoke-ServiceBuildSync {
    param($Svc, [string]$RootDir)
    if (-not $Svc.BuilderDef) { return $false }
    $cmdLine = Expand-BuildCommand -Svc $Svc -RootDir $RootDir
    $workDir = Resolve-ServiceWorkingDir -Svc $Svc -RootDir $RootDir
    if (-not (Test-Path $workDir)) { return $false }

    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        Push-Location $workDir
        Write-ProcompDebugLog -Category "BUILD" -ServiceName $Svc.Name -WorkingDirectory $workDir -Message "cmd.exe /c $cmdLine"
        & cmd.exe /c $cmdLine
        $bExit = $LASTEXITCODE
        Write-ProcompTraceLog -Category "BUILD" -ServiceName $Svc.Name -Message "Sync build exited with code=$bExit"
        return ($bExit -eq 0)
    } finally {
        Pop-Location
        $ErrorActionPreference = $prevEap
    }
}
