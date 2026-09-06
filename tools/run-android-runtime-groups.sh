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

# P0 transfer behavior gets a fresh process and runs first so unrelated launcher/IME flakes
# cannot hide a genuine byte/account/session failure.
run_group files 'com.mekromn.bubble.FileTransferRuntimeTest'
run_group floating 'com.mekromn.bubble.FloatingChromeRuntimeTest,com.mekromn.bubble.FloatingWorkspaceTest'
run_group edge 'com.mekromn.bubble.EdgeAccessRuntimeTest,com.mekromn.bubble.ParkedWorkspaceTest'
run_group core 'com.mekromn.bubble.BlackGlassTest,com.mekromn.bubble.BrowserInputTest,com.mekromn.bubble.BrowserSmokeTest,com.mekromn.bubble.RebuildRegressionTest,com.mekromn.bubble.ToolkitStoreTest,com.mekromn.bubble.WorkspaceRuntimeTest'
run_group tools 'com.mekromn.bubble.LiveToolsRuntimeTest,com.mekromn.bubble.ProfileIsolationTest'

adb pull /sdcard/Android/data/com.mekromn.bubble.debug/files/evidence/ evidence/ || true
adb logcat -d > emulator-logcat.txt
python3 tools/ci-file-evidence.py
exit "$failures"
