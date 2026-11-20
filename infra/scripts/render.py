"""Render non-secret bootstrap artifacts; no cloud or Kubernetes writes.

python infra/scripts/render.py --foundation infra/generated/foundation.json \
  --config infra/config.json --output infra/generated
"""
import argparse
import json
import re
from pathlib import Path


def render(outputs, config):
    foundation = {key: value["value"] for key, value in outputs.items()}
    required = ("platform_host", "base_domain", "gitops_owner", "gitops_repo",
                "gitops_branch", "registry_user", "image_tag", "oidc_issuer",
                "oidc_client_id", "acme_email", "acme_server")
    for key in required:
        if not isinstance(config.get(key), str) or not config[key].strip():
            raise ValueError("Missing configuration: " + key)
        if any(word in config[key] for word in ("REPLACE_", "YOUR_")):
            raise ValueError("Replace example value: " + key)
    for key in ("platform_host", "base_domain"):
        if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", config[key]):
            raise ValueError("Expected a DNS hostname: " + key)
    for key in ("gitops_owner", "gitops_repo", "registry_user"):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", config[key]):
            raise ValueError("Invalid repository/registry identity: " + key)
    if config["image_tag"] == "latest" or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", config["image_tag"]):
        raise ValueError("Use a valid immutable image tag, not latest")
    if not config["oidc_issuer"].startswith("https://") or not config["acme_server"].startswith("https://"):
        raise ValueError("OIDC and ACME endpoints must use HTTPS")
    if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", config["acme_email"]):
        raise ValueError("Invalid ACME email")
    consumer = foundation["database_consumers"]["platform"]
    if consumer["namespace"] != "aio-platform" or consumer["service_account"] != "aio-platform":
        raise ValueError("Current platform bootstrap requires aio-platform namespace and account")
    repo = f'https://github.com/{config["gitops_owner"]}/{config["gitops_repo"]}.git'
    context = f'gke_{foundation["project_id"]}_{foundation["region"]}_{foundation["cluster_name"]}'
    runtime = {"kube_context": context, "ingress_ip": foundation["ingress_ip"],
               "database_consumers": foundation["database_consumers"]}
    env = {
        "ARGOCD_URL": "https://argocd-server.argocd.svc",
        "ARGOCD_INSECURE": "true",  # internal chart certificate; replace with trusted CA before production
        "GITOPS_REPO_URL": repo, "GITOPS_OWNER": config["gitops_owner"],
        "GITOPS_REPO": config["gitops_repo"], "GITOPS_BRANCH": config["gitops_branch"],
        "ALLOWED_USERS": "", "ALLOWED_ROLES": "aio-admin,aio-business,aio-community",
        "APPROVERS": "",  # preserve four-eyes default; do not inherit a named self-approver
        "APP_CLUSTERS_0_NAME": "in-cluster",
        "APP_CLUSTERS_0_SERVER": "https://kubernetes.default.svc",
        "APP_CLUSTERS_0_ENVS": "dev,staging,prod",
        "APP_CLUSTERS_0_BASEDOMAIN": config["base_domain"],
        "APP_CLUSTERS_0_INGRESSIP": foundation["ingress_ip"],
        "APP_CLUSTERS_0_ISSUER": "aio-acme",
        "APP_CLUSTERS_0_TLS": "false"
    }
    values = {
        "nameOverride": "aio-platform", "replicaCount": 1,
        "serviceAccountName": "aio-platform", "containerPort": 8080,
        "image": {"repository": foundation["image_repository"], "tag": config["image_tag"]},
        "envFrom": [{"secretRef": {"name": "aio-platform-secrets"}},
                    {"secretRef": {"name": "aio-platform-oidc"}}],
        "env": [{"name": k, "value": v} for k, v in env.items()],
        "probes": {"liveness": {"path": "/actuator/health/liveness", "initialDelaySeconds": 120},
                   "readiness": {"path": "/actuator/health/readiness", "initialDelaySeconds": 20}},
        "resources": {"requests": {"cpu": "100m", "memory": "448Mi"},
                      "limits": {"cpu": "900m", "memory": "768Mi"}},
        "ingress": {"enabled": True, "className": "nginx", "host": config["platform_host"],
                    "tls": True, "issuer": "aio-acme", "forceHttps": True},
        "routes": [], "pdb": {"enabled": False}, "autoscaling": {"enabled": False}
    }
    projects = []
    for name, namespaces in (("apps", ["dev", "staging", "prod"]), ("platform", ["aio-platform"])):
        projects.append({"apiVersion": "argoproj.io/v1alpha1", "kind": "AppProject",
            "metadata": {"name": name, "namespace": "argocd"},
            "spec": {"sourceRepos": [repo], "destinations": [
                {"server": "https://kubernetes.default.svc", "namespace": n} for n in namespaces],
                "clusterResourceWhitelist": [],
                "namespaceResourceBlacklist": [{"group": "rbac.authorization.k8s.io", "kind": "*"},
                                               {"group": "", "kind": "ResourceQuota"},
                                               {"group": "", "kind": "LimitRange"}]}})
    application = {"apiVersion": "argoproj.io/v1alpha1", "kind": "Application",
        "metadata": {"name": "aio-platform", "namespace": "argocd"},
        "spec": {"project": "platform", "source": {"repoURL": repo,
            "targetRevision": config["gitops_branch"], "path": "helm/app",
            "helm": {"valueFiles": ["../../platform/values-platform.json"]}},
            "destination": {"server": "https://kubernetes.default.svc", "namespace": "aio-platform"},
            "syncPolicy": {"automated": {"prune": False, "selfHeal": True}}}}
    issuer = {"apiVersion": "cert-manager.io/v1", "kind": "ClusterIssuer",
        "metadata": {"name": "aio-acme"}, "spec": {"acme": {
            "email": config["acme_email"], "server": config["acme_server"],
            "privateKeySecretRef": {"name": "aio-acme-account"},
            "solvers": [{"http01": {"ingress": {"ingressClassName": "nginx"}}}]}}}
    # This template contains no credentials. Fill ARGOCD_TOKEN before uploading
    # it to Secret Manager; keep all populated files outside the repository.
    platform_config = {"DB_URL": consumer["jdbc_url"], "DB_USER": consumer["database_user"],
        "DB_PASSWORD": "", "APP_PUBLIC_URL": "https://" + config["platform_host"],
        "APP_BASE_DOMAIN": config["base_domain"], "APP_REGISTRY": "ghcr.io/" + config["registry_user"],
        "APP_REGISTRY_USER": config["registry_user"], "ARGOCD_TOKEN": "REPLACE_BEFORE_UPLOAD"}
    oidc = {"OIDC_ISSUER_URI": config["oidc_issuer"], "OIDC_CLIENT_ID": config["oidc_client_id"],
            "OIDC_CLIENT_SECRET": "REPLACE_BEFORE_UPLOAD"}
    # SQL identifiers are quoted, never interpolated as untrusted SQL fragments.
    quote = lambda value: '"' + value.replace('"', '""') + '"'
    grants = []
    for c in foundation["database_consumers"].values():
        grants.extend([f'\\connect {quote(c["database"])}',
            f'GRANT CONNECT ON DATABASE {quote(c["database"])} TO {quote(c["database_user"])};',
            f'GRANT USAGE, CREATE ON SCHEMA public TO {quote(c["database_user"])};'])
    return {"runtime.tfvars.json": runtime, "values-platform.json": values,
            "projects.json": {"apiVersion": "v1", "kind": "List", "items": projects},
            "application.json": application, "issuer.json": issuer,
            "platform-config.template.json": platform_config, "oidc.template.json": oidc,
            "database-grants.sql": "\n".join(grants) + "\n"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--foundation", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    artifacts = render(json.loads(args.foundation.read_text(encoding="utf-8-sig")),
                       json.loads(args.config.read_text(encoding="utf-8-sig")))
    args.output.mkdir(parents=True, exist_ok=True)
    for name, value in artifacts.items():
        target = args.output / name
        if target.exists():
            raise FileExistsError(f"Refusing to overwrite {target}; use a new output directory")
    for name, value in artifacts.items():
        (args.output / name).write_text(value if isinstance(value, str) else json.dumps(value, indent=2) + "\n", encoding="utf-8")
    print("Rendered non-secret bootstrap artifacts into", args.output)


if __name__ == "__main__":
    main()
