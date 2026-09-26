@echo off
REM =====================================================================
REM  Update the Pawnbroking Sync Agent jar in-place.
REM  Usage: drop the new pawnbroking-sync-agent.jar next to this .bat
REM  (or pass its path as the first argument), then run as admin.
REM  Stops the service, backs up the current jar, swaps in the new one,
REM  restarts the service. Config in ProgramData is left untouched.
REM =====================================================================

setlocal

set "TARGET=%~dp0pawnbroking-sync-agent.jar"

if "%~1" NEQ "" (
    set "NEWJAR=%~1"
) else (
    set "NEWJAR=%~dp0pawnbroking-sync-agent.jar.new"
)

if not exist "%NEWJAR%" (
    echo ERROR: new jar not found at "%NEWJAR%"
    echo Drop the replacement as "pawnbroking-sync-agent.jar.new" next to this
    echo script, or pass its path as the first argument.
    exit /b 1
)

echo Stopping pawnbroking-sync service...
"%~dp0pawnbroking-sync.exe" stop >nul 2>&1
timeout /t 3 /nobreak >nul 2>&1

if exist "%TARGET%" (
    for /f "tokens=1-4 delims=/: " %%A in ("%date% %time%") do (
        copy /y "%TARGET%" "%TARGET%.bak_%%D%%B%%C_%%A%%B%%C" >nul
    )
)

copy /y "%NEWJAR%" "%TARGET%"
if errorlevel 1 (
    echo ERROR: copy failed — restarting old service and aborting.
    "%~dp0pawnbroking-sync.exe" start
    exit /b 1
)

echo Starting pawnbroking-sync service...
"%~dp0pawnbroking-sync.exe" start
if errorlevel 1 (
    echo WARNING: service failed to start — check pawnbroking-sync.err.log
    exit /b 1
)

echo Done.
exit /b 0
