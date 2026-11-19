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
    . (Join-Path $RootDir "mock-service\MockFixtures.ps1")

    $mockJar = Join-Path $RootDir "mock-service\mock-service.jar"
    if (-not (Test-Path $mockJar)) {
        Write-Host "[demo] Compiling mock-service.jar..." -ForegroundColor Cyan
        [void](Ensure-MockServiceJar -RootDir $RootDir)
    }

    $demoRepos = Join-Path $RootDir ".demo_repos"
    if (-not (Test-Path $demoRepos)) {
        Write-Host "[demo] Creating mock Git repos in .demo_repos/..." -ForegroundColor Cyan
    }
    [void](New-MockGitRepo -BaseDir $demoRepos -Name "commerce-backend" -Branch "main" -AddRemoteCommit)
    [void](New-MockGitRepo -BaseDir $demoRepos -Name "billing-quarkus"  -Branch "feat/invoice-v2" -MakeDirty)
    [void](New-MockGitRepo -BaseDir $demoRepos -Name "web-bff"          -Branch "main")
} catch {
    Write-SetupCrashLog -ErrRecord $_
    exit 1
}
