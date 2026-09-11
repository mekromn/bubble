#!/usr/bin/env python3
"""Compare Bubble Renderer Probe JSON/ZIP reports. Uses only the Python standard library.

rAF/UI rates and presentation-fence events are deliberately separate. This tool never
labels rAF as physical FPS or infers HWC composition/front-buffer operation from it.
"""
from __future__ import annotations
import argparse
import csv
import json
import math
from pathlib import Path
import statistics
import zipfile


def summarize(intervals):
    values = [float(x) for x in intervals if isinstance(x, (int, float)) and math.isfinite(x) and x > 0]
    if not values:
        return {"hz": None, "p50_ms": None, "p95_ms": None, "p99_ms": None, "samples": 0}
    ordered = sorted(values)
    return {"hz": 1000 * len(values) / sum(values), "samples": len(values),
            **{f"p{q}_ms": ordered[max(0, math.ceil(q / 100 * len(ordered)) - 1)] for q in (50, 95, 99)}}


def timestamp_rate(ns):
    times = sorted(set(int(n) for n in ns if isinstance(n, (int, float)) and n > 0))
    return summarize([(b-a)/1e6 for a,b in zip(times, times[1:])])


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


def load(path):
    if path.is_dir():
        for item in sorted(path.rglob("*.json")):
            if item.name == "plan.json" or item.name.endswith((".pending.json", ".partial.json")):
                continue
            yield str(item), json.loads(item.read_text())
    elif zipfile.is_zipfile(path):
        # Read entries in memory; never extract an untrusted archive onto the filesystem.
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                if not info.filename.endswith(".json") or info.filename.endswith(("plan.json", ".pending.json", ".partial.json")):
                    continue
                if info.file_size > 8_000_000:
                    raise ValueError(f"Oversized report: {info.filename}")
                yield info.filename, json.loads(archive.read(info))
    else:
        yield str(path), json.loads(path.read_text())


def analyze(reports):
    rows, indexed, pairs = [], {}, []
    for filename, report in reports:
        if not isinstance(report, dict) or "spec" not in report:
            continue
        spec = report["spec"]
        page = report.get("page") or {}
        native = report.get("native") or {}
        env = report.get("environmentStart") or {}
        end = report.get("environmentEnd") or {}
        raf = summarize((page.get("raf") or {}).get("intervals", []))
        frames, decoding = decode_native_samples(native)
        presentation_times = [f["presentFenceSignalNs"] for f in frames
                              if isinstance(f.get("presentFenceSignalNs"), (int, float))
                              and f["presentFenceSignalNs"] > 0]
        present = timestamp_rate(presentation_times)
        latency = observed_delta(frames, "acquireNs", "presentFenceSignalNs")
        problems = []
        if not report.get("status", "").startswith("OK"):
            problems.append(report.get("status", "NO_STATUS"))
        if decoding["errors"]:
            problems.append("native_schema_error")
        if report.get("visualFailureReported"):
            problems.append("user_reported_visual_failure")
        if not report.get("firstContentfulPaint"):
            problems.append("no_first_paint")
        if report.get("touchedDuringMeasurement"):
            problems.append("human_interaction_during_measurement")
        if page.get("visibilityState") != "visible" or any(e.get("state") != "visible" for e in page.get("visibility", [])):
            problems.append("page_not_continuously_visible")
        if max(env.get("thermalStatus", 0), end.get("thermalStatus", 0)) >= 3:
            problems.append("severe_thermal_state")
        if env.get("powerSave") != end.get("powerSave") or env.get("charging") != end.get("charging"):
            problems.append("power_state_changed")
        if raf["samples"] < 30:
            problems.append("too_few_samples")
        if spec.get("workload") == "apz" and report.get("injectedInputEvents", 0) == 0:
            problems.append("no_apz_input")
        row = {"file": filename, "variant": spec.get("variant"), "mode": "floating" if spec.get("floating") else "fullscreen_window",
               "workload": spec.get("workload"), "round": spec.get("round"), "status": report.get("status"),
               "raf_hz": raf["hz"], "raf_p95_ms": raf["p95_ms"], "raf_p99_ms": raf["p99_ms"],
               "ui_callback_hz": summarize(report.get("uiChoreographerIntervalsMs", []))["hz"],
               "native_presentation_fence_event_hz": present["hz"], "native_measured_samples": len(frames),
               "native_input_rows": decoding["input_rows"], "native_rejected_rows": decoding["rejected_rows"],
               "native_schema_source": decoding["schema_source"], "native_schema_errors": ";".join(decoding["errors"]),
               "present_observations": len(presentation_times), "unique_present_events": len(set(presentation_times)),
               "missing_present_observations": len(frames)-len(presentation_times),
               "present_coverage_pct": 100*len(presentation_times)/len(frames) if frames else None,
               "observed_acquire_to_present_p50_ms": latency["p50_ms"],
               "observed_acquire_to_present_p95_ms": latency["p95_ms"],
               "native_submitted_total_including_warmup": native.get("submittedTotal"),
               "requested_hz": report.get("requestedHz"), "android_reported_hz": env.get("reportedDisplayHz"),
               "visual_policy": report.get("visualPolicy", "LEGACY_CONFIRMATION"),
               "visual_assessment": report.get("visualAssessment", "LEGACY_RECORDED_STATE"),
               "visual_confirmed": bool(report.get("visualConfirmed")), "thermal_start": env.get("thermalStatus"),
               "thermal_end": end.get("thermalStatus"), "battery_temperature_c": env.get("batteryTemperatureC"),
               "valid_timing_observation": not problems, "problems": ";".join(problems)}
        rows.append(row)
        engine = (report.get("engineArtifact") or {}).get("sha256")
        # Missing engine identity is not silently pooled across builds.
        identity = engine or (report.get("engine"), report.get("engineRepository"), report.get("source"))
        suite = str(Path(filename).parent)
        key = (suite, report.get("source"), spec.get("block", spec.get("round")), spec.get("variant"),
               spec.get("workload"), spec.get("workloadRevision", "v1"), spec.get("intensity", 1),
               spec.get("geometry", "matched"), spec.get("instrumentation", "legacy"),
               spec.get("warmupMs"), spec.get("measureMs"), row["visual_policy"], str(identity),
               report.get("viewportWidthPx"), report.get("viewportHeightPx"), page.get("cssWidth"), page.get("cssHeight"), page.get("dpr"))
        indexed.setdefault(key, {}).setdefault(bool(spec.get("floating")), []).append((row, report))
    for key, modes in indexed.items():
        if False not in modes or True not in modes:
            continue
        if len(modes[False]) != 1 or len(modes[True]) != 1:
            pairs.append({"valid_pair": False, "reason": "DUPLICATE_PAIR", "winner": None})
            continue
        full, rf = modes[False][0]
        floating, ro = modes[True][0]
        ef, eo = rf.get("environmentStart") or {}, ro.get("environmentStart") or {}
        stable = all(ef.get(x) == eo.get(x) for x in ("thermalStatus", "powerSave", "charging"))
        valid = full["valid_timing_observation"] and floating["valid_timing_observation"] and stable
        ratio = floating["raf_hz"] / full["raf_hz"] if valid and full["raf_hz"] else None
        pairs.append({"variant": full["variant"], "workload": full["workload"], "round": full["round"],
                      "full_file": full["file"], "floating_file": floating["file"], "matched_viewport": True,
                      "power_thermal_matched": stable, "valid_pair": valid, "floating_to_full_raf_ratio": ratio,
                      "both_visually_confirmed": full["visual_confirmed"] and floating["visual_confirmed"],
                      "winner": None})
    return {"notice": "These are timing observations, not certified physical FPS, HWC assignment, or touch-to-photon results. No automatic winner is selected.",
            "trials": rows, "matched_pairs": pairs}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", type=Path)
    parser.add_argument("--out", type=Path, default=Path("probe-comparison"))
    args = parser.parse_args()
    result = analyze(load(args.reports))
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "comparison.json").write_text(json.dumps(result, indent=2, allow_nan=False))
    if result["trials"]:
        with (args.out / "trials.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(result["trials"][0]))
            writer.writeheader(); writer.writerows(result["trials"])
    print(result["notice"])
    for row in result["trials"]:
        rate = "unknown" if row["raf_hz"] is None else f'{row["raf_hz"]:.2f}'
        print(f'{row["variant"]:24} {row["mode"]:18} {row["workload"]:8} rAF={rate:>7} Hz  {row["status"]}')
    print(f'{len(result["matched_pairs"])} matched fullscreen/floating pairs; reports written to {args.out}')


if __name__ == "__main__":
    main()
