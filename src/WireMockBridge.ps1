Set-StrictMode -Version Latest

function Get-WireMockServiceUrl {
    param($Services)
    $wm = $Services | Where-Object { $_.Type -eq "wiremock" } | Select-Object -First 1
    if ($wm) {
        return "http://127.0.0.1:$($wm.Port)"
    }
    return $null
}

function Get-WireMockMappingId {
    param([string]$ServiceName)
    # Deterministic UUID v5-like hex from MD5 of service name so PUT /__admin/mappings/{id} is idempotent
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
            proxyBaseUrl             = $targetBase
            proxyUrlPrefixToRemove   = $prefix
        }
    }
}

function Set-ServiceWireMockRoute {
    param($Svc, [string]$WireMockBaseUrl)
    if (-not $WireMockBaseUrl -or $Svc.Type -eq "wiremock") { return $false }
    $mapping = Build-WireMockMapping -Svc $Svc
    $json = $mapping | ConvertTo-Json -Depth 5
    $id = $mapping.id
    try {
        Invoke-RestMethod -Uri "$WireMockBaseUrl/__admin/mappings/$id" -Method Put -Body $json -ContentType "application/json" -TimeoutSec 2 | Out-Null
        return $true
    } catch {
        try {
            Invoke-RestMethod -Uri "$WireMockBaseUrl/__admin/mappings" -Method Post -Body $json -ContentType "application/json" -TimeoutSec 2 | Out-Null
            return $true
        } catch {
            return $false
        }
    }
}

function Sync-WireMockMappings {
    param($Services, [string]$WireMockBaseUrl)
    if (-not $WireMockBaseUrl) { return 0 }
    $synced = 0
    foreach ($svc in $Services) {
        if ($svc.Type -eq "wiremock") { continue }
        if (Set-ServiceWireMockRoute -Svc $svc -WireMockBaseUrl $WireMockBaseUrl) {
            $synced++
        }
    }
    return $synced
}
