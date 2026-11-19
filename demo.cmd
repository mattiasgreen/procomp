@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0demo\setup-demo.ps1"
if errorlevel 1 exit /b %errorlevel%
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0procomp.ps1" -ConfigPath "%~dp0demo\demo.procomp.json" %*
