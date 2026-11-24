"""Check whether a Dependabot patch only modifies supported dependency files."""

import json
import os
from pathlib import Path
import re

PATCH = "version-update:semver-patch"
STABLE_VERSION = re.compile(r"v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")

PATCH_FILES = {
    "npm_and_yarn": {
        f"platform/{workspace}/{filename}"
        for workspace in ("banner-bootstrap", "banner-component", "creative-designer", "dashboard-tests")
        for filename in ("package.json", "package-lock.json")
    },
    "go_modules": {"platform/go.mod", "platform/go.sum"},
    "sbt": {"build.sbt", "project/plugins.sbt"},
}


def is_patch(update):
    if update.get("updateType") != PATCH:
        return False
    old = STABLE_VERSION.fullmatch(update.get("prevVersion", ""))
    new = STABLE_VERSION.fullmatch(update.get("newVersion", ""))
    return bool(old and new and old.groups()[:2] == new.groups()[:2] and int(old[3]) < int(new[3]))


def is_eligible(updates, changed_files):
    if not isinstance(updates, list) or not updates:
        return False
    if not isinstance(changed_files, list) or not changed_files:
        return False
    if not all(isinstance(update, dict) and is_patch(update) for update in updates):
        return False
    if not all(
        isinstance(file, dict)
        and file.get("status") == "modified"
        and isinstance(file.get("filename"), str)
        for file in changed_files
    ):
        return False

    ecosystems = {update.get("packageEcosystem") for update in updates}
    if len(ecosystems) != 1:
        return False
    allowed_files = PATCH_FILES.get(ecosystems.pop(), set())
    filenames = {file["filename"] for file in changed_files}
    return filenames <= allowed_files


def main():
    try:
        updates = json.loads(os.environ["DEPENDABOT_UPDATES"])
        temp = Path(os.environ["RUNNER_TEMP"])
        files = json.loads((temp / "dependabot-files.json").read_text(encoding="utf-8"))
        eligible = is_eligible(updates, files)
    except (KeyError, OSError, TypeError, ValueError):
        print("Could not verify Dependabot metadata or changed files")
        return

    if not eligible:
        print("Update requires manual review")
        return

    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write("eligible=true\n")


if __name__ == "__main__":
    main()
