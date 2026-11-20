"""Contract/safety checks for generated deployment artifacts; no cloud calls."""
import base64
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "infra" / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


renderer, secrets = load("render"), load("sync_secrets")


def fixture():
    outputs = {k: {"value": v} for k, v in {
        "project_id": "test-project", "region": "asia-south1", "cluster_name": "aio-test",
        "ingress_ip": "203.0.113.10", "image_repository": "asia-south1-docker.pkg.dev/test-project/aio-test/aio-platform",
        "database_consumers": {"platform": {
            "namespace": "aio-platform", "service_account": "aio-platform",
            "google_identity": "aio-test-platform@test-project.iam.gserviceaccount.com",
            "database": "aio", "database_user": "aio-test-platform@test-project.iam",
            "jdbc_url": "jdbc:postgresql:///aio?enableIamAuth=true"}}}.items()}
    config = json.loads((ROOT / "infra" / "config.example.json").read_text())
    config.update(gitops_owner="test-owner", registry_user="test-owner", image_tag="abcdef123456")
    return outputs, config


class RenderTests(unittest.TestCase):
    def test_isolated_values_and_contracts(self):
        outputs, config = fixture()
        result = renderer.render(outputs, config)
        values = result["values-platform.json"]
        env = {entry["name"]: entry["value"] for entry in values["env"]}
        self.assertEqual(values["replicaCount"], 1)
        self.assertEqual(env["GITOPS_REPO_URL"], "https://github.com/test-owner/aio-dev-gitops.git")
        self.assertEqual(env["APPROVERS"], "")
        self.assertEqual(result["runtime.tfvars.json"]["kube_context"], "gke_test-project_asia-south1_aio-test")
        self.assertEqual(result["application.json"]["spec"]["source"]["helm"]["valueFiles"],
                         ["../../platform/values-platform.json"])
        self.assertEqual(result["projects.json"]["items"][0]["spec"]["clusterResourceWhitelist"], [])
        self.assertEqual(result["platform-config.template.json"]["DB_PASSWORD"], "")
        self.assertNotIn("CREATEROLE", result["database-grants.sql"])
        self.assertIn('TO "aio-test-platform@test-project.iam";', result["database-grants.sql"])

    def test_reject_placeholder(self):
        outputs, config = fixture()
        config["gitops_owner"] = "YOUR_GITHUB_OWNER"
        with self.assertRaises(ValueError):
            renderer.render(outputs, config)

    def test_reject_unversioned_image_and_invalid_host(self):
        for field, value in (("image_tag", "latest"), ("platform_host", "https://wrong/path")):
            outputs, config = fixture()
            config[field] = value
            with self.assertRaises(ValueError):
                renderer.render(outputs, config)

    def test_cli_does_not_overwrite_existing_files(self):
        outputs, config = fixture()
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            (path / "outputs.json").write_text(json.dumps(outputs))
            (path / "config.json").write_text(json.dumps(config))
            args = [sys.executable, str(ROOT / "infra/scripts/render.py"), "--foundation", str(path / "outputs.json"),
                    "--config", str(path / "config.json"), "--output", str(path / "rendered")]
            subprocess.run(args, capture_output=True, check=True)
            target = path / "rendered" / "values-platform.json"
            target.write_text("existing content")
            second = subprocess.run(args, capture_output=True)
            self.assertNotEqual(second.returncode, 0)
            self.assertEqual(target.read_text(), "existing content")

    def test_chart_accepts_generated_values(self):
        outputs, config = fixture()
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "values.json"
            path.write_text(json.dumps(renderer.render(outputs, config)["values-platform.json"]))
            result = subprocess.run(["helm", "template", "aio-platform", str(ROOT / "helm/app"),
                "--namespace", "aio-platform", "-f", str(path)], capture_output=True, check=True)
            text = result.stdout.decode()
            self.assertIn("serviceAccountName: aio-platform", text)
            self.assertIn("name: aio-platform-secrets", text)
            self.assertIn("aio-dev.example.com", text)
            self.assertNotIn("app.sarvaikam.com", text)


class SecretTests(unittest.TestCase):
    def setUp(self):
        outputs, config = fixture()
        rendered = renderer.render(outputs, config)
        self.config = rendered["platform-config.template.json"]
        self.config["ARGOCD_TOKEN"] = "fake-test-token"
        self.oidc = rendered["oidc.template.json"]
        self.oidc["OIDC_CLIENT_SECRET"] = "fake-test-secret"
        self.github = {"token": "fake-test-github"}
        self.registry = {"auths": {"ghcr.io": {"auth": "ZmFrZTp0ZXN0"}}}
        self.repository = {"url": "https://github.com/test-owner/aio-dev-gitops.git", "username": "x-access-token", "password": "fake-read"}

    def test_secret_names_keys_and_types_match_consumers(self):
        result = secrets.manifests(self.config, self.oidc, self.github, self.registry, self.repository)
        self.assertEqual(len(result), 5)
        self.assertEqual([(s["metadata"]["namespace"], s["metadata"]["name"]) for s in result], [
            ("aio-platform", "aio-platform-secrets"), ("aio-platform", "aio-platform-oidc"),
            ("ci", "github-token"), ("ci", "registry-credentials"), ("argocd", "aio-gitops-repository")])
        self.assertEqual(base64.b64decode(result[0]["data"]["GITHUB_TOKEN"]).decode(), "fake-test-github")
        self.assertEqual(json.loads(base64.b64decode(result[3]["data"]["config.json"])), self.registry)
        self.assertNotIn("GITHUB_TOKEN", self.config)  # caller's input was not modified

    def test_reject_unexpected_secret_keys(self):
        self.config["UNEXPECTED"] = "oops"
        with self.assertRaises(ValueError):
            secrets.manifests(self.config, self.oidc, self.github, self.registry, self.repository)

    def test_reject_empty_or_placeholder_credentials(self):
        for invalid in ("", "REPLACE_BEFORE_UPLOAD"):
            self.oidc["OIDC_CLIENT_SECRET"] = invalid
            with self.assertRaises(ValueError):
                secrets.manifests(self.config, self.oidc, self.github, self.registry, self.repository)

    def test_failure_does_not_echo_child_secret_output(self):
        argv = ["sync_secrets.py", "--project", "test-project", "--name", "aio-test",
            "--context", "gke_test-project_asia-south1_aio-test", "--config-version", "1", "--oidc-version", "1",
            "--github-version", "1", "--registry-version", "1", "--repository-version", "1"]
        with patch.object(sys, "argv", argv), patch.object(secrets.shutil, "which", side_effect=lambda name: name), \
             patch.object(secrets.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "gcloud", output=b"DO-NOT-PRINT", stderr=b"DO-NOT-PRINT")):
            with self.assertRaises(SystemExit) as error:
                secrets.main()
            self.assertNotIn("DO-NOT-PRINT", str(error.exception))

    def test_success_uses_explicit_context_stdin_and_no_force(self):
        argv = ["sync_secrets.py", "--project", "test-project", "--name", "aio-test",
            "--context", "gke_test-project_asia-south1_aio-test", "--config-version", "1", "--oidc-version", "2",
            "--github-version", "3", "--registry-version", "4", "--repository-version", "5"]
        results = [subprocess.CompletedProcess([], 0, stdout=json.dumps(item).encode()) for item in
                   (self.config, self.oidc, self.github, self.registry, self.repository)]
        results.append(subprocess.CompletedProcess([], 0, stdout=b""))
        with patch.object(sys, "argv", argv), patch.object(secrets.shutil, "which", side_effect=lambda name: name), \
             patch.object(secrets.subprocess, "run", side_effect=results) as calls:
            secrets.main()
            command = calls.call_args.args[0]
            self.assertIn("gke_test-project_asia-south1_aio-test", command)
            self.assertIn("--server-side", command)
            self.assertNotIn("--force-conflicts", command)
            payload = json.loads(calls.call_args.kwargs["input"])
            self.assertEqual(len(payload["items"]), 5)


if __name__ == "__main__":
    unittest.main()
