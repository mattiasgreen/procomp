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
    $prevPrompt = [System.Environment]::GetEnvironmentVariable("GIT_TERMINAL_PROMPT", "Process")
    $prevSsh    = [System.Environment]::GetEnvironmentVariable("GIT_SSH_COMMAND", "Process")
    $prevEap    = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        [System.Environment]::SetEnvironmentVariable("GIT_TERMINAL_PROMPT", "0", "Process")
        if (-not $prevSsh) {
            [System.Environment]::SetEnvironmentVariable("GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o ConnectTimeout=5", "Process")
        }
        Write-ProcompDebugLog -Category "GIT" -Message ("git " + ($GitArgs -join " "))
        $out = @(& git @GitArgs 2>&1)
        $exitCode = $LASTEXITCODE
        $outText = ($out -join " ").Trim()
        if ($exitCode -ne 0) {
            Write-ProcompTraceLog -Category "GIT" -Message "git exit=$exitCode output='$outText'"
        }
        return [PSCustomObject]@{ ExitCode = $exitCode; Output = $outText }
    } finally {
        [System.Environment]::SetEnvironmentVariable("GIT_TERMINAL_PROMPT", $prevPrompt, "Process")
        [System.Environment]::SetEnvironmentVariable("GIT_SSH_COMMAND", $prevSsh, "Process")
        $ErrorActionPreference = $prevEap
    }
}

function Get-GitRepoStatus {
    param([string]$RepoPath, [bool]$HasRepoUrl = $false)
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
    param($Services, [string]$RootDir, [hashtable]$ActiveOps = $null)
    $cache = @{}
    foreach ($svc in $Services) {
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
        if (-not $cache.ContainsKey($rp)) {
            $cache[$rp] = (Get-GitRepoStatus -RepoPath $rp -HasRepoUrl $hasUrl).Summary
        }
        $svc.GitSummary = $cache[$rp]
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
    $workDir = if ($Svc.Repo) { Resolve-RepoPath -Repo $Svc.Repo -RootDir $RootDir } else { $RootDir }
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
    $workDir = if ($Svc.Repo) { Resolve-RepoPath -Repo $Svc.Repo -RootDir $RootDir } else { $RootDir }
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
