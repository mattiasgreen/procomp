@echo off
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-Pester -Path '%~dp0tests' -EnableExit"
