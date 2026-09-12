#!/usr/bin/env python3
"""Fail closed on the packaged ARM64 bridge/policy; does not infer actual GPU execution."""
from __future__ import annotations
import argparse, hashlib, json, re, subprocess, tempfile, zipfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    baseline = json.loads(Path('tools/baseline136-engine-sha256.json').read_text())['sha256']
    with zipfile.ZipFile(args.apk) as apk:
        config = apk.read('assets/gecko-hardware.yaml')
        assert config == Path('app/src/main/assets/gecko-hardware.yaml').read_bytes(), 'Policy differs from reviewed source'
        prefs = dict(re.findall(r'^  ([a-z][a-z0-9.-]+): (true|false)$', config.decode(), re.M))
        assert len(prefs) == 13, 'Incorrect preference inventory'
        engine = apk.read('lib/arm64-v8a/libxul.so')
        for name in prefs:
            assert name.encode() + b'\0' in engine, f'Preference name absent in this engine: {name}'
        unchanged = []
        for name, expected in baseline.items():
            assert hashlib.sha256(apk.read(name)).hexdigest() == expected, f'Unexpected baseline change: {name}'
            unchanged.append(name)
        with tempfile.TemporaryDirectory() as tmp:
            bridge = Path(tmp) / 'libbubble-ahb.so'
            bridge.write_bytes(apk.read('lib/arm64-v8a/libbubble-ahb.so'))
            symbols = subprocess.check_output(['readelf', '--wide', '--dyn-syms', str(bridge)], text=True)
            dynamic = subprocess.check_output(['readelf', '--wide', '--dynamic', str(bridge)], text=True)
            assert '__android_log_' not in symbols, 'Native bridge still imports a log function'
            assert 'liblog.so' not in dynamic, 'Native bridge still directly links liblog'
    report = {
        'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        'nativeBridgeAndroidLoggingReferences': 0,
        'nativeBridgeDirectLiblogDependency': False,
        'packagedPolicyMatchesReviewedSource': True,
        'hardwarePrefNamesPresentInPinnedEngine': len(prefs),
        'unchangedBaselineFiles': unchanged,
        'prefReadbackAndHardwareExecution': 'Not inferred from this static audit; see Android runtime evidence.',
        'loggingScope': 'Our native relay and guarded application diagnostics. Prebuilt Gecko and Android may still log.',
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
