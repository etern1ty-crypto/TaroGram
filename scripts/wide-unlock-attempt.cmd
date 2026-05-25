@echo off
REM ---------------------------------------------------------------------------
REM TaroGram wide-angle UNLOCK ATTEMPT for Realme/OPPO/OnePlus.
REM
REM Hypothesis from the prior probe: the gate that hides cameras 2 (WIDE),
REM 3 (MACRO) and 4 (MAIN-FULL) is the `persist.camera.privapp.list`
REM allow-list. The stock OPPO Camera is on that list; we are not. If we
REM add our package to it and bounce cameraserver, the framework should
REM stop filtering those cameras out for us.
REM
REM Mapping established from dumpsys media.camera (Camera HAL device static
REM information block):
REM   Device 0: BACK,  focal 5.59mm, sensor 8.19x6.14    -> MAIN (cropped)
REM   Device 1: FRONT, focal 3.23mm, sensor 4.61x3.46    -> SELFIE
REM   Device 2: BACK,  focal 1.68mm, sensor 3.26x2.45    -> WIDE  <- want this
REM   Device 3: BACK,  focal 1.63mm, sensor 2.80x2.10    -> MACRO/AUX
REM   Device 4: BACK,  focal 5.59mm, sensor 8.19x6.14    -> MAIN (full)
REM
REM REQUIREMENTS: root + adb. SELinux mode is irrelevant -- this is a
REM framework-level filter, not an SELinux one. No SELinux denials were
REM observed for cameraserver access in the previous probe.
REM
REM Usage:
REM     scripts\wide-unlock-attempt.cmd
REM
REM Output:
REM   wide-unlock-{stamp}.txt -- before/after dumpsys media.camera plus
REM                              the setprop transaction. Send it back.
REM ---------------------------------------------------------------------------

setlocal enabledelayedexpansion

set "PKG=uz.unnarsx.cherrygram"
set "PRIVAPP_PROP=persist.camera.privapp.list"

for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value ^| find "="') do set DT=%%I
set "STAMP=%DT:~0,8%-%DT:~8,6%"
set "OUT=wide-unlock-%STAMP%.txt"

echo TaroGram wide-angle unlock attempt
echo ----------------------------------
echo Output: %OUT%
echo Package to add: %PKG%
echo Property: %PRIVAPP_PROP%
echo.

adb get-state >nul 2>&1
if errorlevel 1 (
    echo [error] adb does not see any device. Plug in the phone, enable USB debugging, then re-run.
    exit /b 1
)

> "%OUT%" echo TaroGram wide-angle unlock attempt -- %STAMP%
>> "%OUT%" echo Package: %PKG%
>> "%OUT%" echo.

echo [step 1/8] Capture BEFORE state of the privapp list and camera count...
call :hdr "BEFORE: persist.camera.privapp.list"
call :run "getprop %PRIVAPP_PROP%"

call :hdr "BEFORE: dumpsys media.camera head"
call :run "dumpsys media.camera | head -10"

echo [step 2/8] Read current privapp list value...
for /f "delims=" %%V in ('adb shell getprop %PRIVAPP_PROP%') do set "CUR_LIST=%%V"
echo   current list: !CUR_LIST!

REM Sanity-check: if our package is already in the list, do not modify.
echo !CUR_LIST! | findstr /C:"%PKG%" >nul
if not errorlevel 1 (
    echo   our package is already in the list. No change needed.
    >> "%OUT%" echo NOTE: %PKG% was already in %PRIVAPP_PROP% -- no modification done.
    goto :verify
)

set "NEW_LIST=!CUR_LIST!,%PKG%"
echo   new list: !NEW_LIST!

echo [step 3/8] Verify root...
adb shell "su -c 'id'" 1>"%TEMP%\rootcheck.txt" 2>&1
set "ROOT_OK=n"
findstr /C:"uid=0" "%TEMP%\rootcheck.txt" >nul && set "ROOT_OK=y"
if /i "%ROOT_OK%"=="n" (
    echo [error] root not available. su returned:
    type "%TEMP%\rootcheck.txt"
    >> "%OUT%" echo ERROR: root not available, aborting.
    type "%TEMP%\rootcheck.txt" >> "%OUT%"
    exit /b 2
)
echo   root available.

echo [step 4/8] setprop %PRIVAPP_PROP% with appended package...
call :hdr "ACTION: setprop %PRIVAPP_PROP% to add %PKG%"
adb shell "su -c 'setprop %PRIVAPP_PROP% \"!NEW_LIST!\"'" >> "%OUT%" 2>&1
call :run "getprop %PRIVAPP_PROP%"

echo [step 5/8] killall cameraserver to force re-read of the prop...
call :hdr "ACTION: killall cameraserver"
adb shell "su -c 'killall cameraserver'" >> "%OUT%" 2>&1

echo [step 6/8] wait 4s for cameraserver to come back up...
ping -n 5 127.0.0.1 >nul

call :hdr "AFTER kill: cameraserver process"
call :run "ps -A | grep -iE 'cameraserver|camera.provider'"

:verify
echo [step 7/8] AFTER state of the camera count...
call :hdr "AFTER: dumpsys media.camera head"
call :run "dumpsys media.camera | head -10"

echo [step 8/8] full per-device characteristics summary (5 devices)...
call :hdr "AFTER: dumpsys media.camera per-device facing+focal"
call :run "dumpsys media.camera"

echo.
echo Done. Send %OUT% back to Devin.
echo.
echo Quick read for you:
echo   - if BEFORE said 'public camera devices visible to API1: 2'
echo     and AFTER says ': 5'  -- the unlock WORKED.
echo   - if AFTER still says ': 2' -- the gate is elsewhere; send the file
echo     and we will try the next vector.
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
