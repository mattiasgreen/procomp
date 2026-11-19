Set-StrictMode -Version Latest

function Ensure-MockServiceJar {
    param([string]$RootDir)
    $mockDir = Join-Path $RootDir "mock-service"
    $mockJar = Join-Path $mockDir "mock-service.jar"
    if (Test-Path $mockJar) { return $mockJar }

    if (-not (Get-Command "javac" -ErrorAction SilentlyContinue)) {
        throw "javac not found on PATH. A JDK (not just JRE) is required to compile mock-service.jar."
    }
    if (-not (Get-Command "jar" -ErrorAction SilentlyContinue)) {
        throw "jar not found on PATH. A JDK is required to package mock-service.jar."
    }

    Push-Location $mockDir
    try {
        & javac MockService.java
        if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
        & jar cfe mock-service.jar MockService MockService.class
        if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    return $mockJar
}

function New-MockGitRepo {
    param(
        [string]$BaseDir,
        [string]$Name,
        [string]$Branch = "main",
        [switch]$MakeDirty,
        [switch]$AddRemoteCommit,
        [switch]$Force
    )
    $path = Join-Path $BaseDir $Name
    $remotePath = Join-Path (Join-Path $BaseDir "_remotes") "$Name.git"
    if ((-not $Force) -and (Test-Path (Join-Path $path ".git")) -and (Test-Path $remotePath)) {
        return [PSCustomObject]@{ LocalPath = $path; RemotePath = $remotePath; Created = $false }
    }

    if (-not (Get-Command "git" -ErrorAction SilentlyContinue)) {
        throw "git not found on PATH. Git is required for mock Git fixtures."
    }

    if (Test-Path $path) { Remove-Item -Recurse -Force $path }
    if (Test-Path $remotePath) { Remove-Item -Recurse -Force $remotePath }
    New-Item -ItemType Directory -Force -Path $path, $remotePath | Out-Null

    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & git init --bare $remotePath >$null 2>&1
        & git -C $path init >$null 2>&1
        & git -C $path checkout -B main >$null 2>&1
        & git -C $path config user.email "demo@procomp.local" >$null 2>&1
        & git -C $path config user.name "procomp-demo" >$null 2>&1
        "# $Name (mock repo)" | Set-Content (Join-Path $path "README.md") -Encoding UTF8
        & git -C $path add README.md >$null 2>&1
        & git -C $path commit -m "Initial commit" >$null 2>&1
        & git -C $path remote add origin $remotePath >$null 2>&1
        & git -C $path push -u origin main >$null 2>&1

        if ($AddRemoteCommit) {
            $tmpClone = Join-Path $BaseDir "_tmp_$Name"
            if (Test-Path $tmpClone) { Remove-Item -Recurse -Force $tmpClone }
            & git clone --branch main $remotePath $tmpClone >$null 2>&1
            & git -C $tmpClone config user.email "teammate@procomp.local" >$null 2>&1
            & git -C $tmpClone config user.name "teammate" >$null 2>&1
            "Upstream change ready to pull" | Set-Content (Join-Path $tmpClone "CHANGELOG.md") -Encoding UTF8
            & git -C $tmpClone add CHANGELOG.md >$null 2>&1
            & git -C $tmpClone commit -m "Upstream feature commit" >$null 2>&1
            & git -C $tmpClone push origin main >$null 2>&1
            Remove-Item -Recurse -Force $tmpClone -ErrorAction SilentlyContinue
        }

        if ($Branch -ne "main") {
            & git -C $path checkout -b $Branch >$null 2>&1
        }
        if ($MakeDirty) {
            "// uncommitted local work" | Set-Content (Join-Path $path "WIP.txt") -Encoding UTF8
        }
    } finally {
        $ErrorActionPreference = $prevEap
    }

    return [PSCustomObject]@{ LocalPath = $path; RemotePath = $remotePath; Created = $true }
}
