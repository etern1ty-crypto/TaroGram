@echo off
REM ---------------------------------------------------------------------------
REM TaroGram stock-camera trace.
REM
REM Capture what the stock OPPO/Realme Camera (com.oplus.camera) actually
REM does when the user switches to ultra-wide and records a video. We
REM enable verbose log tags on the QCom cameraserver, the OPLUS service
REM layer, and the CamX HAL, run a clean logcat, then sit and wait while
REM the user records the 0.6x clip in the stock app. The result is the
REM exact sequence of CaptureRequest writes and vendor-key values we need
REM to replicate to reach the wide sensor without owning SYSTEM_CAMERA.
REM
REM Usage:
REM     scripts\stock-camera-trace.cmd
REM
REM Output:
REM     stock-camera-trace-{stamp}.txt    (the full filtered logcat)
REM     stock-camera-meta-{stamp}.txt     (vendor key + tag mapping helper)
REM ---------------------------------------------------------------------------

setlocal enabledelayedexpansion

for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value ^| find "="') do set DT=%%I
set "STAMP=%DT:~0,8%-%DT:~8,6%"
set "OUT=stock-camera-trace-%STAMP%.txt"
set "META=stock-camera-meta-%STAMP%.txt"

echo TaroGram stock-camera trace
echo ----------------------------
echo Output: %OUT% and %META%
echo.

adb get-state >nul 2>&1
if errorlevel 1 (
    echo [error] no adb device.
    exit /b 1
)

echo [step 1/6] check root...
adb shell "su -c 'id'" 1>"%TEMP%\stock_root.txt" 2>&1
findstr /C:"uid=0" "%TEMP%\stock_root.txt" >nul
set "HAS_ROOT=n"
if not errorlevel 1 set "HAS_ROOT=y"
echo   root=%HAS_ROOT%

echo [step 2/6] crank up camera logging verbosity...
> "%OUT%" echo TaroGram stock-camera trace %STAMP%
>> "%OUT%" echo root=%HAS_ROOT%
>> "%OUT%" echo.

REM Set log tags. We chase: CameraDeviceClient + CameraDevice + CamX HAL.
REM Also bump CamX core to debug. Without root, only persist.log.tag.*
REM that init exports survives a reboot; non-persistent setprops on log.tag.*
REM work even without root if uid=shell.
set "TAGS=Camera2 CameraManager CameraDevice CameraDeviceClient CaptureSession CameraExtensionSession CameraCharacteristics CameraMetadataNative CameraExtensionImpl CameraExtensionService OpCameraPanorama OpCameraSensorBox MtkVideoQuality FocusManager CamX CamX:Core CamX:HAL CHIUSECASE camxconfig camxsdk com.oplus.camera"
for %%T in (%TAGS%) do (
    adb shell "setprop log.tag.%%T VERBOSE" 1>nul 2>&1
)

if /i "%HAS_ROOT%"=="y" (
    REM Some tags require root because they sit under camera-server's selinux ctx.
    for %%T in (%TAGS%) do (
        adb shell "su -c 'setprop log.tag.%%T VERBOSE'" 1>nul 2>&1
    )
    REM OPLUS-side service. Bump core service flags too.
    adb shell "su -c 'setprop persist.vendor.camera.debug.logInfoMask 0xff'" 1>nul 2>&1
    adb shell "su -c 'setprop persist.vendor.camera.debug.coreLogMask 0xff'" 1>nul 2>&1
    adb shell "su -c 'setprop persist.vendor.camera.debug.HALLogMask 0xff'" 1>nul 2>&1
)
echo   verbosity bumped.

echo [step 3/6] clear logcat buffer + ensure stock Camera is closed...
adb shell "logcat -c"
adb shell "am force-stop com.oplus.camera" 1>nul 2>&1
adb shell "am force-stop com.coloros.camera" 1>nul 2>&1
adb shell "am force-stop com.oppo.camera" 1>nul 2>&1
ping -n 2 127.0.0.1 >nul

echo.
echo ===========================================================
echo   USER ACTION REQUIRED
echo ===========================================================
echo.
echo   1. On the phone, open the STOCK Realme/OPPO Camera app
echo      (the one that ships with the phone).
echo   2. Switch to VIDEO mode.
echo   3. Tap the 0.6x button to engage ULTRA-WIDE.
echo   4. Tap the red shutter to start recording.
echo   5. Wait 5 seconds.
echo   6. Tap the stop button to stop recording.
echo   7. Close the Camera app (swipe up to recents, swipe it away).
echo   8. Come back here and press ANY KEY.
echo.
echo ===========================================================
echo.
pause

echo [step 4/6] pull logcat...
adb shell "logcat -d -v threadtime" > "%TEMP%\stock_logcat_raw.txt" 2>&1
adb shell "logcat -d -v threadtime -b system" >> "%TEMP%\stock_logcat_raw.txt" 2>&1
adb shell "logcat -d -v threadtime -b crash" >> "%TEMP%\stock_logcat_raw.txt" 2>&1

echo [step 5/6] filter for camera + vendor-key activity...
findstr /R /C:"com\.oplus\.camera" /C:"com\.oplus\." /C:"camx" /C:"CamX" /C:"qcam" /C:"QCam" /C:"CameraDevice" /C:"CaptureRequest" /C:"CaptureSession" /C:"zoomRatio" /C:"Zoom" /C:"appZoomMultiples" /C:"LogicalCameraId" /C:"setPhysicalCameraId" /C:"openCamera" /C:"connectDevice" /C:"USE_CASE" /C:"sat\." /C:"aps\." /C:"dualscene" /C:"original.zoom" /C:"operation\.mode" "%TEMP%\stock_logcat_raw.txt" >> "%OUT%"

REM Also: full vendor key dictionary so we have section IDs handy.
echo. >> "%META%"
echo ====== vendor keys defined on this device ====== >> "%META%"
adb shell "dumpsys media.camera | sed -n '/Vendor tags:/,$p' | head -2000" >> "%META%"

echo [step 6/6] reset log tags...
for %%T in (%TAGS%) do (
    adb shell "setprop log.tag.%%T INFO" 1>nul 2>&1
)
if /i "%HAS_ROOT%"=="y" (
    for %%T in (%TAGS%) do (
        adb shell "su -c 'setprop log.tag.%%T INFO'" 1>nul 2>&1
    )
)

echo.
echo Done.
echo Filtered logcat: %OUT%
echo Vendor key index: %META%
echo Raw logcat (huge, only if filtered file is empty): %TEMP%\stock_logcat_raw.txt
echo.
echo Send both .txt files back to Devin.

exit /b 0
