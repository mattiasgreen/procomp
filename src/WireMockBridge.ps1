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
    param($Services, [string]$WireMockBaseUrl, [string]$GatewayServiceName = "")
    if (-not $WireMockBaseUrl) { return 0 }
    $synced = 0
    foreach ($svc in $Services) {
        if ($GatewayServiceName -and $svc.Name -eq $GatewayServiceName) { continue }
        if (Set-ServiceWireMockRoute -Svc $svc -WireMockBaseUrl $WireMockBaseUrl -GatewayServiceName $GatewayServiceName) {
            $synced++
        }
    }
    return $synced
}
