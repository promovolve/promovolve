#!/usr/bin/env python3
"""Verify that a ready rollout passes and broken node or volume checks fail."""

import importlib.util
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
SECURITY = {
    "runAsNonRoot": True,
    "runAsUser": 1000,
    "runAsGroup": 1000,
    "allowPrivilegeEscalation": False,
    "capabilities": {"drop": ["ALL"]},
    "seccompProfile": {"type": "Localhost", "localhostProfile": MODULE.PROFILE},
}


def fixtures():
    container = {
        "name": "api", "image": IMAGE, "securityContext": SECURITY,
        "env": [{"name": "CHROMIUM_NO_SANDBOX", "value": "false"}],
        "volumeMounts": [{"name": "ddata", "mountPath": "/data"}],
    }
    pod_spec = {
        "securityContext": {"fsGroup": 1000, "fsGroupChangePolicy": "Always"},
        "nodeName": "node-a", "containers": [container],
        "volumes": [{"name": "ddata", "persistentVolumeClaim": {"claimName": "ddata-api-0"}}],
    }
    return {
        ("daemonset", "chromium-seccomp"): {
            "metadata": {"generation": 2},
            "status": {"desiredNumberScheduled": 1, "numberReady": 1,
                       "updatedNumberScheduled": 1, "observedGeneration": 2},
        },
        ("pods", "-l", "app=chromium-seccomp"): {
            "items": [{"spec": {"nodeName": "node-a"},
                       "status": {"conditions": [{"type": "Ready", "status": "True"}]}}],
        },
        ("statefulset", "promovolve-singleton"): {
            "metadata": {"generation": 2},
            "status": {"readyReplicas": 0, "observedGeneration": 2,
                       "currentRevision": "v2", "updateRevision": "v2"},
            "spec": {"replicas": 0, "template": {"spec": {
                "securityContext": {"fsGroup": 1000, "fsGroupChangePolicy": "Always"},
                "containers": [dict(container, name="singleton")],
            }}},
        },
        ("statefulset", "promovolve-api"): {
            "metadata": {"generation": 2},
            "status": {"readyReplicas": 1, "observedGeneration": 2,
                       "currentRevision": "v2", "updateRevision": "v2"},
            "spec": {"replicas": 1, "template": {"spec": pod_spec}},
        },
        ("pods", "-l", "app=promovolve-api,tier=singleton"): {"items": []},
        ("pods", "-l", "app=promovolve-api,tier=app"): {
            "items": [{"metadata": {"name": "promovolve-api-0"}, "spec": pod_spec,
                       "status": {"conditions": [{"type": "Ready", "status": "True"}]}}],
        },
    }


class VerifyApiRuntimeTests(unittest.TestCase):
    def run_check(self, data, exec_failure=False):
        def get_json(_kubectl, *args):
            return data[args]

        def exec_pod(*_args, **_kwargs):
            if exec_failure:
                raise subprocess.CalledProcessError(1, "kubectl exec")

        with patch.object(MODULE, "get_json", side_effect=get_json), \
                patch.object(MODULE.subprocess, "run", side_effect=exec_pod), \
                patch.object(sys, "argv", ["verify-api-runtime-k8s.py", "test-cluster"]):
            MODULE.main()

    def test_ready_pod_with_installer_and_ddata_access_passes(self):
        self.run_check(fixtures())

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
                        [{"name": "ddata", "persistentVolumeClaim": {
                            "claimName": "ddata-api-0", "readOnly": True}}]):
            with self.subTest(volumes=volumes):
                data = fixtures()
                data[("pods", "-l", "app=promovolve-api,tier=app")]["items"][0]["spec"]["volumes"] = volumes
                with self.assertRaisesRegex(RuntimeError, "writable PVC"):
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


if __name__ == "__main__":
    unittest.main()
