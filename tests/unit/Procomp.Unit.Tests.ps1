Set-StrictMode -Version Latest

Describe 'procomp Unit Tests' {
    BeforeAll {
        $script:RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
        . (Join-Path $script:RepoRoot "tests\TestHelpers.ps1")
        . (Join-Path $script:RepoRoot "src\Config.ps1")
        . (Join-Path $script:RepoRoot "src\EnvLoader.ps1")
        . (Join-Path $script:RepoRoot "src\BuildRunner.ps1")
        . (Join-Path $script:RepoRoot "src\IdeDetector.ps1")
        . (Join-Path $script:RepoRoot "src\ProcessManager.ps1")
        . (Join-Path $script:RepoRoot "src\HealthMonitor.ps1")
        . (Join-Path $script:RepoRoot "src\WireMockBridge.ps1")
        . (Join-Path $script:RepoRoot "src\LogViewer.ps1")
        . (Join-Path $script:RepoRoot "src\ConsoleInput.ps1")
        . (Join-Path $script:RepoRoot "src\Tui.ps1")
    }

    Context 'Config, Templates & Artifact Resolution' {
        It 'expands template variables and resolves module paths' {
            Assert-Eq (Resolve-ModulePath ":services:order-service") "services\order-service"
            Assert-Eq (Resolve-ModulePath "billing-api") "billing-api"
            $out = Expand-Template -Template "java {{jvmArgs}} -Dquarkus.http.port={{port}} -jar {{artifact}}" -Vars @{ jvmArgs = "-Xmx256m"; port = "8083"; artifact = "quarkus-run.jar" }
            Assert-Eq $out "java -Xmx256m -Dquarkus.http.port=8083 -jar quarkus-run.jar"
        }

        It 'resolves newest matching artifact while honoring artifactExclude' {
            $libs = Join-Path $TestDrive "mod\build\libs"
            New-Item -ItemType Directory -Force -Path $libs | Out-Null
            Set-Content (Join-Path $libs "app-1.0-plain.jar") "plain"
            Set-Content (Join-Path $libs "app-1.0.jar") "boot"
            $found = Resolve-Artifact -BaseDir $TestDrive -Glob "mod\build\libs\*.jar" -Exclude "*-plain.jar"
            Assert-Match $found "app-1\.0\.jar$"
        }

        It 'loads polyglot procomp.json with Gradle Spring Boot and Maven Quarkus builders/runtimes' {
            $polyPath = Join-Path $script:RepoRoot "examples\polyglot.procomp.json"
            $cfg = Load-ProcompConfig -ConfigPath $polyPath -StateDir (Join-Path $TestDrive "st") -LogDir (Join-Path $TestDrive "lg")
            Assert-Eq $cfg.Services.Count 4
            $orders = $cfg.Services | Where-Object { $_.Name -eq "order-service" }
            $quarkus = $cfg.Services | Where-Object { $_.Name -eq "billing-quarkus" }
            Assert-Eq $orders.MemLimit "384M"
            Assert-Eq $orders.SwaggerUri "swagger-ui/index.html"
            Assert-Eq $quarkus.HealthPath "/q/health/ready"
            Assert-Eq (Expand-BuildCommand -Svc $orders -RootDir $script:RepoRoot) "gradlew.bat :services:order-service:bootJar -q"
            Assert-Eq (Expand-BuildCommand -Svc $quarkus -RootDir $script:RepoRoot) "mvnw.cmd -pl billing-api package -DskipTests -q"
        }

        It 'loads user apps schema with string localport, swaggeruri, and servicerepositoryurl' {
            $uPath = Join-Path $TestDrive "user-apps.json"
            @'
{
    "reposRoot": "..",
    "runtimes": {
        "java": { "command": "java -Dserver.port={{localport}} -jar {{subproject}}.jar", "healthPath": "/actuator/health" }
    },
    "apps": [{
        "servicename": "service1",
        "localport": "9001",
        "subproject": "service1",
        "type": "java",
        "swaggeruri": "swagger-ui/index.html",
        "servicerepositoryurl": "ssh://git@scm.corp:7999/acme/service1.git"
    }]
}
'@ | Set-Content -Path $uPath -Encoding UTF8
            $cfg = Load-ProcompConfig -ConfigPath $uPath -StateDir (Join-Path $TestDrive "ust") -LogDir (Join-Path $TestDrive "ulg")
            Assert-Eq $cfg.Services.Count 1
            $s = $cfg.Services[0]
            Assert-Eq $s.Name "service1"
            Assert-Eq $s.Port 9001
            Assert-Eq $s.Module "service1"
            Assert-Eq $s.Runtime "java"
            Assert-Eq $s.SwaggerUri "swagger-ui/index.html"
            Assert-Eq $s.ServiceRepositoryUrl "ssh://git@scm.corp:7999/acme/service1.git"
            Assert-Eq $s.Repo "..\service1"
        }

        It 'round-trips routing.json state' {
            $rFile = Join-Path $TestDrive "state\routing.json"
            Save-RoutingState -RoutingMap @{ "order-service" = "REMOTE"; "auth-service" = "LOCAL" } -RoutingFile $rFile
            $loaded = Load-RoutingState -RoutingFile $rFile
            Assert-Eq $loaded["order-service"] "REMOTE"
            Assert-Eq $loaded["auth-service"] "LOCAL"
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
            Assert-Eq (Test-IsIdeDescendant -ProcessId 300 -HierarchyMap $map) $true
            Assert-Eq (Test-IsIdeDescendant -ProcessId 500 -HierarchyMap $map) $false
        }

        It 'handles cycles safely without infinite loops' {
            $cyclic = @{
                10 = [PSCustomObject]@{ Name = "foo.exe"; ParentProcessId = 11 }
                11 = [PSCustomObject]@{ Name = "bar.exe"; ParentProcessId = 10 }
            }
            Assert-Eq (Test-IsIdeDescendant -ProcessId 10 -HierarchyMap $cyclic) $false
        }
    }

    Context 'LogViewer' {
        It 'normalizes JSON UTC timestamps to local time and detects levels' {
            $utc = "2026-11-18T08:30:00.123Z"
            $expectedLocal = ([DateTimeOffset]::Parse($utc)).LocalDateTime.ToString("yyyy-MM-dd HH:mm:ss.fff")
            $json = "{`" @timestamp`":`"$utc`",`"level`":`"WARN`",`"service`":`"order-service`",`"message`":`"Slow call`"}".Replace(" @timestamp", "@timestamp")
            $entry = Normalize-LogEntry -RawLine $json -DefaultService "order-service" -RawMode $false
            Assert-Eq $entry.Level "WARN"
            Assert-Eq $entry.SortKey $expectedLocal
            Assert-Match $entry.Text "\[order-service\] --- Slow call"
        }

        It 'handles 0-line missing logs without throwing under StrictMode' {
            $svcs = @(
                [PSCustomObject]@{
                    Name = "empty-svc"; LogFile = (Join-Path $TestDrive "missing.log"); ErrFile = (Join-Path $TestDrive "missing.err.log")
                    StatusText = "DISABLED"; CachedLogRawMode = $null
                }
            )
            $entries = @(Get-UnifiedLogs -Services $svcs -SelectedIndex 0 -AllMode $false -ErrorsOnly $false -RawMode $false -MaxRows 10)
            Assert-Eq $entries.Count 0
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
            Assert-Eq $folded.Count 1
            Assert-Match $folded[0].Text "\[trace=tr-999\]"
            Assert-Match $folded[0].Text "\[\+3 stack lines\]"

            $expanded = @(Get-UnifiedLogs -Services $svcs -SelectedIndex 0 -AllMode $false -ErrorsOnly $false -RawMode $false -MaxRows 10 -ExpandStacks $true)
            Assert-Eq $expanded.Count 4

            Add-Content -Path $logPath -Value '{"ts":"2026-11-18T08:00:01.000Z","lvl":"INFO","event":"Other","reqId":"tr-111"}' -Encoding UTF8
            $filtered = @(Get-UnifiedLogs -Services $svcs -SelectedIndex 0 -AllMode $false -ErrorsOnly $false -RawMode $false -MaxRows 10 -FilterText "tr-999")
            Assert-Eq $filtered.Count 1
            Assert-Match $filtered[0].Text "Boom"
        }

        It 'folds plain-text exception headers and expands only the latest stack trace' {
            $logPath = Join-Path $TestDrive "plain-stack.log"
            @(
                "2026-11-18 09:00:01.000  ERROR [auth-service] --- First failure"
                "java.lang.IllegalStateException: First boom"
                "`tat com.foo.A.run(A.java:10)"
                "2026-11-18 09:00:02.000  ERROR [auth-service] --- Second failure"
                "java.lang.RuntimeException: Second boom"
                "`tat com.foo.B.run(B.java:20)"
                "`tCaused by: java.io.IOException: Closed"
            ) | Set-Content -Path $logPath -Encoding UTF8

            $svc = [PSCustomObject]@{
                Name = "auth-service"; LogFile = $logPath; ErrFile = (Join-Path $TestDrive "plain-stack.err.log")
                StatusText = "HEALTHY"; CachedLogRawMode = $null
            }
            $svcs = @($svc)

            # ExpandStacks=$true should keep first trace folded (+2) and expand only the latest (+3 lines -> 1 header + 3 stack)
            $rows = @(Get-UnifiedLogs -Services $svcs -SelectedIndex 0 -AllMode $false -ErrorsOnly $false -RawMode $false -MaxRows 10 -ExpandStacks $true)
            Assert-Eq $rows.Count 5
            Assert-Match $rows[0].Text "First failure \[\+2 stack lines\]"
            Assert-Match $rows[1].Text "Second failure"
            Assert-Match $rows[2].Text "java\.lang\.RuntimeException: Second boom"
            Assert-Match $rows[4].Text "Caused by: java\.io\.IOException: Closed"
        }
    }

    Context 'TUI DryRun Renderer' {
        It 'renders RUNTIME, ROUTING, GIT, LIMIT, IDE-UP, and ConfirmQuit' {
            $svcs = @(
                [PSCustomObject]@{
                    Name = "billing-quarkus"; Runtime = "quarkus"; Port = 8083; MemLimit = "256M"; LogFormat = "json"
                    RoutingMode = "LOCAL"; GitSummary = "feat/pay*"; StatusText = "IDE-UP"; CachedProc = $null
                    LogFile = (Join-Path $TestDrive "none1.log"); ErrFile = (Join-Path $TestDrive "none1.err.log")
                    CachedLogRawMode = $null
                },
                [PSCustomObject]@{
                    Name = "frontend-bff"; Runtime = "node"; Port = 3000; MemLimit = "-"; LogFormat = "text"
                    RoutingMode = "REMOTE"; GitSummary = "main"; StatusText = "REMOTE"; CachedProc = $null
                    LogFile = (Join-Path $TestDrive "none2.log"); ErrFile = (Join-Path $TestDrive "none2.err.log")
                    CachedLogRawMode = $null
                }
            )
            $frame = Render-Tui -Services $svcs -SelectedIndex 0 -BannerMsg "ok" -AllLogs $false -ErrorsOnly $false -RawLogs $false -ConfirmQuit $true -DryRun
            Assert-Match $frame "RUNTIME"
            Assert-Match $frame "ROUTING"
            Assert-Match $frame "GIT"
            Assert-Match $frame "LIMIT"
            Assert-Match $frame "quarkus"
            Assert-Match $frame "feat/pay\*"
            Assert-Match $frame "IDE-UP"
            Assert-Match $frame "LOCAL :8083"
            Assert-Match $frame "REMOTE"
            Assert-Match $frame "\[CONFIRM\]"
        }

        It 'scrolls log viewport, pauses follow when scrolled up, and expands specific clicked stack trace' {
            $entries = @(
                [PSCustomObject]@{ EntryId = "e1"; SortKey = "1"; Level = "ERROR"; Text = "First err";  StackLines = @("at A", "at B") }
                [PSCustomObject]@{ EntryId = "e2"; SortKey = "2"; Level = "INFO";  Text = "Line 2";     StackLines = @() }
                [PSCustomObject]@{ EntryId = "e3"; SortKey = "3"; Level = "INFO";  Text = "Line 3";     StackLines = @() }
                [PSCustomObject]@{ EntryId = "e4"; SortKey = "4"; Level = "ERROR"; Text = "Second err"; StackLines = @("at C") }
                [PSCustomObject]@{ EntryId = "e5"; SortKey = "5"; Level = "INFO";  Text = "Line 5";     StackLines = @() }
            )

            # At bottom (offset 0) -> IsFollowing = $true
            $vp0 = Get-LogViewport -Entries $entries -MaxRows 3 -ScrollOffset 0
            Assert-Eq $vp0.IsFollowing $true
            Assert-Eq $vp0.Rows[2].Text "Line 5"

            # Scrolled up by 2 -> IsFollowing = $false, shows earlier lines
            $vp2 = Get-LogViewport -Entries $entries -MaxRows 3 -ScrollOffset 2
            Assert-Eq $vp2.IsFollowing $false
            Assert-Eq $vp2.ClampedOffset 2
            Assert-Match $vp2.Rows[0].Text "First err \[\+2 stack lines\]"

            # Expanding specifically e1 (not e4) via ExpandedEntryIds
            $vpExp = Get-LogViewport -Entries $entries -MaxRows 10 -ScrollOffset 0 -ExpandedEntryIds @{ "e1" = $true }
            Assert-Eq $vpExp.TotalRows 7
            Assert-Match $vpExp.Rows[1].Text "at A"
            Assert-Match $vpExp.Rows[5].Text "Second err \[\+1 stack lines\]"
        }

        It 'resolves mouse clicks on toolbar, service rows, routing cell, and stack trace lines' {
            $logFile = Join-Path $TestDrive "click.log"
            @("2026-11-18 09:00:00.000  ERROR [svc1] --- Boom", "`tat com.foo.Bar(Bar.java:1)") | Set-Content -Path $logFile -Encoding UTF8

            $svcs = @(
                [PSCustomObject]@{ Name = "svc1"; Runtime = "java"; Port = 8081; MemLimit = "256M"; LogFormat = "text"; RoutingMode = "LOCAL"; GitSummary = "main"; StatusText = "HEALTHY"; CachedProc = $null; LogFile = $logFile; ErrFile = (Join-Path $TestDrive "c.err"); CachedLogRawMode = $null },
                [PSCustomObject]@{ Name = "svc2"; Runtime = "node"; Port = 3000; MemLimit = "-";    LogFormat = "text"; RoutingMode = "REMOTE"; GitSummary = "main"; StatusText = "REMOTE"; CachedProc = $null; LogFile = (Join-Path $TestDrive "c2.log"); ErrFile = (Join-Path $TestDrive "c2.err"); CachedLogRawMode = $null }
            )
            $layout = $null
            $lRef = [ref]$layout
            $frame = Render-Tui -Services $svcs -SelectedIndex 0 -BannerMsg "ok" -AllLogs $false -ErrorsOnly $false -RawLogs $false -ConfirmQuit $false -ViewMode "SERVICES" -LayoutOut $lRef -DryRun
            Assert-NotNull $layout

            # Toolbar click on first button ([s]Start)
            $hitBtn = Resolve-UiClick -Layout $layout -X $layout.ToolbarButtons[0].StartX -Y 1
            Assert-Eq $hitBtn.Action "Toolbar"
            Assert-Eq $hitBtn.Command "start"

            # Click second service row -> SelectService index 1
            $hitSvc = Resolve-UiClick -Layout $layout -X 5 -Y ($layout.ServiceStartY + 1)
            Assert-Eq $hitSvc.Action "SelectService"
            Assert-Eq $hitSvc.Index 1
            Assert-Eq $hitSvc.ToggleRoute $false

            # Click ROUTING column on first service row -> ToggleRoute = $true
            $hitRoute = Resolve-UiClick -Layout $layout -X $layout.RoutingStartX -Y $layout.ServiceStartY
            Assert-Eq $hitRoute.ToggleRoute $true

            # Double-click service row -> OpenLogs
            $hitDbl = Resolve-UiClick -Layout $layout -X 5 -Y $layout.ServiceStartY -IsDoubleClick $true
            Assert-Eq $hitDbl.Action "OpenLogs"

            # Click stack line in log pane -> ToggleStack
            $hitStack = Resolve-UiClick -Layout $layout -X 10 -Y $layout.LogStartY
            Assert-Eq $hitStack.Action "ToggleStack"

            # Full-screen LOGS mode renders cleanly and has [Esc]Services toolbar button
            $logLayout = $null
            $llRef = [ref]$logLayout
            $logFrame = Render-Tui -Services $svcs -SelectedIndex 0 -BannerMsg "ok" -AllLogs $false -ErrorsOnly $false -RawLogs $false -ConfirmQuit $false -ViewMode "LOGS" -LayoutOut $llRef -DryRun
            Assert-Match $logFrame "procomp LOGS"
            Assert-Eq $logLayout.ToolbarButtons[0].Command "back"
        }
    }

    Context 'EnvLoader' {
        It 'merges .env, inline env, and per-service .env in precedence order' {
            $envDir = Join-Path $TestDrive "envtest"
            New-Item -ItemType Directory -Force -Path $envDir | Out-Null
            "DB_HOST=global`nAPI_KEY=`"secret123`"`n# comment" | Set-Content (Join-Path $envDir ".env")
            "API_KEY=override-svc`nSVC_ONLY=yes" | Set-Content (Join-Path $envDir ".env.orders")

            $svc = [PSCustomObject]@{ Name = "orders"; Env = @{ DB_HOST = "inline-host"; INLINE_ONLY = "1" } }
            $resolved = Resolve-ServiceEnvironment -Svc $svc -RootDir $envDir
            Assert-Eq $resolved["DB_HOST"] "inline-host"
            Assert-Eq $resolved["API_KEY"] "override-svc"
            Assert-Eq $resolved["SVC_ONLY"] "yes"
            Assert-Eq $resolved["INLINE_ONLY"] "1"
        }
    }

    Context 'Git & WireMockBridge & Profiles' {
        It 'reads Git branch and dirty state for an existing repo' {
            $st = Get-GitRepoStatus -RepoPath $script:RepoRoot
            Assert-Eq $st.Exists $true
            Assert-Neq $st.Branch "-"
        }

        It 'builds LOCAL and REMOTE WireMock proxy mappings with deterministic IDs' {
            $svc = [PSCustomObject]@{
                Name = "order-service"; Port = 8082; ProxyPath = "/orders"
                RemoteUrl = "https://shared-dev.mycorp/orders"; RoutingMode = "LOCAL"
            }
            $mLocal = Build-WireMockMapping -Svc $svc
            Assert-Eq $mLocal.response.proxyBaseUrl "http://127.0.0.1:8082"
            Assert-Eq $mLocal.response.proxyUrlPrefixToRemove "/orders"

            $svc.RoutingMode = "REMOTE"
            $mRemote = Build-WireMockMapping -Svc $svc
            Assert-Eq $mRemote.id $mLocal.id
            Assert-Eq $mRemote.response.proxyBaseUrl "https://shared-dev.mycorp/orders"
        }

        It 'applies named routing profiles from procomp.json and persists routing.json' {
            $rFile = Join-Path $TestDrive "prof-routing.json"
            $polyPath = Join-Path $script:RepoRoot "examples\polyglot.procomp.json"
            $cfg = Load-ProcompConfig -ConfigPath $polyPath -StateDir (Join-Path $TestDrive "pst") -LogDir (Join-Path $TestDrive "plg")
            Apply-RoutingProfile -Config $cfg -ProfileName "checkout" -RoutingFile $rFile

            $orders = $cfg.Services | Where-Object { $_.Name -eq "order-service" }
            $bff = $cfg.Services | Where-Object { $_.Name -eq "frontend-bff" }
            Assert-Eq $orders.RoutingMode "LOCAL"
            Assert-Eq $bff.RoutingMode "REMOTE"
            Assert-Eq $bff.Enabled $false

            $saved = Load-RoutingState -RoutingFile $rFile
            Assert-Eq $saved["order-service"] "LOCAL"
            Assert-Eq $saved["frontend-bff"] "REMOTE"
        }
    }
}
