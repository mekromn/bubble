#!/usr/bin/env bash
# Count actual app operations under a deterministic stubbed reader sequence.
# These counts do not measure Android CPU, latency, GPU or display performance.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${BASELINE_REF:-837d575fc615d9d44ff572d98323dfebb61528de}"
EXPECTED=62520f9a51a7e6b2fd62bb1e0340c726df85f8d8
[[ "$(git rev-parse "$BASE:app/src/main/cpp/bubble_ahb.cpp")" == "$EXPECTED" ]]
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
mkdir -p "$TEMP/baseline"
git archive "$BASE" app/src/main/cpp tools/native-tests/stubs | tar -x -C "$TEMP/baseline"
cp tools/native-tests/idle_release_compare.cpp "$TEMP/baseline/tools/native-tests/"
for name in baseline current; do
  ROOT="$TEMP/baseline"; [[ "$name" == current ]] && ROOT="$PWD"
  ${CXX:-c++} -std=c++17 -O2 -g -Wall -Wextra -Werror -pthread -fsanitize=address,undefined \
    -I"$ROOT/tools/native-tests/stubs" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    "$ROOT/tools/native-tests/idle_release_compare.cpp" -o "$TEMP/$name.exe"
  ASAN_OPTIONS=detect_leaks=1 "$TEMP/$name.exe" > "$TEMP/$name.json"
done
python3 - "$TEMP" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1]);old=json.loads((p/'baseline.json').read_text());new=json.loads((p/'current.json').read_text())
for result in (old,new):
    assert result['submissions']==result['releases']==result['frames']==10000
    assert result['errors']==0 and result['frameTransactionsCreated']==1
assert old['releaseOnlyConsumerPasses']==10000 and old['acquireCalls']==30000
assert new['releaseOnlyConsumerPasses']==0 and new['acquireCalls']==20000
print(json.dumps({'scope':'Deterministic host API stubs, real relay code. NOT device speed/latency.',
                  'baseline137':old,'candidate138':new},indent=2))
PY
