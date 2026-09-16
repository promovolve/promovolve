#!/usr/bin/env python3
"""Image and UID migrate in one rollout after the node profiles are ready."""

import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
IMAGE = "registry.example/api@sha256:" + "a" * 64
STUB = r'''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ["ISOLATION_CALLS"], "a") as output:
    output.write(json.dumps(args) + "\n")
if args[0] == "kustomize":
    print("fixture manifest")
elif "apply" in args:
    sys.stdin.read()
if os.environ.get("ISOLATION_FAIL") in args:
    sys.exit(1)
'''

with tempfile.TemporaryDirectory(prefix="promovolve-isolation-test-") as directory:
    temp = Path(directory)
    kubectl = temp / "kubectl"
    kubectl.write_text(STUB)
    kubectl.chmod(0o755)
    for failure in ("", "apply", "daemonset/chromium-seccomp",
                    "statefulset/promovolve-singleton", "statefulset/promovolve-api"):
        log = temp / "calls.jsonl"
        log.write_text("")
        env = dict(os.environ, PATH=f"{temp}:{os.environ['PATH']}",
                   ISOLATION_CALLS=str(log), ISOLATION_FAIL=failure)
        result = subprocess.run([str(ROOT / "k8s/roll-api.sh"), "fixture-context", IMAGE],
                                env=env, capture_output=True, text=True, timeout=15)
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        assert (result.returncode != 0) == bool(failure), result.stderr
        operations = [args[4:] for args in calls if args[0] != "kustomize"]
        for args in calls:
            if args[0] != "kustomize":
                assert args[:4] == ["--context", "fixture-context", "-n", "promovolve"]
        patches = [args for args in operations if args[0] == "patch"]
        if failure in ("apply", "daemonset/chromium-seccomp"):
            assert not patches, "Workloads changed before profile installation succeeded"
        else:
            wait = next(i for i, args in enumerate(operations) if "daemonset/chromium-seccomp" in args)
            assert all(operations.index(args) > wait for args in patches)
            assert len(patches) == (1 if failure == "statefulset/promovolve-singleton" else 2)
            for patch in patches:
                spec = json.loads(patch[patch.index("--patch") + 1])["spec"]["template"]["spec"]
                assert spec["securityContext"]["fsGroup"] == 1000
                container = spec["containers"][0]
                assert container["image"] == IMAGE
                security = container["securityContext"]
                assert security["runAsUser"] == 1000
                assert security["runAsGroup"] == 1000
                assert security["runAsNonRoot"] is True
                assert "volumes" not in spec, "Existing PVC mounts must be preserved"
                assert security["allowPrivilegeEscalation"] is False
                assert security["seccompProfile"]["type"] == "Localhost"
                assert container["env"] == [{"name": "CHROMIUM_NO_SANDBOX", "value": "false"}]
        print(f"PASS isolated image rollout: {failure or 'successful rollout'}")

    log.write_text("")
    result = subprocess.run([str(ROOT / "k8s/roll-api.sh"), "fixture-context", "registry.example/api:latest"],
                            env=env, capture_output=True, text=True, timeout=15)
    assert result.returncode == 2 and not log.read_text(), "Mutable image reached the cluster"
    print("PASS mutable image rejected before any cluster operation")
