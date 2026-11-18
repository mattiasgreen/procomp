Set-StrictMode -Version Latest

function Get-TypeDefaults {
    param([string]$Type)
    switch ($Type) {
        "activemq"    { return @{ HealthPath = "/api/jolokia";     JvmArgs = "-Xmx256m" } }
        "wiremock"    { return @{ HealthPath = "/__admin/health";  JvmArgs = "-Xmx256m" } }
        "spring-boot" { return @{ HealthPath = "/actuator/health"; JvmArgs = "-Xmx384m" } }
        "exec"        { return @{ HealthPath = "/health";          JvmArgs = "" } }
        default       { return @{ HealthPath = "/health";          JvmArgs = "-Xmx256m" } }
    }
}

function Parse-MaxHeap {
    param([string]$JvmArgs)
    if ($JvmArgs -match '-Xmx(\d+[kmgKMG]?)') {
        return $Matches[1].ToUpper()
    }
    return "-"
}

function Load-RoutingState {
    param([string]$RoutingFile)
    $map = @{}
    if (Test-Path $RoutingFile) {
        try {
            $raw = Get-Content -Path $RoutingFile -Raw | ConvertFrom-Json
            if ($raw) {
                foreach ($prop in $raw.PSObject.Properties) {
                    $map[$prop.Name] = [string]$prop.Value
                }
            }
        } catch {
            # Ignore corrupt routing state and start fresh
        }
    }
    return $map
}

function Save-RoutingState {
    param([hashtable]$RoutingMap, [string]$RoutingFile)
    $dir = Split-Path -Parent $RoutingFile
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    $RoutingMap | ConvertTo-Json | Set-Content -Path $RoutingFile -Encoding UTF8
}

function Load-Services {
    param(
        [string]$ConfigPath,
        [string]$StateDir,
        [string]$LogDir
    )
    if (-not (Test-Path $ConfigPath)) {
        throw "Config file not found: $ConfigPath"
    }

    $routingFile = Join-Path $StateDir "routing.json"
    $routingMap = Load-RoutingState -RoutingFile $routingFile

    $raw = Get-Content -Path $ConfigPath -Raw | ConvertFrom-Json
    $list = @()
    foreach ($item in $raw) {
        $type = if ($item.PSObject.Properties["type"] -and $item.type) { [string]$item.type } else { "spring-boot" }
        $defaults = Get-TypeDefaults -Type $type
        $healthPath = if ($item.PSObject.Properties["healthPath"] -and $item.healthPath) { [string]$item.healthPath } else { $defaults.HealthPath }
        $jvmArgs = if ($item.PSObject.Properties["jvmArgs"] -and $null -ne $item.jvmArgs) { [string]$item.jvmArgs } else { $defaults.JvmArgs }
        $enabled = if ($item.PSObject.Properties["enabled"]) { [bool]$item.enabled } else { $true }
        $delayMs = if ($item.PSObject.Properties["startupDelayMs"] -and $item.startupDelayMs) { [int]$item.startupDelayMs } else { 2500 }
        $logFmt = if ($item.PSObject.Properties["logFormat"] -and $item.logFormat) { [string]$item.logFormat } else { "text" }
        $proxyPath = if ($item.PSObject.Properties["proxyPath"] -and $item.proxyPath) { [string]$item.proxyPath } else { "/$($item.name)" }
        $remoteUrl = if ($item.PSObject.Properties["remoteUrl"] -and $item.remoteUrl) { [string]$item.remoteUrl } else { "" }
        $command = if ($item.PSObject.Properties["command"] -and $item.command) { [string]$item.command } else { "" }

        $routeMode = if ($routingMap.ContainsKey([string]$item.name)) {
            $routingMap[[string]$item.name]
        } elseif (-not $enabled -and $remoteUrl) {
            "REMOTE"
        } else {
            "LOCAL"
        }

        $gitRepo = if ($item.PSObject.Properties["gitrepo"] -and $item.gitrepo) { [string]$item.gitrepo } else { "" }
        $gradleSub = if ($item.PSObject.Properties["gradlesubproject"] -and $item.gradlesubproject) { [string]$item.gradlesubproject } else { "" }

        $envMap = @{}
        if ($item.PSObject.Properties["env"] -and $item.env) {
            foreach ($ep in $item.env.PSObject.Properties) {
                $envMap[$ep.Name] = [string]$ep.Value
            }
        }

        $logMap = @{}
        if ($item.PSObject.Properties["logMapping"] -and $item.logMapping) {
            foreach ($lp in $item.logMapping.PSObject.Properties) {
                $logMap[$lp.Name] = [string]$lp.Value
            }
        }

        $list += [PSCustomObject]@{
            Name             = [string]$item.name
            Type             = $type
            Port             = [int]$item.localport
            GitRepo          = $gitRepo
            GradleSubproj    = $gradleSub
            Command          = $command
            HealthPath       = $healthPath
            JvmArgs          = $jvmArgs
            MaxHeap          = (Parse-MaxHeap -JvmArgs $jvmArgs)
            StartupDelayMs   = $delayMs
            LogFormat        = $logFmt
            LogMapping       = $logMap
            Env              = $envMap
            ProxyPath        = $proxyPath
            RemoteUrl        = $remoteUrl
            RoutingMode      = $routeMode
            Enabled          = $enabled
            PidFile          = (Join-Path $StateDir "$($item.name).pid")
            LogFile          = (Join-Path $LogDir "$($item.name).log")
            ErrFile          = (Join-Path $LogDir "$($item.name).err.log")
            Managed          = $false
            ExternalIde      = $false
            PortConflict     = $false
            Healthy          = $false
            Stopping         = $false
            BuildProc        = $null
            GitSummary       = "-"
            LastHealthAt     = [datetime]::MinValue
            HealthTask       = $null
            CachedPid        = $null
            CachedProc       = $null
            CachedLogStamp   = ""
            CachedLogRawMode = $null
            CachedLogTail    = 0
            CachedLogEntries = @()
            StatusText       = if ($enabled) { "STOPPED" } else { "DISABLED" }
        }
    }
    return $list
}

function Apply-RoutingProfile {
    param(
        $Services,
        [string]$ProfileName,
        [string]$ProfilesPath,
        [string]$RoutingFile
    )
    if (-not $ProfileName) { return }
    if (-not (Test-Path $ProfilesPath)) {
        throw "Profiles file not found: $ProfilesPath"
    }
    $profiles = Get-Content -Path $ProfilesPath -Raw | ConvertFrom-Json
    if (-not $profiles.PSObject.Properties[$ProfileName]) {
        throw "Profile '$ProfileName' not found in $ProfilesPath"
    }

    $prof = $profiles.$ProfileName
    $localSet = @{}
    $remoteSet = @{}
    if ($prof.PSObject.Properties["local"] -and $prof.local) {
        foreach ($n in $prof.local) { $localSet[[string]$n] = $true }
    }
    if ($prof.PSObject.Properties["remote"] -and $prof.remote) {
        foreach ($n in $prof.remote) { $remoteSet[[string]$n] = $true }
    }

    $rMap = @{}
    foreach ($svc in $Services) {
        if ($localSet.ContainsKey($svc.Name)) {
            $svc.RoutingMode = "LOCAL"
            $svc.Enabled = $true
            $svc.StatusText = "STOPPED"
        } elseif ($remoteSet.ContainsKey($svc.Name)) {
            $svc.RoutingMode = "REMOTE"
            $svc.Enabled = $false
            $svc.StatusText = "REMOTE"
        }
        $rMap[$svc.Name] = $svc.RoutingMode
    }
    Save-RoutingState -RoutingMap $rMap -RoutingFile $RoutingFile
}
