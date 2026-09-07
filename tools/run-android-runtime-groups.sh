#!/usr/bin/env bash
set -uo pipefail

: > instrumentation.txt
failures=0

reset_between_groups() {
  # Keep the emulator/image alive but isolate Bubble and the occasionally-stuck Pixel Launcher.
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

# Emulator time is intentionally a CURRENT-FEATURE gate, not an ever-growing historical suite.
# Older regression tests remain in source and unit coverage, but once a shipped feature has already
# been proven we do not spend every build re-running it. Add a runtime test here while actively
# developing that feature, then rotate it out when the next feature becomes the focus.
#
# Current work: floating-window pill/switcher/chrome behavior and forced webpage appearance.
run_group floating 'com.mekromn.bubble.FloatingChromeRuntimeTest'
run_group appearance 'com.mekromn.bubble.PageAppearanceRuntimeTest'

adb logcat -d > emulator-logcat.txt
exit "$failures"
