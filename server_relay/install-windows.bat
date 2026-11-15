@echo off
rem SENTRA relay - one-time install on Windows 10/11.
rem Double-click this file (or run it from a Command Prompt). It:
rem   1. installs Node.js LTS with winget if Node is missing,
rem   2. checks that Node is new enough for the built-in SQLite (22.13 or later),
rem   3. installs the one dependency (firebase-admin, only needed for push),
rem   4. runs the relay's end-to-end test,
rem   5. optionally opens port 8787 in Windows Firewall so phones on the WiFi can reach it.
rem Then start the relay with run-windows.bat.

setlocal EnableExtensions
title SENTRA relay - install
cd /d "%~dp0"
if not defined PORT set "PORT=8787"

echo.
echo === SENTRA relay: install ===
echo Folder: %CD%
echo.

rem --- 1. Node.js ---------------------------------------------------------------------------------
where node >nul 2>&1
if not errorlevel 1 goto :have_node

echo Node.js was not found. Installing Node.js LTS with winget...
where winget >nul 2>&1
if errorlevel 1 (
  echo.
  echo winget is not available on this PC. Install Node.js LTS by hand from https://nodejs.org
  echo then run this script again.
  goto :fail
)
winget install -e --id OpenJS.NodeJS.LTS --accept-source-agreements --accept-package-agreements
rem This window still has the old PATH; add the default install folder for the rest of the script.
set "PATH=%ProgramFiles%\nodejs;%PATH%"
where node >nul 2>&1
if errorlevel 1 (
  echo.
  echo Node.js was installed but this window cannot see it yet.
  echo Close this window and run install-windows.bat again.
  goto :fail
)

:have_node
for /f "delims=" %%v in ('node --version') do set "NODE_VERSION=%%v"
echo Node.js %NODE_VERSION% found.

rem --- 2. Built-in SQLite (Node 22.13 or later) --------------------------------------------------
node -e "require('node:sqlite')" >nul 2>&1
if errorlevel 1 (
  echo.
  echo Node.js %NODE_VERSION% is too old: the relay needs Node 22.13 or later for its built-in SQLite.
  echo Update it with:   winget upgrade -e --id OpenJS.NodeJS.LTS
  echo or download the LTS from https://nodejs.org, then run this script again.
  goto :fail
)

rem --- 3. Dependencies --------------------------------------------------------------------------
echo.
echo Installing dependencies (firebase-admin)...
call npm ci --omit=dev --no-audit --no-fund
if errorlevel 1 (
  echo.
  echo WARNING: npm could not install firebase-admin. The relay still works without it,
  echo but only over the live stream ^(no Firebase push^). Check the internet connection and
  echo run this script again if you need push.
)

rem --- 4. Self-test -----------------------------------------------------------------------------
echo.
echo Running the relay test (if Windows Firewall asks about Node.js, you can allow it)...
node --disable-warning=ExperimentalWarning test.mjs
if errorlevel 1 (
  echo.
  echo The relay test FAILED. See the messages above.
  goto :fail
)

rem --- 5. Firewall (optional, needs administrator) ----------------------------------------------
echo.
net session >nul 2>&1
if errorlevel 1 (
  echo To let phones on the same WiFi reach the relay, open port %PORT% once from an
  echo administrator Command Prompt:
  echo    netsh advfirewall firewall add rule name="SENTRA relay" dir=in action=allow protocol=TCP localport=%PORT% profile=private
  echo Or click "Allow access" when Windows Firewall asks the first time the relay starts.
  goto :done
)
netsh advfirewall firewall show rule name="SENTRA relay" >nul 2>&1
if not errorlevel 1 (
  echo Firewall rule "SENTRA relay" already exists.
  goto :done
)
choice /c YN /m "Open TCP port %PORT% in Windows Firewall for private networks"
if errorlevel 2 goto :done
netsh advfirewall firewall add rule name="SENTRA relay" dir=in action=allow protocol=TCP localport=%PORT% profile=private

:done
echo.
if exist "firebase-service-account.json" (
  echo Firebase key found: push to parents' phones is on.
) else (
  echo No firebase-service-account.json here: the relay runs stream-only, which is fine for testing.
)
echo.
echo === Install finished. Start the relay with run-windows.bat ===
echo.
pause
exit /b 0

:fail
echo.
echo === Install did not finish. ===
echo.
pause
exit /b 1
