@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

if not exist "..\..\vmparams" (
    echo [MixinLib] vmparams not found
    echo Please ensure MixinLib is in your Starsector mods folder.
    pause
    exit /b 1
)

set "AGENT_LINE=-javaagent:..\mods\MixinLib\jars\MixinLib-bridge.jar"
set "MAIN_CLASS=com.fs.starfarer.StarfarerLauncher"

findstr /c:"%AGENT_LINE%" "..\..\vmparams" >nul 2>&1
if %errorlevel% equ 0 (
    echo [MixinLib] Already installed.
    pause
    exit /b 0
)

set "TMPFILE=%TEMP%\vmparams_mixinlib_%RANDOM%.tmp"
for /f "usebackq delims=" %%a in ("..\..\vmparams") do (
    set "LINE=%%a"
    set "LINE=!LINE:%MAIN_CLASS%=%AGENT_LINE% %MAIN_CLASS%!"
)
<nul > "%TMPFILE%" set /p "=!LINE!"

move /y "%TMPFILE%" "..\..\vmparams" >nul
echo [MixinLib] Installed. Launch Starsector normally.
pause
