#!/usr/bin/env python3
"""Verify rendered browser isolation and staged deployment manifests."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]


class BrowserRuntimeTest(unittest.TestCase):
    def setUp(self):
        fixture = tempfile.TemporaryDirectory(prefix="browser-manifests-")
        self.addCleanup(fixture.cleanup)
        self.fixture = Path(fixture.name)
        for directory in ("k8s", "k8s-gke", "k8s-local", "docker"):
            for source in (ROOT / directory).rglob("*"):
                if not source.is_file():
                    continue
                if source.suffix not in {".yaml", ".conf", ".xml", ".sql"} and source != ROOT / "docker/chromium-seccomp.json":
                    continue
                target = self.fixture / source.relative_to(ROOT)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
        (self.fixture / "k8s/secrets.env").write_text(
            "JDBC_PASSWORD=dummy\nGEMINI_API_KEY=dummy\nR2_ACCOUNT_ID=dummy\n"
            "R2_ACCESS_KEY_ID=dummy\nR2_SECRET_ACCESS_KEY=dummy\nR2_BUCKET=dummy\n"
        )
        (self.fixture / "k8s/platform-secrets.env").write_text("JWT_SECRET=dummy\n")

    def render(self, overlay):
        result = subprocess.run(
            ["kubectl", "kustomize", "--load-restrictor", "LoadRestrictionsNone",
             str(self.fixture / overlay)],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return list(yaml.safe_load_all(result.stdout))

    def test_browser_is_hardened_without_serving_credentials_or_persistent_storage(self):
        for overlay in ("k8s", "k8s-gke", "k8s-local"):
            with self.subTest(overlay=overlay):
                resources = self.render(overlay)
                for workload in resources:
                    if workload["kind"] not in {"StatefulSet", "Deployment"}:
                        continue
                    for volume in workload["spec"]["template"]["spec"].get("volumes", []):
                        name = volume.get("configMap", {}).get("name")
                        if name:
                            self.assertTrue(
                                any(r["kind"] == "ConfigMap" and r["metadata"]["name"] == name for r in resources),
                                f"{overlay}: {workload['metadata']['name']} references missing ConfigMap {name}",
                            )
                browser = next(r for r in resources if r["metadata"]["name"] == "promovolve-browser")
                spec = browser["spec"]["template"]["spec"]
                self.assertEqual(browser["spec"]["replicas"], 1)
                self.assertEqual(spec["securityContext"]["runAsUser"], 1000)
                profile = spec["securityContext"]["seccompProfile"]
                self.assertEqual(profile["type"], "Localhost")
                self.assertEqual(profile["localhostProfile"], "promovolve/chromium-v1.json")
                self.assertFalse(any("persistentVolumeClaim" in v or "hostPath" in v for v in spec["volumes"]))
                container = spec["containers"][0]
                self.assertEqual(container["securityContext"]["capabilities"]["drop"], ["ALL"])
                self.assertIs(container["securityContext"]["allowPrivilegeEscalation"], False)
                self.assertNotIn("envFrom", container)
                secrets = sorted(e["name"] for e in container["env"] if "secretKeyRef" in e.get("valueFrom", {}))
                self.assertEqual(secrets, ["R2_ACCESS_KEY_ID", "R2_ACCOUNT_ID", "R2_BUCKET", "R2_SECRET_ACCESS_KEY"])
                self.assertIn("promovolve-api", container["image"])
                config = next(r["data"]["application-browser.conf"] for r in resources if "application-browser.conf" in r.get("data", {}))
                self.assertIn('roles = ["browser"]', config)
                self.assertIn("durable.keys = []", config)
                installer = next(r for r in resources if r["kind"] == "DaemonSet" and r["metadata"]["name"] == "chromium-seccomp")
                self.assertEqual(installer["spec"]["template"]["spec"]["tolerations"], [{"operator": "Exists"}])
                api = next(r for r in resources if r["kind"] == "StatefulSet" and r["metadata"]["name"] == "promovolve-api")
                api_spec = api["spec"]["template"]["spec"]
                self.assertNotIn("securityContext", api_spec["containers"][0])
                self.assertNotIn("fsGroup", api_spec.get("securityContext") or {})
                self.assertNotEqual(api["spec"]["template"]["metadata"]["labels"].get("tier"), "browser")

    def test_browser_bootstrap_does_not_apply_serving_workloads_or_their_role_config(self):
        selected = [r for r in self.render("k8s-gke")
                    if r["metadata"].get("labels", {}).get("promovolve.dev/browser-bootstrap") == "true"]
        self.assertEqual(sorted(r["kind"] for r in selected), ["ConfigMap", "ConfigMap", "Deployment", "Secret"])
        self.assertFalse(any("application-app.conf" in r.get("data", {}) for r in selected))
        self.assertEqual([r["metadata"]["name"] for r in selected if r["kind"] == "Deployment"], ["promovolve-browser"])


class GkeSetupTest(unittest.TestCase):
    def setUp(self):
        fixture = tempfile.TemporaryDirectory(prefix="browser-setup-")
        self.addCleanup(fixture.cleanup)
        self.fixture = Path(fixture.name)
        for name in ("k8s-gke/setup.sh", "k8s/kustomization.yaml", "docker/chromium-seccomp.json"):
            target = self.fixture / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        for name in ("secrets.env", "platform-secrets.env"):
            (self.fixture / "k8s" / name).write_text("TEST_SECRET=dummy\n")
        self.log = self.fixture / "commands.jsonl"
        bin_dir = self.fixture / "bin"
        bin_dir.mkdir()
        stub = f"#!{sys.executable}\n" + '''import json
import os
from pathlib import Path
import sys

name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ["SETUP_TEST_LOG"], "a") as log:
    log.write(json.dumps([name, *args]) + "\\n")
if name == "gh":
    print(os.environ["SETUP_TEST_OPEN_PINS"])
elif name == "kubectl":
    if "apply" in args and "-" in args:
        sys.stdin.read()
    if "kustomize" in args:
        print('{"apiVersion":"v1","kind":"List","items":[]}')
else:
    sys.exit("Unexpected cloud command")
'''
        for name in ("kubectl", "gh", "gcloud"):
            path = bin_dir / name
            path.write_text(stub)
            path.chmod(0o755)
        self.env = {
            **os.environ, "PATH": f"{bin_dir}:{os.defpath}", "CTX": "fixture-context",
            "REGISTRY": "registry.example", "ALLOW_OPEN_PINS": "0",
            "SETUP_TEST_LOG": str(self.log), "SETUP_TEST_OPEN_PINS": "",
        }

    def run_setup(self, *args, open_pins=""):
        self.log.write_text("")
        result = subprocess.run(
            ["bash", str(self.fixture / "k8s-gke/setup.sh"), *args],
            env={**self.env, "SETUP_TEST_OPEN_PINS": open_pins},
            cwd=self.fixture, capture_output=True, text=True, timeout=30,
        )
        return result, [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_open_pins_abort_before_any_kubernetes_mutation(self):
        for args in (("--browser-only",), ("--apply-only",), ("--deploy-only", "--build-images")):
            with self.subTest(args=args):
                result, commands = self.run_setup(*args, open_pins="https://example.invalid/pin-pr")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("pin-back PR is still open", result.stderr)
                self.assertEqual([c for c in commands if c[0] == "kubectl"],
                                 [["kubectl", "--context", "fixture-context", "cluster-info"]])

    def test_closed_pins_allow_cleanup_and_deployment_after_the_check(self):
        for mode in ("--browser-only", "--apply-only"):
            with self.subTest(mode=mode):
                result, commands = self.run_setup(mode, "--pin-images")
                self.assertEqual(result.returncode, 0, result.stderr)
                pin_check = next(i for i, c in enumerate(commands) if c[0] == "gh")
                cleanup = [i for i, c in enumerate(commands) if "BANNER_SCRIPT_URL-" in c]
                self.assertTrue(cleanup)
                self.assertTrue(all(i > pin_check for i in cleanup))
                self.assertTrue(any("apply" in c for c in commands))
                if mode == "--browser-only":
                    self.assertFalse(any("deployment/promovolve-platform" in c for c in commands))

    def test_explicit_open_pins_override_still_allows_deployment(self):
        result, commands = self.run_setup("--browser-only", "--pin-images", "--allow-open-pins",
                                          open_pins="https://example.invalid/pin-pr")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any(c[0] == "gh" for c in commands))
        self.assertTrue(any("apply" in c for c in commands))


if __name__ == "__main__":
    unittest.main()
