#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$#" -eq 0 ]; then set -- :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug; fi
docker build --platform linux/amd64 \
  --build-arg "BUILDER_UID=$(id -u)" \
  --build-arg "BUILDER_GID=$(id -g)" \
  -t mmp-go-android-builder:latest docker
docker run --rm --platform linux/amd64 --mount "type=bind,src=$PWD,dst=/workspace" \
  --mount type=volume,src=android-gradle-cache,dst=/home/builder/.gradle \
  mmp-go-android-builder:latest bash -c '
    mkdir -p "$GRADLE_USER_HOME/mmp-go"
    if [ ! -f "$GRADLE_USER_HOME/mmp-go/debug.keystore" ]; then
      keytool -genkeypair -keystore "$GRADLE_USER_HOME/mmp-go/debug.keystore" -storepass android -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -validity 10000
    fi
    exec ./gradlew --no-daemon "$@"
  ' bash "$@"
