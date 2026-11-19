Set-StrictMode -Version Latest

function Expand-Template {
    param([string]$Template, [hashtable]$Vars)
    if (-not $Template) { return "" }
    $result = $Template
    foreach ($k in $Vars.Keys) {
        $val = if ($null -ne $Vars[$k]) { [string]$Vars[$k] } else { "" }
        $result = $result.Replace("{{$k}}", $val)
    }
    return $result
}

function Resolve-ModulePath {
    param([string]$Module)
    if (-not $Module) { return "" }
    return ($Module.TrimStart(':').Replace(':', '\').Replace('/', '\'))
}

function Resolve-Artifact {
    param([string]$BaseDir, [string]$Glob, [string]$Exclude = "")
    if (-not $Glob) { return $null }

    $pattern = $Glob.TrimStart('\', '/')
    $fullPattern = if ([System.IO.Path]::IsPathRooted($pattern)) {
        $pattern
    } elseif ($BaseDir) {
        Join-Path $BaseDir $pattern
    } else {
        $pattern
    }

    $matches = @(Get-ChildItem -Path $fullPattern -File -ErrorAction SilentlyContinue)
    if ($Exclude) {
        $matches = @($matches | Where-Object { $_.Name -notlike $Exclude })
    }
    $newest = $matches | Sort-Object -Property LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($newest) { return $newest.FullName }
    return $null
}

function Parse-MemoryLimit {
    param([string]$Text, [string]$Pattern)
    if (-not $Pattern -or -not $Text) { return "-" }
    if ($Text -match $Pattern) {
        if ($Matches.Count -gt 1) { return $Matches[1].ToUpper() }
        return $Matches[0].ToUpper()
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
        } catch { }
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

function Load-ProcompConfig {
    param(
        [string]$ConfigPath,
        [string]$StateDir,
        [string]$LogDir
    )
    if (-not (Test-Path $ConfigPath)) {
        throw "Config file not found: $ConfigPath"
    }

    $doc = Get-Content -Path $ConfigPath -Raw | ConvertFrom-Json

    $builders = @{}
    if ($doc.PSObject.Properties["builders"] -and $doc.builders) {
        foreach ($bp in $doc.builders.PSObject.Properties) {
            $bCmd = if ($bp.Value.PSObject.Properties["command"]) { [string]$bp.Value.command } else { [string]$bp.Value }
            $builders[$bp.Name] = [PSCustomObject]@{ Name = $bp.Name; Command = $bCmd }
        }
    }

    $runtimes = @{}
    if ($doc.PSObject.Properties["runtimes"] -and $doc.runtimes) {
        foreach ($rp in $doc.runtimes.PSObject.Properties) {
            $r = $rp.Value
            $runtimes[$rp.Name] = [PSCustomObject]@{
                Name             = $rp.Name
                Command          = [string]$r.command
                HealthPath       = if ($r.PSObject.Properties["healthPath"] -and $r.healthPath) { [string]$r.healthPath } else { "" }
                ArtifactGlob     = if ($r.PSObject.Properties["artifactGlob"] -and $r.artifactGlob) { [string]$r.artifactGlob } else { "" }
                ArtifactExclude  = if ($r.PSObject.Properties["artifactExclude"] -and $r.artifactExclude) { [string]$r.artifactExclude } else { "" }
                MemoryArgPattern = if ($r.PSObject.Properties["memoryArgPattern"] -and $r.memoryArgPattern) { [string]$r.memoryArgPattern } else { "" }
            }
        }
    }

    $gateway = $null
    if ($doc.PSObject.Properties["gateway"] -and $doc.gateway) {
        $g = $doc.gateway
        $gateway = [PSCustomObject]@{
            Type        = if ($g.PSObject.Properties["type"] -and $g.type) { [string]$g.type } else { "wiremock" }
            AdminUrl    = if ($g.PSObject.Properties["adminUrl"] -and $g.adminUrl) { ([string]$g.adminUrl).TrimEnd('/') } else { "" }
            ServiceName = if ($g.PSObject.Properties["serviceName"] -and $g.serviceName) { [string]$g.serviceName } else { "" }
        }
    }

    $profiles = @{}
    if ($doc.PSObject.Properties["profiles"] -and $doc.profiles) {
        foreach ($pp in $doc.profiles.PSObject.Properties) {
            $profiles[$pp.Name] = $pp.Value
        }
    }

    $routingFile = Join-Path $StateDir "routing.json"
    $routingMap = Load-RoutingState -RoutingFile $routingFile

    $services = @()
    $rawServices = if ($doc.PSObject.Properties["services"] -and $doc.services) { @($doc.services) } else { @() }
    foreach ($item in $rawServices) {
        $runtimeName = [string]$item.runtime
        if (-not $runtimes.ContainsKey($runtimeName)) {
            throw "Service '$($item.name)' references unknown runtime '$runtimeName'."
        }
        $rt = $runtimes[$runtimeName]

        $builderName = if ($item.PSObject.Properties["builder"] -and $item.builder) { [string]$item.builder } else { "" }
        if ($builderName -and (-not $builders.ContainsKey($builderName))) {
            throw "Service '$($item.name)' references unknown builder '$builderName'."
        }

        $healthPath = if ($item.PSObject.Properties["healthPath"] -and $item.healthPath) { [string]$item.healthPath } else { $rt.HealthPath }
        $enabled = if ($item.PSObject.Properties["enabled"]) { [bool]$item.enabled } else { $true }
        $repo = if ($item.PSObject.Properties["repo"] -and $item.repo) { [string]$item.repo } else { "" }
        $module = if ($item.PSObject.Properties["module"] -and $item.module) { [string]$item.module } else { "" }
        $logFmt = if ($item.PSObject.Properties["logFormat"] -and $item.logFormat) { [string]$item.logFormat } else { "text" }
        $proxyPath = if ($item.PSObject.Properties["proxyPath"] -and $item.proxyPath) { [string]$item.proxyPath } else { "/$($item.name)" }
        $remoteUrl = if ($item.PSObject.Properties["remoteUrl"] -and $item.remoteUrl) { [string]$item.remoteUrl } else { "" }

        $vars = @{}
        if ($item.PSObject.Properties["vars"] -and $item.vars) {
            foreach ($vp in $item.vars.PSObject.Properties) {
                $vars[$vp.Name] = [string]$vp.Value
            }
        }

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

        $routeMode = if ($routingMap.ContainsKey([string]$item.name)) {
            $routingMap[[string]$item.name]
        } elseif (-not $enabled -and $remoteUrl) {
            "REMOTE"
        } else {
            "LOCAL"
        }

        $memSource = ($vars.Values -join " ") + " " + $rt.Command
        $memLimit = Parse-MemoryLimit -Text $memSource -Pattern $rt.MemoryArgPattern

        $services += [PSCustomObject]@{
            Name             = [string]$item.name
            Runtime          = $runtimeName
            RuntimeDef       = $rt
            Builder          = $builderName
            BuilderDef       = if ($builderName) { $builders[$builderName] } else { $null }
            Port             = [int]$item.port
            Repo             = $repo
            Module           = $module
            Vars             = $vars
            HealthPath       = $healthPath
            MemLimit         = $memLimit
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
            CachedLogRawMode = $null
            StatusText       = if ($enabled) { "STOPPED" } else { "DISABLED" }
        }
    }

    return [PSCustomObject]@{
        Builders = $builders
        Runtimes = $runtimes
        Gateway  = $gateway
        Profiles = $profiles
        Services = $services
    }
}

function Build-ServiceTemplateVars {
    param($Svc, [string]$RootDir, [string]$Artifact = "")
    $repoAbs = ""
    if ($Svc.Repo) {
        $repoAbs = if ([System.IO.Path]::IsPathRooted($Svc.Repo)) { $Svc.Repo } else { [System.IO.Path]::GetFullPath((Join-Path $RootDir $Svc.Repo)) }
    }
    $vars = @{
        name       = $Svc.Name
        port       = [string]$Svc.Port
        repo       = $repoAbs
        module     = $Svc.Module
        modulePath = (Resolve-ModulePath -Module $Svc.Module)
        healthPath = $Svc.HealthPath
        logFormat  = $Svc.LogFormat
        artifact   = $Artifact
    }
    if ($Svc.Vars) {
        foreach ($k in $Svc.Vars.Keys) {
            $vars[$k] = [string]$Svc.Vars[$k]
        }
    }
    return $vars
}

function Apply-RoutingProfile {
    param(
        $Config,
        [string]$ProfileName,
        [string]$RoutingFile
    )
    if (-not $ProfileName) { return }
    if (-not $Config.Profiles.ContainsKey($ProfileName)) {
        throw "Profile '$ProfileName' not found in procomp.json"
    }

    $prof = $Config.Profiles[$ProfileName]
    $localSet = @{}
    $remoteSet = @{}
    if ($prof.PSObject.Properties["local"] -and $prof.local) {
        foreach ($n in $prof.local) { $localSet[[string]$n] = $true }
    }
    if ($prof.PSObject.Properties["remote"] -and $prof.remote) {
        foreach ($n in $prof.remote) { $remoteSet[[string]$n] = $true }
    }

    $rMap = @{}
    foreach ($svc in $Config.Services) {
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
