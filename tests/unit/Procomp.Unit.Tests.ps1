Set-StrictMode -Version Latest

Describe 'procomp Unit Tests' {
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
    }

    Context 'Config & Routing' {
        It 'parses -Xmx heap limits cleanly' {
            Parse-MaxHeap "-Xmx384m -XX:+UseG1GC" | Should Be "384M"
            Parse-MaxHeap "-Xms128m" | Should Be "-"
        }

        It 'provides type-specific defaults' {
            (Get-TypeDefaults "spring-boot").HealthPath | Should Be "/actuator/health"
            (Get-TypeDefaults "activemq").HealthPath | Should Be "/api/jolokia"
            (Get-TypeDefaults "wiremock").HealthPath | Should Be "/__admin/health"
        }

        It 'round-trips routing.json state' {
            $tmpDir = Join-Path $TestDrive "state"
            $rFile = Join-Path $tmpDir "routing.json"
            Save-RoutingState -RoutingMap @{ "order-service" = "REMOTE"; "auth-service" = "LOCAL" } -RoutingFile $rFile
            $loaded = Load-RoutingState -RoutingFile $rFile
            $loaded["order-service"] | Should Be "REMOTE"
            $loaded["auth-service"] | Should Be "LOCAL"
        }
    }

    Context 'IdeDetector' {
        It 'detects direct and multi-hop IDE ancestors' {
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

        It 'handles cycles safely without infinite loops' {
            $cyclic = @{
                10 = [PSCustomObject]@{ Name = "foo.exe"; ParentProcessId = 11 }
                11 = [PSCustomObject]@{ Name = "bar.exe"; ParentProcessId = 10 }
            }
            Test-IsIdeDescendant -ProcessId 10 -HierarchyMap $cyclic | Should Be $false
        }
    }

    Context 'LogViewer' {
        It 'normalizes JSON UTC timestamps to local time and detects levels' {
            $utc = "2026-11-18T08:30:00.123Z"
            $expectedLocal = ([DateTimeOffset]::Parse($utc)).LocalDateTime.ToString("yyyy-MM-dd HH:mm:ss.fff")
            $json = "{`" @timestamp`":`"$utc`",`"level`":`"WARN`",`"service`":`"order-service`",`"message`":`"Slow call`"}".Replace(" @timestamp", "@timestamp")
            $entry = Normalize-LogEntry -RawLine $json -DefaultService "order-service" -RawMode $false
            $entry.Level | Should Be "WARN"
            $entry.SortKey | Should Be $expectedLocal
            $entry.Text | Should Match "\[order-service\] --- Slow call"
        }

        It 'handles 0-line missing logs without throwing under StrictMode' {
            $svcs = @(
                [PSCustomObject]@{
                    Name = "empty-svc"; LogFile = (Join-Path $TestDrive "missing.log"); ErrFile = (Join-Path $TestDrive "missing.err.log")
                    StatusText = "DISABLED"; CachedLogStamp = ""; CachedLogRawMode = $null; CachedLogTail = 0; CachedLogEntries = @()
                }
            )
            $entries = @(Get-UnifiedLogs -Services $svcs -SelectedIndex 0 -AllMode $false -ErrorsOnly $false -RawMode $false -MaxRows 10)
            $entries.Count | Should Be 0
        }

        It 'supports custom logMapping, incremental byte-offset tailing, stack folding, and traceId filter' {
            $logPath = Join-Path $TestDrive "custom.log"
            $customJson = '{"ts":"2026-11-18T08:00:00.000Z","lvl":"ERROR","event":"Boom","reqId":"tr-999","stack_trace":"java.lang.RuntimeException\n\tat com.foo.Bar(Bar.java:10)\n\tat com.foo.Baz(Baz.java:20)"}'
            Set-Content -Path $logPath -Value $customJson -Encoding UTF8

            $svc = [PSCustomObject]@{
                Name = "custom-svc"; LogFile = $logPath; ErrFile = (Join-Path $TestDrive "custom.err.log")
                StatusText = "HEALTHY"; CachedLogRawMode = $null
                LogMapping = @{ timestamp = "ts"; level = "lvl"; message = "event"; traceId = "reqId" }
            }
            $svcs = @($svc)

            $folded = @(Get-UnifiedLogs -Services $svcs -SelectedIndex 0 -AllMode $false -ErrorsOnly $false -RawMode $false -MaxRows 10 -ExpandStacks $false)
            $folded.Count | Should Be 1
            $folded[0].Text | Should Match "\[trace=tr-999\]"
            $folded[0].Text | Should Match "\[\+3 stack lines\]"

            $expanded = @(Get-UnifiedLogs -Services $svcs -SelectedIndex 0 -AllMode $false -ErrorsOnly $false -RawMode $false -MaxRows 10 -ExpandStacks $true)
            $expanded.Count | Should Be 4

            Add-Content -Path $logPath -Value '{"ts":"2026-11-18T08:00:01.000Z","lvl":"INFO","event":"Other","reqId":"tr-111"}' -Encoding UTF8
            $filtered = @(Get-UnifiedLogs -Services $svcs -SelectedIndex 0 -AllMode $false -ErrorsOnly $false -RawMode $false -MaxRows 10 -FilterText "tr-999")
            $filtered.Count | Should Be 1
            $filtered[0].Text | Should Match "Boom"
        }
    }

    Context 'TUI DryRun Renderer' {
        It 'renders ROUTING, GIT, IDE-UP, and ConfirmQuit' {
            $svcs = @(
                [PSCustomObject]@{
                    Name = "order-service"; Type = "spring-boot"; Port = 8082; MaxHeap = "384M"; LogFormat = "json"
                    RoutingMode = "LOCAL"; GitSummary = "feat/pay*"; StatusText = "IDE-UP"; CachedProc = $null
                    LogFile = (Join-Path $TestDrive "none1.log"); ErrFile = (Join-Path $TestDrive "none1.err.log")
                    CachedLogStamp = ""; CachedLogRawMode = $null; CachedLogTail = 0; CachedLogEntries = @()
                },
                [PSCustomObject]@{
                    Name = "auth-service"; Type = "spring-boot"; Port = 8091; MaxHeap = "384M"; LogFormat = "text"
                    RoutingMode = "REMOTE"; GitSummary = "main"; StatusText = "REMOTE"; CachedProc = $null
                    LogFile = (Join-Path $TestDrive "none2.log"); ErrFile = (Join-Path $TestDrive "none2.err.log")
                    CachedLogStamp = ""; CachedLogRawMode = $null; CachedLogTail = 0; CachedLogEntries = @()
                }
            )
            $frame = Render-Tui -Services $svcs -SelectedIndex 0 -BannerMsg "ok" -AllLogs $false -ErrorsOnly $false -RawLogs $false -ConfirmQuit $true -DryRun
            $frame | Should Match "ROUTING"
            $frame | Should Match "GIT"
            $frame | Should Match "feat/pay\*"
            $frame | Should Match "IDE-UP"
            $frame | Should Match "LOCAL :8082"
            $frame | Should Match "REMOTE"
            $frame | Should Match "\[CONFIRM\]"
        }
    }

    Context 'EnvLoader' {
        It 'merges .env, inline env, and .env.<service> in precedence order' {
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

    Context 'GitGradle' {
        It 'formats Gradle bootJar task names for root and subprojects' {
            Get-GradleTaskName -GradleSubproj "" | Should Be "bootJar"
            Get-GradleTaskName -GradleSubproj ":services:order-service" | Should Be ":services:order-service:bootJar"
        }

        It 'reads Git branch and dirty state for an existing repo' {
            $st = Get-GitRepoStatus -RepoPath $script:RepoRoot
            $st.Exists | Should Be $true
            $st.Branch | Should Not Be "-"
        }
    }

    Context 'WireMockBridge & Profiles' {
        It 'builds LOCAL and REMOTE WireMock proxy mappings with deterministic IDs' {
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

        It 'applies named routing profiles and persists routing.json' {
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
}
