@echo off
rem SENTRA relay - one-time install on Windows 10/11.
rem Double-click this file (or run it from a Command Prompt). It:
rem   1. installs Node.js LTS if Node is missing (winget, or the installer from nodejs.org),
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
rem Installed earlier, but this window was opened before PATH was updated.
if exist "%ProgramFiles%\nodejs\node.exe" goto :add_node_path

echo Node.js was not found. Installing Node.js LTS...
where winget >nul 2>&1
if errorlevel 1 goto :node_msi
rem --source winget: skip the Microsoft Store source, which fails on some PCs (certificate errors)
rem and makes winget refuse to choose between the two sources.
winget install -e --id OpenJS.NodeJS.LTS --source winget --accept-source-agreements --accept-package-agreements
if exist "%ProgramFiles%\nodejs\node.exe" goto :add_node_path
echo.
echo winget could not install Node.js. Downloading the installer from nodejs.org instead...

:node_msi
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $a=if($env:PROCESSOR_ARCHITECTURE -eq 'ARM64'){'arm64'}else{'x64'}; $v=(Invoke-RestMethod -UseBasicParsing 'https://nodejs.org/dist/index.json' | Where-Object { $_.lts } | Select-Object -First 1).version; $f=Join-Path $env:TEMP ('node-'+$v+'-'+$a+'.msi'); Write-Host ('Downloading Node.js '+$v+' ('+$a+')...'); Invoke-WebRequest -UseBasicParsing ('https://nodejs.org/dist/'+$v+'/node-'+$v+'-'+$a+'.msi') -OutFile $f; Write-Host 'Running the Node.js installer...'; $p=Start-Process msiexec.exe -ArgumentList ('/i '+[char]34+$f+[char]34+' /passive /norestart') -Wait -PassThru; if($p.ExitCode -in 0,3010){exit 0}else{Write-Host ('Installer exit code '+$p.ExitCode); exit 1}"
if errorlevel 1 goto :node_failed
if exist "%ProgramFiles%\nodejs\node.exe" goto :add_node_path

:node_failed
echo.
echo Node.js could not be installed automatically. Install Node.js LTS by hand from
echo https://nodejs.org (the Windows Installer .msi), then run this script again.
echo If the errors above mention certificates, check that this PC's date and time are correct.
goto :fail

:add_node_path
rem This window still has the old PATH; add the install folder for the rest of the script.
set "PATH=%ProgramFiles%\nodejs;%PATH%"

:have_node
for /f "delims=" %%v in ('node --version') do set "NODE_VERSION=%%v"
echo Node.js %NODE_VERSION% found.

rem --- 2. Built-in SQLite (Node 22.13 or later) --------------------------------------------------
node -e "require('node:sqlite')" >nul 2>&1
if errorlevel 1 (
  echo.
  echo Node.js %NODE_VERSION% is too old: the relay needs Node 22.13 or later for its built-in SQLite.
  echo Update it with:   winget upgrade -e --id OpenJS.NodeJS.LTS --source winget
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
