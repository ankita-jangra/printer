@echo off
REM Add Gradle wrapper to project. Run this from project root.
REM Requires: Gradle in PATH (or C:\gradle-9.4.1\bin) and Java (JAVA_HOME or Android Studio JBR)

if exist "C:\Program Files\Android\Android Studio\jbr" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)

if exist "C:\gradle-9.4.1\bin\gradle.bat" (
    call "C:\gradle-9.4.1\bin\gradle.bat" wrapper --gradle-version 8.4
) else (
    gradle wrapper --gradle-version 8.4
)

if exist "gradlew.bat" (
    echo.
    echo Gradle wrapper added successfully.
    echo Run: gradlew.bat tasks
    echo Or:  gradlew.bat assembleDebug
) else (
    echo Failed. Ensure Java and Gradle are installed and in PATH.
)
pause
