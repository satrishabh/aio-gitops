#!/usr/bin/env bash
# Supply-chain add-ons, on any conformant cluster that already runs Tekton Pipelines (v1.x):
#   - Tekton Chains: signs every image built by the build-image task and attaches SLSA v1.0 provenance
#   - Kyverno: checks at admission that images in dev/staging/prod carry that signature and provenance
# Re-runnable: an existing signing key is kept. Needs kubectl, helm and openssl, and the ci/registry-credentials
# Secret (created by gcp-setup.sh or by hand, see docs/README.md). Uses the current kubectl context.
#
#   POLICY_MODE=Audit (default) reports unsigned images in PolicyReports; POLICY_MODE=Deny blocks them.
#   APP_REGISTRY is the registry prefix the platform pushes to (same variable as the platform, application.yml).
set -euo pipefail

CHAINS_VERSION="${CHAINS_VERSION:-v0.29.9}"
KYVERNO_CHART_VERSION="${KYVERNO_CHART_VERSION:-3.9.2}"   # Kyverno v1.19.2
REGISTRY_PREFIX="${APP_REGISTRY:-ghcr.io/satrishabh}"
POLICY_MODE="${POLICY_MODE:-Audit}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PUB_KEY_FILE="$ROOT/supply-chain/keys/chains.pub"

step() { echo; echo "==> $*"; }
fail() { echo "ERROR: $*" >&2; exit 1; }

case "$POLICY_MODE" in
  Audit) FAILURE_POLICY=Ignore ;;   # never block admission because Kyverno or the registry is unreachable
  Deny)  FAILURE_POLICY=Fail ;;
  *) fail "POLICY_MODE must be Audit or Deny" ;;
esac
for c in kubectl helm openssl; do command -v "$c" >/dev/null || fail "$c is required"; done
[[ "$REGISTRY_PREFIX" =~ ^[a-z0-9.-]+(:[0-9]+)?(/[a-z0-9._-]+)+$ ]] || fail "APP_REGISTRY '$REGISTRY_PREFIX' is not a registry/namespace prefix"
kubectl -n ci get secret registry-credentials >/dev/null 2>&1 || fail "Secret ci/registry-credentials not found; create it first"

# Secrets pass through RAM-backed /dev/shm when available and are removed on exit.
TMPD=$(mktemp -d -p /dev/shm 2>/dev/null || mktemp -d)
chmod 700 "$TMPD"
trap 'rm -rf "$TMPD"' EXIT

# Copies config.json from a ci Secret into a dockerconfigjson Secret <ns>/<name>.
copy_registry_secret() {   # src-secret dest-ns dest-name
  kubectl -n ci get secret "$1" -o jsonpath='{.data.config\.json}' | base64 -d > "$TMPD/config.json"
  [ -s "$TMPD/config.json" ] || fail "ci/$1 has no config.json key"
  kubectl -n "$2" create secret generic "$3" --type=kubernetes.io/dockerconfigjson \
    --from-file=.dockerconfigjson="$TMPD/config.json" --dry-run=client -o yaml | kubectl apply -f -
  rm -f "$TMPD/config.json"
}

# ---------------------------------------------------------------- Tekton Chains
step "Tekton Chains ${CHAINS_VERSION}"
kubectl apply --server-side --force-conflicts \
  -f "https://infra.tekton.dev/tekton-releases/chains/previous/${CHAINS_VERSION}/release.yaml"
kubectl apply --server-side --force-conflicts -f "$ROOT/supply-chain/chains/chains-config.yaml"

step "Chains signing key (tekton-chains/signing-secrets)"
if kubectl -n tekton-chains get secret signing-secrets -o jsonpath='{.data.x509\.pem}' 2>/dev/null | grep -q .; then
  echo "kept the existing key"
else
  # ECDSA P-256, unencrypted PKCS#8 - the format Chains' x509 signer expects. Never written outside $TMPD.
  ( umask 077; openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$TMPD/x509.pem" )
  kubectl -n tekton-chains create secret generic signing-secrets --from-file=x509.pem="$TMPD/x509.pem" \
    --dry-run=client -o yaml | kubectl apply --server-side --force-conflicts -f -
  rm -f "$TMPD/x509.pem"
  echo "generated a new key"
fi
mkdir -p "$(dirname "$PUB_KEY_FILE")"
kubectl -n tekton-chains get secret signing-secrets -o jsonpath='{.data.x509\.pem}' | base64 -d \
  | openssl pkey -pubout > "$PUB_KEY_FILE"
echo "public key: $PUB_KEY_FILE (safe to commit; Kyverno and cosign verify with it)"
kubectl -n tekton-chains rollout restart deploy/tekton-chains-controller
kubectl -n tekton-chains rollout status deploy/tekton-chains-controller --timeout=300s

step "Registry credentials for Chains (ci/registry-signing, used via the pipeline-runner service account)"
copy_registry_secret registry-credentials ci registry-signing
kubectl apply -f "$ROOT/tekton/rbac/"
kubectl apply -n ci -f "$ROOT/tekton/tasks/build-image.yaml"
kubectl apply -n ci -f "$ROOT/tekton/pipelines/"

# ---------------------------------------------------------------- Kyverno
step "Kyverno (chart ${KYVERNO_CHART_VERSION})"
helm repo add kyverno https://kyverno.github.io/kyverno/ >/dev/null 2>&1 || true
helm repo update kyverno >/dev/null
helm upgrade --install kyverno kyverno/kyverno -n kyverno --create-namespace \
  --version "$KYVERNO_CHART_VERSION" -f "$ROOT/supply-chain/kyverno/values.yaml" --wait --timeout 10m

step "Registry read credentials for Kyverno (kyverno/registry-read)"
if kubectl -n ci get secret registry-read-credentials >/dev/null 2>&1; then
  copy_registry_secret registry-read-credentials kyverno registry-read
else
  echo "NOTE: ci/registry-read-credentials not found; using the push credentials. Create a read-only token and re-run."
  copy_registry_secret registry-credentials kyverno registry-read
fi

step "Policy aio-verify-signed-images (${POLICY_MODE}, registry ${REGISTRY_PREFIX})"
awk -v mode="$POLICY_MODE" -v fp="$FAILURE_POLICY" -v reg="$REGISTRY_PREFIX" -v keyfile="$PUB_KEY_FILE" '
  /__PUBLIC_KEY__/ { while ((getline line < keyfile) > 0) print "            " line; next }
  { gsub(/__POLICY_MODE__/, mode); gsub(/__FAILURE_POLICY__/, fp); gsub(/__REGISTRY_PREFIX__/, reg); print }
' "$ROOT/supply-chain/kyverno/verify-aio-images.yaml.tmpl" > "$TMPD/policy.yaml"
kubectl apply -f "$TMPD/policy.yaml"

cat <<EOF

==================================================================
 Supply chain ready. Next CI run is signed automatically.

 Check a TaskRun was signed:
   kubectl -n ci get taskrun -l tekton.dev/pipelineTask=build-push \\
     -o custom-columns=NAME:.metadata.name,SIGNED:'.metadata.annotations.chains\.tekton\.dev/signed'
 Verify an image by hand (cosign CLI):
   cosign verify --key $PUB_KEY_FILE --insecure-ignore-tlog <image>@<digest>
   cosign verify-attestation --key $PUB_KEY_FILE --insecure-ignore-tlog --type slsaprovenance1 <image>@<digest>
 Policy results (Audit mode):
   kubectl get policyreports -n dev
 Enforce once every running image is signed:
   POLICY_MODE=Deny bash bootstrap/install-supply-chain.sh
==================================================================
EOF
