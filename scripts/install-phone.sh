#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$#" -ne 1 ]; then echo 'Usage: scripts/install-phone.sh DEVICE_SERIAL (run where adb is available)' >&2; exit 2; fi
apk=app/build/outputs/apk/debug/app-debug.apk
test -f "$apk"
adb -s "$1" install -r "$apk"
adb -s "$1" shell am start -n dev.mmp.go/.MainActivity
