@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

if not exist "..\..\vmparams" (
    echo [MixinLib] vmparams not found
    pause
    exit /b 1
)

set "AGENT_LINE=-javaagent:..\mods\MixinLib\jars\MixinLib-bridge.jar"

findstr /c:"%AGENT_LINE%" "..\..\vmparams" >nul 2>&1
if %errorlevel% neq 0 (
    echo [MixinLib] Already uninstalled.
    pause
    exit /b 0
)

set "TMPFILE=%TEMP%\vmparams_mixinlib_%RANDOM%.tmp"
for /f "usebackq delims=" %%a in ("..\..\vmparams") do (
    set "LINE=%%a"
    set "LINE=!LINE:%AGENT_LINE% =!"
    set "LINE=!LINE: %AGENT_LINE%=!"
)
<nul > "%TMPFILE%" set /p "=!LINE!"

move /y "%TMPFILE%" "..\..\vmparams" >nul
echo [MixinLib] Uninstalled.
pause
