#!/usr/bin/env python3
"""Verify rendered browser isolation and staged deployment manifests."""

from pathlib import Path
import shutil
import subprocess
import tempfile
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


if __name__ == "__main__":
    unittest.main()
