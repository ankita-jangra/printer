# Build LandiPrintApp

## Prerequisites

- **Java 17** (Android Studio includes it, or set `JAVA_HOME`)
- **OmniDriver AAR** in `app/libs/`

## Gradle Wrapper (included)

The project includes `gradlew.bat` and `gradle/wrapper/`. No separate Gradle install needed.

## Verify setup

```cmd
check-build.bat
```

Or manually:

```cmd
gradlew.bat tasks
```

## Build

```cmd
gradlew.bat assembleDebug
```

Output: `app\build\outputs\apk\debug\app-debug.apk`

## Install on device

```cmd
gradlew.bat installDebug
```

## If JAVA_HOME is not set

Set it to Android Studio's JBR before running gradlew:

```cmd
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat tasks
```
