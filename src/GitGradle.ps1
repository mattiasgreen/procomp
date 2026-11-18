Set-StrictMode -Version Latest

function Resolve-RepoPath {
    param([string]$GitRepo, [string]$RootDir)
    if (-not $GitRepo) { return $null }
    if ([System.IO.Path]::IsPathRooted($GitRepo)) {
        return $GitRepo
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RootDir $GitRepo))
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

function Sync-GitRepos {
    param($Services, [string]$RootDir)
    $seen = @{}
    $results = @()

    foreach ($svc in $Services) {
        $repoPath = Resolve-RepoPath -GitRepo $svc.GitRepo -RootDir $RootDir
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
            $code = $LASTEXITCODE
            if ($code -eq 0) {
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

function Find-ServiceBootJar {
    param([string]$RepoPath, [string]$GradleSubproj)
    if (-not $RepoPath) { return $null }
    $subPath = if ($GradleSubproj) { $GradleSubproj.TrimStart(':') -replace ':', '\' } else { "" }
    $libsDir = if ($subPath) { Join-Path (Join-Path $RepoPath $subPath) "build\libs" } else { Join-Path $RepoPath "build\libs" }

    if (-not (Test-Path $libsDir)) { return $null }
    $jar = Get-ChildItem -Path $libsDir -Filter "*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-plain.jar" } |
        Sort-Object -Property LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($jar) { return $jar.FullName }
    return $null
}

function Get-GradleTaskName {
    param([string]$GradleSubproj)
    if ($GradleSubproj) {
        $clean = ":" + $GradleSubproj.TrimStart(':')
        return "${clean}:bootJar"
    }
    return "bootJar"
}

function Start-GradleBuildAsync {
    param($Svc, [string]$RootDir)
    $repoPath = Resolve-RepoPath -GitRepo $Svc.GitRepo -RootDir $RootDir
    if (-not $repoPath -or -not (Test-Path $repoPath)) {
        throw "Git repo not found for $($Svc.Name): $($Svc.GitRepo)"
    }
    $gradlew = Join-Path $repoPath "gradlew.bat"
    if (-not (Test-Path $gradlew)) {
        throw "gradlew.bat not found in $repoPath"
    }

    $task = Get-GradleTaskName -GradleSubproj $Svc.GradleSubproj
    $proc = Start-Process -FilePath $gradlew `
        -ArgumentList @($task, "--console=plain") `
        -WorkingDirectory $repoPath `
        -RedirectStandardOutput $Svc.LogFile `
        -RedirectStandardError $Svc.ErrFile `
        -WindowStyle Hidden `
        -PassThru

    $Svc.BuildProc = $proc
    $Svc.StatusText = "BUILDING"
}

function Invoke-GradleBuildSync {
    param($Svc, [string]$RootDir)
    $repoPath = Resolve-RepoPath -GitRepo $Svc.GitRepo -RootDir $RootDir
    if (-not $repoPath -or -not (Test-Path $repoPath)) {
        return $false
    }
    $gradlew = Join-Path $repoPath "gradlew.bat"
    if (-not (Test-Path $gradlew)) {
        throw "gradlew.bat not found in $repoPath"
    }
    $task = Get-GradleTaskName -GradleSubproj $Svc.GradleSubproj
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        Push-Location $repoPath
        & $gradlew $task -q
        return ($LASTEXITCODE -eq 0)
    } finally {
        Pop-Location
        $ErrorActionPreference = $prevEap
    }
}
