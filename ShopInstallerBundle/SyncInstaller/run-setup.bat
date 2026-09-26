@echo off
REM ===================================================================
REM  Run the shop PC setup again.
REM
REM  Same thing PawnBrokingSyncSetup.exe does after it installs the
REM  agent: sync tables and triggers, the desktop app's SUSPENSE and
REM  Re+ bits, the repledge_billing key, the one-time history send,
REM  and a check that the photo and backup folders exist on this PC.
REM
REM  Safe to run whenever you like. It only does what is still left,
REM  and it never sends the history twice.
REM
REM  Run it after restoring a database, after moving the shop to
REM  another PC, or when support asks you to.
REM
REM  Right-click -> Run as administrator.
REM ===================================================================

setlocal
set "HERE=%~dp0"
set "JAVA=%HERE%runtime\bin\java.exe"
set "JAR=%HERE%pawnbroking-sync-agent.jar"
set "CFG=%PROGRAMDATA%\PawnBroking\sync.properties"
set "REPORT=%HERE%logs\setup-report.txt"

if not exist "%JAVA%" (
    echo.
    echo  ERROR: %JAVA% not found.
    echo  Run PawnBrokingSyncSetup.exe once first.
    echo.
    pause
    exit /b 1
)
if not exist "%CFG%" (
    echo.
    echo  ERROR: %CFG% not found.
    echo  Run PawnBrokingSyncSetup.exe once first - it writes that file.
    echo.
    pause
    exit /b 1
)

REM  How far it may go:
REM     --mode check      looks only, changes NOTHING on this PC
REM     --mode backups    the backup files and nothing else; add
REM                       --backup-retention 0  and/or  --requeue-backups
REM     (no --mode)       the full setup: schema, history once, folders
REM
REM  Full setup only:
REM     --history skip    does everything EXCEPT sending the history
REM     --history force   sends the whole history again (support only)
"%JAVA%" -cp "%JAR%" com.magizhchi.sync.Setup --run --config "%CFG%" --report "%REPORT%" %*

echo.
echo  The report above was also saved to:
echo     %REPORT%
echo.
pause
