@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "ENV_FILE=%SCRIPT_DIR%.env"
if not "%~1"=="" set "ENV_FILE=%~1"

if exist "%ENV_FILE%" (
  echo [info] Loading env from "%ENV_FILE%"
  for /f "usebackq delims=" %%L in ("%ENV_FILE%") do (
    set "line=%%L"
    if defined line (
      if not "!line:~0,1!"=="#" (
        for /f "tokens=1* delims==" %%A in ("!line!") do (
          set "key=%%~A"
          set "val=%%~B"
          if defined key (
            for /f "tokens=* delims= " %%K in ("!key!") do set "key=%%~K"
            if defined val if "!val:~0,1!"==" " set "val=!val:~1!"
            set "!key!=!val!"
          )
        )
      )
    )
  )
) else (
  echo [warn] Env file not found at "%ENV_FILE%". Using current shell env vars.
)

if not defined PRIMARY_MODE set "PRIMARY_MODE=openai"
if not defined SHADOW_MODE set "SHADOW_MODE=true"
if not defined BACKEND_PORT set "BACKEND_PORT=4000"

echo [info] Starting backend with PRIMARY_MODE=%PRIMARY_MODE% SHADOW_MODE=%SHADOW_MODE% BACKEND_PORT=%BACKEND_PORT%
python "%SCRIPT_DIR%reply_backend.py"
