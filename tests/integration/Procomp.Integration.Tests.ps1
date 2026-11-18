Set-StrictMode -Version Latest

Describe 'Real-JVM Integration Tests' {
    BeforeAll {
        $script:RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
        . (Join-Path $script:RepoRoot "src\Config.ps1")
        . (Join-Path $script:RepoRoot "src\EnvLoader.ps1")
        . (Join-Path $script:RepoRoot "src\GitGradle.ps1")
        . (Join-Path $script:RepoRoot "src\IdeDetector.ps1")
        . (Join-Path $script:RepoRoot "src\ProcessManager.ps1")
        . (Join-Path $script:RepoRoot "src\HealthMonitor.ps1")
        . (Join-Path $script:RepoRoot "src\WireMockBridge.ps1")
        . (Join-Path $script:RepoRoot "src\LogViewer.ps1")
        . (Join-Path $script:RepoRoot "src\Tui.ps1")

        $script:mockJar    = Join-Path $script:RepoRoot "mock-service\mock-service.jar"
        $script:stateDir   = Join-Path $TestDrive ".stack_state"
        $script:logDir     = Join-Path $TestDrive "logs"
        $script:cfgPath    = Join-Path $TestDrive "services.json"
        $script:httpClient = New-HealthHttpClient -TimeoutMs 400
        $script:services   = @()

        New-Item -ItemType Directory -Force -Path $script:stateDir, $script:logDir | Out-Null

        @(
            @{ name = "it-broker"; type = "activemq";    localport = 18161; healthPath = "/api/jolokia";     jvmArgs = "-Xmx128m"; startupDelayMs = 800;  logFormat = "text"; enabled = $true },
            @{ name = "it-orders"; type = "spring-boot"; localport = 18181; healthPath = "/actuator/health"; jvmArgs = "-Xmx128m"; startupDelayMs = 1000; logFormat = "json"; enabled = $true; proxyPath = "/orders"; remoteUrl = "https://shared-dev.mycorp/orders" },
            @{ name = "it-unused"; type = "spring-boot"; localport = 18182; healthPath = "/actuator/health"; jvmArgs = "-Xmx128m"; startupDelayMs = 800;  logFormat = "text"; enabled = $false }
        ) | ConvertTo-Json | Set-Content -Path $script:cfgPath -Encoding UTF8
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
        $script:services = @(Load-Services -ConfigPath $script:cfgPath -StateDir $script:stateDir -LogDir $script:logDir)
        foreach ($s in $script:services) {
            if ($s.Enabled) { Start-ServiceProcess -Svc $s -MockJarPath $script:mockJar -RootDir $script:RepoRoot }
        }

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        while ($sw.ElapsedMilliseconds -lt 6000) {
            Refresh-ProcessSnapshot -Services $script:services -DetectIde
            Poll-AsyncHealth -Services $script:services -HttpClient $script:httpClient
            if (($script:services[0].StatusText -eq "HEALTHY") -and ($script:services[1].StatusText -eq "HEALTHY")) { break }
            Start-Sleep -Milliseconds 100
        }

        $script:services[0].StatusText | Should Be "HEALTHY"
        $script:services[1].StatusText | Should Be "HEALTHY"
        $script:services[2].StatusText | Should Be "DISABLED"

        $script:services[0].CachedProc | Should Not BeNullOrEmpty
        (Format-RamMb -Proc $script:services[0].CachedProc) | Should Match "MB$"

        $merged = @(Get-UnifiedLogs -Services $script:services -SelectedIndex 0 -AllMode $true -ErrorsOnly $false -RawMode $false -MaxRows 20)
        $merged.Count | Should BeGreaterThan 3
        for ($i = 1; $i -lt $merged.Count; $i++) {
            ($merged[$i].SortKey -ge $merged[$i - 1].SortKey) | Should Be $true
        }

        foreach ($s in $script:services) { Stop-ServiceProcess -Svc $s }
        Refresh-ProcessSnapshot -Services $script:services
        @($script:services | Where-Object { $_.CachedProc -ne $null }).Count | Should Be 0
    }

    It 'detects PORT-CONFLICT when another java.exe already owns the port' {
        $script:services = @(Load-Services -ConfigPath $script:cfgPath -StateDir $script:stateDir -LogDir $script:logDir)
        Start-ServiceProcess -Svc $script:services[1] -MockJarPath $script:mockJar -RootDir $script:RepoRoot
        Start-Sleep -Milliseconds 1200

        $colliding = [PSCustomObject]@{
            Name = "it-collider"; Type = "spring-boot"; Port = 18181; HealthPath = "/actuator/health"
            JvmArgs = "-Xmx128m"; MaxHeap = "128M"; StartupDelayMs = 500; LogFormat = "text"
            RoutingMode = "LOCAL"; Enabled = $true
            PidFile = (Join-Path $script:stateDir "it-collider.pid")
            LogFile = (Join-Path $script:logDir "it-collider.log")
            ErrFile = (Join-Path $script:logDir "it-collider.err.log")
            Managed = $false; ExternalIde = $false; PortConflict = $false; Healthy = $false
            Stopping = $false; LastHealthAt = [datetime]::MinValue; HealthTask = $null
            CachedPid = $null; CachedProc = $null; StatusText = "STOPPED"
        }

        { Start-ServiceProcess -Svc $colliding -MockJarPath $script:mockJar -RootDir $script:RepoRoot } | Should Throw
        $colliding.StatusText | Should Be "PORT-CONFLICT"
    }

    It 'syncs WireMock routes over HTTP for LOCAL and REMOTE modes' {
        $script:services = @(Load-Services -ConfigPath $script:cfgPath -StateDir $script:stateDir -LogDir $script:logDir)
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
            $ok1 | Should Be $true

            $orders.RoutingMode = "REMOTE"
            $ok2 = Set-ServiceWireMockRoute -Svc $orders -WireMockBaseUrl "http://127.0.0.1:18080"
            $ok2 | Should Be $true

            $ps.EndInvoke($handle)
            $b1 = ""; $b2 = ""
            [void]$captured.TryDequeue([ref]$b1)
            [void]$captured.TryDequeue([ref]$b2)
            $b1 | Should Match "127\.0\.0\.1:18181"
            $b2 | Should Match "https://shared-dev\.mycorp/orders"
        } finally {
            $ps.Dispose()
            $listener.Stop()
            $listener.Close()
        }
    }
}
