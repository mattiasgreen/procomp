Set-StrictMode -Version Latest

Describe 'Real-JVM Integration Tests' {
    BeforeAll {
        $script:RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
        . (Join-Path $script:RepoRoot "tests\TestHelpers.ps1")
        . (Join-Path $script:RepoRoot "mock-service\MockFixtures.ps1")
        . (Join-Path $script:RepoRoot "src\Config.ps1")
        . (Join-Path $script:RepoRoot "src\EnvLoader.ps1")
        . (Join-Path $script:RepoRoot "src\BuildRunner.ps1")
        . (Join-Path $script:RepoRoot "src\IdeDetector.ps1")
        . (Join-Path $script:RepoRoot "src\ProcessManager.ps1")
        . (Join-Path $script:RepoRoot "src\HealthMonitor.ps1")
        . (Join-Path $script:RepoRoot "src\WireMockBridge.ps1")
        . (Join-Path $script:RepoRoot "src\LogViewer.ps1")
        . (Join-Path $script:RepoRoot "src\BackgroundWorker.ps1")
        . (Join-Path $script:RepoRoot "src\Tui.ps1")

        $script:stateDir   = Join-Path $TestDrive ".stack_state"
        $script:logDir     = Join-Path $TestDrive "logs"
        $script:cfgPath    = Join-Path $TestDrive "procomp.json"
        $script:httpClient = New-HealthHttpClient -TimeoutMs 400
        $script:services   = @()

        New-Item -ItemType Directory -Force -Path $script:stateDir, $script:logDir | Out-Null

        @{
            runtimes = @{
                "mock-broker" = @{
                    command = "java {{jvmArgs}} -Dservice.name={{servicename}} -Dservice.type=activemq -Dserver.port={{localport}} -Dhealth.path={{healthPath}} -Dstartup.delay.ms={{startupDelayMs}} -Dlog.format={{logFormat}} -jar mock-service\mock-service.jar"
                    healthPath = "/api/jolokia"
                    memoryArgPattern = "-Xmx(\d+[kmgKMG]?)"
                }
                "mock-service" = @{
                    command = "java {{jvmArgs}} -Dservice.name={{servicename}} -Dservice.type=spring-boot -Dserver.port={{localport}} -Dhealth.path={{healthPath}} -Dstartup.delay.ms={{startupDelayMs}} -Dlog.format={{logFormat}} -jar mock-service\mock-service.jar"
                    healthPath = "/actuator/health"
                    memoryArgPattern = "-Xmx(\d+[kmgKMG]?)"
                }
            }
            apps = @(
                @{ servicename = "it-broker"; type = "mock-broker";  localport = "18161"; vars = @{ jvmArgs = "-Xmx128m"; startupDelayMs = "800" };  logFormat = "text"; enabled = $true },
                @{ servicename = "it-orders"; type = "mock-service"; localport = "18181"; vars = @{ jvmArgs = "-Xmx128m"; startupDelayMs = "1000" }; logFormat = "json"; enabled = $true; proxyPath = "/orders"; remoteUrl = "https://shared-dev.mycorp/orders" },
                @{ servicename = "it-unused"; type = "mock-service"; localport = "18182"; vars = @{ jvmArgs = "-Xmx128m"; startupDelayMs = "800" };  logFormat = "text"; enabled = $false }
            )
        } | ConvertTo-Json -Depth 6 | Set-Content -Path $script:cfgPath -Encoding UTF8
    }

    AfterEach {
        foreach ($s in $script:services) {
            Stop-ServiceProcess -Svc $s
        }
        Start-Sleep -Milliseconds 200
    }

    AfterAll {
        if ($script:httpClient) { $script:httpClient.Dispose() }
    }

    It 'boots real JVMs in parallel, reaches HEALTHY, merges JSON+text logs chronologically, and tears down cleanly' {
        $cfg = Load-ProcompConfig -ConfigPath $script:cfgPath -StateDir $script:stateDir -LogDir $script:logDir
        $script:services = @($cfg.Services)
        foreach ($s in $script:services) {
            if ($s.Enabled) { Start-ServiceProcess -Svc $s -RootDir $script:RepoRoot }
        }

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        while ($sw.ElapsedMilliseconds -lt 20000) {
            Refresh-ProcessSnapshot -Services $script:services -DetectIde
            Poll-AsyncHealth -Services $script:services -HttpClient $script:httpClient
            if (($script:services[0].StatusText -eq "HEALTHY") -and ($script:services[1].StatusText -eq "HEALTHY")) { break }
            Start-Sleep -Milliseconds 100
        }

        Assert-Eq $script:services[0].StatusText "HEALTHY"
        Assert-Eq $script:services[1].StatusText "HEALTHY"
        Assert-Eq $script:services[2].StatusText "DISABLED"

        Assert-NotNull $script:services[0].CachedProc
        Assert-Match (Format-RamMb -Proc $script:services[0].CachedProc) "MB$"

        $merged = @(Get-UnifiedLogs -Services $script:services -SelectedIndex 0 -AllMode $true -ErrorsOnly $false -RawMode $false -MaxRows 20)
        Assert-Gt $merged.Count 3
        for ($i = 1; $i -lt $merged.Count; $i++) {
            Assert-Eq ($merged[$i].SortKey -ge $merged[$i - 1].SortKey) $true
        }

        foreach ($s in $script:services) { Stop-ServiceProcess -Svc $s }
        Refresh-ProcessSnapshot -Services $script:services
        Assert-Eq @($script:services | Where-Object { $_.CachedProc -ne $null }).Count 0
    }

    It 'detects PORT-CONFLICT when another java.exe already owns the port' {
        $cfg = Load-ProcompConfig -ConfigPath $script:cfgPath -StateDir $script:stateDir -LogDir $script:logDir
        $script:services = @($cfg.Services)
        Start-ServiceProcess -Svc $script:services[1] -RootDir $script:RepoRoot
        Start-Sleep -Milliseconds 1200

        $colliding = [PSCustomObject]@{
            Name = "it-collider"; Runtime = "mock-service"; RuntimeDef = $script:services[1].RuntimeDef; BuilderDef = $null
            Port = 18181; Repo = ""; Module = ""; Vars = @{ jvmArgs = "-Xmx128m"; startupDelayMs = "500" }; Env = @{}
            HealthPath = "/actuator/health"; MemLimit = "128M"; LogFormat = "text"
            RoutingMode = "LOCAL"; Enabled = $true
            PidFile = (Join-Path $script:stateDir "it-collider.pid")
            LogFile = (Join-Path $script:logDir "it-collider.log")
            ErrFile = (Join-Path $script:logDir "it-collider.err.log")
            Managed = $false; ExternalIde = $false; PortConflict = $false; Healthy = $false
            Stopping = $false; LastHealthAt = [datetime]::MinValue; HealthTask = $null
            CachedPid = $null; CachedProc = $null; StatusText = "STOPPED"
        }

        Assert-Throws { Start-ServiceProcess -Svc $colliding -RootDir $script:RepoRoot }
        Assert-Eq $colliding.StatusText "PORT-CONFLICT"
    }

    It 'syncs WireMock routes over HTTP for LOCAL and REMOTE modes' {
        $cfg = Load-ProcompConfig -ConfigPath $script:cfgPath -StateDir $script:stateDir -LogDir $script:logDir
        $script:services = @($cfg.Services)
        $orders = $script:services[1]

        $captured = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
        $listener = New-Object System.Net.HttpListener
        $listener.Prefixes.Add("http://127.0.0.1:18080/")
        $listener.Start()

        $ps = [PowerShell]::Create()
        [void]$ps.AddScript({
            param($L, $Q)
            for ($n = 0; $n -lt 2; $n++) {
                $ctx = $L.GetContext()
                $r = New-Object System.IO.StreamReader($ctx.Request.InputStream)
                $Q.Enqueue($r.ReadToEnd())
                $r.Dispose()
                $ctx.Response.StatusCode = 200
                $ctx.Response.Close()
            }
        }).AddArgument($listener).AddArgument($captured)
        $handle = $ps.BeginInvoke()

        try {
            $ok1 = Set-ServiceWireMockRoute -Svc $orders -WireMockBaseUrl "http://127.0.0.1:18080"
            Assert-Eq $ok1 $true

            $orders.RoutingMode = "REMOTE"
            $ok2 = Set-ServiceWireMockRoute -Svc $orders -WireMockBaseUrl "http://127.0.0.1:18080"
            Assert-Eq $ok2 $true

            [void]$handle.AsyncWaitHandle.WaitOne(5000)
            $b1 = ""; $b2 = ""
            [void]$captured.TryDequeue([ref]$b1)
            [void]$captured.TryDequeue([ref]$b2)
            Assert-Match $b1 "127\.0\.0\.1:18181"
            Assert-Match $b2 "https://shared-dev\.mycorp/orders"
        } finally {
            $listener.Stop()
            $listener.Close()
            $ps.Dispose()
        }
    }

    It 'runs BackgroundMonitor Runspace asynchronously and populates ServiceMetrics and LogBuffers' {
        $cfg = Load-ProcompConfig -ConfigPath $script:cfgPath -StateDir $script:stateDir -LogDir $script:logDir
        $script:services = @($cfg.Services)
        Start-ServiceProcess -Svc $script:services[1] -RootDir $script:RepoRoot

        $shared = New-SharedTuiState -RootDir $script:RepoRoot -ConfigPath $script:cfgPath -StateDir $script:stateDir -LogDir $script:logDir
        $worker = Start-BackgroundMonitor -SharedState $shared
        try {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            while ($sw.ElapsedMilliseconds -lt 20000) {
                if ($shared.SnapshotVersion -gt 0) {
                    Apply-SharedSnapshot -Services $script:services -SharedState $shared
                    if ($script:services[1].StatusText -eq "HEALTHY") { break }
                }
                Start-Sleep -Milliseconds 100
            }

            Assert-Eq $script:services[1].StatusText "HEALTHY"
            Assert-Match $script:services[1].UiRamUsed "MB$"
            $memLogs = @(Get-UnifiedLogsFromMemory -Services $script:services -LogBuffers $shared.LogBuffers -SelectedIndex 1 -AllMode $false -ErrorsOnly $false -MaxRows 10)
            Assert-Gt $memLogs.Count 0
        } finally {
            Stop-BackgroundMonitor -Worker $worker
        }
    }

    It 'boots local service to HEALTHY quickly with 46 services even with 3s simulated Git latency' {
        $jar = Join-Path $script:RepoRoot "mock-service\mock-service.jar"
        $td = (Resolve-Path $TestDrive).ProviderPath
        $scaleCfgPath = Join-Path $td "scale46.procomp.json"
        $scaleState = Join-Path $td "scale46-st"
        $scaleLog = Join-Path $td "scale46-lg"
        New-Item -ItemType Directory -Force -Path $scaleState, $scaleLog, (Join-Path $td "svc-01") | Out-Null

        $apps = @(
            @{ servicename = "svc-01"; localport = "18201"; type = "java"; enabled = $true; servicerepositoryurl = "ssh://git@scm/svc-01.git" }
        )
        for ($i = 2; $i -le 46; $i++) {
            $num = $i.ToString("D2")
            $apps += @{ servicename = "svc-$num"; localport = [string](18200 + $i); type = "java"; enabled = $false; remoteUrl = "https://dev.corp/svc-$num"; servicerepositoryurl = "ssh://git@scm/svc-$num.git" }
        }
        @{
            reposRoot = $td
            runtimes  = @{
                java = @{
                    command    = "java -Xmx64m -Dservice.name={{servicename}} -Dserver.port={{localport}} -Dhealth.path=/actuator/health -Dstartup.delay.ms=400 -Dlog.format=text -jar `"$jar`""
                    healthPath = "/actuator/health"
                }
            }
            apps      = $apps
        } | ConvertTo-Json -Depth 5 | Set-Content -Path $scaleCfgPath -Encoding UTF8

        $cfg46 = Load-ProcompConfig -ConfigPath $scaleCfgPath -StateDir $scaleState -LogDir $scaleLog
        $svcs46 = @($cfg46.Services)
        Assert-Eq $svcs46.Count 46

        $prevDelay = [System.Environment]::GetEnvironmentVariable("PROCOMP_MOCK_GIT_DELAY_MS", "Process")
        $shared46 = New-SharedTuiState -RootDir $script:RepoRoot -ConfigPath $scaleCfgPath -StateDir $scaleState -LogDir $scaleLog
        $shared46.CommandJobQueue.Enqueue([PSCustomObject]@{ Action = "Startup"; ServiceName = "" })
        $worker46 = $null
        try {
            [System.Environment]::SetEnvironmentVariable("PROCOMP_MOCK_GIT_DELAY_MS", "3000", "Process")
            $worker46 = Start-BackgroundMonitor -SharedState $shared46
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            while ($sw.ElapsedMilliseconds -lt 10000) {
                if ($shared46.SnapshotVersion -gt 0) {
                    Apply-SharedSnapshot -Services $svcs46 -SharedState $shared46
                    if ($svcs46[0].StatusText -eq "HEALTHY") { break }
                }
                Start-Sleep -Milliseconds 100
            }
            $script:services = $svcs46
            Assert-Eq $svcs46[0].StatusText "HEALTHY" " (err=$($shared46.LastError) banner=$($shared46.BannerNotification) snap=$($shared46.SnapshotVersion))"
            Assert-Eq $svcs46[45].StatusText "REMOTE"

            $frame46 = Render-Tui -Services $svcs46 -SelectedIndex 0 -BannerMsg "Ready" -AllLogs $false -ErrorsOnly $false -RawLogs $false -ConfirmQuit $false -DryRun
            Assert-Match $frame46 "Services: 46"
        } finally {
            [System.Environment]::SetEnvironmentVariable("PROCOMP_MOCK_GIT_DELAY_MS", $prevDelay, "Process")
            Stop-BackgroundMonitor -Worker $worker46
        }
    }

    It 'integrates with a real WireMock JVM: gateway.command launch, clear+import sync, HTTP proxying, and Journal/Mappings viewer' {
        $wmJar = Ensure-WireMockJar -RootDir $script:RepoRoot
        $mockJar = Join-Path $script:RepoRoot "mock-service\mock-service.jar"
        $td = (Resolve-Path $TestDrive).ProviderPath
        $wmCfgPath = Join-Path $td "real-wm.procomp.json"
        $wmState = Join-Path $td "wm-st"
        $wmLog = Join-Path $td "wm-lg"
        New-Item -ItemType Directory -Force -Path $wmState, $wmLog | Out-Null

        @{
            runtimes = @{
                java = @{
                    command    = "java -Xmx64m -Dservice.name={{servicename}} -Dserver.port={{localport}} -Dhealth.path=/actuator/health -Dstartup.delay.ms=200 -Dlog.format=text -jar `"$mockJar`""
                    healthPath = "/actuator/health"
                }
            }
            gateway  = @{
                type       = "wiremock"
                port       = 18089
                command    = "java -Xmx128m -jar `"$wmJar`" --port {{port}}"
                workingDir = $td
                healthPath = "/__admin/health"
            }
            apps     = @(
                @{ servicename = "orders"; localport = "18205"; type = "java"; proxyPath = "/orders"; remoteUrl = "http://127.0.0.1:18206"; enabled = $true }
                @{ servicename = "remote-svc"; localport = "18207"; type = "java"; proxyPath = "/remote"; remoteUrl = "https://example.invalid/remote"; enabled = $false }
            )
        } | ConvertTo-Json -Depth 5 | Set-Content -Path $wmCfgPath -Encoding UTF8

        $cfgWm = Load-ProcompConfig -ConfigPath $wmCfgPath -StateDir $wmState -LogDir $wmLog
        $svcsWm = @($cfgWm.Services)
        $script:services = $svcsWm

        Assert-Eq $svcsWm[0].Name "wiremock-gateway"
        Assert-Eq $svcsWm[0].Port 18089

        Start-ServiceProcess -Svc $svcsWm[0] -RootDir $td
        Start-ServiceProcess -Svc $svcsWm[1] -RootDir $td

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        while ($sw.ElapsedMilliseconds -lt 20000) {
            Refresh-ProcessSnapshot -Services $svcsWm -DetectIde
            Poll-AsyncHealth -Services $svcsWm -HttpClient $script:httpClient
            if (($svcsWm[0].StatusText -eq "HEALTHY") -and ($svcsWm[1].StatusText -eq "HEALTHY")) { break }
            Start-Sleep -Milliseconds 150
        }
        Assert-Eq $svcsWm[0].StatusText "HEALTHY"
        Assert-Eq $svcsWm[1].StatusText "HEALTHY"

        $syncedCount = Sync-WireMockMappings -Services $svcsWm -WireMockBaseUrl $cfgWm.Gateway.AdminUrl -GatewayServiceName $cfgWm.Gateway.ServiceName
        Assert-Eq $syncedCount 2

        # Proxy a real HTTP request through WireMock -> orders mock-service
        $proxied = Invoke-RestMethod -Uri "http://127.0.0.1:18089/orders/actuator/health" -Method Get -TimeoutSec 3
        Assert-Eq $proxied.status "UP"

        # Verify Mappings and Journal viewer entries from real WireMock
        $mapEntries = @(Get-WireMockViewerEntries -WireMockBaseUrl $cfgWm.Gateway.AdminUrl -SubMode "MAPPINGS")
        Assert-Eq $mapEntries.Count 2
        Assert-Match ($mapEntries | ForEach-Object { $_.Text } | Out-String) "procomp-orders"

        $jrnEntries = @(Get-WireMockViewerEntries -WireMockBaseUrl $cfgWm.Gateway.AdminUrl -SubMode "JOURNAL")
        Assert-Gt $jrnEntries.Count 0
        Assert-Match ($jrnEntries | ForEach-Object { $_.Text } | Out-String) "/orders/actuator/health => 200"

        # Toggle orders to REMOTE and verify Set-ServiceWireMockRoute updates real WireMock
        $svcsWm[1].RoutingMode = "REMOTE"
        $okToggle = Set-ServiceWireMockRoute -Svc $svcsWm[1] -WireMockBaseUrl $cfgWm.Gateway.AdminUrl -GatewayServiceName $cfgWm.Gateway.ServiceName
        Assert-Eq $okToggle $true
        $mapAfter = @(Get-WireMockViewerEntries -WireMockBaseUrl $cfgWm.Gateway.AdminUrl -SubMode "MAPPINGS")
        Assert-Match ($mapAfter | ForEach-Object { $_.Text } | Out-String) "127\.0\.0\.1:18206"
    }
}
