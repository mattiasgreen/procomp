@echo off
if not exist "%~dp0logs" mkdir "%~dp0logs"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0procomp.ps1" %*
if errorlevel 1 (
    set "EC=%ERRORLEVEL%"
    echo.
    echo [procomp.cmd] Failed with exit code %ERRORLEVEL%. Check "%~dp0logs\procomp-error.log" for details.
    exit /b %ERRORLEVEL%
)
