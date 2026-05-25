@echo off
REM ---------------------------------------------------------------------------
REM TaroGram wide-angle UNLOCK ATTEMPT v2.
REM
REM v1 hit the Android property value limit (92 bytes). v2 replaces the
REM unused 'com.oplus.engineercamera' entry (factory-engineering build, not
REM user-facing) with our package, staying under the 92-byte limit while
REM keeping the stock OPLUS Camera (com.oplus.camera) in the allow-list.
REM
REM Final list:
REM   com.oneplus.camera,com.oppo.camera,uz.unnarsx.cherrygram,com.oplus.camera
REM   (72 bytes, well under the 92-byte limit)
REM
REM Camera providers also need to restart properly. We use init's
REM `ctl.restart` and `ctl.stop`/`ctl.start` props instead of `killall`
REM which was tearing down the QCom HAL provider without bringing it back.
REM
REM Usage:
REM     scripts\wide-unlock-attempt.cmd
REM
REM Or to verify after a manual reboot (skip the setprop+restart phase):
REM     scripts\wide-unlock-attempt.cmd verify
REM ---------------------------------------------------------------------------

setlocal enabledelayedexpansion

set "PKG=uz.unnarsx.cherrygram"
set "PRIVAPP_PROP=persist.camera.privapp.list"
set "NEW_LIST=com.oneplus.camera,com.oppo.camera,uz.unnarsx.cherrygram,com.oplus.camera"

set "MODE=full"
if /i "%~1"=="verify" set "MODE=verify"

for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value ^| find "="') do set DT=%%I
set "STAMP=%DT:~0,8%-%DT:~8,6%"
set "OUT=wide-unlock-%STAMP%.txt"

echo TaroGram wide-angle unlock attempt v2
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

> "%OUT%" echo TaroGram wide-angle unlock attempt v2 -- %STAMP%
>> "%OUT%" echo Mode: %MODE%
>> "%OUT%" echo Package: %PKG%
>> "%OUT%" echo Target new list: %NEW_LIST%
>> "%OUT%" echo.

echo [step] BEFORE state...
call :hdr "BEFORE: persist.camera.privapp.list"
call :run "getprop %PRIVAPP_PROP%"

call :hdr "BEFORE: dumpsys media.camera head"
call :run "dumpsys media.camera | head -10"

if /i "%MODE%"=="verify" goto :verify

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

echo [step] setprop %PRIVAPP_PROP% to new shorter list...
call :hdr "ACTION: setprop %PRIVAPP_PROP%"
adb shell "su -c 'setprop %PRIVAPP_PROP% \"%NEW_LIST%\"'" >> "%OUT%" 2>&1
call :run "getprop %PRIVAPP_PROP%"

echo [step] restart camera provider + cameraserver via init (not killall)...
call :hdr "ACTION: ctl.restart camera-provider"
adb shell "su -c 'setprop ctl.restart camera-provider-2-7'" >> "%OUT%" 2>&1
ping -n 3 127.0.0.1 >nul
call :run "ps -A | grep -iE 'cameraserver|camera.provider'"

call :hdr "ACTION: ctl.restart cameraserver"
adb shell "su -c 'setprop ctl.restart cameraserver'" >> "%OUT%" 2>&1
ping -n 4 127.0.0.1 >nul
call :run "ps -A | grep -iE 'cameraserver|camera.provider'"

echo [step] wait 6s for cameraserver to enumerate cameras...
ping -n 7 127.0.0.1 >nul

:verify
echo [step] AFTER state...
call :hdr "AFTER: dumpsys media.camera head"
call :run "dumpsys media.camera | head -10"

call :hdr "AFTER: dumpsys media.camera full -- look for 5 devices"
call :run "dumpsys media.camera"

echo.
echo Done. Send %OUT% back to Devin.
echo.
echo Read:
echo   - BEFORE 'public camera devices visible to API1: 2' is normal.
echo   - AFTER 'public ... : 5' = unlock WORKED.
echo   - AFTER 'Number of camera devices: 0' = cameraserver did not finish
echo     re-enumerating. REBOOT the phone (persist.* survives) and run:
echo         scripts\wide-unlock-attempt.cmd verify
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
