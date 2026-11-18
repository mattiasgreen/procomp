Set-StrictMode -Version Latest

Add-Type -AssemblyName System.Net.Http

function New-HealthHttpClient {
    param([int]$TimeoutMs = 400)
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMilliseconds($TimeoutMs)
    return $client
}

function Poll-AsyncHealth {
    param($Services, $HttpClient)
    $now = Get-Date
    foreach ($svc in $Services) {
        if ($svc.Stopping) {
            $svc.StatusText = "STOPPING"
            continue
        }

        if ($svc.PortConflict) {
            $svc.StatusText = "PORT-CONFLICT"
            $svc.Healthy = $false
            continue
        }

        if (-not $svc.CachedProc) {
            if ($svc.HealthTask) { $svc.HealthTask = $null }
            if ($svc.CachedPid) {
                $svc.StatusText = "CRASHED"
                $svc.Healthy = $false
            } elseif ($svc.RoutingMode -eq "REMOTE") {
                $svc.StatusText = "REMOTE"
                $svc.Healthy = $false
            } else {
                $svc.StatusText = if ($svc.Enabled) { "STOPPED" } else { "DISABLED" }
                $svc.Healthy = $false
            }
            continue
        }

        if ($svc.HealthTask -and $svc.HealthTask.IsCompleted) {
            try {
                if ($svc.HealthTask.Status -eq [System.Threading.Tasks.TaskStatus]::RanToCompletion) {
                    $resp = $svc.HealthTask.Result
                    $code = [int]$resp.StatusCode
                    $resp.Dispose()
                    $svc.Healthy = ($code -ge 200 -and $code -lt 300)
                } else {
                    $svc.Healthy = $false
                }
            } catch {
                $svc.Healthy = $false
            }
            $svc.HealthTask = $null
            $svc.LastHealthAt = $now
        }

        if ((-not $svc.HealthTask) -and (($now - $svc.LastHealthAt).TotalMilliseconds -ge 1200)) {
            $url = "http://127.0.0.1:$($svc.Port)$($svc.HealthPath)"
            try {
                $svc.HealthTask = $HttpClient.GetAsync($url)
            } catch {
                $svc.Healthy = $false
                $svc.LastHealthAt = $now
            }
        }

        if ($svc.ExternalIde) {
            $svc.StatusText = if ($svc.Healthy) { "IDE-UP" } else { "IDE-INIT" }
        } else {
            $svc.StatusText = if ($svc.Healthy) { "HEALTHY" } else { "STARTING" }
        }
    }
}
