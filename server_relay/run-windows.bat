@echo off
rem SENTRA relay - start it on Windows. Run install-windows.bat once first.
rem   run-windows.bat            listens on port 8787
rem   run-windows.bat 9000       listens on port 9000
rem Environment variables PORT, DB_PATH, LAN_ONLY and FIREBASE_KEY are passed through
rem (see README.md). Stop the relay with Ctrl+C.

setlocal EnableExtensions
title SENTRA relay
cd /d "%~dp0"

where node >nul 2>&1
if errorlevel 1 (
  echo Node.js was not found. Run install-windows.bat first.
  goto :fail
)
node -e "require('node:sqlite')" >nul 2>&1
if errorlevel 1 (
  echo This Node.js is too old ^(the relay needs 22.13 or later^). Run install-windows.bat.
  goto :fail
)

if not "%~1"=="" set "PORT=%~1"
if not defined PORT set "PORT=8787"

echo.
echo === SENTRA relay ===
echo SENTRA Care in a browser on this PC:  http://localhost:%PORT%/
echo Addresses for phones on the same WiFi:
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
  for /f "tokens=1" %%b in ("%%a") do echo    http://%%b:%PORT%
)
echo In the app: Settings - Find relay on this WiFi, or type one of the addresses above.
echo Press Ctrl+C to stop.
echo.

node --disable-warning=ExperimentalWarning server.mjs
echo.
echo The relay stopped.
pause
exit /b 0

:fail
echo.
pause
exit /b 1
