"""One-shot, exact-anchor Gate 1 migration. Changes only the isolated renderer probe.
The workflow commits the resulting ordinary source files before build validation.
"""
from pathlib import Path
root=Path(__file__).resolve().parents[1]

def replace(path, old, new, count=1):
 p=root/path;s=p.read_text();assert s.count(old)==count,(path,s.count(old),old[:80]);p.write_text(s.replace(old,new))

trial='renderer-probe/src/main/java/com/mekromn/bubble/probe/TrialActivity.java'
replace(trial,'    private Button visibleButton;\n','    private boolean injectingSyntheticInput;\n    private long visualFailureReportedNs;\n')
replace(trial,'firstPaint,firstComposite,visualConfirmed,controlCreating','firstPaint,firstComposite,controlCreating')
replace(trial,'        visibleButton=button("Visible",()->{visualConfirmed=true;progress.setText(label()+"\\nVisual confirmation recorded");});buttons.addView(visibleButton);\n','')
replace(trial,'buttons.addView(button("Black / broken",()->end("VISUAL_FAIL",null)));','buttons.addView(button("Black / broken",()->{visualFailureReportedNs=SystemClock.elapsedRealtimeNanos();end("VISUAL_FAIL",null);}));')
replace(trial,'b.setOnClickListener(v->{touchedDuringMeasurement|=measuring;action.run();});','b.setOnClickListener(v->action.run());')
replace(trial,'                geckoView.setRequestedFrameRate(vote);page.addView(geckoView,new FrameLayout.LayoutParams(-1,-1));','''                geckoView.setOnTouchListener((v,event)->{
                    if(!injectingSyntheticInput) touchedDuringMeasurement|=measuring;
                    return false;
                });
                geckoView.setRequestedFrameRate(vote);page.addView(geckoView,new FrameLayout.LayoutParams(-1,-1));''')
replace(trial,'if(geckoView!=null)geckoView.dispatchTouchEvent(event);else session.getPanZoomController().onTouchEvent(event);\n        measurements.input(now,dispatchStart,System.nanoTime(),action);\n        event.recycle();injectedInputEvents++;','''injectingSyntheticInput=true;
        try {
            if(geckoView!=null)geckoView.dispatchTouchEvent(event);else session.getPanZoomController().onTouchEvent(event);
            measurements.input(now,dispatchStart,System.nanoTime(),action);
            injectedInputEvents++;
        } finally {
            injectingSyntheticInput=false;
            event.recycle();
        }''')
replace(trial,'measurements.begin();visibleButton.setEnabled(false);','measurements.begin();')
replace(trial,'end(visualConfirmed?"OK_VISIBLE":"OK_VISUAL_UNCONFIRMED",pageReport)','end("OK_NO_VISUAL_FAILURE_REPORTED",pageReport)')
replace(trial,'"visualConfirmed",visualConfirmed,','''"visualConfirmed",false,"visualPolicy","ASSUME_VISIBLE_UNLESS_REPORTED",
            "visualFailureReported",visualFailureReportedNs>0,"visualFailureReportedNs",visualFailureReportedNs,
            "visualAssessment",visualFailureReportedNs>0?"USER_REPORTED_FAILURE":"NO_FAILURE_REPORTED_NOT_INDEPENDENTLY_VERIFIED",''')
replace(trial,'report.put("lifecycleError",e.toString());report.put("status","LIFECYCLE_ERROR");','report.put("lifecycleError",e.toString());if(report.optString("status").startsWith("OK"))report.put("status","LIFECYCLE_ERROR");')
controller='renderer-probe/src/main/java/com/mekromn/bubble/probe/ProbeActivity.java'
replace(controller,'Tap Visible during warm-up, or Black whenever a case fails.','Do not touch a successful test. Press Black / broken only on failure, or Stop to cancel.')
replace(controller,'"Matrix · 17 cases"','"Matrix · "+(TrialPlan.VARIANTS.size()*2)+" cases"')
replace(controller,'"Repeated matrix · 3 workloads × 3 rounds"','"Repeated matrix · 6 workloads × 3 rounds"')
analysis='renderer-probe/src/main/assets/probe-analysis.js'
replace(analysis,"if(!r.visualConfirmed)issues.push('VISIBILITY_UNCONFIRMED');", "if(!r.visualConfirmed&&!(r.visualPolicy==='ASSUME_VISIBLE_UNLESS_REPORTED'&&r.status==='OK_NO_VISUAL_FAILURE_REPORTED'&&r.visualFailureReported===false))issues.push('VISIBILITY_UNCONFIRMED');if(r.visualFailureReported===true)issues.push('USER_REPORTED_VISUAL_FAILURE');")
replace(analysis,"warnings:[...(pipe.measuredSubmissions", "warnings:[...(r.visualPolicy==='ASSUME_VISIBLE_UNLESS_REPORTED'?['VISIBILITY_ASSUMED_NOT_INDEPENDENTLY_VERIFIED']:[]),...(pipe.measuredSubmissions")
replace(analysis,'requestedHz:r.requestedHz,page:', 'requestedHz:r.requestedHz,visualPolicy:r.visualPolicy||"LEGACY_CONFIRMATION",page:')
replace(analysis,'t.suite,r.source,','t.suite,r.source,r.visualPolicy,')
plan='renderer-probe/src/main/java/com/mekromn/bubble/probe/TrialPlan.java'
replace(plan,'"relay_small_pool","raw_auto"','"relay_small_pool","relay_pool6_drain2","relay_pool4_drain4","raw_auto"')
replace(plan,'variant.equals("relay_small_pool")?4:6','(variant.equals("relay_small_pool")||variant.equals("relay_pool4_drain4"))?4:6')
replace(plan,'variant.equals("relay_small_pool")?2:4','(variant.equals("relay_small_pool")||variant.equals("relay_pool6_drain2"))?2:4')
measure='renderer-probe/src/main/java/com/mekromn/bubble/probe/Measurements.java'
replace(measure,'m.getMetric(FrameMetrics.FRAME_TIMELINE_VSYNC_ID)','frameTimelineId(m)')
replace(measure,'    private final Choreographer.VsyncCallback callback=', '''    // Public API 36 metric; the SDK's getMetric @IntDef omits it even though the
    // constant is documented. Keep this workaround local; -1 remains unavailable.
    @android.annotation.SuppressLint("WrongConstant")
    private static long frameTimelineId(FrameMetrics metrics) {
        return metrics.getMetric(FrameMetrics.FRAME_TIMELINE_VSYNC_ID);
    }
    private final Choreographer.VsyncCallback callback=''')
p=root/'tools/compare-renderer-probe.py'
s=p.read_text()
anchor='\ndef load(path):'
assert s.count(anchor)==1
block='''
LEGACY_COLUMNS = ["acquireNs", "submitNs", "imageTimestampNs", "latchNs",
                  "completionCallbackNs", "presentFenceSignalNs", "hardwareBufferId"]
V2_COLUMNS = LEGACY_COLUMNS + ["acquireCallNs", "applyCallNs",
                              "releaseCallbackNs", "outstandingDepth"]


def decode_native_samples(native):
    """Decode JSON sample arrays by column names, with explicit legacy fallbacks.

    Extra named columns and column reordering are supported. Malformed records are
    counted/reported, never silently passed off as no frames or no presentation.
    Hardware-buffer IDs remain integer allocation identifiers, not frame counters.
    """
    if not isinstance(native, dict):
        return [], {"input_rows": 0, "decoded_rows": 0, "rejected_rows": 0,
                    "schema_source": "absent", "errors": ["native_not_object"]}
    samples = native.get("samples", [])
    info = {"input_rows": len(samples) if isinstance(samples, list) else 0,
            "decoded_rows": 0, "rejected_rows": 0,
            "schema_source": "sampleColumns", "errors": []}
    if not isinstance(samples, list):
        info["errors"].append("samples_not_array")
        return [], info
    names = native.get("sampleColumns")
    if names is None:
        info["schema_source"] = "explicit_legacy_length_fallback"
        lengths = {len(r) for r in samples if isinstance(r, list)}
        if not samples:
            return [], info
        if lengths == {7}:
            names = LEGACY_COLUMNS
        elif lengths == {11}:
            names = V2_COLUMNS
        else:
            info["errors"].append("missing_or_ambiguous_sample_columns")
            info["rejected_rows"] = len(samples)
            return [], info
    if (not isinstance(names, list) or not all(isinstance(k, str) and k for k in names)
            or len(names) != len(set(names)) or not set(LEGACY_COLUMNS).issubset(names)):
        info["errors"].append("invalid_or_incomplete_sample_columns")
        info["rejected_rows"] = len(samples)
        return [], info
    rows = []
    for index, values in enumerate(samples):
        if not isinstance(values, list) or len(values) != len(names):
            info["rejected_rows"] += 1
            if len(info["errors"]) < 16:
                info["errors"].append(f"row_{index}_shape_mismatch")
            continue
        row = dict(zip(names, values))
        required = [row[k] for k in LEGACY_COLUMNS]
        if not all(x is None or (isinstance(x, (int, float)) and not isinstance(x, bool)
                                 and math.isfinite(x)) for x in required):
            info["rejected_rows"] += 1
            if len(info["errors"]) < 16:
                info["errors"].append(f"row_{index}_invalid_required_value")
            continue
        rows.append(row)
    info["decoded_rows"] = len(rows)
    return rows, info


def observed_delta(rows, start, end):
    values = []
    for row in rows:
        a, b = row.get(start), row.get(end)
        if (isinstance(a, (int, float)) and isinstance(b, (int, float))
                and a > 0 and b >= a):
            values.append((b-a)/1e6)
    return summarize(values)

'''
s=s.replace(anchor,block+anchor)
s=s.replace('item.name.endswith(".pending.json")','item.name.endswith((".pending.json", ".partial.json"))')
s=s.replace('("plan.json", ".pending.json")','("plan.json", ".pending.json", ".partial.json")')
s=s.replace('''        frames = native.get("samples", [])
        present = timestamp_rate([f[5] for f in frames if isinstance(f, list) and len(f) == 7])''','''        frames, decoding = decode_native_samples(native)
        presentation_times = [f["presentFenceSignalNs"] for f in frames
                              if isinstance(f.get("presentFenceSignalNs"), (int, float))
                              and f["presentFenceSignalNs"] > 0]
        present = timestamp_rate(presentation_times)
        latency = observed_delta(frames, "acquireNs", "presentFenceSignalNs")''')
s=s.replace('''        if not report.get("firstContentfulPaint"):
            problems.append("no_first_paint")''','''        if decoding["errors"]:
            problems.append("native_schema_error")
        if report.get("visualFailureReported"):
            problems.append("user_reported_visual_failure")
        if not report.get("firstContentfulPaint"):
            problems.append("no_first_paint")''')
s=s.replace('''               "native_presentation_fence_event_hz": present["hz"], "native_measured_samples": len(frames),''','''               "native_presentation_fence_event_hz": present["hz"], "native_measured_samples": len(frames),
               "native_input_rows": decoding["input_rows"], "native_rejected_rows": decoding["rejected_rows"],
               "native_schema_source": decoding["schema_source"], "native_schema_errors": ";".join(decoding["errors"]),
               "present_observations": len(presentation_times), "unique_present_events": len(set(presentation_times)),
               "missing_present_observations": len(frames)-len(presentation_times),
               "present_coverage_pct": 100*len(presentation_times)/len(frames) if frames else None,
               "observed_acquire_to_present_p50_ms": latency["p50_ms"],
               "observed_acquire_to_present_p95_ms": latency["p95_ms"],''')
s=s.replace('''               "visual_confirmed": bool(report.get("visualConfirmed")),''','''               "visual_policy": report.get("visualPolicy", "LEGACY_CONFIRMATION"),
               "visual_assessment": report.get("visualAssessment", "LEGACY_RECORDED_STATE"),
               "visual_confirmed": bool(report.get("visualConfirmed")),''')
s=s.replace('''        key = (suite, spec.get("round"), spec.get("variant"), spec.get("workload"), str(identity),''','''        key = (suite, report.get("source"), spec.get("block", spec.get("round")), spec.get("variant"),
               spec.get("workload"), spec.get("workloadRevision", "v1"), spec.get("intensity", 1),
               spec.get("geometry", "matched"), spec.get("instrumentation", "legacy"),
               spec.get("warmupMs"), spec.get("measureMs"), row["visual_policy"], str(identity),''')
s=s.replace('''        indexed.setdefault(key, {})[bool(spec.get("floating"))] = (row, report)''','''        indexed.setdefault(key, {}).setdefault(bool(spec.get("floating")), []).append((row, report))''')
s=s.replace('''        full, rf = modes[False]
        floating, ro = modes[True]''','''        if len(modes[False]) != 1 or len(modes[True]) != 1:
            pairs.append({"valid_pair": False, "reason": "DUPLICATE_PAIR", "winner": None})
            continue
        full, rf = modes[False][0]
        floating, ro = modes[True][0]''')
p.write_text(s)
