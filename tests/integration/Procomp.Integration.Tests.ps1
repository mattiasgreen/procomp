Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $RepoRoot "src\Config.ps1")
. (Join-Path $RepoRoot "src\IdeDetector.ps1")
. (Join-Path $RepoRoot "src\ProcessManager.ps1")
. (Join-Path $RepoRoot "src\HealthMonitor.ps1")
. (Join-Path $RepoRoot "src\LogViewer.ps1")
. (Join-Path $RepoRoot "src\Tui.ps1")

Describe "Real-JVM Integration Tests" {
    $mockJar = Join-Path $RepoRoot "mock-service\mock-service.jar"
    $stateDir = Join-Path $TestDrive ".stack_state"
    $logDir   = Join-Path $TestDrive "logs"
    $cfgPath  = Join-Path $TestDrive "services.json"
    New-Item -ItemType Directory -Force -Path $stateDir, $logDir | Out-Null

    @(
        @{ name = "it-broker"; type = "activemq";    localport = 18161; healthPath = "/api/jolokia";     jvmArgs = "-Xmx128m"; startupDelayMs = 800;  logFormat = "text"; enabled = $true },
        @{ name = "it-orders"; type = "spring-boot"; localport = 18181; healthPath = "/actuator/health"; jvmArgs = "-Xmx128m"; startupDelayMs = 1000; logFormat = "json"; enabled = $true },
        @{ name = "it-unused"; type = "spring-boot"; localport = 18182; healthPath = "/actuator/health"; jvmArgs = "-Xmx128m"; startupDelayMs = 800;  logFormat = "text"; enabled = $false }
    ) | ConvertTo-Json | Set-Content -Path $cfgPath -Encoding UTF8

    $httpClient = New-HealthHttpClient -TimeoutMs 400
    $services = @()

    AfterEach {
        foreach ($s in $services) {
            Stop-ServiceProcess -Svc $s
        }
        Start-Sleep -Milliseconds 200
    }

    It "boots real JVMs in parallel, reaches HEALTHY, merges JSON+text logs chronologically, and tears down cleanly" {
        $services = @(Load-Services -ConfigPath $cfgPath -StateDir $stateDir -LogDir $logDir)
        foreach ($s in $services) {
            if ($s.Enabled) { Start-ServiceProcess -Svc $s -MockJarPath $mockJar }
        }

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        while ($sw.ElapsedMilliseconds -lt 6000) {
            Refresh-ProcessSnapshot -Services $services -DetectIde
            Poll-AsyncHealth -Services $services -HttpClient $httpClient
            if (($services[0].StatusText -eq "HEALTHY") -and ($services[1].StatusText -eq "HEALTHY")) { break }
            Start-Sleep -Milliseconds 100
        }

        $services[0].StatusText | Should Be "HEALTHY"
        $services[1].StatusText | Should Be "HEALTHY"
        $services[2].StatusText | Should Be "DISABLED"

        # Verify RAM and PID are populated from real java.exe
        $services[0].CachedProc | Should Not BeNullOrEmpty
        (Format-RamMb -Proc $services[0].CachedProc) | Should Match "MB$"

        # Verify JSON (UTC) + plain-text (local) unified log sort
        $merged = @(Get-UnifiedLogs -Services $services -SelectedIndex 0 -AllMode $true -ErrorsOnly $false -RawMode $false -MaxRows 20)
        $merged.Count | Should BeGreaterThan 3
        for ($i = 1; $i -lt $merged.Count; $i++) {
            ($merged[$i].SortKey -ge $merged[$i - 1].SortKey) | Should Be $true
        }

        # Clean teardown
        foreach ($s in $services) { Stop-ServiceProcess -Svc $s }
        Refresh-ProcessSnapshot -Services $services
        @($services | Where-Object { $_.CachedProc -ne $null }).Count | Should Be 0
    }

    It "detects PORT-CONFLICT when another java.exe already owns the port" {
        $services = @(Load-Services -ConfigPath $cfgPath -StateDir $stateDir -LogDir $logDir)
        # Start it-orders on 18181
        Start-ServiceProcess -Svc $services[1] -MockJarPath $mockJar
        Start-Sleep -Milliseconds 1200

        # Simulate another service config targeting the same port 18181 without a .pid file
        $colliding = [PSCustomObject]@{
            Name = "it-collider"; Type = "spring-boot"; Port = 18181; HealthPath = "/actuator/health"
            JvmArgs = "-Xmx128m"; MaxHeap = "128M"; StartupDelayMs = 500; LogFormat = "text"
            RoutingMode = "LOCAL"; Enabled = $true
            PidFile = (Join-Path $stateDir "it-collider.pid")
            LogFile = (Join-Path $logDir "it-collider.log")
            ErrFile = (Join-Path $logDir "it-collider.err.log")
            Managed = $false; ExternalIde = $false; PortConflict = $false; Healthy = $false
            Stopping = $false; LastHealthAt = [datetime]::MinValue; HealthTask = $null
            CachedPid = $null; CachedProc = $null; StatusText = "STOPPED"
        }

        { Start-ServiceProcess -Svc $colliding -MockJarPath $mockJar } | Should Throw
        $colliding.StatusText | Should Be "PORT-CONFLICT"
    }
}
