#!/usr/bin/env python3
"""Exercise deployed browser checks, including drift and failed exec paths."""

from copy import deepcopy
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("verify_browser_runtime", Path(__file__).with_name("verify-browser-runtime-k8s.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
PROFILE = b"profile fixture"
REVISION = hashlib.sha256(PROFILE).hexdigest()
CONFIG = 'pekko.cluster.roles = ["browser"]\n'
IMAGE = "registry.example/api@sha256:" + "a" * 64
OTHER_IMAGE = "registry.example/api@sha256:" + "b" * 64
READY = {"conditions": [{"type": "Ready", "status": "True"}]}


def fixtures():
    browser = {
        "securityContext": {"runAsNonRoot": True, "runAsUser": 1000, "runAsGroup": 1000,
                            "seccompProfile": {"type": "Localhost", "localhostProfile": MODULE.PROFILE}},
        "containers": [{"name": "browser", "image": IMAGE,
                        "securityContext": {"allowPrivilegeEscalation": False, "capabilities": {"drop": ["ALL"]}},
                        "args": ["-Dconfig.file=/conf/application-browser.conf"],
                        "ports": [{"name": "management", "containerPort": 8558}],
                        "readinessProbe": {"httpGet": {"path": "/ready", "port": "management"}},
                        "env": [{"name": "CHROMIUM_NO_SANDBOX", "value": "false"}] + [
                            {"name": key, "valueFrom": {"secretKeyRef": {"name": "api-secret-hash", "key": key}}}
                            for key in sorted(MODULE.R2_KEYS)],
                        "volumeMounts": [{"name": "conf", "mountPath": "/conf", "readOnly": True}]}],
        "volumes": [{"name": "conf", "configMap": {"name": "browser-conf-hash"}},
                    {"name": "shm", "emptyDir": {"medium": "Memory"}}],
    }
    labels = {"app": "promovolve-api", "tier": "browser"}
    template = {"metadata": {"annotations": {MODULE.PROFILE_ANNOTATION: REVISION}, "labels": labels}, "spec": browser}
    api = {"containers": [{"name": "api", "image": IMAGE}]}
    return {
        ("daemonset", "chromium-seccomp"): {
            "metadata": {"generation": 2},
            "spec": {"template": {"spec": {"tolerations": [{"operator": "Exists"}]}}},
            "status": {"desiredNumberScheduled": 1, "numberReady": 1,
                       "updatedNumberScheduled": 1, "observedGeneration": 2}},
        ("pods", "-l", "app=chromium-seccomp"): {"items": [
            {"metadata": {"name": "installer-a"}, "spec": {"nodeName": "node-a"}, "status": deepcopy(READY)}]},
        ("deployment", "promovolve-browser"): {
            "metadata": {"generation": 2, "uid": "deployment-uid"},
            "spec": {"replicas": 1, "template": template, "selector": {"matchLabels": deepcopy(labels)}},
            "status": {"observedGeneration": 2, "replicas": 1, "updatedReplicas": 1,
                       "readyReplicas": 1, "availableReplicas": 1}},
        ("pods", "-l", "app=promovolve-api,tier=browser"): {"items": [
            {"metadata": {"name": "browser-a", "annotations": {MODULE.PROFILE_ANNOTATION: REVISION},
                          "ownerReferences": [{"kind": "ReplicaSet", "name": "browser-rs", "uid": "rs-uid", "controller": True}]},
             "spec": dict(deepcopy(browser), nodeName="node-a"), "status": deepcopy(READY)}]},
        ("replicaset", "browser-rs"): {"metadata": {"uid": "rs-uid", "ownerReferences": [
            {"kind": "Deployment", "uid": "deployment-uid", "controller": True}]}},
        ("statefulset", "promovolve-api"): {
            "metadata": {"generation": 2}, "spec": {"replicas": 1, "template": {"spec": api}},
            "status": {"observedGeneration": 2, "readyReplicas": 1, "updatedReplicas": 1,
                       "currentRevision": "v2", "updateRevision": "v2"}},
        ("pods", "-l", "app=promovolve-api,tier=app"): {"items": [
            {"metadata": {"name": "api-0"}, "spec": deepcopy(api), "status": deepcopy(READY)}]},
    }


class VerifyBrowserRuntimeTests(unittest.TestCase):
    def check(self, data, exec_error=None, hashes=None, config=CONFIG):
        self.commands = []

        def get_json(kubectl, *args):
            self.assertEqual(kubectl, ["kubectl", "--context", "test-cluster", "-n", "test-namespace"])
            return data[args]

        def exec_pod(command, **kwargs):
            self.commands.append(command)
            self.assertEqual(command[:5], ["kubectl", "--context", "test-cluster", "-n", "test-namespace"])
            self.assertIn(command[5:], [
                ["exec", "installer-a", "-c", "install", "--", "sha256sum",
                 "/source/chromium.json", "/profiles/chromium-v1.json"],
                ["exec", "browser-a", "-c", "browser", "--", "sh", "-ec",
                 'test "$(id -u)" = 1000; test "$(id -g)" = 1000'],
                ["exec", "browser-a", "-c", "browser", "--", "cat", "/conf/application-browser.conf"],
            ])
            self.assertTrue(kwargs["check"])
            self.assertTrue(kwargs["capture_output"])
            self.assertEqual(kwargs["timeout"], 60)
            if exec_error:
                raise exec_error
            if "sha256sum" in command:
                output = "".join(f"{REVISION}  {path}\n" for path in command[-2:])
                return subprocess.CompletedProcess(command, 0, hashes if hashes is not None else output)
            return subprocess.CompletedProcess(command, 0, config if "cat" in command else "")

        with patch.object(MODULE, "get_json", side_effect=get_json), \
                patch.object(MODULE.subprocess, "run", side_effect=exec_pod), \
                patch.object(Path, "read_bytes", return_value=PROFILE), \
                patch.object(Path, "read_text", return_value=CONFIG), \
                patch.object(sys, "stdout", io.StringIO()), \
                patch.object(sys, "argv", ["verify-browser-runtime-k8s.py", "test-cluster", "--namespace", "test-namespace"]):
            MODULE.main()

    def browser_specs(self, data):
        return [data[("deployment", "promovolve-browser")]["spec"]["template"]["spec"],
                data[("pods", "-l", "app=promovolve-api,tier=browser")]["items"][0]["spec"]]

    def test_ready_browser_with_root_compatible_api_passes(self):
        self.check(fixtures())
        self.assertEqual(len(self.commands), 3)
        self.assertFalse(any("/data" in str(command) for command in self.commands))

    def test_explicit_context_is_required(self):
        with patch.object(sys, "argv", ["verify-browser-runtime-k8s.py"]), patch.object(sys, "stderr", io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                MODULE.main()
        self.assertEqual(error.exception.code, 2)

    def test_projected_discovery_token_is_allowed(self):
        data = fixtures()
        spec = self.browser_specs(data)[1]
        spec["volumes"].append({"name": "kube-api-access", "projected": {"sources": [
            {"serviceAccountToken": {"path": "token"}},
            {"configMap": {"name": "kube-root-ca.crt"}},
            {"downwardAPI": {"items": [{"path": "namespace", "fieldRef": {"fieldPath": "metadata.namespace"}}]}},
        ]}})
        spec["containers"][0]["volumeMounts"].append(
            {"name": "kube-api-access", "mountPath": "/var/run/secrets/kubernetes.io/serviceaccount", "readOnly": True})
        self.check(data)

    def test_browser_security_drift_in_template_or_live_pod_fails(self):
        mutations = [
            lambda s: s["securityContext"].update(runAsUser=0),
            lambda s: s["containers"][0].setdefault("securityContext", {}).update(runAsUser=0),
            lambda s: s["securityContext"].update(seccompProfile={"type": "RuntimeDefault"}),
            lambda s: s["containers"][0]["securityContext"].update(seccompProfile={"type": "Unconfined"}),
            lambda s: s["containers"][0]["securityContext"].update(allowPrivilegeEscalation=True),
            lambda s: s["containers"][0]["securityContext"].update(capabilities={"drop": ["ALL"], "add": ["SYS_ADMIN"]}),
            lambda s: s.update(hostPID=True),
        ]
        for index in (0, 1):
            for mutate in mutations:
                with self.subTest(index=index, mutation=mutate):
                    data = fixtures()
                    mutate(self.browser_specs(data)[index])
                    with self.assertRaises(RuntimeError):
                        self.check(data)

    def test_browser_credentials_or_storage_drift_fails(self):
        mutations = [
            lambda s: s["containers"][0].update(envFrom=[{"secretRef": {"name": "api-secret"}}]),
            lambda s: s["containers"][0]["env"].append({"name": "JDBC_PASSWORD", "value": "unwanted"}),
            lambda s: s["containers"][0]["env"][1]["valueFrom"]["secretKeyRef"].update(key="GEMINI_API_KEY"),
            lambda s: s["volumes"].append({"name": "data", "persistentVolumeClaim": {"claimName": "ddata"}}),
            lambda s: s["volumes"].append({"name": "host", "hostPath": {"path": "/"}}),
            lambda s: s["volumes"].append({"name": "nfs", "nfs": {"server": "files.example", "path": "/data"}}),
            lambda s: s["volumes"].append({"name": "csi", "csi": {"driver": "storage.example"}}),
            lambda s: s["volumes"].append({"name": "disk", "gcePersistentDisk": {"pdName": "data"}}),
            lambda s: s["volumes"].append({"name": "secrets", "projected": {"sources": [{"secret": {"name": "api-secret"}}]}}),
            lambda s: s.update(initContainers=[{"name": "unexpected"}]),
            lambda s: s["containers"].append({"name": "unexpected"}),
        ]
        for index in (0, 1):
            for mutate in mutations:
                with self.subTest(index=index, mutation=mutate):
                    data = fixtures()
                    mutate(self.browser_specs(data)[index])
                    with self.assertRaises(RuntimeError):
                        self.check(data)

    def test_plain_or_configmap_serving_credentials_are_rejected(self):
        for index in (0, 1):
            for name in ("JDBC_PASSWORD", "GEMINI_API_KEY", "INTERNAL_API_KEY",
                         "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "JWT_SECRET"):
                for source in ({"value": "unwanted"},
                               {"valueFrom": {"configMapKeyRef": {"name": "wrong-config", "key": name}}}):
                    data = fixtures()
                    self.browser_specs(data)[index]["containers"][0]["env"].append({"name": name, **source})
                    with self.subTest(index=index, name=name, source=source), \
                            self.assertRaisesRegex(RuntimeError, "serving credentials"):
                        self.check(data)

    def test_missing_disabled_or_duplicate_sandbox_override_fails(self):
        for index in (0, 1):
            for overrides in ([], [{"name": "CHROMIUM_NO_SANDBOX", "value": "true"}],
                              [{"name": "CHROMIUM_NO_SANDBOX", "value": "false"}] * 2):
                data = fixtures()
                container = self.browser_specs(data)[index]["containers"][0]
                container["env"] = [e for e in container["env"] if e["name"] != "CHROMIUM_NO_SANDBOX"] + overrides
                with self.subTest(index=index, overrides=overrides), self.assertRaisesRegex(RuntimeError, "sandbox override"):
                    self.check(data)

    def test_missing_or_stale_profile_annotations_fail(self):
        for live in (False, True):
            for revision in (None, "0" * 64):
                data = fixtures()
                metadata = (data[("pods", "-l", "app=promovolve-api,tier=browser")]["items"][0]["metadata"] if live
                            else data[("deployment", "promovolve-browser")]["spec"]["template"]["metadata"])
                metadata["annotations"] = {} if revision is None else {MODULE.PROFILE_ANNOTATION: revision}
                with self.subTest(live=live, revision=revision), self.assertRaisesRegex(RuntimeError, "seccomp profile revision"):
                    self.check(data)

    def test_installer_hash_drift_or_malformed_output_fails(self):
        for output in ("", "invalid", f"{REVISION}  /source/chromium.json\n",
                       f"{REVISION}  /source/chromium.json\n{'0' * 64}  /profiles/chromium-v1.json\n"):
            with self.subTest(output=output), self.assertRaisesRegex(RuntimeError, "installed seccomp profile"):
                self.check(fixtures(), hashes=output)

    def test_installer_coverage_and_tolerations_fail_closed(self):
        for field, value in (("numberReady", 0), ("updatedNumberScheduled", 0), ("observedGeneration", 1)):
            data = fixtures()
            data[("daemonset", "chromium-seccomp")]["status"][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, "DaemonSet is not ready"):
                self.check(data)
        data = fixtures()
        data[("daemonset", "chromium-seccomp")]["spec"]["template"]["spec"]["tolerations"] = []
        with self.assertRaisesRegex(RuntimeError, "every taint"):
            self.check(data)
        data = fixtures()
        self.browser_specs(data)[1]["nodeName"] = "node-b"
        with self.assertRaisesRegex(RuntimeError, "no ready seccomp installer"):
            self.check(data)

    def test_missing_scaled_down_or_incomplete_browser_rollout_fails(self):
        for field, value in (("observedGeneration", 1), ("replicas", 2), ("updatedReplicas", 0),
                             ("readyReplicas", 0), ("availableReplicas", 0)):
            data = fixtures()
            data[("deployment", "promovolve-browser")]["status"][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, "rollout is not complete"):
                self.check(data)
        data = fixtures()
        data[("deployment", "promovolve-browser")]["spec"]["replicas"] = 0
        with self.assertRaisesRegex(RuntimeError, "rollout is not complete"):
            self.check(data)
        for count in (0, 2):
            data = fixtures()
            data[("pods", "-l", "app=promovolve-api,tier=browser")]["items"] *= count
            with self.subTest(count=count), self.assertRaisesRegex(RuntimeError, "exactly one"):
                self.check(data)

    def test_unready_or_terminating_browser_pod_fails(self):
        for terminating in (False, True):
            data = fixtures()
            pod = data[("pods", "-l", "app=promovolve-api,tier=browser")]["items"][0]
            if terminating:
                pod["metadata"]["deletionTimestamp"] = "2026-11-01T00:00:00Z"
            else:
                pod["status"] = {}
            with self.subTest(terminating=terminating), self.assertRaisesRegex(RuntimeError, "not ready"):
                self.check(data)

    def test_unpinned_mixed_or_stale_images_fail(self):
        for index, image in ((0, "registry.example/api:dev"), (0, OTHER_IMAGE), (1, OTHER_IMAGE)):
            data = fixtures()
            self.browser_specs(data)[index]["containers"][0]["image"] = image
            with self.subTest(index=index, image=image), self.assertRaisesRegex(RuntimeError, "image"):
                self.check(data)
        data = fixtures()
        data[("pods", "-l", "app=promovolve-api,tier=app")]["items"][0]["spec"]["containers"][0]["image"] = OTHER_IMAGE
        with self.assertRaisesRegex(RuntimeError, "old image"):
            self.check(data)

    def test_incomplete_api_rollout_fails(self):
        for field, value in (("observedGeneration", 1), ("readyReplicas", 0), ("updatedReplicas", 0), ("currentRevision", "v1")):
            data = fixtures()
            data[("statefulset", "promovolve-api")]["status"][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, "serving rollout"):
                self.check(data)

    def test_wrong_or_unmounted_browser_config_fails(self):
        for index in (0, 1):
            data = fixtures()
            self.browser_specs(data)[index]["containers"][0]["args"] = ["-Dconfig.file=/conf/application-app.conf"]
            with self.subTest(index=index), self.assertRaisesRegex(RuntimeError, "role config"):
                self.check(data)
        with self.assertRaisesRegex(RuntimeError, "mounted browser role config"):
            self.check(fixtures(), config='pekko.cluster.roles = ["api"]\n')

    def test_overridden_entrypoint_or_readiness_fails(self):
        for index in (0, 1):
            for field, value in (("command", ["sleep", "infinity"]),
                                 ("readinessProbe", {"exec": {"command": ["true"]}})):
                data = fixtures()
                self.browser_specs(data)[index]["containers"][0][field] = value
                with self.subTest(index=index, field=field), self.assertRaises(RuntimeError):
                    self.check(data)

    def test_unrelated_deployment_or_pod_fails(self):
        data = fixtures()
        data[("deployment", "promovolve-browser")]["spec"]["selector"]["matchLabels"]["tier"] = "other"
        with self.assertRaisesRegex(RuntimeError, "selector or labels"):
            self.check(data)
        data = fixtures()
        data[("replicaset", "browser-rs")]["metadata"]["ownerReferences"][0]["uid"] = "other-deployment"
        with self.assertRaisesRegex(RuntimeError, "not owned by this Deployment"):
            self.check(data)

    def test_exec_failure_or_timeout_is_not_success(self):
        for error in (subprocess.CalledProcessError(1, "kubectl exec"), subprocess.TimeoutExpired("kubectl exec", 60)):
            with self.subTest(error=error), self.assertRaises(type(error)):
                self.check(fixtures(), exec_error=error)

    def test_cli_reports_kubectl_failure_and_exits_nonzero(self):
        with tempfile.TemporaryDirectory(prefix="browser-verifier-cli-") as directory:
            kubectl = Path(directory) / "kubectl"
            kubectl.write_text(f"#!{sys.executable}\nimport sys\nsys.exit(1)\n")
            kubectl.chmod(0o755)
            result = subprocess.run(
                [sys.executable, str(Path(MODULE.__file__)), "test-cluster"],
                env={**os.environ, "PATH": directory + os.pathsep + os.defpath},
                capture_output=True, text=True, timeout=10,
            )
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL browser runtime isolation:", result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertNotIn("PASS", result.stdout)


if __name__ == "__main__":
    unittest.main()
