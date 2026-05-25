@echo off
REM ---------------------------------------------------------------------------
REM TaroGram wide-angle trace -- verify PR #9 vendor-key path on the device.
REM
REM PR #9 applies `com.oplus.original.zoomRatio = 0.6` to every CaptureRequest
REM when:
REM   - Lens mode = Wide in TaroCamera settings (or any round-video source)
REM   - "Force OPLUS vendor zoom key" toggle = on (default)
REM   - The recording goes through Camera2Session OR TaroCameraSession
REM
REM The previously-exported diag has NO 'applyLensZoom' / 'OPLUS vendor
REM zoomRatio set' lines -- which means the user did not actually record a
REM round video between (a) opening TaroCamera settings and (b) exporting
REM diag. So we don't yet know whether:
REM   (i)  the vendor-key code path is fired during a real recording, AND
REM   (ii) the HAL accepts or silently drops the key.
REM
REM This script captures the answer in one shot.
REM ---------------------------------------------------------------------------

setlocal enabledelayedexpansion

for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value ^| find "="') do set DT=%%I
set "STAMP=%DT:~0,8%-%DT:~8,6%"
set "OUT=tarogram-wide-trace-%STAMP%.txt"

echo TaroGram wide-angle vendor-key trace
echo -------------------------------------
echo Output: %OUT%
echo.

adb get-state >nul 2>&1
if errorlevel 1 (
    echo [error] no adb device.
    exit /b 1
)

echo [step 1/6] confirm uz.unnarsx.cherrygram is installed...
adb shell "pm path uz.unnarsx.cherrygram" 1>"%TEMP%\twt_path.txt" 2>&1
findstr /C:"package:" "%TEMP%\twt_path.txt" >nul
if errorlevel 1 (
    echo [error] uz.unnarsx.cherrygram not installed. Install PR #9 APK first.
    exit /b 2
)
type "%TEMP%\twt_path.txt"

echo [step 2/6] check root...
adb shell "su -c 'id'" 1>"%TEMP%\twt_root.txt" 2>&1
findstr /C:"uid=0" "%TEMP%\twt_root.txt" >nul
set "HAS_ROOT=n"
if not errorlevel 1 set "HAS_ROOT=y"
echo   root=%HAS_ROOT%

echo [step 3/6] crank Camera2/CamX/CamX:Core verbosity (no reboot)...
set "TAGS=TaroCamera TaroSmartProxy Camera2Session Camera2 CameraManager CameraDevice CameraDeviceClient CaptureSession CameraExtensionSession CameraCaptureSession CameraMetadataNative CamX CamX:Core CamX:HAL CHIUSECASE camxconfig com.oplus.camera RoundVideo InstantCameraView"
for %%T in (%TAGS%) do (
    adb shell "setprop log.tag.%%T VERBOSE" 1>nul 2>&1
)
if /i "%HAS_ROOT%"=="y" (
    for %%T in (%TAGS%) do (
        adb shell "su -c 'setprop log.tag.%%T VERBOSE'" 1>nul 2>&1
    )
    adb shell "su -c 'setprop persist.vendor.camera.debug.logInfoMask 0xff'" 1>nul 2>&1
)

echo [step 4/6] clear logcat + launch TaroGram (already opened? close first)...
adb shell "logcat -c" 1>nul 2>&1
adb shell "am force-stop uz.unnarsx.cherrygram" 1>nul 2>&1
ping -n 2 127.0.0.1 >nul
adb shell "monkey -p uz.unnarsx.cherrygram -c android.intent.category.LAUNCHER 1" 1>nul 2>&1

echo.
echo ===========================================================
echo   USER ACTION REQUIRED
echo ===========================================================
echo.
echo   1. TaroGram is now opening on the phone. Wait for it.
echo   2. Settings -^> TaroGram -^> Camera -^> TaroCamera -^> TaroCamera settings
echo   3. Verify:
echo        * "Lens mode" = Wide
echo        * "Force OPLUS vendor zoom key" = ON (toggle)
echo   4. Back to "Camera" selector menu, choose "Camera 2 (Telegram)".
echo   5. Open any chat (Saved Messages is fine).
echo   6. Tap the microphone icon and hold/swipe up to switch to ROUND VIDEO.
echo   7. Record ~5 seconds.
echo   8. Stop recording. (DO NOT send -- just stop.)
echo   9. Return to this console and press ANY KEY.
echo.
echo ===========================================================
echo.
pause

echo [step 5/6] pull logcat and filter for vendor-key / camera / wide activity...
adb shell "logcat -d -v threadtime" > "%TEMP%\twt_logcat_raw.txt" 2>&1

> "%OUT%" echo TaroGram wide-angle trace %STAMP%
>> "%OUT%" echo root=%HAS_ROOT%
>> "%OUT%" echo apk path:
type "%TEMP%\twt_path.txt" >> "%OUT%"
>> "%OUT%" echo.
>> "%OUT%" echo ===== TaroCamera / TaroSmartProxy / Camera2 (full) =====
findstr /R /C:"TaroCamera" /C:"TaroSmartProxy" /C:"Camera2Session" /C:"InstantCamera" /C:"applyLensZoom" /C:"OPLUS vendor" /C:"vendor zoomRatio" /C:"codeaurora" "%TEMP%\twt_logcat_raw.txt" >> "%OUT%" 2>&1

>> "%OUT%" echo.
>> "%OUT%" echo ===== Framework camera client activity =====
findstr /R /C:"CameraDeviceClient" /C:"CameraManager" /C:"CameraCaptureSession" /C:"CameraMetadataNative" /C:"connectDevice" /C:"openCamera" "%TEMP%\twt_logcat_raw.txt" >> "%OUT%" 2>&1

>> "%OUT%" echo.
>> "%OUT%" echo ===== HAL / CamX vendor-key reception =====
findstr /R /C:"CamX" /C:"qcam" /C:"original.zoomRatio" /C:"zoom.target" /C:"appZoomMultiples" /C:"Sensor\[" /C:"isMaster" /C:"LogicalCameraId" /C:"dualscene" /C:"oplus" "%TEMP%\twt_logcat_raw.txt" >> "%OUT%" 2>&1

>> "%OUT%" echo.
>> "%OUT%" echo ===== full raw tail (last 200 lines for sanity) =====
powershell -NoProfile -Command "Get-Content -Tail 200 '%TEMP%\twt_logcat_raw.txt'" >> "%OUT%" 2>&1

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
echo Filtered trace: %OUT%
echo Raw logcat (fallback): %TEMP%\twt_logcat_raw.txt
echo Send %OUT% back to Devin.

exit /b 0
