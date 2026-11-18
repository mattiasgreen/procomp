Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $RepoRoot "src\Config.ps1")
. (Join-Path $RepoRoot "src\EnvLoader.ps1")
. (Join-Path $RepoRoot "src\GitGradle.ps1")
. (Join-Path $RepoRoot "src\IdeDetector.ps1")
. (Join-Path $RepoRoot "src\ProcessManager.ps1")
. (Join-Path $RepoRoot "src\HealthMonitor.ps1")
. (Join-Path $RepoRoot "src\WireMockBridge.ps1")
. (Join-Path $RepoRoot "src\LogViewer.ps1")
. (Join-Path $RepoRoot "src\Tui.ps1")

Describe "Config & Routing" {
    It "parses -Xmx heap limits cleanly" {
        Parse-MaxHeap "-Xmx384m -XX:+UseG1GC" | Should Be "384M"
        Parse-MaxHeap "-Xms128m" | Should Be "-"
    }

    It "provides type-specific defaults" {
        (Get-TypeDefaults "spring-boot").HealthPath | Should Be "/actuator/health"
        (Get-TypeDefaults "activemq").HealthPath | Should Be "/api/jolokia"
        (Get-TypeDefaults "wiremock").HealthPath | Should Be "/__admin/health"
    }

    It "round-trips routing.json state" {
        $tmpDir = Join-Path $TestDrive "state"
        $rFile = Join-Path $tmpDir "routing.json"
        Save-RoutingState -RoutingMap @{ "order-service" = "REMOTE"; "auth-service" = "LOCAL" } -RoutingFile $rFile
        $loaded = Load-RoutingState -RoutingFile $rFile
        $loaded["order-service"] | Should Be "REMOTE"
        $loaded["auth-service"] | Should Be "LOCAL"
    }
}

Describe "IdeDetector" {
    It "detects direct and multi-hop IDE ancestors" {
        $map = @{
            100 = [PSCustomObject]@{ Name = "idea64.exe"; ParentProcessId = 10 }
            200 = [PSCustomObject]@{ Name = "cmd.exe";    ParentProcessId = 100 }
            300 = [PSCustomObject]@{ Name = "java.exe";   ParentProcessId = 200 }
            400 = [PSCustomObject]@{ Name = "pwsh.exe";   ParentProcessId = 10 }
            500 = [PSCustomObject]@{ Name = "java.exe";   ParentProcessId = 400 }
        }
        Test-IsIdeDescendant -ProcessId 300 -HierarchyMap $map | Should Be $true
        Test-IsIdeDescendant -ProcessId 500 -HierarchyMap $map | Should Be $false
    }

    It "handles cycles safely without infinite loops" {
        $cyclic = @{
            10 = [PSCustomObject]@{ Name = "foo.exe"; ParentProcessId = 11 }
            11 = [PSCustomObject]@{ Name = "bar.exe"; ParentProcessId = 10 }
        }
        Test-IsIdeDescendant -ProcessId 10 -HierarchyMap $cyclic | Should Be $false
    }
}

Describe "LogViewer" {
    It "normalizes JSON UTC timestamps to local time and detects levels" {
        $utc = "2026-11-18T08:30:00.123Z"
        $expectedLocal = ([DateTimeOffset]::Parse($utc)).LocalDateTime.ToString("yyyy-MM-dd HH:mm:ss.fff")
        $json = "{`" @timestamp`":`"$utc`",`"level`":`"WARN`",`"service`":`"order-service`",`"message`":`"Slow call`"}".Replace(" @timestamp", "@timestamp")
        $entry = Normalize-LogEntry -RawLine $json -DefaultService "order-service" -RawMode $false
        $entry.Level | Should Be "WARN"
        $entry.SortKey | Should Be $expectedLocal
        $entry.Text | Should Match "\[order-service\] --- Slow call"
    }

    It "handles 0-line missing logs without throwing under StrictMode" {
        $svcs = @(
            [PSCustomObject]@{
                Name = "empty-svc"; LogFile = (Join-Path $TestDrive "missing.log"); ErrFile = (Join-Path $TestDrive "missing.err.log")
                StatusText = "DISABLED"; CachedLogStamp = ""; CachedLogRawMode = $null; CachedLogTail = 0; CachedLogEntries = @()
            }
        )
        $entries = @(Get-UnifiedLogs -Services $svcs -SelectedIndex 0 -AllMode $false -ErrorsOnly $false -RawMode $false -MaxRows 10)
        $entries.Count | Should Be 0
    }
}

Describe "TUI DryRun Renderer" {
    It "renders ROUTING, IDE-UP, PORT-CONFLICT, and ConfirmQuit" {
        $svcs = @(
            [PSCustomObject]@{
                Name = "order-service"; Type = "spring-boot"; Port = 8082; MaxHeap = "384M"; LogFormat = "json"
                RoutingMode = "LOCAL"; StatusText = "IDE-UP"; CachedProc = $null
                LogFile = (Join-Path $TestDrive "none1.log"); ErrFile = (Join-Path $TestDrive "none1.err.log")
                CachedLogStamp = ""; CachedLogRawMode = $null; CachedLogTail = 0; CachedLogEntries = @()
            },
            [PSCustomObject]@{
                Name = "auth-service"; Type = "spring-boot"; Port = 8091; MaxHeap = "384M"; LogFormat = "text"
                RoutingMode = "REMOTE"; StatusText = "REMOTE"; CachedProc = $null
                LogFile = (Join-Path $TestDrive "none2.log"); ErrFile = (Join-Path $TestDrive "none2.err.log")
                CachedLogStamp = ""; CachedLogRawMode = $null; CachedLogTail = 0; CachedLogEntries = @()
            }
        )
        $frame = Render-Tui -Services $svcs -SelectedIndex 0 -BannerMsg "ok" -AllLogs $false -ErrorsOnly $false -RawLogs $false -ConfirmQuit $true -DryRun
        $frame | Should Match "ROUTING"
        $frame | Should Match "IDE-UP"
        $frame | Should Match "LOCAL :8082"
        $frame | Should Match "REMOTE"
        $frame | Should Match "\[CONFIRM\]"
    }
}

Describe "EnvLoader" {
    It "merges .env < inline env < .env.<service> in precedence order" {
        $envDir = Join-Path $TestDrive "envtest"
        New-Item -ItemType Directory -Force -Path $envDir | Out-Null
        "DB_HOST=global`nAPI_KEY=`"secret123`"`n# comment" | Set-Content (Join-Path $envDir ".env")
        "API_KEY=override-svc`nSVC_ONLY=yes" | Set-Content (Join-Path $envDir ".env.orders")

        $svc = [PSCustomObject]@{ Name = "orders"; Env = @{ DB_HOST = "inline-host"; INLINE_ONLY = "1" } }
        $resolved = Resolve-ServiceEnvironment -Svc $svc -RootDir $envDir
        $resolved["DB_HOST"] | Should Be "inline-host"
        $resolved["API_KEY"] | Should Be "override-svc"
        $resolved["SVC_ONLY"] | Should Be "yes"
        $resolved["INLINE_ONLY"] | Should Be "1"
    }
}

Describe "GitGradle" {
    It "formats Gradle bootJar task names for root and subprojects" {
        Get-GradleTaskName -GradleSubproj "" | Should Be "bootJar"
        Get-GradleTaskName -GradleSubproj ":services:order-service" | Should Be ":services:order-service:bootJar"
    }

    It "reads Git branch and dirty state for an existing repo" {
        $st = Get-GitRepoStatus -RepoPath $RepoRoot
        $st.Exists | Should Be $true
        $st.Branch | Should Not Be "-"
    }
}

Describe "WireMockBridge & Profiles" {
    It "builds LOCAL and REMOTE WireMock proxy mappings with deterministic IDs" {
        $svc = [PSCustomObject]@{
            Name = "order-service"; Port = 8082; ProxyPath = "/orders"
            RemoteUrl = "https://shared-dev.mycorp/orders"; RoutingMode = "LOCAL"
        }
        $mLocal = Build-WireMockMapping -Svc $svc
        $mLocal.response.proxyBaseUrl | Should Be "http://127.0.0.1:8082"
        $mLocal.response.proxyUrlPrefixToRemove | Should Be "/orders"

        $svc.RoutingMode = "REMOTE"
        $mRemote = Build-WireMockMapping -Svc $svc
        $mRemote.id | Should Be $mLocal.id
        $mRemote.response.proxyBaseUrl | Should Be "https://shared-dev.mycorp/orders"
    }

    It "applies named routing profiles and persists routing.json" {
        $pFile = Join-Path $TestDrive "profiles.json"
        $rFile = Join-Path $TestDrive "routing.json"
        @{ checkout = @{ local = @("order-service"); remote = @("auth-service") } } | ConvertTo-Json -Depth 3 | Set-Content $pFile

        $svcs = @(
            [PSCustomObject]@{ Name = "order-service"; RoutingMode = "REMOTE"; Enabled = $false; StatusText = "REMOTE" },
            [PSCustomObject]@{ Name = "auth-service";  RoutingMode = "LOCAL";  Enabled = $true;  StatusText = "STOPPED" }
        )
        Apply-RoutingProfile -Services $svcs -ProfileName "checkout" -ProfilesPath $pFile -RoutingFile $rFile
        $svcs[0].RoutingMode | Should Be "LOCAL"
        $svcs[0].Enabled | Should Be $true
        $svcs[1].RoutingMode | Should Be "REMOTE"
        $svcs[1].Enabled | Should Be $false

        $saved = Load-RoutingState -RoutingFile $rFile
        $saved["order-service"] | Should Be "LOCAL"
        $saved["auth-service"] | Should Be "REMOTE"
    }
}
