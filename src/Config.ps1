Set-StrictMode -Version Latest

function Set-ProcompDebugLogPath {
    param([string]$Path)
    [System.Environment]::SetEnvironmentVariable("PROCOMP_DEBUG_LOG", $Path, "Process")
}

function Set-ProcompTraceLogPath {
    param([string]$Path)
    [System.Environment]::SetEnvironmentVariable("PROCOMP_TRACE_LOG", $Path, "Process")
}

function Write-ProcompLogLine {
    param(
        [string]$LogPath,
        [string]$Category,
        [string]$Message,
        [string]$WorkingDirectory = "",
        [string]$ServiceName = ""
    )
    if (-not $LogPath) { return }
    try {
        $dir = Split-Path -Parent $LogPath
        if ($dir -and (-not (Test-Path -LiteralPath $dir))) {
            New-Item -ItemType Directory -Force -Path $dir | Out-Null
        }
        $ts = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss.fff")
        $svcPart = if ($ServiceName) { " svc=$ServiceName" } else { "" }
        $cwdPart = if ($WorkingDirectory) { " cwd=$WorkingDirectory" } else { "" }
        $line = "[$ts] [PID=$PID] [$Category]$svcPart$cwdPart $Message" + [Environment]::NewLine

        $mtx = New-Object System.Threading.Mutex($false, "procomp-debug-log-$PID")
        $acquired = $false
        try {
            $acquired = $mtx.WaitOne(2000)
            [System.IO.File]::AppendAllText($LogPath, $line, (New-Object System.Text.UTF8Encoding($false)))
        } finally {
            if ($acquired) { $mtx.ReleaseMutex() }
            $mtx.Dispose()
        }
    } catch { }
}

function Write-ProcompDebugLog {
    param(
        [Parameter(Mandatory = $true)][string]$Category,
        [Parameter(Mandatory = $true)][string]$Message,
        [string]$WorkingDirectory = "",
        [string]$ServiceName = ""
    )
    $dbgPath = [System.Environment]::GetEnvironmentVariable("PROCOMP_DEBUG_LOG", "Process")
    if ($dbgPath) {
        Write-ProcompLogLine -LogPath $dbgPath -Category $Category -Message $Message -WorkingDirectory $WorkingDirectory -ServiceName $ServiceName
    }
    $tracePath = [System.Environment]::GetEnvironmentVariable("PROCOMP_TRACE_LOG", "Process")
    if ($tracePath -and ($tracePath -ne $dbgPath)) {
        Write-ProcompLogLine -LogPath $tracePath -Category $Category -Message $Message -WorkingDirectory $WorkingDirectory -ServiceName $ServiceName
    }
}

function Write-ProcompTraceLog {
    param(
        [Parameter(Mandatory = $true)][string]$Category,
        [Parameter(Mandatory = $true)][string]$Message,
        [string]$WorkingDirectory = "",
        [string]$ServiceName = ""
    )
    $tracePath = [System.Environment]::GetEnvironmentVariable("PROCOMP_TRACE_LOG", "Process")
    if ($tracePath) {
        Write-ProcompLogLine -LogPath $tracePath -Category $Category -Message $Message -WorkingDirectory $WorkingDirectory -ServiceName $ServiceName
    }
}

$script:ProcompActivityCallback = $null

function Set-ProcompActivityCallback {
    param([scriptblock]$Callback)
    $script:ProcompActivityCallback = $Callback
}

function Report-ProcompActivity {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [string]$ServiceName = "",
        [string]$Category = "STEP"
    )
    Write-ProcompTraceLog -Category $Category -ServiceName $ServiceName -Message $Message
    if ($null -ne $script:ProcompActivityCallback) {
        try { & $script:ProcompActivityCallback $Message } catch { }
    }
}

function Invoke-ParallelWork {
    param(
        [array]$Items,
        [scriptblock]$ScriptBlock,
        [int]$MaxThreads = 4,
        [string[]]$InitScripts = @()
    )
    $itemList = @($Items)
    if ($itemList.Count -eq 0) { return @() }
    if ($itemList.Count -eq 1 -or $MaxThreads -le 1) {
        $single = @()
        foreach ($it in $itemList) {
            $single += (& $ScriptBlock $it)
        }
        return $single
    }

    $pool = [RunspaceFactory]::CreateRunspacePool(1, [Math]::Min($MaxThreads, $itemList.Count))
    $pool.Open()
    $jobs = @()
    $fnText = $ScriptBlock.ToString()
    try {
        foreach ($it in $itemList) {
            $ps = [PowerShell]::Create()
            $ps.RunspacePool = $pool
            [void]$ps.AddScript({
                param($scripts, $body, $item)
                Set-StrictMode -Version Latest
                foreach ($s in $scripts) {
                    if ($s -and (Test-Path -LiteralPath $s)) { . $s }
                }
                $sb = [scriptblock]::Create($body)
                & $sb $item
            }).AddArgument($InitScripts).AddArgument($fnText).AddArgument($it)
            $jobs += [PSCustomObject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
        }
        $results = @()
        foreach ($j in $jobs) {
            try {
                $res = $j.PS.EndInvoke($j.Handle)
                if ($null -ne $res) { $results += @($res) }
            } finally {
                $j.PS.Dispose()
            }
        }
        return $results
    } finally {
        $pool.Close()
        $pool.Dispose()
    }
}

function Read-ProcompTraceEntries {
    param([int]$TailCount = 400)
    $path = [System.Environment]::GetEnvironmentVariable("PROCOMP_TRACE_LOG", "Process")
    if (-not $path) {
        $path = [System.Environment]::GetEnvironmentVariable("PROCOMP_DEBUG_LOG", "Process")
    }
    if (-not $path -or (-not (Test-Path -LiteralPath $path))) { return @() }
    $lines = @(Get-Content -LiteralPath $path -Tail $TailCount -ErrorAction SilentlyContinue)
    $entries = @()
    $idx = 0
    foreach ($ln in $lines) {
        if (-not $ln) { continue }
        $idx++
        $lvl = if ($ln -match '\[BG-ERROR\]|\[BUILD-FAIL\]|\[START-ERR\]|\[CMD-ERR\]|\[PORT\]|CRASHED|\[EXIT\].*error=|\[WIREMOCK\].*failed') {
            "ERROR"
        } elseif ($ln -match '\[KILL\]|\[GIT\] git exit=|PORT-CONFLICT|BUILD-FAIL|\[WIREMOCK\].*falling back') {
            "WARN"
        } else {
            "INFO"
        }
        $verb = if ($lvl -eq "ERROR" -or $lvl -eq "WARN") {
            "INFO"
        } elseif ($ln -match '\[SUBPROC\]|\[GIT\].*(rev-parse --abbrev-ref HEAD|status --porcelain|rev-list --left-right)') {
            "TRACE"
        } elseif ($ln -match '\[(ARTIFACT|ENV|IDE|PORT|WIREMOCK|UI|GIT)\]') {
            "DEBUG"
        } else {
            "INFO"
        }
        $entries += [PSCustomObject]@{
            EntryId    = "procomp-$idx"
            SortKey    = $idx.ToString("D6")
            Level      = $lvl
            Verbosity  = $verb
            Text       = $ln
            StackLines = @()
            IsJson     = $false
        }
    }
    return $entries
}

function Resolve-JavaTool {
    param([string]$Tool = "java")
    if ($env:JAVA_HOME) {
        $candidate = Join-Path (Join-Path $env:JAVA_HOME "bin") "$Tool.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }
    $cmd = Get-Command $Tool -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $Tool
}

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

function Read-ProcompJsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Label = "JSON file"
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Label not found: $Path"
    }
    $fullPath = (Resolve-Path -LiteralPath $Path).ProviderPath
    if (Test-Path -LiteralPath $fullPath -PathType Container) {
        throw "$Label is a directory, not a file: $fullPath"
    }
    $raw = [System.IO.File]::ReadAllText($fullPath)
    if ($null -ne $raw) {
        $raw = $raw.TrimStart([char]0xFEFF)
    }
    if ([string]::IsNullOrWhiteSpace($raw)) {
        throw "Failed to parse $Label '$fullPath': file is empty."
    }
    try {
        $parsed = $raw | ConvertFrom-Json
        return ,$parsed
    } catch {
        $preview = ($raw.Trim() -replace '[\r\n]+', ' ')
        if ($preview.Length -gt 120) {
            $preview = $preview.Substring(0, 120) + "..."
        }
        throw "Failed to parse $Label '$fullPath': $($_.Exception.Message) (starts with: '$preview')"
    }
}

function Resolve-ProjectName {
    param([string]$ConfigPath, [string]$ProjectOverride = "")
    if ($ProjectOverride) {
        return ($ProjectOverride -replace '[^a-zA-Z0-9._-]', '_')
    }
    if (Test-Path -LiteralPath $ConfigPath) {
        try {
            $doc = Read-ProcompJsonFile -Path $ConfigPath -Label "config file"
            if ($doc.PSObject.Properties["project"] -and $doc.project) {
                return (([string]$doc.project) -replace '[^a-zA-Z0-9._-]', '_')
            }
        } catch { }
    }
    $leaf = [System.IO.Path]::GetFileNameWithoutExtension($ConfigPath)
    if ($leaf -match '^(.+)\.procomp$') { $leaf = $Matches[1] }
    if (-not $leaf -or $leaf -eq "procomp") { return "default" }
    return ($leaf -replace '[^a-zA-Z0-9._-]', '_')
}

function Resolve-ModulePath {
    param([string]$Module)
    if (-not $Module) { return "" }
    return ($Module.TrimStart(':').Replace(':', '\').Replace('/', '\'))
}

function Get-RepoNameFromUrl {
    param([string]$RepoUrl)
    if (-not $RepoUrl) { return "" }
    $trimmed = $RepoUrl.TrimEnd('/')
    $lastSlash = $trimmed.LastIndexOf('/')
    $seg = if ($lastSlash -ge 0) { $trimmed.Substring($lastSlash + 1) } else { $trimmed }
    if ($seg.EndsWith(".git", [System.StringComparison]::OrdinalIgnoreCase)) {
        $seg = $seg.Substring(0, $seg.Length - 4)
    }
    return $seg
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
    if (Test-Path -LiteralPath $RoutingFile) {
        try {
            $raw = Read-ProcompJsonFile -Path $RoutingFile -Label "routing file"
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
        [string]$LogDir,
        [string]$ReposRootOverride = ""
    )
    $script:LastResolvedAppsFile = ""
    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        throw "Config file not found: $ConfigPath"
    }

    $resolvedConfigPath = (Resolve-Path -LiteralPath $ConfigPath).ProviderPath
    $doc = Read-ProcompJsonFile -Path $resolvedConfigPath -Label "config file"

    $reposRoot = if ($ReposRootOverride) {
        $ReposRootOverride
    } elseif ($doc.PSObject.Properties["reposRoot"] -and $doc.reposRoot) {
        [string]$doc.reposRoot
    } else {
        ""
    }

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
            $rtEnv = @{}
            if ($r.PSObject.Properties["env"] -and $r.env) {
                foreach ($ep in $r.env.PSObject.Properties) {
                    $rtEnv[$ep.Name] = [string]$ep.Value
                }
            }
            $runtimes[$rp.Name] = [PSCustomObject]@{
                Name             = $rp.Name
                Command          = [string]$r.command
                HealthPath       = if ($r.PSObject.Properties["healthPath"] -and $r.healthPath) { [string]$r.healthPath } else { "" }
                ArtifactGlob     = if ($r.PSObject.Properties["artifactGlob"] -and $r.artifactGlob) { [string]$r.artifactGlob } else { "" }
                ArtifactExclude  = if ($r.PSObject.Properties["artifactExclude"] -and $r.artifactExclude) { [string]$r.artifactExclude } else { "" }
                MemoryArgPattern = if ($r.PSObject.Properties["memoryArgPattern"] -and $r.memoryArgPattern) { [string]$r.memoryArgPattern } else { "" }
                WorkingDir       = if ($r.PSObject.Properties["workingDir"] -and $r.workingDir) { [string]$r.workingDir } else { "" }
                Env              = $rtEnv
            }
        }
    }

    $gateway = $null
    if ($doc.PSObject.Properties["gateway"] -and $doc.gateway) {
        $g = $doc.gateway
        $rawAdminUrl = if ($g.PSObject.Properties["adminUrl"] -and $g.adminUrl) { ([string]$g.adminUrl).TrimEnd('/') } else { "" }
        $gwPort = if ($g.PSObject.Properties["port"] -and $g.port) {
            [int]$g.port
        } elseif ($rawAdminUrl) {
            try { ([Uri]::new($rawAdminUrl)).Port } catch { 0 }
        } else {
            0
        }
        $gwAdminUrl = if ($rawAdminUrl) { $rawAdminUrl } elseif ($gwPort -gt 0) { "http://127.0.0.1:$gwPort" } else { "" }
        $gwCmd = if ($g.PSObject.Properties["command"] -and $g.command) { [string]$g.command } else { "" }
        $gwSvcName = if ($g.PSObject.Properties["serviceName"] -and $g.serviceName) { [string]$g.serviceName } elseif ($gwCmd) { "wiremock-gateway" } else { "" }
        $cfgDirForGw = Split-Path -Parent $resolvedConfigPath
        $gwWorkDir = if ($g.PSObject.Properties["workingDir"] -and $g.workingDir) {
            Expand-Template -Template ([string]$g.workingDir) -Vars @{ reposRoot = $reposRoot; root = $cfgDirForGw }
        } else {
            ""
        }
        $gwHealth = if ($g.PSObject.Properties["healthPath"] -and $g.healthPath) { [string]$g.healthPath } else { "/__admin/health" }
        $gateway = [PSCustomObject]@{
            Type        = if ($g.PSObject.Properties["type"] -and $g.type) { [string]$g.type } else { "wiremock" }
            Port        = $gwPort
            AdminUrl    = $gwAdminUrl
            ServiceName = $gwSvcName
            Command     = $gwCmd
            WorkingDir  = $gwWorkDir
            HealthPath  = $gwHealth
        }
    }

    $profiles = @{}
    if ($doc.PSObject.Properties["profiles"] -and $doc.profiles) {
        foreach ($pp in $doc.profiles.PSObject.Properties) {
            $profiles[$pp.Name] = $pp.Value
        }
    }

    $defaults = $null
    if ($doc.PSObject.Properties["defaults"] -and $doc.defaults) {
        $defaults = $doc.defaults
    }

    $routingFile = Join-Path $StateDir "routing.json"
    $routingMap = Load-RoutingState -RoutingFile $routingFile

    $rawApps = @()
    $afPath = ""
    if ($doc.PSObject.Properties["appsFile"] -and $doc.appsFile) {
        $afSpec = Expand-Template -Template ([string]$doc.appsFile) -Vars @{ reposRoot = $reposRoot }
        $cfgDir = Split-Path -Parent $resolvedConfigPath
        $combined = if ([System.IO.Path]::IsPathRooted($afSpec)) { $afSpec } else { [System.IO.Path]::Combine($cfgDir, $afSpec) }
        $afPath = [System.IO.Path]::GetFullPath($combined)
        $script:LastResolvedAppsFile = $afPath
        $afDoc = Read-ProcompJsonFile -Path $afPath -Label "appsFile"
        if ($afDoc -is [System.Array]) {
            $rawApps += @($afDoc)
        } elseif ($afDoc.PSObject.Properties["apps"] -and $afDoc.apps) {
            $rawApps += @($afDoc.apps)
        }
    }
    if ($doc.PSObject.Properties["apps"] -and $doc.apps) {
        $rawApps += @($doc.apps)
    }

    $services = @()
    foreach ($item in $rawApps) {
        $svcName = [string]$item.servicename
        $runtimeName = [string]$item.type
        if (-not $runtimes.ContainsKey($runtimeName)) {
            throw "App '$svcName' references unknown runtime type '$runtimeName'."
        }
        $rt = $runtimes[$runtimeName]

        $builderName = ""
        if ($item.PSObject.Properties["builder"]) {
            $builderName = if ($item.builder) { [string]$item.builder } else { "" }
        } elseif ($defaults -and $defaults.PSObject.Properties["builder"] -and $defaults.builder) {
            $builderName = [string]$defaults.builder
        }
        if ($builderName -and (-not $builders.ContainsKey($builderName))) {
            throw "App '$svcName' references unknown builder '$builderName'."
        }

        $port = [int]$item.localport
        $subproject = if ($item.PSObject.Properties["subproject"] -and $item.subproject) { [string]$item.subproject } else { "" }
        $swaggerUri = if ($item.PSObject.Properties["swaggeruri"] -and $item.swaggeruri) { [string]$item.swaggeruri } else { "" }
        $repoUrl = if ($item.PSObject.Properties["servicerepositoryurl"] -and $item.servicerepositoryurl) { [string]$item.servicerepositoryurl } else { "" }

        $repo = ""
        if ($item.PSObject.Properties["repo"] -and $item.repo) {
            $repo = Expand-Template -Template ([string]$item.repo) -Vars @{ reposRoot = $reposRoot }
        } elseif ($repoUrl) {
            $repoDirName = Get-RepoNameFromUrl -RepoUrl $repoUrl
            $repo = if ($reposRoot) { [System.IO.Path]::Combine($reposRoot, $repoDirName) } else { $repoDirName }
        }

        $tmplVars = @{ reposRoot = $reposRoot; root = (Split-Path -Parent $resolvedConfigPath); repo = $repo; servicename = $svcName; name = $svcName; localport = [string]$port; port = [string]$port; subproject = $subproject; modulePath = (Resolve-ModulePath -Module $subproject); type = $runtimeName }

        $rawWorkDir = if ($item.PSObject.Properties["workingDir"] -and $item.workingDir) {
            [string]$item.workingDir
        } elseif ($defaults -and $defaults.PSObject.Properties["workingDir"] -and $defaults.workingDir) {
            [string]$defaults.workingDir
        } elseif ($rt.PSObject.Properties["WorkingDir"] -and $rt.WorkingDir) {
            [string]$rt.WorkingDir
        } else {
            ""
        }
        $workDir = if ($rawWorkDir) { Expand-Template -Template $rawWorkDir -Vars $tmplVars } else { "" }

        $healthPath = if ($item.PSObject.Properties["healthPath"] -and $item.healthPath) { [string]$item.healthPath } else { $rt.HealthPath }
        $enabled = if ($item.PSObject.Properties["enabled"]) {
            [bool]$item.enabled
        } elseif ($defaults -and $defaults.PSObject.Properties["enabled"]) {
            [bool]$defaults.enabled
        } else {
            $true
        }
        $logFmt = if ($item.PSObject.Properties["logFormat"] -and $item.logFormat) {
            [string]$item.logFormat
        } elseif ($defaults -and $defaults.PSObject.Properties["logFormat"] -and $defaults.logFormat) {
            [string]$defaults.logFormat
        } else {
            "text"
        }
        $proxyPath = if ($item.PSObject.Properties["proxyPath"] -and $item.proxyPath) {
            [string]$item.proxyPath
        } elseif ($defaults -and $defaults.PSObject.Properties["proxyPathTemplate"] -and $defaults.proxyPathTemplate) {
            Expand-Template -Template ([string]$defaults.proxyPathTemplate) -Vars $tmplVars
        } else {
            "/$svcName"
        }
        $remoteUrl = if ($item.PSObject.Properties["remoteUrl"] -and $item.remoteUrl) {
            [string]$item.remoteUrl
        } elseif ($defaults -and $defaults.PSObject.Properties["remoteUrlTemplate"] -and $defaults.remoteUrlTemplate) {
            Expand-Template -Template ([string]$defaults.remoteUrlTemplate) -Vars $tmplVars
        } else {
            ""
        }

        $vars = @{}
        if ($defaults -and $defaults.PSObject.Properties["vars"] -and $defaults.vars) {
            foreach ($vp in $defaults.vars.PSObject.Properties) {
                $vars[$vp.Name] = [string]$vp.Value
            }
        }
        if ($item.PSObject.Properties["vars"] -and $item.vars) {
            foreach ($vp in $item.vars.PSObject.Properties) {
                $vars[$vp.Name] = [string]$vp.Value
            }
        }

        $envVarsCtx = @{}
        foreach ($tk in $tmplVars.Keys) { $envVarsCtx[$tk] = $tmplVars[$tk] }
        foreach ($vk in $vars.Keys) { $envVarsCtx[$vk] = $vars[$vk] }

        $envMap = @{}
        if ($defaults -and $defaults.PSObject.Properties["env"] -and $defaults.env) {
            foreach ($ep in $defaults.env.PSObject.Properties) {
                $envMap[$ep.Name] = Expand-Template -Template ([string]$ep.Value) -Vars $envVarsCtx
            }
        }
        if ($rt.PSObject.Properties["Env"] -and $rt.Env) {
            foreach ($ek in $rt.Env.Keys) {
                $envMap[$ek] = Expand-Template -Template ([string]$rt.Env[$ek]) -Vars $envVarsCtx
            }
        }
        if ($item.PSObject.Properties["env"] -and $item.env) {
            foreach ($ep in $item.env.PSObject.Properties) {
                $envMap[$ep.Name] = Expand-Template -Template ([string]$ep.Value) -Vars $envVarsCtx
            }
        }

        $logMap = @{}
        if ($item.PSObject.Properties["logMapping"] -and $item.logMapping) {
            foreach ($lp in $item.logMapping.PSObject.Properties) {
                $logMap[$lp.Name] = [string]$lp.Value
            }
        }

        $routeMode = if ($routingMap.ContainsKey($svcName)) {
            $routingMap[$svcName]
        } elseif (-not $enabled -and $remoteUrl) {
            "REMOTE"
        } else {
            "LOCAL"
        }

        $memSource = ($vars.Values -join " ") + " " + $rt.Command
        $memLimit = Parse-MemoryLimit -Text $memSource -Pattern $rt.MemoryArgPattern

        $services += [PSCustomObject]@{
            Name                 = $svcName
            Runtime              = $runtimeName
            RuntimeDef           = $rt
            Builder              = $builderName
            BuilderDef           = if ($builderName) { $builders[$builderName] } else { $null }
            Port                 = $port
            Repo                 = $repo
            WorkingDir           = $workDir
            ReposRoot            = $reposRoot
            ServiceRepositoryUrl = $repoUrl
            Module               = $subproject
            SwaggerUri           = $swaggerUri
            Vars                 = $vars
            HealthPath           = $healthPath
            MemLimit             = $memLimit
            LogFormat            = $logFmt
            LogMapping           = $logMap
            Env                  = $envMap
            ProxyPath            = $proxyPath
            RemoteUrl            = $remoteUrl
            RoutingMode          = $routeMode
            Enabled              = $enabled
            PidFile              = (Join-Path $StateDir "$svcName.pid")
            LogFile              = (Join-Path $LogDir "$svcName.log")
            ErrFile              = (Join-Path $LogDir "$svcName.err.log")
            Managed              = $false
            ExternalIde          = $false
            PortConflict         = $false
            Healthy              = $false
            Stopping             = $false
            BuildProc            = $null
            GitSummary           = "-"
            LastHealthAt         = [datetime]::MinValue
            HealthTask           = $null
            CachedPid            = $null
            CachedProc           = $null
            CachedLogRawMode     = $null
            StatusText           = if ($enabled) { "STOPPED" } else { "DISABLED" }
        }
    }

    if ($gateway -and $gateway.Command -and ($gateway.Port -gt 0)) {
        $hasExistingGw = (@($services | Where-Object { $_.Name -eq $gateway.ServiceName }).Count -gt 0)
        if (-not $hasExistingGw) {
            $gwRtDef = [PSCustomObject]@{
                Name             = $gateway.Type
                Command          = $gateway.Command
                HealthPath       = $gateway.HealthPath
                ArtifactGlob     = ""
                ArtifactExclude  = ""
                MemoryArgPattern = "-Xmx(\d+[kmgKMG]?)"
            }
            $gwSvc = [PSCustomObject]@{
                Name                 = $gateway.ServiceName
                Runtime              = $gateway.Type
                RuntimeDef           = $gwRtDef
                Builder              = ""
                BuilderDef           = $null
                Port                 = $gateway.Port
                Repo                 = $gateway.WorkingDir
                WorkingDir           = $gateway.WorkingDir
                ReposRoot            = $reposRoot
                ServiceRepositoryUrl = ""
                Module               = ""
                SwaggerUri           = ""
                Vars                 = @{ port = [string]$gateway.Port; localport = [string]$gateway.Port; adminUrl = $gateway.AdminUrl }
                HealthPath           = $gateway.HealthPath
                MemLimit             = (Parse-MemoryLimit -Text $gateway.Command -Pattern "-Xmx(\d+[kmgKMG]?)")
                LogFormat            = "text"
                LogMapping           = @{}
                Env                  = @{}
                ProxyPath            = ""
                RemoteUrl            = ""
                RoutingMode          = "LOCAL"
                Enabled              = $true
                IsGateway            = $true
                PidFile              = (Join-Path $StateDir "$($gateway.ServiceName).pid")
                LogFile              = (Join-Path $LogDir "$($gateway.ServiceName).log")
                ErrFile              = (Join-Path $LogDir "$($gateway.ServiceName).err.log")
                Managed              = $false
                ExternalIde          = $false
                PortConflict         = $false
                Healthy              = $false
                Stopping             = $false
                BuildProc            = $null
                GitSummary           = "-"
                LastHealthAt         = [datetime]::MinValue
                HealthTask           = $null
                CachedPid            = $null
                CachedProc           = $null
                CachedLogRawMode     = $null
                StatusText           = "STOPPED"
            }
            $services = @($gwSvc) + $services
        }
    }

    $gwUrl = if ($gateway) { $gateway.AdminUrl } else { "" }
    Write-ProcompTraceLog -Category "CONFIG" -Message "Loaded config='$resolvedConfigPath' appsFile='$afPath' reposRoot='$reposRoot' services=$($services.Count) gateway='$gwUrl'"

    return [PSCustomObject]@{
        AppsFile  = $afPath
        ReposRoot = $reposRoot
        Builders  = $builders
        Runtimes  = $runtimes
        Gateway   = $gateway
        Profiles  = $profiles
        Services  = $services
    }
}

function Resolve-ServiceWorkingDir {
    param($Svc, [string]$RootDir)
    $spec = if ($Svc.PSObject.Properties["WorkingDir"] -and $Svc.WorkingDir) {
        [string]$Svc.WorkingDir
    } elseif ($Svc.PSObject.Properties["Repo"] -and $Svc.Repo) {
        [string]$Svc.Repo
    } else {
        ""
    }
    if (-not $spec) { return $RootDir }
    if ([System.IO.Path]::IsPathRooted($spec)) { return $spec }
    return [System.IO.Path]::GetFullPath((Join-Path $RootDir $spec))
}

function Build-ServiceTemplateVars {
    param($Svc, [string]$RootDir, [string]$Artifact = "")
    $repoAbs = ""
    if ($Svc.Repo) {
        $repoAbs = if ([System.IO.Path]::IsPathRooted($Svc.Repo)) { $Svc.Repo } else { [System.IO.Path]::GetFullPath((Join-Path $RootDir $Svc.Repo)) }
    }
    $modPath = Resolve-ModulePath -Module $Svc.Module
    $gradlePrefix = if ($Svc.Module) { ":" + $Svc.Module.TrimStart(':') + ":" } else { "" }
    $swagger = if ($Svc.PSObject.Properties["SwaggerUri"]) { [string]$Svc.SwaggerUri } else { "" }
    $repoUrl = if ($Svc.PSObject.Properties["ServiceRepositoryUrl"]) { [string]$Svc.ServiceRepositoryUrl } else { "" }
    $rRoot   = if ($Svc.PSObject.Properties["ReposRoot"]) { [string]$Svc.ReposRoot } else { "" }

    $vars = @{
        root                 = $RootDir
        reposRoot            = $rRoot
        java                 = (Resolve-JavaTool -Tool "java")
        servicename          = $Svc.Name
        name                 = $Svc.Name
        localport            = [string]$Svc.Port
        port                 = [string]$Svc.Port
        type                 = $Svc.Runtime
        repo                 = $repoAbs
        servicerepositoryurl = $repoUrl
        subproject           = $Svc.Module
        module               = $Svc.Module
        modulePath           = $modPath
        gradleTaskPrefix     = $gradlePrefix
        swaggeruri           = $swagger
        healthPath           = $Svc.HealthPath
        logFormat            = $Svc.LogFormat
        artifact             = $Artifact
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
        throw "Profile '$ProfileName' not found in config."
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
    $remoteAllOthers = $remoteSet.ContainsKey("*")
    foreach ($svc in $Config.Services) {
        if ($Config.Gateway -and $Config.Gateway.ServiceName -and ($svc.Name -eq $Config.Gateway.ServiceName)) {
            $svc.RoutingMode = "LOCAL"
            $svc.Enabled = $true
            $rMap[$svc.Name] = "LOCAL"
            continue
        }
        if ($localSet.ContainsKey($svc.Name)) {
            $svc.RoutingMode = "LOCAL"
            $svc.Enabled = $true
            $svc.StatusText = "STOPPED"
        } elseif ($remoteSet.ContainsKey($svc.Name) -or $remoteAllOthers) {
            $svc.RoutingMode = "REMOTE"
            $svc.Enabled = $false
            $svc.StatusText = "REMOTE"
        }
        $rMap[$svc.Name] = $svc.RoutingMode
    }
    Save-RoutingState -RoutingMap $rMap -RoutingFile $RoutingFile
    Write-ProcompTraceLog -Category "PROFILE" -Message "Applied profile='$ProfileName' to $($Config.Services.Count) services -> '$RoutingFile'"
}

function Get-ServiceSwaggerUrl {
    param($Svc)
    if (-not $Svc -or (-not $Svc.PSObject.Properties["SwaggerUri"]) -or (-not $Svc.SwaggerUri)) {
        return $null
    }
    $path = ([string]$Svc.SwaggerUri).TrimStart('/')
    if (($Svc.RoutingMode -eq "REMOTE") -and $Svc.RemoteUrl) {
        return "$($Svc.RemoteUrl.TrimEnd('/'))/$path"
    }
    return "http://127.0.0.1:$($Svc.Port)/$path"
}
