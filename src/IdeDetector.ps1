Set-StrictMode -Version Latest

$script:IdeProcessNames = @("idea64", "idea", "eclipse", "eclipsec", "code", "devenv", "studio64")

function Get-ActiveListeningPorts {
    $set = @{}
    try {
        $props = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties()
        foreach ($ep in $props.GetActiveTcpListeners()) {
            $set[$ep.Port] = $true
        }
    } catch { }
    return $set
}

function Get-ListeningPortMap {
    param([int[]]$TargetPorts = @())
    $map = @{}
    $active = Get-ActiveListeningPorts
    if ($TargetPorts.Count -gt 0) {
        $anyActive = $false
        foreach ($tp in $TargetPorts) {
            if ($active.ContainsKey($tp)) { $anyActive = $true; break }
        }
        if (-not $anyActive) { return $map }
    }
    try {
        $conns = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue)
        foreach ($c in $conns) {
            if ($c.OwningProcess -gt 0 -and -not $map.ContainsKey([int]$c.LocalPort)) {
                $map[[int]$c.LocalPort] = [int]$c.OwningProcess
            }
        }
    } catch { }
    return $map
}

function Get-ListeningPidByPort {
    param([int]$Port)
    $active = Get-ActiveListeningPorts
    if (-not $active.ContainsKey($Port)) { return $null }
    try {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($conn -and $conn.OwningProcess -gt 0) {
            return [int]$conn.OwningProcess
        }
    } catch { }
    return $null
}

function Get-ProcessHierarchyMap {
    $map = @{}
    try {
        $items = Get-CimInstance -ClassName Win32_Process -Property ProcessId, ParentProcessId, Name -ErrorAction SilentlyContinue
        foreach ($p in $items) {
            $map[[int]$p.ProcessId] = [PSCustomObject]@{
                Name            = [string]$p.Name
                ParentProcessId = [int]$p.ParentProcessId
            }
        }
    } catch { }
    return $map
}

function Test-IsIdeDescendant {
    param(
        [int]$ProcessId,
        [hashtable]$HierarchyMap
    )
    $visited = @{}
    $curr = $ProcessId
    for ($depth = 0; $depth -lt 12; $depth++) {
        if ($curr -le 4 -or $visited.ContainsKey($curr)) { break }
        $visited[$curr] = $true
        if (-not $HierarchyMap.ContainsKey($curr)) { break }

        $info = $HierarchyMap[$curr]
        $baseName = ([System.IO.Path]::GetFileNameWithoutExtension($info.Name)).ToLowerInvariant()
        if ($script:IdeProcessNames -contains $baseName) {
            return $true
        }
        $curr = $info.ParentProcessId
    }
    return $false
}
