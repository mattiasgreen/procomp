@echo off
setlocal
if not defined PROCOMP_MOCK_GIT_DELAY_MS set "PROCOMP_MOCK_GIT_DELAY_MS=3000"
if not exist "%~dp0logs" mkdir "%~dp0logs"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0demo\setup-demo.ps1"
if errorlevel 1 goto :fail
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0procomp.ps1" -ConfigPath "%~dp0demo\demo.procomp.json" %*
if errorlevel 1 goto :fail
exit /b 0

:fail
set "EC=%ERRORLEVEL%"
echo.
echo [demo.cmd] Failed with exit code %EC%. Check "%~dp0logs\procomp-error.log" for details.
exit /b %EC%
