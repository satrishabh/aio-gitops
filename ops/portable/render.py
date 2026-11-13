"""Generate non-secret portable Helm values and read-only preflight commands.

No cloud login, provisioning, Kubernetes mutation or secret retrieval is performed.
"""
import argparse
import copy
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "platform/frontend/src/data/cloudProviders.json"
SECRET_KEYS = {"DB_URL", "DB_USER", "DB_PASSWORD", "OIDC_CLIENT_SECRET", "GITHUB_TOKEN", "ARGOCD_TOKEN"}
REQUIRED = ("APP_PUBLIC_URL", "APP_REGISTRY", "APP_REGISTRY_USER", "GITOPS_REPO_URL", "GITOPS_OWNER", "GITOPS_REPO", "OIDC_ISSUER_URI", "OIDC_CLIENT_ID", "ARGOCD_URL", "ALLOWED_ROLES")


def render(provider_id, context, values):
    providers = {p["id"]: p for p in json.loads(CATALOG.read_text(encoding="utf-8"))}
    if provider_id not in providers:
        raise ValueError("Unknown provider; select an ID from the provider catalog")
    provider = providers[provider_id]
    if provider["kind"] != "kubernetes":
        raise ValueError("This target is not a Kubernetes installation: " + provider["status"])
    if not context or context.startswith("-") or any(c.isspace() for c in context):
        raise ValueError("Supply an explicit kubeconfig context name without whitespace")
    result = copy.deepcopy(values)
    config = result.get("config", {})
    if not isinstance(config, dict):
        raise ValueError("config must be an object")
    if SECRET_KEYS.intersection(config):
        raise ValueError("Database and runtime credentials belong in the existing Kubernetes Secret")
    for key in REQUIRED:
        if not isinstance(config.get(key), str) or not config[key].strip():
            raise ValueError("Missing installation setting: " + key)
    for key in ("APP_PUBLIC_URL", "GITOPS_REPO_URL", "OIDC_ISSUER_URI", "ARGOCD_URL"):
        url = urlsplit(config[key])
        if url.scheme != "https" or not url.hostname or url.username or url.password:
            raise ValueError("Expected credential-free HTTPS endpoint: " + key)
    image = result.get("image", {})
    if not image.get("repository") or not re.fullmatch(r"sha256:[a-f0-9]{64}", image.get("digest", "")):
        raise ValueError("Supply your published image repository and immutable sha256 digest")
    if not result.get("existingSecret"):
        raise ValueError("existingSecret must name a pre-provisioned runtime Secret")
    if config.get("AIO_INDEPENDENT_DELIVERY", "false") != "false":
        raise ValueError("Keep independent delivery disabled until the credential acceptance gates pass")
    result["serviceAccount"] = {**result.get("serviceAccount", {}), "name": "aio-platform", "create": False}
    # The current static RBAC contract fixes the installation namespaces.
    config["TEKTON_NAMESPACE"] = "ci"
    config["AIO_INDEPENDENT_DELIVERY"] = "false"
    result["config"] = config
    prefix = ["kubectl", "--context", context]
    checks = [prefix + args for args in (
        ["get", "nodes"], ["get", "storageclass,ingressclass"],
        ["get", "deployments", "-A"], ["auth", "can-i", "create", "namespaces"],
        ["get", "crd", "pipelineruns.tekton.dev", "applications.argoproj.io", "certificates.cert-manager.io"])]
    return {"values-platform.json": result,
            "preflight.json": {"provider": provider_id, "context": context, "status": provider["status"],
                               "commands": checks, "notes": provider["requirements"]}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--provider", required=True)
    parser.add_argument("--context", required=True)
    parser.add_argument("--values", type=Path, required=True, help="Non-secret customer values in JSON")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    artifacts = render(args.provider, args.context, json.loads(args.values.read_text(encoding="utf-8-sig")))
    if args.output.exists():
        raise FileExistsError("Use a new output directory; refusing to overwrite existing artifacts")
    args.output.mkdir(parents=True)
    for name, value in artifacts.items():
        (args.output / name).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    print("Generated portable values and read-only preflight plan; no deployment performed")


if __name__ == "__main__":
    main()
