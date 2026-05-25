@echo off
setlocal enabledelayedexpansion

REM TaroGram local build helper.
REM
REM Usage:
REM   scripts\local-build.cmd                      - build current branch debug APK
REM   scripts\local-build.cmd <branch>             - checkout <branch>, sync, build
REM   scripts\local-build.cmd <branch> install     - also adb install -r on connected device
REM   scripts\local-build.cmd <branch> install logs - also dump TaroGram logcat to logs\
REM
REM Requirements (one-time):
REM   - JDK 17 (set JAVA_HOME)
REM   - Android SDK (set ANDROID_HOME)
REM   - Android NDK 21.4.7075529 (set ANDROID_NDK_HOME = %ANDROID_HOME%\ndk\21.4.7075529)
REM   - adb in PATH (Android SDK platform-tools)
REM   - TELEGRAM_APP_ID and TELEGRAM_APP_HASH env vars set (your Telegram creds from
REM     https://my.telegram.org). Without these the build uses public test creds which
REM     get aggressively rate-limited.

pushd "%~dp0\.."

set BRANCH=%1
set DO_INSTALL=%2
set DO_LOGS=%3

if "%BRANCH%"=="" set BRANCH=

echo ===============================================
echo TaroGram local build
echo Repo: %CD%
echo Branch arg: [%BRANCH%]
echo Install: [%DO_INSTALL%]
echo ===============================================

if not defined JAVA_HOME (
  echo [ERROR] JAVA_HOME is not set. Install JDK 17 from https://learn.microsoft.com/en-us/java/openjdk/download and set JAVA_HOME.
  goto fail
)
if not defined ANDROID_HOME (
  echo [ERROR] ANDROID_HOME is not set. Install Android Studio and set ANDROID_HOME (usually %%LOCALAPPDATA%%\Android\Sdk).
  goto fail
)
if not defined ANDROID_NDK_HOME (
  if exist "%ANDROID_HOME%\ndk\21.4.7075529" (
    set "ANDROID_NDK_HOME=%ANDROID_HOME%\ndk\21.4.7075529"
    echo [INFO] ANDROID_NDK_HOME auto-set to !ANDROID_NDK_HOME!
  ) else (
    echo [ERROR] ANDROID_NDK_HOME not set and %%ANDROID_HOME%%\ndk\21.4.7075529 is missing.
    echo         Install NDK 21.4.7075529 via Android Studio SDK Manager - SDK Tools - NDK Side by side.
    goto fail
  )
)

if "%BRANCH%" NEQ "" (
  echo.
  echo [git] fetch + checkout %BRANCH%
  git fetch origin || goto fail
  git checkout %BRANCH% || goto fail
  git reset --hard origin/%BRANCH% || goto fail
) else (
  echo [INFO] No branch arg; using current branch.
)

echo.
echo [creds] Patching Extra.kt with TELEGRAM_APP_ID and TELEGRAM_APP_HASH
set EXTRA=TMessagesProj\src\main\java\uz\unnarsx\cherrygram\Extra.kt
if not exist "%EXTRA%" (
  echo [ERROR] Extra.kt not found at %EXTRA%
  goto fail
)
if not defined TELEGRAM_APP_ID (
  echo [WARN] TELEGRAM_APP_ID not set, using public test creds (will be rate-limited).
  set TELEGRAM_APP_ID=4
)
if not defined TELEGRAM_APP_HASH (
  set TELEGRAM_APP_HASH=014b35b6184100b085b0d0572f9b5103
)
powershell -NoProfile -Command "(Get-Content '%EXTRA%') -replace 'const val APP_ID = \d+', 'const val APP_ID = %TELEGRAM_APP_ID%' -replace 'const val APP_HASH = \"[^\"]*\"', 'const val APP_HASH = \"%TELEGRAM_APP_HASH%\"' | Set-Content '%EXTRA%'"
findstr /R "APP_ID APP_HASH" "%EXTRA%"

echo.
echo [keystore] Ensuring debug keystore exists
if not exist Your_Key.jks (
  "%JAVA_HOME%\bin\keytool" -genkey -v -keystore Your_Key.jks -storepass Your_Password -keypass Your_Password -alias Your_Alias -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TaroGram Local, OU=Dev, O=etern1ty-crypto, L=NA, S=NA, C=US" || goto fail
) else (
  echo [INFO] Your_Key.jks already exists, skipping keytool.
)

echo.
echo [gradle] Building AfatDebug APK (this is the universal arm64+armv7+x86_64 build)
call gradlew.bat :TMessagesProj_AppStandalone:assembleAfatDebug --stacktrace || goto fail

set APK=
for /f "delims=" %%a in ('dir /b /s TMessagesProj_AppStandalone\build\outputs\apk\*.apk 2^>nul') do (
  set APK=%%a
)
if "%APK%"=="" (
  echo [ERROR] Build finished but no APK found in TMessagesProj_AppStandalone\build\outputs\apk
  goto fail
)
echo.
echo [DONE] APK ready:
echo   %APK%

if /I "%DO_INSTALL%"=="install" (
  echo.
  echo [adb] Uninstalling any previous build to avoid signature mismatch...
  adb uninstall uz.unnarsx.cherrygram >nul 2>&1
  echo [adb] Installing %APK%
  adb install -r -t "%APK%" || goto fail

  if /I "%DO_LOGS%"=="logs" (
    if not exist logs mkdir logs
    set LOGFILE=logs\tarogram-%RANDOM%.log
    echo [adb] Tailing logcat to !LOGFILE!
    adb logcat -c
    adb shell am force-stop uz.unnarsx.cherrygram
    adb shell monkey -p uz.unnarsx.cherrygram -c android.intent.category.LAUNCHER 1
    timeout /t 6 /nobreak >nul
    adb logcat -d > !LOGFILE!
    echo [adb] Log saved.
    echo Filtered TaroGram / SmartProxy / TaroCamera lines:
    findstr /R /I "TaroCamera TaroSmartProxy SmartProxy Camera2Session FATAL AndroidRuntime" !LOGFILE!
  )
)

popd
exit /b 0

:fail
echo.
echo [FAIL] Build aborted. See messages above.
popd
exit /b 1
