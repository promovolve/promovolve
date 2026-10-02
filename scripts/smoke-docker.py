#!/usr/bin/env python3
"""Smoke-test a built image using disposable containers and no production services."""

import argparse
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
# Match the deployed database version; the development Compose tag can float.
DATABASE_IMAGE = "timescale/timescaledb:2.17.2-pg15"


def run(args, **kwargs):
    return subprocess.run(args, check=True, text=True, timeout=180, **kwargs)


def docker(*args):
    return run(["docker", *args], capture_output=True).stdout.strip()


def best_effort(args):
    try:
        result = subprocess.run(args, timeout=30, check=False)
        if result.returncode:
            print(f"Warning: command failed ({result.returncode}): {shlex.join(args)}", flush=True)
    except (subprocess.TimeoutExpired, OSError) as error:
        print(f"Warning: {shlex.join(args)}: {error}", flush=True)


def smoke(kind, image):
    suffix = uuid.uuid4().hex[:12]
    network = f"promovolve-smoke-{suffix}"
    database = f"{network}-db"
    app = f"{network}-app"
    browser = f"{network}-browser"
    containers = []
    network_created = False
    with tempfile.TemporaryDirectory(prefix="promovolve-smoke-") as directory:
        temp = Path(directory)
        try:
            metadata = json.loads(docker("image", "inspect", image))[0]
            if metadata["Architecture"] != "arm64":
                raise AssertionError("Smoke test requires the production arm64 image")
            if kind == "platform" and metadata["Config"].get("User", "").split(":")[0] in ("", "0", "root"):
                raise AssertionError("Platform image must run as non-root")

            # Pull before creating the isolated network; workloads cannot reach cloud APIs.
            present = subprocess.run(["docker", "image", "inspect", DATABASE_IMAGE],
                                     capture_output=True, timeout=15)
            if present.returncode != 0:
                docker("pull", DATABASE_IMAGE)
            docker("network", "create", "--internal", network)
            network_created = True
            containers.append(database)
            docker("create", "--name", database, "--network", network,
                   "--network-alias", "db", "-e", "POSTGRES_USER=promovolve",
                   "-e", "POSTGRES_PASSWORD=smoke-only", "-e", "POSTGRES_DB=promovolve",
                   DATABASE_IMAGE)
            docker("cp", str(ROOT / "docker/init-db.sql"), f"{database}:/docker-entrypoint-initdb.d/init.sql")
            docker("start", database)
            for _ in range(60):
                ready = subprocess.run(["docker", "exec", database, "pg_isready", "-h", "127.0.0.1",
                                        "-U", "promovolve", "-d", "promovolve"],
                                       capture_output=True, timeout=10)
                if ready.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise AssertionError("Disposable database did not become ready")

            port = "8080" if kind == "api" else "9090"
            args = ["create", "--name", app, "--network", network,
                    "--network-alias", "app"]
            if kind == "api":
                conf = temp / "smoke.conf"
                conf.write_text('include classpath("application.conf")\npekko.cluster.roles = ["api"]\n')
                environment = {
                    "JDBC_URL": "jdbc:postgresql://db:5432/promovolve", "JDBC_USER": "promovolve",
                    "JDBC_PASSWORD": "smoke-only", "CDN_BASE_URL": "http://127.0.0.1",
                    "R2_ACCOUNT_ID": "smoke", "R2_ACCESS_KEY_ID": "smoke",
                    "R2_SECRET_ACCESS_KEY": "smoke", "R2_BUCKET": "smoke",
                    "GEMINI_API_KEY": "smoke", "CHROMIUM_NO_SANDBOX": "true",
                }
            else:
                environment = {
                    "DATABASE_URL": "postgres://promovolve:smoke-only@db:5432/promovolve?sslmode=disable",
                    "CORE_API_URL": "http://127.0.0.1:1", "JWT_SECRET": "smoke-only",
                }
            for key, value in environment.items():
                args += ["-e", f"{key}={value}"]
            args += [image]
            if kind == "api":
                args += ["-Dconfig.file=/smoke.conf"]
            containers.append(app)
            docker(*args)
            if kind == "api":
                docker("cp", str(conf), f"{app}:/smoke.conf")
            docker("start", app)
            # Internal networks need no published host ports. The DB image includes wget.
            address = f"http://app:{port}"
            url = address + ("/openapi.yaml" if kind == "api" else "/health")
            for _ in range(90):
                if docker("inspect", "--format", "{{.State.Running}}", app) != "true":
                    raise AssertionError("Application exited before serving HTTP")
                response = subprocess.run(["docker", "exec", database, "wget", "-q", "-T", "2",
                                           "-O", "-", url], capture_output=True, text=True, timeout=10)
                if response.returncode == 0 and response.stdout:
                    if kind == "api" and not response.stdout.startswith("openapi:"):
                        raise AssertionError("API did not return its OpenAPI document")
                    if kind == "platform" and json.loads(response.stdout) != {"status": "ok"}:
                        raise AssertionError("Platform did not return a healthy status")
                    break
                time.sleep(1)
            else:
                raise AssertionError("Application did not serve HTTP before timeout")
            print(f"PASS {kind}: production entrypoint serves HTTP with a disposable database", flush=True)

            if kind == "platform":
                for asset in ("creative-designer.js", "tailwind.css"):
                    content = run(["docker", "exec", database, "wget", "-q", "-T", "5",
                                   "-O", "-", f"{address}/static/{asset}"], capture_output=True).stdout
                    if asset == "creative-designer.js":
                        if content != (ROOT / "platform/static" / asset).read_text():
                            raise AssertionError("Embedded Designer differs from the checked-out artifact")
                    elif "tailwindcss" not in content or "--tw-" not in content:
                        raise AssertionError("Embedded Tailwind stylesheet missing")
                print("PASS platform: embedded Designer and Tailwind assets", flush=True)
            else:
                docker("cp", f"{app}:/app/lib", str(temp / "lib"))
                run(["javac", "--release", "21", "-cp", str(temp / "lib" / "*"),
                     "-d", str(temp), str(ROOT / "scripts/docker-smoke/BrowserSmoke.java")])
                # Use the final image's jars, JRE and browser; never download a browser at runtime.
                containers.append(browser)
                docker("create", "--name", browser, "--network", "none", "--init",
                       "--entrypoint", "java", image, "-cp", "/app/lib/*:/tmp", "BrowserSmoke")
                docker("cp", str(temp / "BrowserSmoke.class"), f"{browser}:/tmp/BrowserSmoke.class")
                result = docker("start", "-a", browser)
                if docker("inspect", "--format", "{{.State.ExitCode}}", browser) != "0":
                    raise AssertionError("Packaged browser smoke test failed")
                print(result, flush=True)
                print(docker("exec", app, "ffmpeg", "-version").splitlines()[0], flush=True)

            docker("stop", "--time", "30", app)
            code = int(docker("inspect", "--format", "{{.State.ExitCode}}", app))
            if code not in (0, 143):
                raise AssertionError(f"Application failed to stop gracefully: exit {code}")
            print(f"PASS {kind}: stops without SIGKILL", flush=True)
        except BaseException as error:
            if isinstance(error, subprocess.CalledProcessError) and error.stderr:
                print(error.stderr, flush=True)
            for container in containers:
                best_effort(["docker", "logs", "--tail", "80", container])
            raise
        finally:
            for container in reversed(containers):
                best_effort(["docker", "rm", "-f", "-v", container])
            if network_created:
                best_effort(["docker", "network", "rm", network])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=["api", "platform"])
    parser.add_argument("image")
    args = parser.parse_args()
    smoke(args.kind, args.image)
