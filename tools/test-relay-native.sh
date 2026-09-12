#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
OUT="${TMPDIR:-/tmp}/bubble-relay-test-$$"
trap 'rm -f "$OUT"' EXIT
${CXX:-c++} -std=c++17 -O2 -g -Wall -Wextra -Werror -pthread -fsanitize=address,undefined \
  -Itools/native-tests/stubs -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  tools/native-tests/relay_test.cpp -o "$OUT"
ASAN_OPTIONS=detect_leaks=1 "$OUT"
