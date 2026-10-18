Route Chrono by MMP Systems records journeys and compares trips between saved endpoints.

Use the Record, Pairs and Results tabs. Press Start, travel, then Stop to save a journey and learn its endpoints. Future trips between those points are recorded automatically in either direction, using any route. You can also add multiple endpoint pairs on the map. Results show each path, time, distance, average speed, and simple timing summaries. Keep automatic recording enabled for repeat trips. A visible notification shows background activity. Press Exit in the app or notification to stop all tracking and close the app; reopening stays paused.

```text
Start → travel → Stop ──────┐
                           ├→ saved endpoint pairs
Choose endpoints on a map ─┘          ↓
                          automatic outward / return trips
                                      ↓
                          recorded maps + times + statistics
```

Build and check with Docker installed and running (no host Java or Android SDK required):

```sh
./scripts/build.sh
```

Install and open on a connected Android phone:

```sh
adb devices
./scripts/install-phone.sh DEVICE_SERIAL
```

Run the isolated phone persistence and migration tests:

```sh
./scripts/build.sh :app:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w dev.mmp.go.test/androidx.test.runner.AndroidJUnitRunner
```

`DEVICE_SERIAL` is the identifier printed by `adb devices` (host ADB is needed for phone installation). Optional arguments to `build.sh` are Gradle tasks; by default it runs JVM tests, builds the debug APK, and runs Android lint. The script builds its own image from the included [Dockerfile](docker/Dockerfile), and Docker creates the Gradle cache volume automatically. The first build downloads the toolchain and dependencies and accepts the Android SDK licenses. See [build details](docs/build.md) for requirements and caching. Phone usage and GPS limits are described in [phone testing](docs/phone-testing.md).
