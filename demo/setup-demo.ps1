Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $PSScriptRoot
$LogDir  = Join-Path $RootDir "logs"
$ErrLog  = Join-Path $LogDir "procomp-error.log"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Write-SetupCrashLog {
    param($ErrRecord)
    $msg = @"
=== $(Get-Date -Format "yyyy-MM-dd HH:mm:ss") [setup-demo.ps1] ===
Message: $($ErrRecord.Exception.Message)
Position: $($ErrRecord.InvocationInfo.PositionMessage)
StackTrace:
$($ErrRecord.ScriptStackTrace)

"@
    Add-Content -Path $ErrLog -Value $msg -Encoding UTF8
    Write-Host ""
    Write-Host "[procomp demo setup ERROR] $($ErrRecord.Exception.Message)" -ForegroundColor Red
    Write-Host "Full details written to: $ErrLog" -ForegroundColor Yellow
}

try {
    $DemoRepos = Join-Path $RootDir ".demo_repos"
    $MockDir = Join-Path $RootDir "mock-service"
    $MockJar = Join-Path $MockDir "mock-service.jar"

    if (-not (Test-Path $MockJar)) {
        if (-not (Get-Command "javac" -ErrorAction SilentlyContinue)) {
            throw "javac not found on PATH. A JDK (not just JRE) is required to compile mock-service.jar."
        }
        if (-not (Get-Command "jar" -ErrorAction SilentlyContinue)) {
            throw "jar not found on PATH. A JDK is required to package mock-service.jar."
        }
        Push-Location $MockDir
        try {
            & javac MockService.java
            if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
            & jar cfe mock-service.jar MockService MockService.class
            if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
        } finally {
            Pop-Location
        }
    }

    function Ensure-DemoRepo {
        param([string]$Name, [string]$Branch = "main", [switch]$MakeDirty, [switch]$AddRemoteCommit)
        $path = Join-Path $DemoRepos $Name
        $remotePath = Join-Path (Join-Path $DemoRepos "_remotes") "$Name.git"
        if ((Test-Path (Join-Path $path ".git")) -and (Test-Path $remotePath)) { return }

        if (-not (Get-Command "git" -ErrorAction SilentlyContinue)) {
            throw "git not found on PATH. Git is required for demo mode."
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
            "# $Name (demo repo)" | Set-Content (Join-Path $path "README.md") -Encoding UTF8
            & git -C $path add README.md >$null 2>&1
            & git -C $path commit -m "Initial demo commit" >$null 2>&1
            & git -C $path remote add origin $remotePath >$null 2>&1
            & git -C $path push -u origin main >$null 2>&1

            if ($AddRemoteCommit) {
                $tmpClone = Join-Path $DemoRepos "_tmp_$Name"
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
    }

    Ensure-DemoRepo -Name "commerce-backend" -Branch "main" -AddRemoteCommit
    Ensure-DemoRepo -Name "billing-quarkus"  -Branch "feat/invoice-v2" -MakeDirty
    Ensure-DemoRepo -Name "web-bff"          -Branch "main"
} catch {
    Write-SetupCrashLog -ErrRecord $_
    exit 1
}
