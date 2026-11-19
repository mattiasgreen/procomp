Set-StrictMode -Version Latest

Describe 'procomp Unit Tests' {
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
            Assert-Eq (Expand-BuildCommand -Svc $orders -RootDir $script:RepoRoot) "gradlew.bat :services:order-service:assemble -q"
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

        It 'loads external appsFile with defaults, remoteUrlTemplate, ReposRootOverride, and wildcard remote profile' {
            $exPath = Join-Path $script:RepoRoot "examples\using-service-index.procomp.json"
            $rFile = Join-Path $TestDrive "ex-routing.json"
            $cfg = Load-ProcompConfig -ConfigPath $exPath -StateDir (Join-Path $TestDrive "exst") -LogDir (Join-Path $TestDrive "exlg") -ReposRootOverride "D:\my-repos"
            Assert-Eq $cfg.Services.Count 3
            Assert-Eq $cfg.Services[0].Builder "gradle"
            Assert-Eq $cfg.Services[0].Repo "D:\my-repos\service1"
            Assert-Eq (Expand-BuildCommand -Svc $cfg.Services[0] -RootDir $script:RepoRoot) "gradlew.bat :service1:assemble -q"
            Assert-Eq $cfg.Services[0].MemLimit "384M"
            Assert-Eq $cfg.Services[0].RemoteUrl "https://shared-dev.mycorp/service1"
            Assert-Eq $cfg.Services[1].RemoteUrl "https://shared-dev.mycorp/service2"

            Apply-RoutingProfile -Config $cfg -ProfileName "one-local" -RoutingFile $rFile
            Assert-Eq $cfg.Services[0].RoutingMode "LOCAL"
            Assert-Eq $cfg.Services[0].Enabled $true
            Assert-Eq $cfg.Services[1].RoutingMode "REMOTE"
            Assert-Eq $cfg.Services[1].Enabled $false
            Assert-Eq $cfg.Services[2].RoutingMode "REMOTE"
            Assert-Eq $cfg.Services[2].Enabled $false
        }

        It 'expands {{reposRoot}} in appsFile and template variables' {
            $fakeRoot = Join-Path $TestDrive "myrepos"
            New-Item -ItemType Directory -Force -Path $fakeRoot | Out-Null
            Copy-Item (Join-Path $script:RepoRoot "examples\service-index.json") (Join-Path $fakeRoot "service-index.json")

            $cfgFile = Join-Path $TestDrive "rr-cfg.json"
            @{
                appsFile = "{{reposRoot}}\service-index.json"
                runtimes = @{ java = @{ command = "java -jar {{reposRoot}}\{{servicename}}.jar" } }
            } | ConvertTo-Json -Depth 4 | Set-Content -Path $cfgFile -Encoding UTF8

            $cfg = Load-ProcompConfig -ConfigPath $cfgFile -StateDir (Join-Path $TestDrive "rrst") -LogDir (Join-Path $TestDrive "rrlg") -ReposRootOverride $fakeRoot
            Assert-Eq $cfg.Services.Count 3
            $vars = Build-ServiceTemplateVars -Svc $cfg.Services[0] -RootDir $script:RepoRoot
            Assert-Eq $vars["reposRoot"] $fakeRoot
            Assert-Eq (Expand-Template -Template $cfg.Services[0].RuntimeDef.Command -Vars $vars) "java -jar $fakeRoot\service1.jar"
        }

        It 'resolves project name from override, config field, or filename stem' {
            Assert-Eq (Resolve-ProjectName -ConfigPath "C:\x\procomp.json" -ProjectOverride "custom") "custom"
            Assert-Eq (Resolve-ProjectName -ConfigPath (Join-Path $script:RepoRoot "examples\using-service-index.procomp.json")) "acme"
            Assert-Eq (Resolve-ProjectName -ConfigPath (Join-Path $script:RepoRoot "demo\demo.procomp.json")) "demo"
            Assert-Eq (Resolve-ProjectName -ConfigPath (Join-Path $script:RepoRoot "procomp.json")) "default"
        }

        It 'builds gradleTaskPrefix for single-project and multi-project repos and resolves Swagger URLs' {
            $single = [PSCustomObject]@{ Name = "s1"; Port = 9001; Runtime = "java"; Repo = ""; Module = ""; Vars = @{}; HealthPath = ""; LogFormat = "text"; SwaggerUri = "swagger-ui/index.html"; RoutingMode = "LOCAL"; RemoteUrl = "https://dev.corp/s1" }
            $vSingle = Build-ServiceTemplateVars -Svc $single -RootDir $script:RepoRoot
            Assert-Eq (Expand-Template "gradlew.bat {{gradleTaskPrefix}}assemble -q" $vSingle) "gradlew.bat assemble -q"
            Assert-Eq (Get-ServiceSwaggerUrl -Svc $single) "http://127.0.0.1:9001/swagger-ui/index.html"

            $single.RoutingMode = "REMOTE"
            Assert-Eq (Get-ServiceSwaggerUrl -Svc $single) "https://dev.corp/s1/swagger-ui/index.html"
        }

        It 'round-trips routing.json state' {
            $rFile = Join-Path $TestDrive "state\routing.json"
            Save-RoutingState -RoutingMap @{ "order-service" = "REMOTE"; "auth-service" = "LOCAL" } -RoutingFile $rFile
            $loaded = Load-RoutingState -RoutingFile $rFile
            Assert-Eq $loaded["order-service"] "REMOTE"
            Assert-Eq $loaded["auth-service"] "LOCAL"
        }

        It 'prefers JAVA_HOME\bin\java.exe when JAVA_HOME is set' {
            $fakeJdk = Join-Path $TestDrive "fake-jdk"
            $fakeBin = Join-Path $fakeJdk "bin"
            New-Item -ItemType Directory -Force -Path $fakeBin | Out-Null
            $fakeJava = Join-Path $fakeBin "java.exe"
            Set-Content -Path $fakeJava -Value ""

            $prevJh = $env:JAVA_HOME
            try {
                $env:JAVA_HOME = $fakeJdk
                Assert-Eq (Resolve-JavaTool -Tool "java") $fakeJava
            } finally {
                $env:JAVA_HOME = $prevJh
            }
        }

        It 'reads UTF-8 BOM and non-BOM JSON with non-ASCII chars and single-element array appsFile' {
            $svWord = "tj" + [char]0x00E4 + "nst-" + [char]0x00E5 + [char]0x00E4 + [char]0x00F6
            $utf8Bom = New-Object System.Text.UTF8Encoding($true)
            $utf8NoBom = New-Object System.Text.UTF8Encoding($false)

            $bomAppsPath = Join-Path $TestDrive "bom-apps.json"
            $bomCfgPath = Join-Path $TestDrive "bom-cfg.json"
            $bomAppsJson = '[{"servicename":"svc-one","localport":"9001","type":"java","vars":{"label":"' + $svWord + '"}}]'
            $bomCfgJson = '{"appsFile":".\\bom-apps.json","runtimes":{"java":{"command":"java -jar {{servicename}}.jar"}}}'
            [System.IO.File]::WriteAllText($bomAppsPath, $bomAppsJson, $utf8Bom)
            [System.IO.File]::WriteAllText($bomCfgPath, $bomCfgJson, $utf8Bom)

            $cfgBom = Load-ProcompConfig -ConfigPath $bomCfgPath -StateDir (Join-Path $TestDrive "bomst") -LogDir (Join-Path $TestDrive "bomlg")
            Assert-Eq $cfgBom.Services.Count 1
            Assert-Eq $cfgBom.Services[0].Vars["label"] $svWord
            Assert-Eq $cfgBom.AppsFile ([System.IO.Path]::GetFullPath($bomAppsPath))

            $noBomAppsPath = Join-Path $TestDrive "nobom-apps.json"
            $noBomCfgPath = Join-Path $TestDrive "nobom-cfg.json"
            $noBomAppsJson = '[{"servicename":"svc-two","localport":"9002","type":"java","vars":{"label":"' + $svWord + '"}}]'
            $noBomCfgJson = '{"appsFile":".\\nobom-apps.json","runtimes":{"java":{"command":"java -jar {{servicename}}.jar"}}}'
            [System.IO.File]::WriteAllText($noBomAppsPath, $noBomAppsJson, $utf8NoBom)
            [System.IO.File]::WriteAllText($noBomCfgPath, $noBomCfgJson, $utf8NoBom)

            $cfgNoBom = Load-ProcompConfig -ConfigPath $noBomCfgPath -StateDir (Join-Path $TestDrive "nbst") -LogDir (Join-Path $TestDrive "nblg")
            Assert-Eq $cfgNoBom.Services.Count 1
            Assert-Eq $cfgNoBom.Services[0].Vars["label"] $svWord
            Assert-Eq $cfgNoBom.AppsFile ([System.IO.Path]::GetFullPath($noBomAppsPath))
        }

        It 'reports full appsFile path and preview when appsFile is invalid, empty, or a directory' {
            $badAppsPath = [System.IO.Path]::GetFullPath((Join-Path $TestDrive "symlink-like.json"))
            [System.IO.File]::WriteAllText($badAppsPath, "./target/service-index.json`n", (New-Object System.Text.UTF8Encoding($false)))
            $badCfgPath = Join-Path $TestDrive "bad-cfg.json"
            '{"appsFile":".\\symlink-like.json","runtimes":{"java":{"command":"java -jar app.jar"}}}' | Set-Content -Path $badCfgPath -Encoding UTF8

            $badErr = ""
            try {
                Load-ProcompConfig -ConfigPath $badCfgPath -StateDir (Join-Path $TestDrive "badst") -LogDir (Join-Path $TestDrive "badlg") | Out-Null
            } catch {
                $badErr = $_.Exception.Message
            }
            Assert-Match $badErr ([regex]::Escape($badAppsPath))
            Assert-Match $badErr "starts with: '\./target/service-index\.json'"
            Assert-Eq $script:LastResolvedAppsFile $badAppsPath

            $emptyAppsPath = [System.IO.Path]::GetFullPath((Join-Path $TestDrive "empty-apps.json"))
            [System.IO.File]::WriteAllText($emptyAppsPath, "   ", (New-Object System.Text.UTF8Encoding($false)))
            $emptyCfgPath = Join-Path $TestDrive "empty-cfg.json"
            '{"appsFile":".\\empty-apps.json","runtimes":{"java":{"command":"java -jar app.jar"}}}' | Set-Content -Path $emptyCfgPath -Encoding UTF8

            $emptyErr = ""
            try {
                Load-ProcompConfig -ConfigPath $emptyCfgPath -StateDir (Join-Path $TestDrive "empst") -LogDir (Join-Path $TestDrive "emplg") | Out-Null
            } catch {
                $emptyErr = $_.Exception.Message
            }
            Assert-Match $emptyErr ([regex]::Escape($emptyAppsPath))
            Assert-Match $emptyErr "file is empty"

            $dirAppsPath = [System.IO.Path]::GetFullPath((Join-Path $TestDrive "apps-dir"))
            New-Item -ItemType Directory -Force -Path $dirAppsPath | Out-Null
            $dirCfgPath = Join-Path $TestDrive "dir-cfg.json"
            '{"appsFile":".\\apps-dir","runtimes":{"java":{"command":"java -jar app.jar"}}}' | Set-Content -Path $dirCfgPath -Encoding UTF8

            $dirErr = ""
            try {
                Load-ProcompConfig -ConfigPath $dirCfgPath -StateDir (Join-Path $TestDrive "dirst") -LogDir (Join-Path $TestDrive "dirlg") | Out-Null
            } catch {
                $dirErr = $_.Exception.Message
            }
            Assert-Match $dirErr ([regex]::Escape($dirAppsPath))
            Assert-Match $dirErr "is a directory, not a file"
        }

        It 'logs executed commands to procomp-debug.log when Set-ProcompDebugLogPath is active' {
            $dbgLog = Join-Path $TestDrive "procomp-debug.log"
            $prevDbg = [System.Environment]::GetEnvironmentVariable("PROCOMP_DEBUG_LOG", "Process")
            try {
                Set-ProcompDebugLogPath -Path $dbgLog
                [void](Invoke-GitNonInteractive -GitArgs @("--version"))

                $fakeSvc = [PSCustomObject]@{
                    Name = "dbg-svc"; Repo = $TestDrive; Module = ""
                    BuilderDef = [PSCustomObject]@{ Name = "b"; Command = "echo building" }
                    Vars = @{}; HealthPath = ""; LogFormat = "text"; Port = 9999; Runtime = "java"
                    SwaggerUri = ""; ServiceRepositoryUrl = ""; ReposRoot = ""
                }
                [void](Invoke-ServiceBuildSync -Svc $fakeSvc -RootDir $TestDrive)

                Assert-Eq (Test-Path $dbgLog) $true
                $dbgContent = Get-Content -Path $dbgLog -Raw
                Assert-Match $dbgContent "\[GIT\].*git --version"
                Assert-Match $dbgContent "\[BUILD\] svc=dbg-svc.*cmd\.exe /c echo building"
            } finally {
                Set-ProcompDebugLogPath -Path $prevDbg
            }
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

        It 'windows large service tables (25 apps) and renders the help overlay' {
            $many = @()
            for ($n = 1; $n -le 25; $n++) {
                $many += [PSCustomObject]@{ Name = "svc-$n"; Runtime = "java"; Port = (9000 + $n); MemLimit = "256M"; LogFormat = "text"; RoutingMode = "REMOTE"; GitSummary = "main"; StatusText = "REMOTE"; CachedProc = $null; LogFile = (Join-Path $TestDrive "m$n.log"); ErrFile = (Join-Path $TestDrive "m$n.err"); CachedLogRawMode = $null }
            }
            $lay = $null
            $lRef = [ref]$lay
            $frame = Render-Tui -Services $many -SelectedIndex 20 -BannerMsg "ok" -AllLogs $false -ErrorsOnly $false -RawLogs $false -ConfirmQuit $false -ViewMode "SERVICES" -ProjectName "acme" -LayoutOut $lRef -DryRun
            Assert-Gt $lay.ServiceScrollTop 0
            Assert-Match $frame "\[acme\]"
            $hitRow = Resolve-UiClick -Layout $lay -X 5 -Y $lay.ServiceStartY
            Assert-Eq $hitRow.Index $lay.ServiceScrollTop

            $helpFrame = Render-Tui -Services $many -SelectedIndex 0 -BannerMsg "" -AllLogs $false -ErrorsOnly $false -RawLogs $false -ConfirmQuit $false -ShowHelp $true -DryRun
            Assert-Match $helpFrame "Keyboard & Mouse Cheat Sheet"
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
            $miss = Get-GitRepoStatus -RepoPath (Join-Path $TestDrive "does-not-exist") -HasRepoUrl $true
            Assert-Eq $miss.Summary "MISSING"
        }

        It 'fetches remote commit, reports behind count, pulls --ff-only, and skips dirty repo' {
            $repo = New-MockGitRepo -BaseDir (Join-Path $TestDrive "mgit") -Name "svc" -Branch "main" -AddRemoteCommit -Force
            $local = $repo.LocalPath

            $fr = Invoke-GitFetchRepo -RepoPath $local
            Assert-Eq $fr.Action "FETCHED"
            $stBehind = Get-GitRepoStatus -RepoPath $local
            Assert-Eq $stBehind.Behind 1
            Assert-Match $stBehind.Summary "main v1"

            $pr = Invoke-GitPullRepo -RepoPath $local
            Assert-Eq $pr.Action "PULLED"
            Assert-Eq $pr.Commits 1
            $stClean = Get-GitRepoStatus -RepoPath $local
            Assert-Eq $stClean.Behind 0

            # Dirty working tree must be skipped safely
            "wip" | Set-Content (Join-Path $local "dirty.txt")
            $prDirty = Invoke-GitPullRepo -RepoPath $local
            Assert-Eq $prDirty.Action "SKIP"
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
