Set-StrictMode -Version Latest

function Read-DotEnvFile {
    param([string]$Path)
    $vars = @{}
    if (-not (Test-Path $Path)) { return $vars }

    $lines = @(Get-Content -Path $Path -ErrorAction SilentlyContinue)
    foreach ($line in $lines) {
        if (-not $line) { continue }
        $trimmed = $line.Trim()
        if ($trimmed -eq "" -or $trimmed.StartsWith("#")) { continue }
        $eqIdx = $trimmed.IndexOf("=")
        if ($eqIdx -le 0) { continue }

        $key = $trimmed.Substring(0, $eqIdx).Trim()
        $val = $trimmed.Substring($eqIdx + 1).Trim()
        if (($val.Length -ge 2) -and (($val.StartsWith('"') -and $val.EndsWith('"')) -or ($val.StartsWith("'") -and $val.EndsWith("'")))) {
            $val = $val.Substring(1, $val.Length - 2)
        }
        $vars[$key] = $val
    }
    return $vars
}

function Resolve-ServiceEnvironment {
    param($Svc, [string]$RootDir)
    $merged = @{}

    # 1. Global .env
    $globalEnv = Read-DotEnvFile -Path (Join-Path $RootDir ".env")
    foreach ($k in $globalEnv.Keys) { $merged[$k] = $globalEnv[$k] }

    # 2. Inline env map from services.json
    if ($Svc.PSObject.Properties["Env"] -and $Svc.Env) {
        foreach ($k in $Svc.Env.Keys) { $merged[$k] = [string]$Svc.Env[$k] }
    }

    # 3. Per-service .env.<name>
    $svcEnv = Read-DotEnvFile -Path (Join-Path $RootDir ".env.$($Svc.Name)")
    foreach ($k in $svcEnv.Keys) { $merged[$k] = $svcEnv[$k] }

    if ($merged.Count -gt 0 -and (Get-Command Write-ProcompTraceLog -ErrorAction SilentlyContinue)) {
        $keysStr = (@($merged.Keys) | Sort-Object) -join ","
        Write-ProcompTraceLog -Category "ENV" -ServiceName $Svc.Name -Message "Injecting $($merged.Count) env var(s): $keysStr"
    }
    return $merged
}
