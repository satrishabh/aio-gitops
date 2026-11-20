"""Copy explicit Secret Manager versions to an explicit cluster; never print values.

Operators need Secret Manager accessor only on these five containers and Secret
write permissions in aio-platform/ci/argocd. AIO's runtime identity needs neither.
"""
import argparse
import base64
import json
import re
import shutil
import subprocess


def checked_strings(value, keys):
    if not isinstance(value, dict) or set(value) != set(keys) or any(not isinstance(v, str) for v in value.values()):
        raise ValueError("Secret JSON has unexpected keys or non-string values")
    if any("REPLACE" in v for v in value.values()):
        raise ValueError("Secret still contains a placeholder")
    return value


def manifests(config, oidc, github, registry, repository):
    config = checked_strings(config, ["DB_URL", "DB_USER", "DB_PASSWORD", "APP_PUBLIC_URL",
        "APP_BASE_DOMAIN", "APP_REGISTRY", "APP_REGISTRY_USER", "ARGOCD_TOKEN"])
    oidc = checked_strings(oidc, ["OIDC_ISSUER_URI", "OIDC_CLIENT_ID", "OIDC_CLIENT_SECRET"])
    github = checked_strings(github, ["token"])
    repository = checked_strings(repository, ["url", "username", "password"])
    if not all([config["ARGOCD_TOKEN"], oidc["OIDC_CLIENT_SECRET"], github["token"], repository["password"]]):
        raise ValueError("Required credentials cannot be empty")
    if not isinstance(registry, dict) or set(registry) != {"auths"} or not isinstance(registry["auths"], dict) or not registry["auths"]:
        raise ValueError("Registry secret must be a Docker config object containing auths")
    def secret(namespace, name, data, kind="Opaque", labels=None):
        # Use data instead of stringData for stable server-side apply ownership.
        return {"apiVersion": "v1", "kind": "Secret", "type": kind,
            "metadata": {"name": name, "namespace": namespace, "labels": labels or {}},
            "data": {k: base64.b64encode(v.encode()).decode() for k, v in data.items()}}
    return [secret("aio-platform", "aio-platform-secrets", dict(config, GITHUB_TOKEN=github["token"])),
            secret("aio-platform", "aio-platform-oidc", oidc),
            secret("ci", "github-token", github),
            secret("ci", "registry-credentials", {"config.json": json.dumps(registry)}),
            secret("argocd", "aio-gitops-repository", repository,
                   labels={"argocd.argoproj.io/secret-type": "repository"})]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--project", required=True)
    p.add_argument("--name", required=True, help="Foundation environment name, e.g. aio-dev")
    p.add_argument("--context", required=True)
    for key in ("config", "oidc", "github", "registry", "repository"):
        p.add_argument("--" + key + "-version", required=True, help="Numeric Secret Manager version (not latest)")
    args = p.parse_args()
    if not args.context.startswith("gke_" + args.project + "_"):
        p.error("Explicit context must belong to the supplied project")
    if not re.fullmatch(r"[a-z][a-z0-9-]{2,17}", args.name):
        p.error("Invalid environment name")
    ids = {"config": "aio-platform-config", "oidc": "aio-platform-oidc", "github": "aio-github-token",
           "registry": "aio-registry-config", "repository": "aio-argocd-repository"}
    gcloud, kubectl = shutil.which("gcloud"), shutil.which("kubectl")
    if not gcloud or not kubectl:
        p.error("gcloud and kubectl must be on PATH")
    values = {}
    try:
        for key, suffix in ids.items():
            version = getattr(args, key + "_version")
            if not re.fullmatch(r"[1-9][0-9]*", version):
                p.error("Use explicit numeric Secret Manager versions")
            result = subprocess.run([gcloud, "secrets", "versions", "access", version,
                "--project", args.project, "--secret", args.name + "-" + suffix],
                capture_output=True, check=True, timeout=30)
            values[key] = json.loads(result.stdout)
        payload = {"apiVersion": "v1", "kind": "List", "items": manifests(**values)}
        subprocess.run([kubectl, "--context", args.context, "apply", "--server-side",
            "--field-manager=aio-secret-sync", "-f", "-"], input=json.dumps(payload).encode(),
            capture_output=True, check=True, timeout=90)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError):
        # Child output can include secret payloads; deliberately suppress it.
        raise SystemExit("Secret synchronization failed (credentials/output suppressed). Check access, versions, JSON schema, and field ownership.") from None
    print("Synchronized five Secrets; values were not written to disk or Terraform state.")
    print("Restart AIO deliberately after a rotation; existing pods keep old environment values.")


if __name__ == "__main__":
    main()
