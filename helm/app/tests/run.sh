#!/usr/bin/env bash
# Golden render tests for the app chart: renders every tests/values/<name>-<env>.yaml (release "demo", namespace <env>)
# and compares it with tests/golden/<name>-<env>.yaml byte for byte.
#   helm/app/tests/run.sh           check (CI)
#   helm/app/tests/run.sh --update  rewrite the goldens after an intended change (review the diff!)
# The goldens without services: were rendered by the chart before services: existed, so this also proves that a values
# file without services: renders exactly as it did before.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
chart="$(dirname "$here")"
update="${1:-}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
fail=0
for values in "$here"/values/*.yaml; do
  name="$(basename "$values" .yaml)"
  env="${name##*-}"
  helm template demo "$chart" --namespace "$env" -f "$values" > "$out/$name.yaml"
  if [ "$update" = "--update" ]; then
    cp "$out/$name.yaml" "$here/golden/$name.yaml"
  elif ! diff -u "$here/golden/$name.yaml" "$out/$name.yaml"; then
    echo "golden mismatch: $name (run helm/app/tests/run.sh --update if the change is intended)" >&2
    fail=1
  fi
done
# A broken manifests block must fail the render, not be skipped
bad="$out/bad.yaml"
printf 'services:\n  broken:\n    manifests: |\n      kind: [unclosed\n' > "$bad"
if helm template demo "$chart" --namespace dev -f "$bad" > /dev/null 2>&1; then
  echo "invalid services manifests rendered without an error" >&2
  fail=1
fi
exit "$fail"
