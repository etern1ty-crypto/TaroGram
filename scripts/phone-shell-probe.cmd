@echo off
REM ---------------------------------------------------------------------------
REM TaroGram phone shell probe.
REM
REM Pure ADB-based diagnostic + attempted-unlock for the wide-angle camera on
REM Realme/OPPO/OnePlus devices that hide their physical sub-cameras behind
REM the SYSTEM_CAMERA permission gate.
REM
REM Usage (from the repo root):
REM     scripts\phone-shell-probe.cmd
REM
REM No APK install needed. Writes a single timestamped txt file in the repo
REM root which you should send back to Devin.
REM ---------------------------------------------------------------------------

setlocal enabledelayedexpansion

set "PKG=uz.unnarsx.cherrygram"

REM Build a yyyyMMdd-HHmmss stamp robustly regardless of locale.
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value ^| find "="') do set DT=%%I
set "STAMP=%DT:~0,8%-%DT:~8,6%"
set "OUT=phone-probe-%STAMP%.txt"

echo TaroGram phone shell probe
echo --------------------------
echo Output file: %OUT%
echo Target app : %PKG%
echo.

REM Verify adb sees a device.
adb get-state >nul 2>&1
if errorlevel 1 (
    echo [error] adb does not see any device. Plug in the phone, allow USB debugging, then re-run.
    exit /b 1
)

REM Ask once whether the user has working root so we know to attempt
REM root-only unlock vectors.
set "HAS_ROOT=n"
set /p HAS_ROOT="Do you have working root on this device (su available)? (y/n): "

REM Reset the file.
> "%OUT%" echo TaroGram phone shell probe -- %STAMP%
>> "%OUT%" echo Package: %PKG%
>> "%OUT%" echo Has root: %HAS_ROOT%
>> "%OUT%" echo.

REM ============================================================
REM Phase 1 -- read-only diagnostic. No state mutation. Always runs.
REM ============================================================

call :hdr "device identity"
call :run "getprop ro.product.model"
call :run "getprop ro.product.device"
call :run "getprop ro.product.brand"
call :run "getprop ro.product.manufacturer"
call :run "getprop ro.build.fingerprint"
call :run "getprop ro.build.version.sdk"
call :run "getprop ro.build.version.release"
call :run "getprop ro.product.cpu.abi"
call :run "getprop ro.hardware"
call :run "getprop ro.board.platform"

call :hdr "camera-related vendor properties"
call :run "getprop ro.vendor.camera.unlock"
call :run "getprop persist.vendor.camera.privapp.list"
call :run "getprop ro.camera.notify_nfd"
call :run "getprop persist.camera.privapp.list"
call :run "getprop vendor.camera.aux.packagelist"
call :run "getprop ro.vendor.qti.va_aosp.support"
call :run "getprop persist.vendor.camera.HAL3.enabled"

call :hdr "selinux state"
call :run "getenforce"

call :hdr "permission definitions (system_camera + camera)"
call :run "pm list permissions -g -d | grep -iE 'system_camera|^permission:android.permission.CAMERA$'"

call :hdr "stock OPPO/Realme/OnePlus camera apps -- which are installed + which have SYSTEM_CAMERA"
call :run "pm list packages | grep -iE 'camera'"
call :run "dumpsys package com.oppo.camera | grep -iE 'system_camera|grantedPermissions'"
call :run "dumpsys package com.coloros.camera | grep -iE 'system_camera|grantedPermissions'"
call :run "dumpsys package com.heytap.camera | grep -iE 'system_camera|grantedPermissions'"
call :run "dumpsys package com.oneplus.camera | grep -iE 'system_camera|grantedPermissions'"
call :run "dumpsys package com.realme.camera | grep -iE 'system_camera|grantedPermissions'"
call :run "dumpsys package com.realme.kidocamera | grep -iE 'system_camera|grantedPermissions'"

call :hdr "OUR APP permissions BEFORE any unlock"
call :run "dumpsys package %PKG% | grep -iE 'system_camera|camera|granted=true' | head -80"

call :hdr "OUR APP appops BEFORE any unlock"
call :run "appops get %PKG%"

call :hdr "cameraserver process"
call :run "pidof cameraserver"
call :run "ps -A | grep -i camera"

call :hdr "media.camera service capabilities"
call :run "cmd media.camera"
call :run "cmd media.camera get-camera-ids"
call :run "cmd media.camera get-camera-ids-with-system"

call :hdr "media.camera per-id characteristics (0..9)"
call :run "cmd media.camera get-camera-characteristics 0"
call :run "cmd media.camera get-camera-characteristics 1"
call :run "cmd media.camera get-camera-characteristics 2"
call :run "cmd media.camera get-camera-characteristics 3"
call :run "cmd media.camera get-camera-characteristics 4"
call :run "cmd media.camera get-camera-characteristics 5"

call :hdr "media.camera dumpsys (FULL)"
call :run "dumpsys media.camera"

call :hdr "v4l2 / camera device nodes (selinux labels)"
call :run "ls -Z /dev/video* 2>/dev/null"
call :run "ls -Z /dev/v4l-subdev* 2>/dev/null"
call :run "ls -Z /dev/camera* 2>/dev/null"

REM ============================================================
REM Phase 2 -- non-root unlock attempts. These are reversible.
REM ============================================================

echo.
echo === Phase 2: non-root unlock attempts ===
echo.

call :hdr "attempt N1: pm grant SYSTEM_CAMERA (expected to fail for non-signed apps)"
call :run "pm grant %PKG% android.permission.SYSTEM_CAMERA"
call :run "dumpsys package %PKG% | grep -iE 'system_camera|granted=true' | head -20"

call :hdr "attempt N2: appops set SYSTEM_CAMERA allow (ColorOS sometimes honors this)"
call :run "appops set %PKG% SYSTEM_CAMERA allow"
call :run "appops get %PKG% SYSTEM_CAMERA"

call :hdr "attempt N3: appops set CAMERA allow (force-allow the runtime permission too)"
call :run "appops set %PKG% CAMERA allow"
call :run "appops get %PKG% CAMERA"

call :hdr "attempt N4: post non-root attempts -- re-enumerate cameras"
call :run "cmd media.camera get-camera-ids"
call :run "cmd media.camera get-camera-ids-with-system"

REM ============================================================
REM Phase 3 -- root attempts. Only if user said y.
REM ============================================================

if /i "%HAS_ROOT%"=="y" (
    echo.
    echo === Phase 3: root unlock attempts ===
    echo.

    call :hdr "root.A: setenforce 0 (SELinux permissive)"
    call :runroot "setenforce 0"
    call :runroot "getenforce"

    call :hdr "root.B: forced grant SYSTEM_CAMERA via cmd package"
    call :runroot "cmd package grant %PKG% android.permission.SYSTEM_CAMERA"
    call :runroot "dumpsys package %PKG% | grep -i system_camera"

    call :hdr "root.C: cmd appops set --user 0 SYSTEM_CAMERA"
    call :runroot "cmd appops set --user 0 %PKG% SYSTEM_CAMERA allow"
    call :runroot "cmd appops get %PKG% SYSTEM_CAMERA"

    call :hdr "root.D: kill cameraserver to force re-enum"
    call :runroot "killall cameraserver"
    call :runroot "ps -A | grep -i cameraserver"

    call :hdr "root.E: post-root re-enumerate cameras"
    call :run "cmd media.camera get-camera-ids"
    call :run "cmd media.camera get-camera-ids-with-system"

    call :hdr "root.F: post-root dumpsys media.camera (first 300 lines)"
    call :runroot "dumpsys media.camera | head -300"
) else (
    echo. >> "%OUT%"
    echo ========================== >> "%OUT%"
    echo  Phase 3 root attempts SKIPPED (user has no root) >> "%OUT%"
    echo ========================== >> "%OUT%"
    echo   [skip] root phase
)

REM ============================================================
REM Phase 4 -- final state + logcat tail.
REM ============================================================

echo.
echo === Phase 4: final state ===
echo.

call :hdr "FINAL: our app permissions"
call :run "dumpsys package %PKG% | grep -iE 'system_camera|camera|granted=true' | head -80"

call :hdr "FINAL: our app appops"
call :run "appops get %PKG%"

call :hdr "FINAL: cmd media.camera get-camera-ids"
call :run "cmd media.camera get-camera-ids"
call :run "cmd media.camera get-camera-ids-with-system"

call :hdr "recent logcat -- camera + selinux denials"
call :run "logcat -d -t 2000"

echo.
echo Done. Send %OUT% back to Devin.
echo.
exit /b 0


REM ---------------------------------------------------------------------------
REM Helpers.

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

:runroot
echo. >> "%OUT%"
echo $ adb shell su -c '%~1' >> "%OUT%"
adb shell "su -c '%~1'" >> "%OUT%" 2>&1
goto :EOF
