@echo off
REM Verify Gradle wrapper and build setup. Run from project root.

echo Checking Gradle wrapper...
if not exist "gradlew.bat" (
    echo ERROR: gradlew.bat not found. Run setup-gradle-wrapper.bat first.
    exit /b 1
)

if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo ERROR: gradle-wrapper.jar not found.
    exit /b 1
)

echo Checking OmniDriver AAR...
set "AAR_COUNT=0"
for %%f in (app\libs\*.aar) do set /a AAR_COUNT+=1
if "%AAR_COUNT%"=="0" (
    echo WARNING: No AAR file in app\libs\. Add OmniDriver AAR to build.
) else (
    echo Found AAR in app\libs\
)

echo.
echo Running gradlew.bat tasks
call gradlew.bat tasks
if %ERRORLEVEL% neq 0 (
    echo.
    echo Build check failed. Ensure JAVA_HOME is set (e.g. Android Studio JBR).
    exit /b 1
)

echo.
echo Check complete. Run gradlew.bat assembleDebug to build APK.
exit /b 0
