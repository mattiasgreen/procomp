Set-StrictMode -Version Latest

function Get-GatewayAdminUrl {
    param($Config)
    if ($Config -and $Config.Gateway -and $Config.Gateway.AdminUrl) {
        return $Config.Gateway.AdminUrl
    }
    return $null
}

function Get-WireMockMappingId {
    param([string]$ServiceName)
    $md5 = [System.Security.Cryptography.MD5]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes("procomp:$ServiceName")
        $hash = $md5.ComputeHash($bytes)
        return ([Guid]::new($hash)).ToString()
    } finally {
        $md5.Dispose()
    }
}

function Build-WireMockMapping {
    param($Svc)
    $prefix = $Svc.ProxyPath.TrimEnd('/')
    $targetBase = if (($Svc.RoutingMode -eq "REMOTE") -and $Svc.RemoteUrl) {
        $Svc.RemoteUrl.TrimEnd('/')
    } else {
        "http://127.0.0.1:$($Svc.Port)"
    }

    $id = Get-WireMockMappingId -ServiceName $Svc.Name
    return @{
        id       = $id
        name     = "procomp-$($Svc.Name)"
        priority = 5
        request  = @{
            method         = "ANY"
            urlPathPattern = "$prefix/(.*)"
        }
        response = @{
            proxyBaseUrl           = $targetBase
            proxyUrlPrefixToRemove = $prefix
        }
    }
}

function Set-ServiceWireMockRoute {
    param($Svc, [string]$WireMockBaseUrl, [string]$GatewayServiceName = "")
    if (-not $WireMockBaseUrl -or ($GatewayServiceName -and $Svc.Name -eq $GatewayServiceName)) { return $false }
    $mapping = Build-WireMockMapping -Svc $Svc
    $json = $mapping | ConvertTo-Json -Depth 5
    $id = $mapping.id
    try {
        Write-ProcompDebugLog -Category "WIREMOCK" -ServiceName $Svc.Name -Message "PUT $WireMockBaseUrl/__admin/mappings/$id -> $($Svc.RoutingMode)"
        Invoke-RestMethod -Uri "$WireMockBaseUrl/__admin/mappings/$id" -Method Put -Body $json -ContentType "application/json" -TimeoutSec 2 | Out-Null
        Write-ProcompTraceLog -Category "WIREMOCK" -ServiceName $Svc.Name -Message "PUT mapping ok ($($Svc.ProxyPath) -> $($mapping.response.proxyBaseUrl))"
        return $true
    } catch {
        Write-ProcompTraceLog -Category "WIREMOCK" -ServiceName $Svc.Name -Message "PUT failed ($($_.Exception.Message)); falling back to POST"
        try {
            Write-ProcompDebugLog -Category "WIREMOCK" -ServiceName $Svc.Name -Message "POST $WireMockBaseUrl/__admin/mappings -> $($Svc.RoutingMode)"
            Invoke-RestMethod -Uri "$WireMockBaseUrl/__admin/mappings" -Method Post -Body $json -ContentType "application/json" -TimeoutSec 2 | Out-Null
            Write-ProcompTraceLog -Category "WIREMOCK" -ServiceName $Svc.Name -Message "POST mapping ok ($($Svc.ProxyPath) -> $($mapping.response.proxyBaseUrl))"
            return $true
        } catch {
            Write-ProcompTraceLog -Category "WIREMOCK" -ServiceName $Svc.Name -Message "POST mapping failed: $($_.Exception.Message)"
            return $false
        }
    }
}

function Sync-WireMockMappings {
    param($Services, [string]$WireMockBaseUrl, [string]$GatewayServiceName = "")
    if (-not $WireMockBaseUrl) { return 0 }
    $synced = 0
    $total = @($Services).Count
    $idx = 0
    foreach ($svc in $Services) {
        $idx++
        if ($GatewayServiceName -and $svc.Name -eq $GatewayServiceName) { continue }
        Report-ProcompActivity -ServiceName $svc.Name -Category "WIREMOCK" -Message "Syncing WireMock route ($idx/$total): $($svc.Name) -> $($svc.RoutingMode)..."
        if (Set-ServiceWireMockRoute -Svc $svc -WireMockBaseUrl $WireMockBaseUrl -GatewayServiceName $GatewayServiceName) {
            $synced++
        }
    }
    return $synced
}

function Get-WireMockDrift {
    param($Services, [string]$WireMockBaseUrl, [string]$GatewayServiceName = "")
    $report = @()
    if (-not $WireMockBaseUrl) { return $report }

    try {
        Write-ProcompTraceLog -Category "WIREMOCK" -Message "GET $WireMockBaseUrl/__admin/mappings (drift check)"
        $resp = Invoke-RestMethod -Uri "$WireMockBaseUrl/__admin/mappings" -Method Get -TimeoutSec 2
    } catch {
        Write-ProcompTraceLog -Category "WIREMOCK" -Message "GET mappings failed: $($_.Exception.Message)"
        return @([PSCustomObject]@{ Service = "gateway"; Status = "UNREACHABLE"; Detail = "Cannot reach $WireMockBaseUrl/__admin/mappings" })
    }

    $actualById = @{}
    if ($resp -and $resp.PSObject.Properties["mappings"] -and $resp.mappings) {
        foreach ($m in $resp.mappings) {
            if ($m.PSObject.Properties["id"] -and $m.id -and $m.PSObject.Properties["response"] -and $m.response.PSObject.Properties["proxyBaseUrl"]) {
                $actualById[[string]$m.id] = ([string]$m.response.proxyBaseUrl).TrimEnd('/')
            }
        }
    }

    foreach ($svc in $Services) {
        if ($GatewayServiceName -and $svc.Name -eq $GatewayServiceName) { continue }
        $expected = Build-WireMockMapping -Svc $svc
        $id = $expected.id
        $expTarget = $expected.response.proxyBaseUrl
        if (-not $actualById.ContainsKey($id)) {
            $report += [PSCustomObject]@{ Service = $svc.Name; Status = "MISSING"; Detail = "No procomp mapping in WireMock (expected $expTarget)" }
        } elseif ($actualById[$id] -ne $expTarget) {
            $report += [PSCustomObject]@{ Service = $svc.Name; Status = "DRIFT"; Detail = "WireMock=$($actualById[$id]) vs procomp=$expTarget" }
        }
    }
    return $report
}

function Format-WireMockJournalEntries {
    param($Resp)
    $entries = @()
    if (-not $Resp -or (-not $Resp.PSObject.Properties["requests"]) -or (-not $Resp.requests)) {
        return $entries
    }
    $reqs = @($Resp.requests)
    [Array]::Reverse($reqs)
    $idx = 0
    foreach ($r in $reqs) {
        $idx++
        $req = if ($r.PSObject.Properties["request"]) { $r.request } else { $null }
        if (-not $req) { continue }
        $method = if ($req.PSObject.Properties["method"] -and $req.method) { [string]$req.method } else { "?" }
        $url = if ($req.PSObject.Properties["url"] -and $req.url) { [string]$req.url } else { "/" }
        $tsStr = "-"
        if ($req.PSObject.Properties["loggedDateString"] -and $req.loggedDateString) {
            $dto = [DateTimeOffset]::MinValue
            if ([DateTimeOffset]::TryParse([string]$req.loggedDateString, [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::AssumeUniversal, [ref]$dto)) {
                $tsStr = $dto.LocalDateTime.ToString("HH:mm:ss.fff")
            }
        }
        $matched = if ($r.PSObject.Properties["wasMatched"]) { [bool]$r.wasMatched } else { $false }
        $status = 0
        if ($r.PSObject.Properties["response"] -and $r.response -and $r.response.PSObject.Properties["status"]) {
            $status = [int]$r.response.status
        } elseif ($r.PSObject.Properties["responseDefinition"] -and $r.responseDefinition -and $r.responseDefinition.PSObject.Properties["status"]) {
            $status = [int]$r.responseDefinition.status
        }
        $target = ""
        if ($r.PSObject.Properties["responseDefinition"] -and $r.responseDefinition -and $r.responseDefinition.PSObject.Properties["proxyBaseUrl"] -and $r.responseDefinition.proxyBaseUrl) {
            $target = " -> $($r.responseDefinition.proxyBaseUrl)"
        } elseif (-not $matched) {
            $target = " (UNMATCHED)"
        }
        $ms = ""
        if ($r.PSObject.Properties["timing"] -and $r.timing -and $r.timing.PSObject.Properties["totalTime"]) {
            $ms = " [$($r.timing.totalTime)ms]"
        }
        $statusStr = if ($status -gt 0) { [string]$status } else { "-" }
        $lvl = if ((-not $matched) -or ($status -ge 500)) { "ERROR" } elseif ($status -ge 400) { "WARN" } else { "INFO" }
        $entries += [PSCustomObject]@{
            EntryId    = "wm-j-$idx"
            SortKey    = $idx.ToString("D6")
            Level      = $lvl
            Text       = ("{0}  {1,-6} {2} => {3}{4}{5}" -f $tsStr, $method, $url, $statusStr, $target, $ms)
            StackLines = @()
            IsJson     = $false
        }
    }
    return $entries
}

function Format-WireMockMappingEntries {
    param($Resp)
    $entries = @()
    if (-not $Resp -or (-not $Resp.PSObject.Properties["mappings"]) -or (-not $Resp.mappings)) {
        return $entries
    }
    $idx = 0
    foreach ($m in $Resp.mappings) {
        $idx++
        $req = if ($m.PSObject.Properties["request"]) { $m.request } else { $null }
        $resp = if ($m.PSObject.Properties["response"]) { $m.response } else { $null }
        $method = if ($req -and $req.PSObject.Properties["method"] -and $req.method) { [string]$req.method } else { "ANY" }
        $pat = "*"
        if ($req) {
            foreach ($pf in @("urlPathPattern", "urlPattern", "urlPath", "url")) {
                if ($req.PSObject.Properties[$pf] -and $req.$pf) {
                    $pat = [string]$req.$pf
                    break
                }
            }
        }
        $dest = "(stub)"
        if ($resp -and $resp.PSObject.Properties["proxyBaseUrl"] -and $resp.proxyBaseUrl) {
            $dest = [string]$resp.proxyBaseUrl
        } elseif ($resp -and $resp.PSObject.Properties["status"]) {
            $dest = "stub:$($resp.status)"
        }
        $prio = if ($m.PSObject.Properties["priority"] -and $null -ne $m.priority) { "p=$($m.priority)" } else { "p=-" }
        $name = if ($m.PSObject.Properties["name"] -and $m.name) { " [$($m.name)]" } else { "" }
        $lvl = if ($dest -match '127\.0\.0\.1|localhost') { "INFO" } elseif ($dest -like "http*") { "WARN" } else { "INFO" }
        $entries += [PSCustomObject]@{
            EntryId    = "wm-m-$idx"
            SortKey    = $idx.ToString("D6")
            Level      = $lvl
            Text       = ("{0,-6} {1} -> {2} ({3}){4}" -f $method, $pat, $dest, $prio, $name)
            StackLines = @()
            IsJson     = $false
        }
    }
    return $entries
}

function Get-WireMockViewerEntries {
    param(
        [string]$WireMockBaseUrl,
        [string]$SubMode = "JOURNAL"
    )
    if (-not $WireMockBaseUrl) {
        return @([PSCustomObject]@{ EntryId = "wm-none"; SortKey = "000001"; Level = "WARN"; Text = "No gateway.adminUrl configured in procomp.json"; StackLines = @(); IsJson = $false })
    }
    try {
        if ($SubMode -eq "MAPPINGS") {
            $resp = Invoke-RestMethod -Uri "$WireMockBaseUrl/__admin/mappings" -Method Get -TimeoutSec 1
            $list = @(Format-WireMockMappingEntries -Resp $resp)
            if ($list.Count -eq 0) {
                return @([PSCustomObject]@{ EntryId = "wm-empty"; SortKey = "000001"; Level = "INFO"; Text = "(no mappings registered in WireMock at $WireMockBaseUrl)"; StackLines = @(); IsJson = $false })
            }
            return $list
        } else {
            $resp = Invoke-RestMethod -Uri "$WireMockBaseUrl/__admin/requests?limit=200" -Method Get -TimeoutSec 1
            $list = @(Format-WireMockJournalEntries -Resp $resp)
            if ($list.Count -eq 0) {
                return @([PSCustomObject]@{ EntryId = "wm-empty"; SortKey = "000001"; Level = "INFO"; Text = "(no requests recorded yet in WireMock at $WireMockBaseUrl)"; StackLines = @(); IsJson = $false })
            }
            return $list
        }
    } catch {
        return @([PSCustomObject]@{ EntryId = "wm-err"; SortKey = "000001"; Level = "ERROR"; Text = "Cannot reach WireMock at $WireMockBaseUrl : $($_.Exception.Message)"; StackLines = @(); IsJson = $false })
    }
}
