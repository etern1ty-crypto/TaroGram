@echo off
REM ---------------------------------------------------------------------------
REM TaroGram wide-angle UNLOCK ATTEMPT v3.
REM
REM v1: setprop append failed -- value exceeded the 92-byte property limit.
REM v2: replaced 'com.oplus.engineercamera' with our package (fits at 72
REM      bytes); ctl.restart cameraserver brought back 5 cameras at the HAL
REM      layer, but the framework (system_server's CameraManager) still
REM      filtered devices 2/3/4 because:
REM        - those devices self-flag SYSTEM_CAMERA in their API2 capabilities
REM        - the OPPO patch which bypasses the SYSTEM_CAMERA check for
REM          packages in persist.camera.privapp.list runs inside system_server
REM        - system_server reads persist.* properties at boot, then caches.
REM
REM v3: in addition to setprop + camera service restart, also bounces the
REM      Android framework via `stop && start` so system_server re-reads
REM      the property. UI restarts but no kernel reboot is needed (~30 s).
REM
REM Modes:
REM     scripts\wide-unlock-attempt.cmd           -- setprop + camera restart only
REM     scripts\wide-unlock-attempt.cmd reboot    -- setprop + soft framework reboot
REM     scripts\wide-unlock-attempt.cmd verify    -- only re-dump state (post manual reboot)
REM ---------------------------------------------------------------------------

setlocal enabledelayedexpansion

set "PKG=uz.unnarsx.cherrygram"
set "PRIVAPP_PROP=persist.camera.privapp.list"
set "NEW_LIST=com.oneplus.camera,com.oppo.camera,uz.unnarsx.cherrygram,com.oplus.camera"

set "MODE=cam-only"
if /i "%~1"=="reboot" set "MODE=reboot"
if /i "%~1"=="verify" set "MODE=verify"

for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value ^| find "="') do set DT=%%I
set "STAMP=%DT:~0,8%-%DT:~8,6%"
set "OUT=wide-unlock-%STAMP%.txt"

echo TaroGram wide-angle unlock attempt v3
echo --------------------------------------
echo Output: %OUT%
echo Mode  : %MODE%
echo Target: %PKG%
echo New value of %PRIVAPP_PROP%:
echo   %NEW_LIST%
echo.

adb get-state >nul 2>&1
if errorlevel 1 (
    echo [error] adb does not see any device. Plug in the phone, enable USB debugging, then re-run.
    exit /b 1
)

> "%OUT%" echo TaroGram wide-angle unlock attempt v3 -- %STAMP%
>> "%OUT%" echo Mode: %MODE%
>> "%OUT%" echo Package: %PKG%
>> "%OUT%" echo Target new list: %NEW_LIST%
>> "%OUT%" echo.

echo [step] BEFORE state...
call :hdr "BEFORE: persist.camera.privapp.list"
call :run "getprop %PRIVAPP_PROP%"

call :hdr "BEFORE: dumpsys media.camera head"
call :run "dumpsys media.camera | head -10"

if /i "%MODE%"=="verify" goto :enumerate_app

echo [step] verify root...
adb shell "su -c 'id'" 1>"%TEMP%\rootcheck.txt" 2>&1
findstr /C:"uid=0" "%TEMP%\rootcheck.txt" >nul
if errorlevel 1 (
    echo [error] root not available. su returned:
    type "%TEMP%\rootcheck.txt"
    >> "%OUT%" echo ERROR: root not available, aborting.
    type "%TEMP%\rootcheck.txt" >> "%OUT%"
    exit /b 2
)
echo   root OK.

echo [step] setprop %PRIVAPP_PROP% (idempotent)...
for /f "delims=" %%V in ('adb shell getprop %PRIVAPP_PROP%') do set "CUR=%%V"
if "!CUR!"=="%NEW_LIST%" (
    echo   already correct value, skipping setprop.
    >> "%OUT%" echo persist.camera.privapp.list already correct, skipped setprop.
) else (
    call :hdr "ACTION: setprop %PRIVAPP_PROP%"
    adb shell "su -c 'setprop %PRIVAPP_PROP% \"%NEW_LIST%\"'" >> "%OUT%" 2>&1
    call :run "getprop %PRIVAPP_PROP%"
)

if /i "%MODE%"=="reboot" goto :framework_restart

REM ----- MODE=cam-only: bounce only cameraserver -----
call :hdr "ACTION: ctl.restart cameraserver"
adb shell "su -c 'setprop ctl.restart cameraserver'" >> "%OUT%" 2>&1
ping -n 5 127.0.0.1 >nul
call :run "ps -A | grep -iE 'cameraserver|camera.provider'"
goto :enumerate_app

:framework_restart
echo [step] soft-reboot Android framework via `stop` + `start`...
echo   this restarts system_server + UI -- the phone screen will go black
echo   and come back to lockscreen in ~30 seconds. persist.* survives.
call :hdr "ACTION: framework stop"
adb shell "su -c 'stop'" >> "%OUT%" 2>&1
echo   waited 4s after stop...
ping -n 5 127.0.0.1 >nul

call :hdr "ACTION: framework start"
adb shell "su -c 'start'" >> "%OUT%" 2>&1

echo   waiting for boot_completed...
:wait_boot
ping -n 4 127.0.0.1 >nul
for /f "delims=" %%B in ('adb shell getprop sys.boot_completed 2^>nul') do set "BC=%%B"
if not "!BC!"=="1" (
    echo   sys.boot_completed=!BC! -- waiting...
    goto :wait_boot
)
echo   boot complete. Settling 5s...
ping -n 6 127.0.0.1 >nul

:enumerate_app
call :hdr "AFTER: dumpsys media.camera head"
call :run "dumpsys media.camera | head -10"

call :hdr "AFTER: dumpsys media.camera Camera service events log (recent)"
call :run "dumpsys media.camera | sed -n '/Camera service events log/,/Camera device 0/p' | head -50"

echo.
echo [step] now open TaroGram on the phone and go to:
echo    Settings -> TaroGram -> Camera -> TaroCamera settings -> 'Re-run probe'
echo.
echo Expected if unlock worked:
echo    'Detected lenses' table shows 5 items (id=0..4) including
echo    id=2 BACK ultra-wide. Or at least the 'hidden' counter goes up.
echo.
echo If still 2 lenses / 0 hidden -- send wide-unlock-%STAMP%.txt back
echo plus a fresh diagnostic export and we will write v4.
echo.

exit /b 0


:hdr
echo. >> "%OUT%"
echo ========================== >> "%OUT%"
echo  %~1 >> "%OUT%"
echo ========================== >> "%OUT%"
echo   ==== %~1
goto :EOF

:run
echo. >> "%OUT%"
echo $ adb shell %~1 >> "%OUT%"
adb shell %~1 >> "%OUT%" 2>&1
goto :EOF
