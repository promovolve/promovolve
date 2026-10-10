#!/usr/bin/env python3
"""Check the deployed API isolation contract without changing cluster state."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


PROFILE = "promovolve/chromium-v1.json"
PROFILE_PATH = Path(__file__).resolve().parents[1] / "docker/chromium-seccomp.json"
PROFILE_ANNOTATION = "promovolve.io/seccomp-sha256"
IMAGE_DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def ready(pod):
    return any(
        condition.get("type") == "Ready" and condition.get("status") == "True"
        for condition in pod.get("status", {}).get("conditions", [])
    )


def get_json(kubectl, *args):
    return json.loads(subprocess.check_output([*kubectl, "get", *args, "-o", "json"], text=True))


def check_container_runtime(container, name):
    overrides = [entry for entry in container.get("env", [])
                 if entry.get("name") == "CHROMIUM_NO_SANDBOX"]
    require(len(overrides) == 1 and overrides[0].get("value") == "false",
            f"{name}: Chromium sandbox override is missing, disabled or duplicated")
    require(any(mount["name"] == "ddata" and mount["mountPath"] == "/data" and
                not mount.get("readOnly", False) and
                not mount.get("subPath") and not mount.get("subPathExpr")
                for mount in container.get("volumeMounts", [])),
            f"{name}: DData volume is not mounted read/write at /data")


def check_tier(kubectl, tier, installer_nodes, profile_revision):
    name = f"promovolve-{tier}"
    statefulset = get_json(kubectl, "statefulset", name)
    spec = statefulset["spec"]
    require(any(claim.get("metadata", {}).get("name") == "ddata"
                for claim in spec.get("volumeClaimTemplates", [])),
            f"{name}: DData volume claim template is missing")
    require(spec["template"].get("metadata", {}).get("annotations", {}).get(PROFILE_ANNOTATION)
            == profile_revision, f"{name}: seccomp profile revision differs or is missing")
    replicas = spec.get("replicas", 1)
    require(tier != "api" or replicas > 0, f"{name}: API has no active replicas")
    require(statefulset.get("status", {}).get("readyReplicas", 0) == replicas,
            f"{name}: StatefulSet is not fully ready")
    require(statefulset.get("status", {}).get("observedGeneration", 0) >=
            statefulset["metadata"]["generation"], f"{name}: rollout is not observed")
    if replicas:
        require(statefulset["status"].get("currentRevision") ==
                statefulset["status"].get("updateRevision"), f"{name}: old revision remains")

    template = spec["template"]["spec"]
    require(template.get("securityContext", {}).get("fsGroup") == 1000 and
            template["securityContext"].get("fsGroupChangePolicy") == "Always",
            f"{name}: DData group migration settings are missing")
    container = next(c for c in template["containers"] if c["name"] == tier)
    security = container.get("securityContext", {})
    require(IMAGE_DIGEST.search(container["image"]), f"{name}: image is not pinned by digest")
    require(security.get("runAsNonRoot") is True and
            security.get("runAsUser") == 1000 and security.get("runAsGroup") == 1000,
            f"{name}: non-root identity is missing")
    require(security.get("seccompProfile") ==
            {"type": "Localhost", "localhostProfile": PROFILE},
            f"{name}: Chromium seccomp profile is missing")
    require(security.get("allowPrivilegeEscalation") is False and
            "ALL" in security.get("capabilities", {}).get("drop", []),
            f"{name}: privilege restrictions are missing")
    check_container_runtime(container, name)

    selector = "app=promovolve-api,tier=" + ("app" if tier == "api" else "singleton")
    pods = get_json(kubectl, "pods", "-l", selector)["items"]
    require(len(pods) == replicas, f"{name}: expected {replicas} pods, found {len(pods)}")
    for pod in pods:
        pod_name = pod["metadata"]["name"]
        require(pod["metadata"].get("annotations", {}).get(PROFILE_ANNOTATION) == profile_revision,
                f"{pod_name}: seccomp profile revision differs or is missing")
        require(ready(pod), f"{pod_name}: pod is not ready")
        require(any(volume.get("name") == "ddata" and
                    volume.get("persistentVolumeClaim", {}).get("claimName") == f"ddata-{pod_name}" and
                    not volume["persistentVolumeClaim"].get("readOnly", False)
                    for volume in pod["spec"].get("volumes", [])),
                f"{pod_name}: DData volume is not backed by a writable PVC")
        require(pod["spec"].get("securityContext", {}).get("fsGroup") == 1000,
                f"{pod_name}: pod fsGroup is not 1000")
        require(pod["spec"]["nodeName"] in installer_nodes,
                f"{pod_name}: no ready seccomp installer on its node")
        pod_container = next(c for c in pod["spec"]["containers"] if c["name"] == tier)
        require(pod_container["image"] == container["image"] and
                pod_container.get("securityContext") == security,
                f"{pod_name}: image or security settings differ from StatefulSet")
        check_container_runtime(pod_container, pod_name)
        subprocess.run([*kubectl, "exec", pod_name, "-c", tier, "--", "sh", "-ec",
                        'test "$(id -u)" = 1000; test "$(id -g)" = 1000; '
                        "test -r /data/ddata; test -w /data/ddata"], check=True)
        print(f"PASS {pod_name}: ready, isolated, DData accessible")
    return container["image"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("context", help="Explicit kubectl context to inspect")
    parser.add_argument("--namespace", default="promovolve")
    args = parser.parse_args()
    kubectl = ["kubectl", "--context", args.context, "-n", args.namespace]

    daemonset = get_json(kubectl, "daemonset", "chromium-seccomp")
    status = daemonset.get("status", {})
    require(status.get("desiredNumberScheduled", 0) > 0 and
            status.get("numberReady", 0) == status["desiredNumberScheduled"] and
            status.get("updatedNumberScheduled", 0) == status["desiredNumberScheduled"] and
            status.get("observedGeneration", 0) >= daemonset["metadata"]["generation"],
            "Chromium seccomp DaemonSet is not ready on all scheduled nodes")
    installers = get_json(kubectl, "pods", "-l", "app=chromium-seccomp")["items"]
    installer_nodes = {pod["spec"]["nodeName"] for pod in installers if ready(pod)}
    require(len(installer_nodes) == status["desiredNumberScheduled"],
            "Ready seccomp installers do not cover scheduled nodes")
    profile_revision = hashlib.sha256(PROFILE_PATH.read_bytes()).hexdigest()
    for pod in installers:
        if not ready(pod):
            continue
        pod_name = pod["metadata"]["name"]
        paths = ("/source/chromium.json", "/profiles/chromium-v1.json")
        result = subprocess.run(
            [*kubectl, "exec", pod_name, "-c", "install", "--", "sha256sum", *paths],
            check=True, capture_output=True, text=True)
        require(result.stdout.splitlines() == [f"{profile_revision}  {path}" for path in paths],
                f"{pod_name}: installed seccomp profile revision differs or is missing")
    images = [check_tier(kubectl, tier, installer_nodes, profile_revision)
              for tier in ("singleton", "api")]
    require(images[0] == images[1], "Singleton and API image references differ")
    print("PASS API runtime isolation checks")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, StopIteration, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"FAIL API runtime isolation: {error}", file=sys.stderr)
        sys.exit(1)
