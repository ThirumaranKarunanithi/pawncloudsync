@echo off
REM =====================================================================
REM  Build PawnBrokingSyncSetup.exe
REM
REM  Produces  dist\PawnBrokingSyncSetup.exe  — a one-click Windows
REM  installer for the Pawnbroking Sync Agent that can be reused for
REM  every new shop (per-shop identity is entered in the wizard).
REM
REM  Steps:
REM    [1/5] Sanity-check JDK, Inno Setup, and source artefacts
REM    [2/5] Build a minimal JRE with jlink               -> stage\runtime\
REM    [3/5] Copy pawnbroking-sync-agent.jar + WinSW      -> stage\
REM    [4/5] Copy per-install WinSW xml                   -> stage\
REM    [5/5] Compile installer.iss                        -> dist\PawnBrokingSyncSetup.exe
REM
REM  Edit paths under "Config" below if your dev box differs.
REM =====================================================================

setlocal
cd /d "%~dp0"

REM ---- Config (override on command line via  set VAR=... before calling) ----
if not defined JDK_HOME    set "JDK_HOME=C:\Program Files\Java\jdk-17"
if not defined INNO        set "INNO=C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
if not defined AGENT_JAR   set "AGENT_JAR=D:\Pawnbroking\PawnBrokingMobApp\pawnbroking-sync-agent\target\pawnbroking-sync-agent.jar"
if not defined WINSW_EXE   set "WINSW_EXE=D:\Pawnbroking\PawnBrokingMobApp\ShopInstallerBundle\3-SyncAgent\pawnbroking-sync.exe"

set "STAGE=stage"

echo =============================================
echo   Pawnbroking Sync Agent Installer Builder
echo =============================================
echo.

REM ---- [1/5] Sanity ----
echo [1/5] Checking prerequisites...
if not exist "%JDK_HOME%\bin\jlink.exe"    goto err_jdk
echo    JDK_HOME   ok  (%JDK_HOME%)
if not exist "%INNO%"                       goto err_inno
echo    Inno Setup ok  (%INNO%)
if not exist "%AGENT_JAR%"                  goto err_jar
echo    Agent jar  ok  (%AGENT_JAR%)
if not exist "%WINSW_EXE%"                  goto err_winsw
echo    WinSW exe  ok  (%WINSW_EXE%)
if not exist "pawnbroking-sync.xml"         goto err_xml
if not exist "sync.properties.template"     goto err_tpl
if not exist "installer.iss"                goto err_iss
if not exist "run-setup.bat"                goto err_helper
if not exist "upload-progress.ps1"          goto err_helper
echo    Local files ok
echo.

REM ---- Clean stage ----
if exist "%STAGE%" rd /s /q "%STAGE%"
mkdir "%STAGE%"

REM ---- [2/5] jlink minimal JRE ----
echo [2/5] Building minimal JRE via jlink...
REM Modules needed by the sync agent (Hikari + Postgres JDBC + Jackson +
REM logback + java.net.http). Small overshoot on modules is cheaper than
REM chasing NoClassDefFoundError at runtime.
"%JDK_HOME%\bin\jlink.exe" ^
    --module-path "%JDK_HOME%\jmods" ^
    --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.localedata,jdk.charsets,jdk.management,jdk.naming.dns,jdk.httpserver ^
    --strip-debug --no-header-files --no-man-pages ^
    --compress=2 --include-locales=en ^
    --output "%STAGE%\runtime"
if errorlevel 1 goto err_jlink
echo    JRE staged at %STAGE%\runtime\  (^)
echo.

REM ---- [3/5] Copy agent + WinSW binary ----
echo [3/5] Staging agent jar + WinSW binary...
copy /y "%AGENT_JAR%" "%STAGE%\pawnbroking-sync-agent.jar" >nul
if errorlevel 1 goto err_copy_jar
copy /y "%WINSW_EXE%" "%STAGE%\pawnbroking-sync.exe" >nul
if errorlevel 1 goto err_copy_winsw
echo    staged.
echo.

REM ---- [4/5] Copy WinSW config ----
echo [4/5] Staging WinSW config...
copy /y "pawnbroking-sync.xml" "%STAGE%\pawnbroking-sync.xml" >nul
if errorlevel 1 goto err_copy_xml
echo    staged.
echo.

REM ---- [5/5] Compile installer ----
echo [5/5] Compiling installer with Inno Setup...
if not exist "dist" mkdir "dist"
if exist "dist\PawnBrokingSyncSetup.exe" del /q "dist\PawnBrokingSyncSetup.exe" >nul 2>&1
"%INNO%" "installer.iss"
if errorlevel 1 goto err_inno_run

echo.
echo =============================================
echo   SUCCESS
echo   Installer: %CD%\dist\PawnBrokingSyncSetup.exe
echo =============================================
pause
exit /b 0

:err_jdk
echo.
echo ERROR: JDK not found at "%JDK_HOME%"
echo Install JDK 17 (Adoptium Temurin) or  set JDK_HOME=...  before running.
pause
exit /b 1

:err_inno
echo.
echo ERROR: Inno Setup compiler not found at "%INNO%"
echo Install Inno Setup 6 from https://jrsoftware.org/isinfo.php  or  set INNO=...  first.
pause
exit /b 1

:err_jar
echo.
echo ERROR: sync-agent jar not found at "%AGENT_JAR%"
echo Run 'mvn clean package' inside  D:\Pawnbroking\PawnBrokingMobApp\pawnbroking-sync-agent\  first.
pause
exit /b 1

:err_winsw
echo.
echo ERROR: WinSW binary not found at "%WINSW_EXE%"
echo Expected the renamed  pawnbroking-sync.exe  from the 3-SyncAgent bundle.
pause
exit /b 1

:err_xml
echo.
echo ERROR: pawnbroking-sync.xml missing from this folder.
pause
exit /b 1

:err_tpl
echo.
echo ERROR: sync.properties.template missing from this folder.
pause
exit /b 1

:err_iss
echo.
echo ERROR: installer.iss missing from this folder.
pause
exit /b 1

:err_helper
echo.
echo ERROR: run-setup.bat or upload-progress.ps1 missing from this folder.
echo Both are installed beside the agent and are what the shop runs later
echo (upload-progress.ps1 is the copy from ..\3-SyncAgent).
pause
exit /b 1

:err_jlink
echo.
echo ERROR: jlink failed (exit %ERRORLEVEL%). Check that your JDK includes jmods.
pause
exit /b 1

:err_copy_jar
echo.
echo ERROR: could not copy sync agent jar into stage.
pause
exit /b 1

:err_copy_winsw
echo.
echo ERROR: could not copy WinSW exe into stage.
pause
exit /b 1

:err_copy_xml
echo.
echo ERROR: could not copy WinSW xml into stage.
pause
exit /b 1

:err_inno_run
echo.
echo ERROR: Inno Setup compilation failed (exit %ERRORLEVEL%).
pause
exit /b 1
