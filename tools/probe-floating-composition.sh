#!/usr/bin/env bash
set -euo pipefail

# Physical-device compositor/scheduler probe for Bubble's fullscreen vs floating Gecko page.
# Requires adb shell access. It does not modify Bubble or lower rendering quality.
#
# Usage:
#   tools/probe-floating-composition.sh start
#   # scroll the same page fullscreen for ~10 s, then:
#   tools/probe-floating-composition.sh snapshot fullscreen
#   # open Bubble's floating chat and scroll the same page for ~10 s, then:
#   tools/probe-floating-composition.sh snapshot floating
#   tools/probe-floating-composition.sh stop
#
# Build 156+ names the independent page window "Bubble opaque floating page" so SurfaceFlinger
# captures are easier to identify. SurfaceFlinger layer names are vendor/version dependent, so the
# script also keeps the complete raw dumps rather than pretending one grep is universally correct.
# Build 157 additionally records Android process/scheduler/cgroup state because an overlay foreground
# service is not necessarily scheduled like the currently resumed fullscreen Activity.

ADB=${ADB:-adb}
OUT=${BUBBLE_PROBE_OUT:-bubble-compositor-probe}
PKG=com.mekromn.bubble.debug
mkdir -p "$OUT"

have_adb() {
  "$ADB" get-state >/dev/null 2>&1 || {
    echo "No adb device. Set ADB=/path/to/adb or connect the Pixel first." >&2
    exit 2
  }
}

layers() {
  "$ADB" shell dumpsys SurfaceFlinger --list 2>/dev/null | tr -d '\r'
}

capture_scheduler() {
  local output=$1
  {
    echo "===== package pid(s) ====="
    "$ADB" shell pidof "$PKG" 2>/dev/null || true
    echo
    echo "===== process table ====="
    "$ADB" shell ps -A -o USER,PID,PPID,PRI,NI,PCY,S,NAME,ARGS 2>/dev/null | grep -F "$PKG" || true
    echo
    echo "===== ActivityManager process state excerpts ====="
    "$ADB" shell dumpsys activity processes 2>/dev/null | grep -E -A16 -B4 "${PKG//./\\.}" || true
    echo

    for pid in $("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r'); do
      case "$pid" in (*[!0-9]*|'') continue;; esac
      echo "===== pid $pid cgroup ====="
      "$ADB" shell cat "/proc/$pid/cgroup" 2>/dev/null || true
      echo "===== pid $pid cpuset ====="
      "$ADB" shell cat "/proc/$pid/cpuset" 2>/dev/null || true
      echo "===== pid $pid status ====="
      "$ADB" shell cat "/proc/$pid/status" 2>/dev/null | grep -E '^(Name|Pid|Tgid|Threads|Cpus_allowed|Cpus_allowed_list|voluntary_ctxt_switches|nonvoluntary_ctxt_switches):' || true
      echo "===== pid $pid scheduler ====="
      "$ADB" shell cat "/proc/$pid/sched" 2>/dev/null | head -80 || true
      echo "===== selected graphics/input threads ====="
      "$ADB" shell 'for t in /proc/'"$pid"'/task/*; do n=$(cat "$t/comm" 2>/dev/null); case "$n" in *RenderThread*|*Compositor*|*WRRender*|*WebRender*|*Renderer*|*GeckoMain*|*APZ*) tid=${t##*/}; printf "%s\t%s\t" "$tid" "$n"; cat "$t/cpuset" 2>/dev/null; esac; done' 2>/dev/null || true
      echo
    done
  } > "$output"
}

case "${1:-}" in
  start)
    have_adb
    "$ADB" shell dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1 || true
    "$ADB" shell dumpsys SurfaceFlinger --timestats -enable >/dev/null 2>&1 || true
    echo "SurfaceFlinger timestats armed. Test fullscreen and floating separately with the same page."
    ;;

  snapshot)
    have_adb
    LABEL=${2:-snapshot}
    STAMP=$(date +%Y%m%d-%H%M%S)
    BASE="$OUT/${STAMP}-${LABEL}"

    "$ADB" shell dumpsys display > "${BASE}-display.txt" || true
    "$ADB" shell dumpsys gfxinfo "$PKG" framestats > "${BASE}-gfxinfo-framestats.txt" || true
    "$ADB" shell dumpsys SurfaceFlinger --timestats -dump > "${BASE}-sf-timestats.txt" || true
    "$ADB" shell dumpsys SurfaceFlinger > "${BASE}-surfaceflinger.txt" || true
    layers > "${BASE}-layers.txt" || true
    capture_scheduler "${BASE}-scheduler.txt"

    grep -Ei 'Bubble opaque floating page|SurfaceView.*com\.mekromn\.bubble\.debug|Gecko|bubble\.debug' "${BASE}-layers.txt" \
      > "${BASE}-bubble-layers.txt" || true

    # --latency is best-effort and may be unsupported or restricted by a vendor build. Keep a sample
    # for every plausible Bubble/Gecko layer instead of silently picking the wrong one.
    : > "${BASE}-latency.txt"
    while IFS= read -r layer; do
      [ -n "$layer" ] || continue
      {
        echo "===== $layer ====="
        "$ADB" shell dumpsys SurfaceFlinger --latency "$layer" 2>/dev/null || true
        echo
      } >> "${BASE}-latency.txt"
    done < "${BASE}-bubble-layers.txt"

    echo "Saved $BASE-*"
    echo "Compare sf-timestats/present cadence AND scheduler/cgroup state between fullscreen/floating."
    echo "The complete SurfaceFlinger dump is retained for client/device composition inspection."
    ;;

  trace)
    have_adb
    SECONDS=${2:-10}
    REMOTE=/data/misc/perfetto-traces/bubble-sf-${SECONDS}s.perfetto-trace
    LOCAL="$OUT/bubble-sf-${SECONDS}s-$(date +%Y%m%d-%H%M%S).perfetto-trace"
    echo "Starting ${SECONDS}s SurfaceFlinger composition/HWC trace. Scroll the target page now."
    "$ADB" shell -t perfetto -c - --txt -o "$REMOTE" <<EOF
unique_session_name: "bubble_sf_probe"
duration_ms: $((SECONDS * 1000))
buffers: {
  size_kb: 32768
  fill_policy: RING_BUFFER
}
data_sources: {
  config {
    name: "android.surfaceflinger.layers"
    surfaceflinger_layers_config: {
      mode: MODE_ACTIVE
      trace_flags: TRACE_FLAG_COMPOSITION
      trace_flags: TRACE_FLAG_HWC
      trace_flags: TRACE_FLAG_EXTRA
    }
  }
}
EOF
    "$ADB" pull "$REMOTE" "$LOCAL" >/dev/null
    "$ADB" shell rm -f "$REMOTE" >/dev/null 2>&1 || true
    echo "Saved $LOCAL"
    echo "Open in Winscope/Perfetto and search for Bubble opaque floating page and Bubble's SurfaceView."
    ;;

  stop)
    have_adb
    "$ADB" shell dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1 || true
    echo "SurfaceFlinger timestats disabled."
    ;;

  *)
    echo "Usage: $0 {start|snapshot <label>|trace [seconds]|stop}" >&2
    exit 2
    ;;
esac
