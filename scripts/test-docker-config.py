#!/usr/bin/env python3
"""Verify secret exclusions with Docker itself and local-only Compose defaults."""

import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BUILDX = shlex.split(os.environ.get("DOCKER_BUILDX", "docker buildx"))
COMPOSE = shlex.split(os.environ.get("DOCKER_COMPOSE", "docker compose"))


def run(args, **kwargs):
    return subprocess.run(args, check=True, text=True, timeout=120, **kwargs)


def check_context(relative, required):
    with tempfile.TemporaryDirectory(prefix="docker-context-test-") as directory:
        temp = Path(directory)
        context = temp / "context"
        context.mkdir()
        shutil.copyfile(ROOT / relative / ".dockerignore", context / ".dockerignore")
        # Synthetic contexts avoid sending any developer credentials to the builder.
        secrets = [
            ".env", ".env.local", "secrets.env", "key.pem", "key.key",
            "cert.p12", "cert.pfx", "id_rsa", "id_ed25519",
            "terraform.tfstate", "terraform.tfstate.backup",
            ".terraform/state", ".ssh/config", ".kube/config",
        ]
        excluded = [prefix + name for prefix in ("", "nested/") for name in secrets]
        if relative == ".":
            excluded += ["k8s/secrets.env", "k8s/platform-secrets.env", "private/notes.txt"]
        for name in excluded:
            path = context / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("dummy fixture\n")
        for name in required:
            source = ROOT / relative / name
            if not source.is_file():
                raise AssertionError(f"{relative}: required repository input missing: {name}")
            path = context / name
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, path)
        dockerfile = temp / "Dockerfile"
        dockerfile.write_text("FROM scratch\nCOPY . /\n")
        output = temp / "output"
        run(BUILDX + ["build", "--file", str(dockerfile), "--output",
                      f"type=local,dest={output}", str(context)])
        for name in excluded:
            if (output / name).exists():
                raise AssertionError(f"{relative}: secret fixture included: {name}")
        for name in required:
            if not (output / name).is_file():
                raise AssertionError(f"{relative}: required fixture excluded: {name}")
        print(f"PASS {relative}: secrets excluded, required resources retained", flush=True)


def check_compose(relative, expected_ports):
    for address in ("127.0.0.1", "0.0.0.0"):
        env = dict(os.environ)
        env.pop("DEV_BIND_ADDRESS", None)
        if address == "0.0.0.0":
            env["DEV_BIND_ADDRESS"] = address
        result = run(COMPOSE + ["--env-file", os.devnull, "-f", str(ROOT / relative),
                                "config", "--format", "json"],
                     env=env, capture_output=True)
        services = json.loads(result.stdout)["services"]
        actual_ports = {
            service: {
                (port["target"], int(port["published"]), port.get("host_ip"))
                for port in services.get(service, {}).get("ports", [])
            }
            for service in expected_ports
        }
        expected = {
            service: {(target, published, address) for target, published in ports}
            for service, ports in expected_ports.items()
        }
        if actual_ports != expected:
            raise AssertionError(
                f"{relative}: expected ports {expected}, got {actual_ports}"
            )
    print(f"PASS {relative}: loopback default and explicit override", flush=True)


if __name__ == "__main__":
    check_context(".", ["build.sbt", "project/build.properties",
                        "modules/api/src/main/resources/application.conf",
                        "modules/core/src/main/scala/promovolve/package.scala"])
    check_context("platform", ["go.mod", "go.sum", "cmd/server/main.go",
                               "templates/layout.html", "static/passkey.js",
                               "help/advertiser.md"])
    check_compose("docker-compose.yml", {
        "app": {(9090, 9090)},
        "postgres": {(5432, 5432)},
    })
    check_compose("integrations/wordpress/docker-compose.yml", {
        "wordpress": {(80, 8088)},
    })
