#!/bin/sh
# Runs a k6 script from loadtest/k6 in Docker (no local k6 installation needed) against the application on the host.
#
#   loadtest/run-k6.sh rest-mix.js -e RATE=100 -e DURATION=3m
#
# The end-of-test summary is printed and also written to loadtest/results/<script>-<time>.json. Set BASE_URL to test
# another address than http://host.docker.internal:8080 (the host, as seen from the container).
set -eu

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <script.js> [k6 options...]" >&2
  exit 2
fi

cd "$(dirname "$0")"
script="$1"
shift
mkdir -p results
result="results/$(basename "$script" .js)-$(date +%Y%m%d-%H%M%S).json"
# Git Bash on Windows would rewrite the container paths; Docker Desktop accepts C:/... host paths.
here="$(pwd -W 2>/dev/null || pwd)"

MSYS_NO_PATHCONV=1 docker run --rm -i \
  --add-host=host.docker.internal:host-gateway \
  -v "$here/k6:/scripts:ro" -v "$here/results:/results" \
  -e BASE_URL="${BASE_URL:-http://host.docker.internal:8080}" \
  grafana/k6:1.3.0 run --summary-export "/$result" "$@" "/scripts/$script"

echo "Summary written to loadtest/$result"
