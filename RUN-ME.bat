@echo off
rem Double-click to run BMS Bank. First run: checks tools, creates .env, builds everything (needs internet).
cd /d "%~dp0"
if not exist ".env" goto setup
if not exist "backend-java\target\bms-backend.jar" goto setup
if not exist "frontend\dist\index.html" goto setup
if not exist "analytics-python\.venv\Scripts\python.exe" goto setup
goto start

:setup
echo First run - setting up (this takes a few minutes)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"
if errorlevel 1 (
  echo.
  echo Setup failed. Install the missing tools listed above, then run RUN-ME.bat again.
  pause
  exit /b 1
)

:start
rem Already up? Just open the browser.
powershell -NoProfile -Command "try { $r = Invoke-WebRequest http://127.0.0.1:8080/api/v1/health -UseBasicParsing -TimeoutSec 3; Invoke-WebRequest http://127.0.0.1:5173/ -UseBasicParsing -TimeoutSec 3 | Out-Null; exit 0 } catch { exit 1 }"
if not errorlevel 1 (
  echo BMS Bank is already running.
  goto open
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
if errorlevel 1 (
  echo.
  echo Start failed. See the logs folder, or run STOP.bat and try again.
  pause
  exit /b 1
)

:open
start "" http://localhost:5173
echo.
echo BMS Bank is running at http://localhost:5173
echo Logins are in the .env file (DEMO_PASSWORD, BOOTSTRAP_ADMIN_PASSWORD).
echo Close everything with STOP.bat
pause
