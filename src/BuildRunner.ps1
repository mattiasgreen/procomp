Set-StrictMode -Version Latest

function Resolve-RepoPath {
    param([string]$Repo, [string]$RootDir)
    if (-not $Repo) { return $null }
    if ([System.IO.Path]::IsPathRooted($Repo)) {
        return $Repo
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RootDir $Repo))
}

function Get-GitRepoStatus {
    param([string]$RepoPath)
    if (-not $RepoPath -or -not (Test-Path (Join-Path $RepoPath ".git"))) {
        return [PSCustomObject]@{ Exists = $false; Branch = "-"; Dirty = $false; Summary = "-" }
    }

    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $branch = (& git -C $RepoPath rev-parse --abbrev-ref HEAD 2>$null | Select-Object -First 1)
        if (-not $branch) { $branch = "HEAD" }
        $branch = $branch.Trim()
        $porcelain = @(& git -C $RepoPath status --porcelain 2>$null)
        $dirty = ($porcelain.Count -gt 0)
        $summary = if ($dirty) { "$branch*" } else { $branch }
        return [PSCustomObject]@{ Exists = $true; Branch = $branch; Dirty = $dirty; Summary = $summary }
    } finally {
        $ErrorActionPreference = $prevEap
    }
}

function Refresh-GitSummaries {
    param($Services, [string]$RootDir)
    $cache = @{}
    foreach ($svc in $Services) {
        if (-not $svc.Repo) {
            $svc.GitSummary = "-"
            continue
        }
        $rp = Resolve-RepoPath -Repo $svc.Repo -RootDir $RootDir
        if (-not $rp) {
            $svc.GitSummary = "-"
            continue
        }
        if (-not $cache.ContainsKey($rp)) {
            $cache[$rp] = (Get-GitRepoStatus -RepoPath $rp).Summary
        }
        $svc.GitSummary = $cache[$rp]
    }
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

        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $out = (& git -C $repoPath pull --ff-only 2>&1) -join " "
            if ($LASTEXITCODE -eq 0) {
                $results += [PSCustomObject]@{ Repo = $repoPath; Action = "PULLED"; Reason = $out }
            } else {
                $results += [PSCustomObject]@{ Repo = $repoPath; Action = "ERROR"; Reason = $out }
            }
        } finally {
            $ErrorActionPreference = $prevEap
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

    $proc = Start-Process -FilePath "cmd.exe" `
        -ArgumentList @("/c", $cmdLine) `
        -WorkingDirectory $workDir `
        -RedirectStandardOutput $Svc.LogFile `
        -RedirectStandardError $Svc.ErrFile `
        -WindowStyle Hidden `
        -PassThru

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
        & cmd.exe /c $cmdLine
        return ($LASTEXITCODE -eq 0)
    } finally {
        Pop-Location
        $ErrorActionPreference = $prevEap
    }
}
