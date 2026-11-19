Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $PSScriptRoot
$DemoRepos = Join-Path $RootDir ".demo_repos"
$MockDir = Join-Path $RootDir "mock-service"
$MockJar = Join-Path $MockDir "mock-service.jar"

if (-not (Test-Path $MockJar)) {
    Push-Location $MockDir
    try {
        & javac MockService.java
        & jar cfe mock-service.jar MockService MockService.class
    } finally {
        Pop-Location
    }
}

function Ensure-DemoRepo {
    param([string]$Name, [string]$Branch = "main", [switch]$MakeDirty)
    $path = Join-Path $DemoRepos $Name
    $remotePath = Join-Path (Join-Path $DemoRepos "_remotes") "$Name.git"
    if ((Test-Path (Join-Path $path ".git")) -and (Test-Path $remotePath)) { return }

    if (Test-Path $path) { Remove-Item -Recurse -Force $path }
    if (Test-Path $remotePath) { Remove-Item -Recurse -Force $remotePath }
    New-Item -ItemType Directory -Force -Path $path, $remotePath | Out-Null

    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & git init --bare $remotePath >$null 2>&1
        & git -C $path init -b main >$null 2>&1
        & git -C $path config user.email "demo@procomp.local" >$null 2>&1
        & git -C $path config user.name "procomp-demo" >$null 2>&1
        "# $Name (demo repo)" | Set-Content (Join-Path $path "README.md") -Encoding UTF8
        & git -C $path add README.md >$null 2>&1
        & git -C $path commit -m "Initial demo commit" >$null 2>&1
        & git -C $path remote add origin $remotePath >$null 2>&1
        & git -C $path push -u origin main >$null 2>&1

        if ($Branch -ne "main") {
            & git -C $path checkout -b $Branch >$null 2>&1
        }
        if ($MakeDirty) {
            "// uncommitted local work" | Set-Content (Join-Path $path "WIP.txt") -Encoding UTF8
        }
    } finally {
        $ErrorActionPreference = $prevEap
    }
}

Ensure-DemoRepo -Name "commerce-backend" -Branch "main"
Ensure-DemoRepo -Name "billing-quarkus"  -Branch "feat/invoice-v2" -MakeDirty
Ensure-DemoRepo -Name "web-bff"          -Branch "main"
