import copy
import json
from pathlib import Path
import unittest
import subprocess
import sys
import tempfile
from render import render, CATALOG, ROOT


class PortableProfilesTest(unittest.TestCase):
    def setUp(self):
        self.values = json.loads(Path(__file__).with_name("values-example.json").read_text())

    def test_all_kubernetes_targets_have_identical_runtime_contract(self):
        for provider in json.loads(CATALOG.read_text()):
            if provider["kind"] != "kubernetes":
                continue
            with self.subTest(provider=provider["id"]):
                output = render(provider["id"], "customer-context", self.values)
                self.assertEqual(output["values-platform.json"]["config"]["AIO_INDEPENDENT_DELIVERY"], "false")
                self.assertFalse(output["values-platform.json"]["serviceAccount"]["create"])
                for cmd in output["preflight.json"]["commands"]:
                    self.assertEqual(cmd[:3], ["kubectl", "--context", "customer-context"])
                    self.assertIn(cmd[3], ("get", "auth"))

    def test_cloudflare_is_not_misrepresented_as_kubernetes(self):
        for provider in ("cloudflare-edge", "cloudflare-dns"):
            with self.assertRaisesRegex(ValueError, "not a Kubernetes"):
                render(provider, "customer-context", self.values)

    def test_input_not_mutated(self):
        original = copy.deepcopy(self.values)
        render("aws", "eks-context", self.values)
        self.assertEqual(original, self.values)

    def test_every_kubernetes_profile_renders_the_real_chart(self):
        with tempfile.TemporaryDirectory() as directory:
            for provider in json.loads(CATALOG.read_text()):
                if provider["kind"] != "kubernetes":
                    continue
                with self.subTest(provider=provider["id"]):
                    path = Path(directory) / "values.json"
                    path.write_text(json.dumps(render(provider["id"], "test-context", self.values)["values-platform.json"]))
                    result = subprocess.run(["helm", "template", "aio-platform", str(ROOT / "helm/aio-platform"), "-f", str(path)], capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn("kind: Deployment", result.stdout)
                    self.assertNotIn("kind: ServiceAccount", result.stdout)

    def test_cli_refuses_to_overwrite_existing_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            command = [sys.executable, str(Path(__file__).with_name("render.py")), "--provider", "azure",
                       "--context", "test-context", "--values", str(Path(__file__).with_name("values-example.json")), "--output", str(output)]
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            before = (output / "values-platform.json").read_bytes()
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(second.returncode, 0)
            self.assertEqual(before, (output / "values-platform.json").read_bytes())

    def test_rejects_secrets_and_invalid_endpoints(self):
        for field, value in (("DB_PASSWORD", "secret"), ("OIDC_ISSUER_URI", "http://issuer"),
                             ("ARGOCD_URL", "https://user:pass@example.com"), ("GITOPS_OWNER", "")):
            with self.subTest(field=field):
                config = copy.deepcopy(self.values)
                config["config"][field] = value
                with self.assertRaises(ValueError):
                    render("azure", "aks-context", config)

    def test_rejects_unknown_provider_and_missing_context(self):
        for provider, context in (("made-up", "context"), ("aws", ""), ("azure", "--insecure")):
            with self.assertRaises(ValueError):
                render(provider, context, self.values)

    def test_rejects_mutable_image_and_premature_delivery(self):
        self.values["image"]["digest"] = "latest"
        with self.assertRaises(ValueError):
            render("alibaba", "ack", self.values)
        self.setUp()
        self.values["config"]["AIO_INDEPENDENT_DELIVERY"] = "true"
        with self.assertRaises(ValueError):
            render("alibaba", "ack", self.values)


if __name__ == "__main__":
    unittest.main()
