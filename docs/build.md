# Docker build

The repository includes its own [Dockerfile](../docker/Dockerfile), based on the public `eclipse-temurin:21-jdk-noble` image. It supplies Java 21, Android SDK 36, Build Tools 35.0.0, and platform-tools. No pre-existing private image, host JDK, Android SDK, or Android Studio is required.

Install and start Docker, then run the commands below as a non-root user with Docker access. On Windows use a WSL2 Bash shell with Docker integration. The toolchain targets Linux amd64; ARM hosts need Docker's amd64 emulation. Internet access is required for the first build and uncached dependencies. Building the image accepts the Android SDK licenses.

`build.sh` builds `mmp-go-android-builder:latest` from the repository on every invocation, reusing Docker layers when unchanged. It matches the builder UID/GID to the invoking user so Linux build outputs remain user-owned. It mounts this repository and the `android-gradle-cache` volume, which Docker creates automatically on first use. Reuse that volume with the same UID/GID. The original local `android-builder:latest` image is no longer needed.

To refresh the base image and rebuild the toolchain explicitly:

```sh
docker build --pull --platform linux/amd64 \
  --build-arg BUILDER_UID="$(id -u)" \
  --build-arg BUILDER_GID="$(id -g)" \
  -t mmp-go-android-builder:latest docker
```

The Android command-line tools archive is pinned and SHA-256 checked. The base image tag, OS packages, platform-tools, and SDK platform revisions can receive updates, so the environment is self-contained but not byte-for-byte reproducible. Remove any host-only `sdk.dir` entry from `local.properties` before building in Docker.

The host scripts use Bash, standard command-line utilities, and Docker; phone installation additionally uses public Android platform-tools (`adb`). Java, `keytool`, and the Android SDK come from the included image. The checked-in Gradle wrapper downloads Gradle from `services.gradle.org`; project dependencies resolve through Google Maven, Maven Central, and the Gradle Plugin Portal. No sibling repositories or private images are required.

```sh
./scripts/build.sh
./scripts/build.sh :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
sha256sum app/build/outputs/apk/debug/app-debug.apk
./scripts/install-phone.sh DEVICE_SERIAL
```

`DEVICE_SERIAL` comes from `adb devices` on the host. Route Chrono uses application ID `dev.mmp.go`, version 0.12.0 (code 12), supporting Android 8/API 26 and newer, targeting API 36. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

Pinned build dependencies: Gradle 8.13, AGP 8.13.0, Kotlin 2.2.20, Room 2.8.4, AndroidX core 1.17.0, Play services location 21.3.0, MapLibre 11.13.5 and OkHttp 4.12.0. The wrapper validates the Gradle distribution checksum. Missing dependencies require network access.

The debug signing key lives at `$GRADLE_USER_HOME/mmp-go/debug.keystore` inside the persistent Gradle volume. Keep this volume to update the installed app without uninstalling it or losing data. No production signing is configured. Room schema exports are under `app/schemas`; future upgrades need explicit migrations. Build output, signing files and private phone captures are excluded from Git.
