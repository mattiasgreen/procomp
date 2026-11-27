@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "JAR_PATH=%SCRIPT_DIR%build\libs\procomp.jar"
if not exist "%JAR_PATH%" (
  echo Error: '%JAR_PATH%' not found. Build it first with 'gradlew.bat :assemble'. 1>&2
  exit /b 1
)
set "JAVA_BIN=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
"%JAVA_BIN%" -jar "%JAR_PATH%" %*
exit /b %ERRORLEVEL%
