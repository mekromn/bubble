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

# Current-feature gate for Bubble 0.7.4. This deliberately includes the new UI/Vault runtime proof,
# the archive engine used by the repaired picker, the tray/lifecycle regressions affected by moving
# Your chats into a blurred Dialog window, and the still-current fullscreen->floating handoff test.
run_group current 'com.mekromn.bubble.UiVaultRuntimeTest,com.mekromn.bubble.ArchiveRuntimeTest,com.mekromn.bubble.RebuildRegressionTest,com.mekromn.bubble.FullscreenShrinkRuntimeTest'

adb logcat -d > emulator-logcat.txt
exit "$failures"
