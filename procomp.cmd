@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "JAVA_BIN=java"
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
  )
)
"%JAVA_BIN%" "%SCRIPT_DIR%src\procomp\cli\Procomp.java" %*
exit /b %ERRORLEVEL%
