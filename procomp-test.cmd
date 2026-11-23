@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "JAVA_BIN=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
pushd "%SCRIPT_DIR%"
"%JAVA_BIN%" "%SCRIPT_DIR%test\TestRunner.java" %*
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%
