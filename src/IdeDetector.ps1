Set-StrictMode -Version Latest

$script:IdeProcessNames = @("idea64", "idea", "eclipse", "eclipsec", "code", "devenv", "studio64")

function Get-ListeningPidByPort {
    param([int]$Port)
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
