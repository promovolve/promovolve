#!/usr/bin/env python3
"""Check deployed browser isolation without changing cluster state."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


PROFILE = "promovolve/chromium-v1.json"
PROFILE_PATH = Path(__file__).resolve().parents[1] / "docker/chromium-seccomp.json"
CONFIG_PATH = Path(__file__).resolve().parents[1] / "k8s/application-browser.conf"
PROFILE_ANNOTATION = "promovolve.dev/seccomp-sha256"
IMAGE_DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")
R2_KEYS = {"R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET"}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def ready(pod):
    return not pod["metadata"].get("deletionTimestamp") and any(
        condition.get("type") == "Ready" and condition.get("status") == "True"
        for condition in pod.get("status", {}).get("conditions", [])
    )


def get_json(kubectl, *args):
    return json.loads(subprocess.check_output([*kubectl, "get", *args, "-o", "json"], text=True, timeout=60))


def exec_pod(kubectl, pod, container, *command):
    return subprocess.run([*kubectl, "exec", pod, "-c", container, "--", *command],
                          check=True, capture_output=True, text=True, timeout=60).stdout


def check_browser_spec(spec, name):
    require(not spec.get("initContainers") and len(spec.get("containers", [])) == 1,
            f"{name}: unexpected additional containers")
    require(not spec.get("ephemeralContainers"), f"{name}: ephemeral containers are present")
    security = spec.get("securityContext", {})
    container = spec["containers"][0]
    require(container.get("name") == "browser", f"{name}: browser container is missing")
    effective = {**security, **container.get("securityContext", {})}
    require(effective.get("runAsNonRoot") is True and effective.get("runAsUser") == 1000 and
            effective.get("runAsGroup") == 1000, f"{name}: non-root identity is missing")
    require(effective.get("seccompProfile") == {"type": "Localhost", "localhostProfile": PROFILE},
            f"{name}: Chromium seccomp profile is missing")
    require(effective.get("allowPrivilegeEscalation") is False and
            not effective.get("privileged", False) and
            "ALL" in effective.get("capabilities", {}).get("drop", []) and
            not effective.get("capabilities", {}).get("add"),
            f"{name}: privilege restrictions are missing")
    require(not any(spec.get(field, False) for field in ("hostPID", "hostIPC", "hostNetwork")),
            f"{name}: host namespaces are enabled")
    require(not container.get("envFrom"), f"{name}: bulk environment injection is present")
    env = container.get("env", [])
    overrides = [e for e in env if e.get("name") == "CHROMIUM_NO_SANDBOX"]
    require(len(overrides) == 1 and overrides[0].get("value") == "false",
            f"{name}: Chromium sandbox override is missing, disabled or duplicated")
    require(not any(e.get("name", "").startswith(
                ("JDBC_", "GEMINI_", "INTERNAL_API_", "OPENAI_", "ANTHROPIC_", "JWT_")) for e in env),
            f"{name}: serving credentials are present")
    refs = [e for e in env if "secretKeyRef" in e.get("valueFrom", {})]
    require(len(refs) == 4 and {e["name"] for e in refs} == R2_KEYS and
            all(e["valueFrom"]["secretKeyRef"].get("key") == e["name"] for e in refs) and
            len({e["valueFrom"]["secretKeyRef"].get("name") for e in refs}) == 1,
            f"{name}: expected only four R2 Secret references")
    for volume in spec.get("volumes", []):
        sources = set(volume) - {"name"}
        require(len(sources) == 1 and sources <= {"configMap", "emptyDir", "projected"} and
                not any("secret" in source for source in volume.get("projected", {}).get("sources", [])),
                f"{name}: persistent, host or Secret volume is present")
    require(not container.get("command") and
            container.get("args") == ["-Dconfig.file=/conf/application-browser.conf"],
            f"{name}: browser role config is not selected")
    probe = container.get("readinessProbe", {}).get("httpGet", {})
    require(probe.get("path") == "/ready" and probe.get("port") == "management" and
            probe.get("scheme", "HTTP") == "HTTP" and not probe.get("host") and
            any(p.get("name") == "management" and p.get("containerPort") == 8558
                for p in container.get("ports", [])), f"{name}: browser readiness probe is missing")
    mounts = container.get("volumeMounts", [])
    config_mounts = [m for m in mounts if m.get("mountPath") == "/conf"]
    require(len(config_mounts) == 1 and config_mounts[0].get("readOnly") is True and
            not config_mounts[0].get("subPath") and not config_mounts[0].get("subPathExpr") and
            any(v.get("name") == config_mounts[0]["name"] and "configMap" in v
                for v in spec.get("volumes", [])), f"{name}: browser role config is not mounted read-only")
    require(IMAGE_DIGEST.search(container.get("image", "")), f"{name}: image is not pinned by digest")
    return container["image"]


def check_installers(kubectl, revision):
    daemonset = get_json(kubectl, "daemonset", "chromium-seccomp")
    status = daemonset.get("status", {})
    desired = status.get("desiredNumberScheduled", 0)
    require(desired > 0 and status.get("numberReady", 0) == desired and
            status.get("updatedNumberScheduled", 0) == desired and
            status.get("observedGeneration", 0) >= daemonset["metadata"]["generation"],
            "Chromium seccomp DaemonSet is not ready on all scheduled nodes")
    tolerations = daemonset["spec"]["template"]["spec"].get("tolerations", [])
    require(any(t.get("operator") == "Exists" and not t.get("key") and not t.get("effect")
                and "tolerationSeconds" not in t for t in tolerations),
            "Chromium seccomp installer does not tolerate every taint")
    installers = get_json(kubectl, "pods", "-l", "app=chromium-seccomp")["items"]
    nodes = set()
    for pod in installers:
        if not ready(pod):
            continue
        node = pod["spec"]["nodeName"]
        require(node not in nodes, f"{node}: duplicate ready seccomp installers")
        nodes.add(node)
        paths = ("/source/chromium.json", "/profiles/chromium-v1.json")
        output = exec_pod(kubectl, pod["metadata"]["name"], "install", "sha256sum", *paths)
        require(output.splitlines() == [f"{revision}  {path}" for path in paths],
                f"{pod['metadata']['name']}: installed seccomp profile revision differs or is missing")
    require(len(nodes) == desired, "Ready seccomp installers do not cover scheduled nodes")
    return nodes


def check_api(kubectl):
    workload = get_json(kubectl, "statefulset", "promovolve-api")
    replicas = workload["spec"].get("replicas", 1)
    status = workload.get("status", {})
    require(replicas > 0 and status.get("readyReplicas", 0) == replicas and
            status.get("updatedReplicas", 0) == replicas and
            status.get("observedGeneration", 0) >= workload["metadata"]["generation"] and
            status.get("currentRevision") and status.get("currentRevision") == status.get("updateRevision"),
            "promovolve-api: serving rollout is not complete")
    container = next(c for c in workload["spec"]["template"]["spec"]["containers"] if c["name"] == "api")
    require(IMAGE_DIGEST.search(container["image"]), "promovolve-api: image is not pinned by digest")
    pods = get_json(kubectl, "pods", "-l", "app=promovolve-api,tier=app")["items"]
    require(len(pods) == replicas, "promovolve-api: unexpected serving pod count")
    for pod in pods:
        live = next(c for c in pod["spec"]["containers"] if c["name"] == "api")
        require(ready(pod) and live["image"] == container["image"],
                f"{pod['metadata']['name']}: serving pod is not ready or uses an old image")
    return container["image"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("context", help="Explicit kubectl context to inspect")
    parser.add_argument("--namespace", default="promovolve")
    args = parser.parse_args()
    kubectl = ["kubectl", "--context", args.context, "-n", args.namespace]
    revision = hashlib.sha256(PROFILE_PATH.read_bytes()).hexdigest()
    installer_nodes = check_installers(kubectl, revision)
    api_image = check_api(kubectl)
    deployment = get_json(kubectl, "deployment", "promovolve-browser")
    status = deployment.get("status", {})
    require(deployment["spec"].get("replicas", 1) == 1 and
            status.get("observedGeneration", 0) >= deployment["metadata"]["generation"] and
            all(status.get(field, 0) == 1 for field in
                ("replicas", "updatedReplicas", "readyReplicas", "availableReplicas")),
            "promovolve-browser: single-replica rollout is not complete")
    template = deployment["spec"]["template"]
    labels = {"app": "promovolve-api", "tier": "browser"}
    require(deployment["spec"].get("selector") == {"matchLabels": labels} and
            all(template.get("metadata", {}).get("labels", {}).get(key) == value
                for key, value in labels.items()), "promovolve-browser: unexpected Deployment selector or labels")
    require(template.get("metadata", {}).get("annotations", {}).get(PROFILE_ANNOTATION) == revision,
            "promovolve-browser: seccomp profile revision differs or is missing")
    image = check_browser_spec(template["spec"], "promovolve-browser")
    require(image == api_image, "Browser and API image references differ")
    pods = get_json(kubectl, "pods", "-l", "app=promovolve-api,tier=browser")["items"]
    require(len(pods) == 1, "promovolve-browser: expected exactly one browser pod")
    for pod in pods:
        name = pod["metadata"]["name"]
        require(ready(pod), f"{name}: browser pod is not ready")
        owner = next((o for o in pod["metadata"].get("ownerReferences", []) if o.get("controller")), {})
        require(owner.get("kind") == "ReplicaSet", f"{name}: browser pod is not owned by a ReplicaSet")
        replica_set = get_json(kubectl, "replicaset", owner["name"])
        require(replica_set["metadata"].get("uid") == owner.get("uid") and
                any(o.get("controller") and o.get("kind") == "Deployment" and
                    o.get("uid") == deployment["metadata"]["uid"]
                    for o in replica_set["metadata"].get("ownerReferences", [])),
                f"{name}: browser pod is not owned by this Deployment")
        require(pod["metadata"].get("annotations", {}).get(PROFILE_ANNOTATION) == revision,
                f"{name}: seccomp profile revision differs or is missing")
        require(pod["spec"]["nodeName"] in installer_nodes, f"{name}: no ready seccomp installer on its node")
        require(check_browser_spec(pod["spec"], name) == image, f"{name}: browser image differs from Deployment")
        exec_pod(kubectl, name, "browser", "sh", "-ec", 'test "$(id -u)" = 1000; test "$(id -g)" = 1000')
        require(exec_pod(kubectl, name, "browser", "cat", "/conf/application-browser.conf") == CONFIG_PATH.read_text(),
                f"{name}: mounted browser role config differs from this checkout")
        print(f"PASS {name}: ready, isolated, current profile and browser role config")
    print("PASS browser workload checks; renderer sandbox, LP/banner dispatch and R2 writes still require live smoke tests")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, StopIteration, ValueError, RuntimeError, OSError,
            subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f"FAIL browser runtime isolation: {error}", file=sys.stderr)
        sys.exit(1)
