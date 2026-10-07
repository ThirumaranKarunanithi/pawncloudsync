@echo off
REM =====================================================================
REM  UPDATE THE SYNC AGENT on an already-running shop
REM  (annanagar / balamurugan / karumbalai / alwarpuram ...)
REM
REM  Swaps in the new pawnbroking-sync-agent.jar and restarts the service.
REM  The new build adds:
REM    * gzip for backup uploads  - 500MB dumps became ~70MB, fixing the
REM      "cloud backup status=502 upstream error" failures
REM    * change detection - a backup file REPLACED at the same path is now
REM      re-uploaded instead of being skipped forever
REM
REM  RUN AS ADMINISTRATOR, from the folder that holds the NEW jar.
REM  Optional argument = install folder (default C:\Program Files\PawnbrokingSync)
REM
REM      update-agent.bat
REM      update-agent.bat "D:\PawnbrokingSync"
REM =====================================================================

setlocal
cd /d "%~dp0"

set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=C:\Program Files\PawnbrokingSync"
set "JAR=pawnbroking-sync-agent.jar"

echo.
echo ==========================================
echo   Sync Agent update
echo   Install folder: %TARGET%
echo ==========================================
echo.

REM ---- Must be admin (service stop/start needs it) ----
net session >nul 2>&1
if errorlevel 1 (
    echo ERROR: not running as Administrator.
    echo Right-click this file and choose "Run as administrator".
    pause
    exit /b 1
)

REM ---- Sanity checks ----
if not exist "%~dp0%JAR%" (
    echo ERROR: %JAR% not found next to this script.
    echo Copy the new jar here first.
    pause
    exit /b 1
)
if not exist "%TARGET%\%JAR%" (
    echo ERROR: no existing agent at "%TARGET%\%JAR%".
    echo Pass the correct install folder, e.g.
    echo     update-agent.bat "D:\PawnbrokingSync"
    pause
    exit /b 1
)

REM ---- Stop the service ----
echo [1/4] Stopping service...
"%TARGET%\pawnbroking-sync.exe" stop
REM Give Windows a moment to release the jar's file lock.
ping -n 4 127.0.0.1 >nul

REM ---- Keep the old jar so this is reversible ----
echo [2/4] Backing up the current jar...
set "STAMP=%DATE:~-4%%DATE:~4,2%%DATE:~7,2%-%TIME:~0,2%%TIME:~3,2%"
set "STAMP=%STAMP: =0%"
copy /Y "%TARGET%\%JAR%" "%TARGET%\%JAR%.%STAMP%.bak" >nul
if errorlevel 1 (
    echo ERROR: could not back up the old jar - is the service really stopped?
    pause
    exit /b 1
)
echo       saved as %JAR%.%STAMP%.bak

REM ---- Swap in the new jar ----
echo [3/4] Installing the new jar...
copy /Y "%~dp0%JAR%" "%TARGET%\%JAR%" >nul
if errorlevel 1 (
    echo ERROR: copy failed. The service may still be running.
    pause
    exit /b 1
)

REM ---- Start again ----
echo [4/4] Starting service...
"%TARGET%\pawnbroking-sync.exe" start
ping -n 4 127.0.0.1 >nul
"%TARGET%\pawnbroking-sync.exe" status

echo.
echo ==========================================
echo   DONE
echo ==========================================
echo Watch it work:
echo   findstr /C:"gzipped" "%TARGET%\pawnbroking-sync.out.log"
echo   findstr /C:"backup upload failed" "%TARGET%\pawnbroking-sync.err.log"
echo.
echo If status is not "Running", check pawnbroking-sync.err.log -
echo the usual causes are a wrong db.password or Java not being 17+.
echo.
pause
