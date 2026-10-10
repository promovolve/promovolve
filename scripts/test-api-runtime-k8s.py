#!/usr/bin/env python3
"""Verify that a ready rollout passes and broken node or volume checks fail."""

import importlib.util
import hashlib
from copy import deepcopy
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("verify-api-runtime-k8s.py")
SPEC = importlib.util.spec_from_file_location("verify_api_runtime_k8s", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
IMAGE = "registry.example/api@sha256:" + "a" * 64
PROFILE_REVISION = hashlib.sha256(MODULE.PROFILE_PATH.read_bytes()).hexdigest()
SECURITY = {
    "runAsNonRoot": True,
    "runAsUser": 1000,
    "runAsGroup": 1000,
    "allowPrivilegeEscalation": False,
    "capabilities": {"drop": ["ALL"]},
    "seccompProfile": {"type": "Localhost", "localhostProfile": MODULE.PROFILE},
}


def fixtures(active_singleton=False):
    revision = hashlib.sha256(MODULE.PROFILE_PATH.read_bytes()).hexdigest()
    def metadata():
        return {"annotations": {MODULE.PROFILE_ANNOTATION: revision}}

    container = {
        "name": "api", "image": IMAGE, "securityContext": deepcopy(SECURITY),
        "env": [{"name": "CHROMIUM_NO_SANDBOX", "value": "false"}],
        "volumeMounts": [{"name": "ddata", "mountPath": "/data"}],
    }
    pod_spec = {
        "securityContext": {"fsGroup": 1000, "fsGroupChangePolicy": "Always"},
        "nodeName": "node-a", "containers": [container],
        "volumes": [{"name": "ddata", "persistentVolumeClaim": {"claimName": "ddata-promovolve-api-0"}}],
    }
    data = {
        ("daemonset", "chromium-seccomp"): {
            "metadata": {"generation": 2},
            "status": {"desiredNumberScheduled": 1, "numberReady": 1,
                       "updatedNumberScheduled": 1, "observedGeneration": 2},
        },
        ("pods", "-l", "app=chromium-seccomp"): {
            "items": [{"metadata": {"name": "installer-a"}, "spec": {"nodeName": "node-a"},
                       "status": {"conditions": [{"type": "Ready", "status": "True"}]}}],
        },
        ("statefulset", "promovolve-singleton"): {
            "metadata": {"generation": 2},
            "status": {"readyReplicas": 0, "observedGeneration": 2,
                       "currentRevision": "v2", "updateRevision": "v2"},
            "spec": {"replicas": 0, "template": {"metadata": metadata(), "spec": {
                "securityContext": {"fsGroup": 1000, "fsGroupChangePolicy": "Always"},
                "containers": [dict(deepcopy(container), name="singleton")],
            }}},
        },
        ("statefulset", "promovolve-api"): {
            "metadata": {"generation": 2},
            "status": {"readyReplicas": 1, "observedGeneration": 2,
                       "currentRevision": "v2", "updateRevision": "v2"},
            "spec": {"replicas": 1, "template": {"metadata": metadata(), "spec": deepcopy(pod_spec)}},
        },
        ("pods", "-l", "app=promovolve-api,tier=singleton"): {"items": []},
        ("pods", "-l", "app=promovolve-api,tier=app"): {
            "items": [{"metadata": dict(metadata(), name="promovolve-api-0"), "spec": pod_spec,
                       "status": {"conditions": [{"type": "Ready", "status": "True"}]}}],
        },
    }
    for tier in ("singleton", "api"):
        data[("statefulset", "promovolve-" + tier)]["spec"]["volumeClaimTemplates"] = [
            {"metadata": {"name": "ddata"}}]
    if active_singleton:
        statefulset = data[("statefulset", "promovolve-singleton")]
        statefulset["spec"]["replicas"] = statefulset["status"]["readyReplicas"] = 1
        singleton_spec = deepcopy(pod_spec)
        singleton_spec["containers"][0]["name"] = "singleton"
        singleton_spec["volumes"][0]["persistentVolumeClaim"]["claimName"] = "ddata-promovolve-singleton-0"
        data[("pods", "-l", "app=promovolve-api,tier=singleton")]["items"] = [{
            "metadata": dict(metadata(), name="promovolve-singleton-0"), "spec": singleton_spec,
            "status": {"conditions": [{"type": "Ready", "status": "True"}]},
        }]
    return data


class VerifyApiRuntimeTests(unittest.TestCase):
    def run_check(self, data, exec_failure=False, installer_outputs=None, installer_failure=False):
        def get_json(_kubectl, *args):
            return data[args]

        def exec_pod(command, **kwargs):
            if "sha256sum" in command:
                self.assertTrue(kwargs["check"])
                self.assertTrue(kwargs["capture_output"])
                self.assertTrue(kwargs["text"])
                self.assertEqual(command[command.index("-c") + 1], "install")
                self.assertEqual(command[-2:], ["/source/chromium.json", "/profiles/chromium-v1.json"])
                if installer_failure:
                    raise subprocess.CalledProcessError(1, command)
                pod_name = command[command.index("exec") + 1]
                output = "".join(f"{PROFILE_REVISION}  {path}\n" for path in command[-2:])
                return subprocess.CompletedProcess(command, 0, (installer_outputs or {}).get(pod_name, output))
            if exec_failure:
                raise subprocess.CalledProcessError(1, "kubectl exec")

        with patch.object(MODULE, "get_json", side_effect=get_json), \
                patch.object(MODULE.subprocess, "run", side_effect=exec_pod), \
                patch.object(sys, "argv", ["verify-api-runtime-k8s.py", "test-cluster"]):
            MODULE.main()

    def test_ready_pod_with_installer_and_ddata_access_passes(self):
        self.run_check(fixtures())

    def test_both_active_tiers_pass_and_singleton_runtime_failure_fails(self):
        self.run_check(fixtures(active_singleton=True))
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_check(fixtures(active_singleton=True), exec_failure=True)

    def test_missing_or_stale_profile_revision_fails(self):
        for target in ("singleton", "api", "pod"):
            for revision in (None, "0" * 64):
                with self.subTest(target=target, revision=revision):
                    data = fixtures()
                    if target == "pod":
                        metadata = data[("pods", "-l", "app=promovolve-api,tier=app")]["items"][0]["metadata"]
                    else:
                        metadata = data[("statefulset", "promovolve-" + target)]["spec"]["template"]["metadata"]
                    metadata["annotations"] = {} if revision is None else {MODULE.PROFILE_ANNOTATION: revision}
                    with self.assertRaisesRegex(RuntimeError, "seccomp profile revision"):
                        self.run_check(data)

    def test_changed_local_profile_requires_new_rollout(self):
        data = fixtures()
        with patch.object(Path, "read_bytes", return_value=b"changed profile"):
            with self.assertRaisesRegex(RuntimeError, "seccomp profile revision"):
                self.run_check(data)

    def test_source_or_installed_profile_drift_on_any_node_fails(self):
        for path in ("/source/chromium.json", "/profiles/chromium-v1.json"):
            with self.subTest(path=path):
                data = fixtures()
                status = data[("daemonset", "chromium-seccomp")]["status"]
                status.update(desiredNumberScheduled=2, numberReady=2, updatedNumberScheduled=2)
                data[("pods", "-l", "app=chromium-seccomp")]["items"].append({
                    "metadata": {"name": "installer-b"}, "spec": {"nodeName": "node-b"},
                    "status": {"conditions": [{"type": "Ready", "status": "True"}]},
                })
                output = "".join(f"{'0' * 64 if item == path else PROFILE_REVISION}  {item}\n"
                                 for item in ("/source/chromium.json", "/profiles/chromium-v1.json"))
                with self.assertRaisesRegex(RuntimeError, "installer-b: installed seccomp profile revision"):
                    self.run_check(data, installer_outputs={"installer-b": output})

    def test_missing_or_malformed_installer_hashes_fail(self):
        for output in ("", "invalid output", f"{PROFILE_REVISION}  /source/chromium.json\n"):
            with self.subTest(output=output):
                with self.assertRaisesRegex(RuntimeError, "installed seccomp profile revision"):
                    self.run_check(fixtures(), installer_outputs={"installer-a": output})

    def test_unreadable_installer_profile_fails(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_check(fixtures(), installer_failure=True)

    def test_pod_without_node_profile_fails(self):
        data = fixtures()
        data[("pods", "-l", "app=promovolve-api,tier=app")]["items"][0]["spec"]["nodeName"] = "node-b"
        with self.assertRaisesRegex(RuntimeError, "no ready seccomp installer"):
            self.run_check(data)

    def test_scaled_down_api_fails(self):
        data = fixtures()
        statefulset = data[("statefulset", "promovolve-api")]
        statefulset["spec"]["replicas"] = 0
        statefulset["status"]["readyReplicas"] = 0
        data[("pods", "-l", "app=promovolve-api,tier=app")]["items"] = []
        with self.assertRaisesRegex(RuntimeError, "no active replicas"):
            self.run_check(data)

    def test_mixed_tier_images_fail(self):
        data = fixtures()
        data[("statefulset", "promovolve-singleton")]["spec"]["template"]["spec"]["containers"][0]["image"] = (
            "registry.example/api@sha256:" + "b" * 64)
        with self.assertRaisesRegex(RuntimeError, "image references differ"):
            self.run_check(data)

    def test_unreadable_or_unwritable_ddata_fails(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_check(fixtures(), exec_failure=True)

    def test_non_pvc_ddata_fails(self):
        for volumes in ([], [{"name": "ddata", "emptyDir": {}}],
                        [{"name": "ddata", "hostPath": {"path": "/data"}}],
                        [{"name": "ddata", "persistentVolumeClaim": {"claimName": ""}}],
                        [{"name": "ddata", "persistentVolumeClaim": {"claimName": "unrelated-pvc"}}],
                        [{"name": "ddata", "persistentVolumeClaim": {
                            "claimName": "ddata-api-0", "readOnly": True}}]):
            with self.subTest(volumes=volumes):
                data = fixtures()
                data[("pods", "-l", "app=promovolve-api,tier=app")]["items"][0]["spec"]["volumes"] = volumes
                with self.assertRaisesRegex(RuntimeError, "writable PVC"):
                    self.run_check(data)

    def test_missing_ddata_claim_template_fails(self):
        for tier in ("singleton", "api"):
            with self.subTest(tier=tier):
                data = fixtures()
                data[("statefulset", "promovolve-" + tier)]["spec"]["volumeClaimTemplates"] = []
                with self.assertRaisesRegex(RuntimeError, "DData volume claim template is missing"):
                    self.run_check(data)

    def test_old_statefulset_revision_fails(self):
        data = fixtures()
        data[("statefulset", "promovolve-api")]["status"]["currentRevision"] = "v1"
        with self.assertRaisesRegex(RuntimeError, "old revision remains"):
            self.run_check(data)

    def test_sandbox_disable_setting_fails(self):
        data = fixtures()
        data[("statefulset", "promovolve-api")]["spec"]["template"]["spec"]["containers"][0]["env"][0]["value"] = "true"
        with self.assertRaisesRegex(RuntimeError, "sandbox override is missing"):
            self.run_check(data)

    def test_live_pod_sandbox_override_missing_or_disabled_fails(self):
        for env in ([], [{"name": "CHROMIUM_NO_SANDBOX", "value": "true"}],
                    [{"name": "CHROMIUM_NO_SANDBOX", "value": "false"},
                     {"name": "CHROMIUM_NO_SANDBOX", "value": "true"}]):
            with self.subTest(env=env):
                data = fixtures()
                data[("pods", "-l", "app=promovolve-api,tier=app")]["items"][0]["spec"]["containers"][0]["env"] = env
                with self.assertRaisesRegex(RuntimeError, "promovolve-api-0: Chromium sandbox override"):
                    self.run_check(data)

    def test_live_pod_missing_wrong_or_readonly_ddata_mount_fails(self):
        for mounts in ([], [{"name": "ddata", "mountPath": "/other"}],
                       [{"name": "ddata", "mountPath": "/data", "readOnly": True}],
                       [{"name": "ddata", "mountPath": "/data", "subPath": "other"}]):
            with self.subTest(mounts=mounts):
                data = fixtures()
                data[("pods", "-l", "app=promovolve-api,tier=app")]["items"][0]["spec"]["containers"][0]["volumeMounts"] = mounts
                with self.assertRaisesRegex(RuntimeError, "promovolve-api-0: DData volume is not mounted"):
                    self.run_check(data)


if __name__ == "__main__":
    unittest.main()
