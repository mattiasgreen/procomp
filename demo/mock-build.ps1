param(
    [string]$Tool = "gradle",
    [string]$Service = "service",
    [string]$Module = ""
)

$ErrorActionPreference = "Stop"

switch ($Tool) {
    "gradle" {
        Write-Host "> Configure project $Module"
        Start-Sleep -Milliseconds 400
        Write-Host "> Task ${Module}:compileJava"
        Start-Sleep -Milliseconds 500
        Write-Host "> Task ${Module}:processResources"
        Start-Sleep -Milliseconds 300
        Write-Host "> Task ${Module}:bootJar"
        Start-Sleep -Milliseconds 500
        Write-Host "BUILD SUCCESSFUL in 2s ($Service)"
    }
    "maven" {
        Write-Host "[INFO] Scanning for projects..."
        Start-Sleep -Milliseconds 400
        Write-Host "[INFO] Building $Service ($Module) 1.0.0-SNAPSHOT"
        Start-Sleep -Milliseconds 500
        Write-Host "[INFO] --- quarkus-maven-plugin:3.15.0:build ---"
        Start-Sleep -Milliseconds 500
        Write-Host "[INFO] Quarkus fast-jar built in 1.8s"
        Write-Host "[INFO] BUILD SUCCESS"
    }
    "npm" {
        Write-Host "> $Service@1.0.0 build"
        Write-Host "> tsc && vite build"
        Start-Sleep -Milliseconds 600
        Write-Host "dist/index.js  42.8 kB | built in 0.9s"
    }
    default {
        Write-Host "Mock build ($Tool) completed for $Service"
    }
}
exit 0
