@echo off
REM ===================================================================
REM  Install the Pawnbroking Sync Agent as a Windows Service.
REM  Run this file AS ADMINISTRATOR (right-click → Run as administrator).
REM ===================================================================

setlocal

REM 1. Verify sync.properties exists — refuse to install otherwise.
if not exist "%~dp0sync.properties" (
    echo.
    echo  ERROR: sync.properties was not found in this folder.
    echo  Please copy sync.properties.sample to sync.properties and
    echo  fill in your db password, shop_id, and cloud api key first.
    echo.
    pause
    exit /b 1
)

REM 2. Install + start the service using winsw (pawnbroking-sync.exe).
echo Installing Windows service "pawnbroking-sync"...
"%~dp0pawnbroking-sync.exe" install
if errorlevel 1 (
    echo  ERROR: install failed. See pawnbroking-sync.wrapper.log
    pause
    exit /b 1
)

echo Starting service...
"%~dp0pawnbroking-sync.exe" start
if errorlevel 1 (
    echo  ERROR: start failed. See pawnbroking-sync.err.log
    pause
    exit /b 1
)

echo.
echo  Done. The Sync Agent will now start automatically with Windows.
echo  To check status:   "%~dp0pawnbroking-sync.exe" status
echo  To stop:           "%~dp0pawnbroking-sync.exe" stop
echo  To uninstall:      "%~dp0pawnbroking-sync.exe" uninstall
echo.
pause
