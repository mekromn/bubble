#!/usr/bin/env bash
set -uo pipefail

: > instrumentation.txt
failures=0

reset_between_groups() {
  adb shell am force-stop com.mekromn.bubble.debug >/dev/null 2>&1 || true
  adb shell am force-stop com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
  adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
  adb shell cmd statusbar collapse >/dev/null 2>&1 || true
  sleep 1
}

run_group() {
  local label="$1"
  local classes="$2"
  local tmp="instrumentation-${label}.txt"

  printf '\n===== Android runtime group: %s =====\n' "$label" | tee -a instrumentation.txt
  reset_between_groups

  set +e
  adb shell am instrument -w -e class "$classes" \
    com.mekromn.bubble.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$tmp"
  local rc=${PIPESTATUS[0]}
  set -e

  cat "$tmp" >> instrumentation.txt
  if [[ $rc -ne 0 ]] || ! grep -q 'OK (' "$tmp"; then
    failures=1
    printf 'GROUP_RESULT=%s:FAIL rc=%s\n' "$label" "$rc" | tee -a instrumentation.txt
  else
    printf 'GROUP_RESULT=%s:PASS\n' "$label" | tee -a instrumentation.txt
  fi
}

# Emulator time follows the feature being actively changed. Archive Picker passed the previous gate,
# so this build only re-runs the fullscreen -> floating shrink handoff after replacing non-uniform
# screenshot stretching with aspect-preserving compositor scale + crop.
run_group shrink 'com.mekromn.bubble.FullscreenShrinkRuntimeTest'

adb logcat -d > emulator-logcat.txt
exit "$failures"
