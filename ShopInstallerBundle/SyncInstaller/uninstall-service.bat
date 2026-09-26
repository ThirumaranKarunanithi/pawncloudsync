@echo off
REM =====================================================================
REM  Uninstall the Pawnbroking Sync Agent Windows service.
REM  Called by PawnBrokingSyncSetup.exe during uninstall — also safe
REM  to run standalone (right-click Run as administrator).
REM  Does NOT delete C:\ProgramData\PawnBroking\sync.properties, so a
REM  re-install can keep the same shop identity.
REM =====================================================================

setlocal

if not exist "%~dp0pawnbroking-sync.exe" (
    echo pawnbroking-sync.exe not found in %~dp0 — nothing to uninstall.
    exit /b 0
)

sc query pawnbroking-sync >nul 2>&1
if errorlevel 1060 (
    echo Service pawnbroking-sync not installed — nothing to do.
    exit /b 0
)

echo Stopping service pawnbroking-sync...
"%~dp0pawnbroking-sync.exe" stop >nul 2>&1

REM Wait a moment for the JVM to fully release file handles before
REM the installer replaces / deletes the jar.
timeout /t 3 /nobreak >nul 2>&1

echo Uninstalling service pawnbroking-sync...
"%~dp0pawnbroking-sync.exe" uninstall
if errorlevel 1 (
    echo WARNING: service uninstall returned non-zero — check pawnbroking-sync.wrapper.log
    exit /b 1
)

echo Done.
exit /b 0
