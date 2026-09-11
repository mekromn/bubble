#!/usr/bin/env bash
set -euo pipefail
PACKAGE=com.mekromn.bubble.probe
adb install -r renderer-probe/build/outputs/apk/debug/*.apk
adb shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow
adb shell settings put system screen_off_timeout 1800000
adb logcat -c
adb shell am start -W -n "$PACKAGE/.ProbeActivity" --es automation quick
finished=false
for i in $(seq 1 100); do
  progress=$(adb exec-out run-as "$PACKAGE" cat files/latest-suite.json 2>/dev/null || true)
  if echo "$progress" | grep -q '"complete":true'; then finished=true; break; fi
  sleep 3
done
adb logcat -d -v threadtime > probe-smoke-logcat.txt
mkdir -p dist
adb exec-out run-as "$PACKAGE" tar -C files -cf - benchmarks latest-suite.json > dist/probe-smoke.tar
if [ "$finished" != true ]; then echo 'Controller did not finish the smoke suite'; exit 1; fi
python3 - <<'PY'
import json, tarfile
with tarfile.open('dist/probe-smoke.tar') as archive:
    trials=[]
    for member in archive.getmembers():
        if member.name.endswith('.json') and not member.name.endswith(('plan.json','.pending.json','latest-suite.json')):
            obj=json.load(archive.extractfile(member))
            if 'spec' in obj: trials.append(obj)
    assert len(trials)==4, f'Expected 4 smoke cases, got {len(trials)}'
    for trial in trials:
        print(trial['spec']['variant'],trial['spec']['floating'],trial['status'])
        assert trial['status'].startswith('OK'), trial
        assert trial['firstContentfulPaint'], trial
        assert len(trial['page']['raf']['intervals'])>=30, trial
print('Emulator smoke passed. No visual, hardware-overlay, physical-FPS, or Pixel certification is implied.')
PY
