#!/usr/bin/env bash
set -euo pipefail

mkdir -p smoke-evidence
APP="app/build/outputs/apk/performance/app-performance.apk"
TEST="app/build/outputs/apk/androidTest/performance/app-performance-androidTest.apk"

adb install -r "$APP"
adb install -r -t "$TEST"
adb logcat -c

set +e
adb shell am instrument -w -r \
  -e class com.mekromn.bubble.ActivityFloatingTransitionSmokeTest \
  com.mekromn.bubble.debug.test/androidx.test.runner.AndroidJUnitRunner \
  > smoke-evidence/instrumentation.txt 2>&1
TEST_RC=$?
set -e

adb logcat -d -v threadtime > smoke-evidence/logcat.txt || true
adb shell dumpsys activity activities > smoke-evidence/activities.txt || true
adb shell dumpsys activity processes > smoke-evidence/processes.txt || true
adb shell dumpsys window windows > smoke-evidence/windows.txt || true

cat smoke-evidence/instrumentation.txt

test "$TEST_RC" -eq 0
grep -q 'OK (1 test)' smoke-evidence/instrumentation.txt
if grep -E 'FATAL EXCEPTION:|Process: com\.mekromn\.bubble\.debug' smoke-evidence/logcat.txt; then
  echo 'Smoke gate detected a Bubble process crash in logcat' >&2
  exit 1
fi

echo 'FULLSCREEN_TO_FLOATING_SMOKE_PASS'
