# TimeTracker Android

Android client for the [TimeTracker](https://github.com/EdwardAIProjects/TimeTracker) web app.

## Features

- Configurable server URL
- Read-only dashboard for progress, remaining time, and todos
- Configurable metric cards for hours, days, and weeks
- Glance home screen widget with progress and percent

## Requirements

- Android Studio or the Android Gradle plugin toolchain.
- Android SDK 36.
- Android 14 or newer device or emulator.

## Build

```sh
./gradlew :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`
