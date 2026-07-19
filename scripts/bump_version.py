#!/usr/bin/env python3
"""Update app versionName/versionCode and optionally build-install the debug APK."""

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE_FILE = ROOT / "app" / "build.gradle"
VERSION_NAME_PATTERN = re.compile(r'(versionName\s+")([^"]+)(")')
VERSION_CODE_PATTERN = re.compile(r"(versionCode\s+)(\d+)")


def parse_version(value: str) -> tuple[int, ...]:
    parts = value.split(".")
    if not parts or any(not part.isdigit() for part in parts):
        raise ValueError("versionName must contain only dot-separated numbers, for example 1.0.3")
    return tuple(int(part) for part in parts)


def next_patch_version(current: str) -> str:
    parts = list(parse_version(current))
    while len(parts) < 3:
        parts.append(0)
    parts[-1] += 1
    return ".".join(map(str, parts))


def run_command(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def require_clean_worktree() -> None:
    status = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    if status.strip():
        raise RuntimeError("--push-tag requires a clean Git worktree; commit or stash existing changes first")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", nargs="?", help="target versionName, for example 1.0.3")
    parser.add_argument("--code", type=int, help="target versionCode (defaults to current + 1)")
    parser.add_argument("--install", action="store_true", help="run gradlew.bat installDebug after updating")
    parser.add_argument(
        "--push-tag",
        action="store_true",
        help="commit the version bump, create v<version>, and push the branch and tag",
    )
    parser.add_argument("--remote", default="origin", help="Git remote used by --push-tag (default: origin)")
    args = parser.parse_args()

    if args.push_tag:
        require_clean_worktree()

    content = GRADLE_FILE.read_text(encoding="utf-8")
    name_match = VERSION_NAME_PATTERN.search(content)
    code_match = VERSION_CODE_PATTERN.search(content)
    if not name_match or not code_match:
        raise RuntimeError("Could not find versionName/versionCode in app/build.gradle")

    current_name = name_match.group(2)
    current_code = int(code_match.group(2))
    target_name = args.version or next_patch_version(current_name)
    parse_version(target_name)
    target_code = args.code if args.code is not None else current_code + 1
    if target_code <= current_code:
        raise ValueError(f"versionCode must be greater than {current_code}")

    content = VERSION_NAME_PATTERN.sub(rf'\g<1>{target_name}\g<3>', content, count=1)
    content = VERSION_CODE_PATTERN.sub(rf'\g<1>{target_code}', content, count=1)
    GRADLE_FILE.write_text(content, encoding="utf-8", newline="\n")
    print(f"Updated versionName {current_name} -> {target_name}")
    print(f"Updated versionCode {current_code} -> {target_code}")

    if args.install or args.push_tag:
        install_result = subprocess.run(
            [str(ROOT / "gradlew.bat"), "installDebug", "--console=plain"],
            cwd=ROOT,
            check=False,
        )
        if install_result.returncode != 0:
            return install_result.returncode

    if args.push_tag:
        branch = subprocess.run(
            ["git", "branch", "--show-current"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        if not branch:
            raise RuntimeError("--push-tag requires a checked-out branch")
        tag_name = f"v{target_name}"
        run_command(["git", "add", "--", "app/build.gradle"])
        run_command(["git", "commit", "-m", f"chore: release {tag_name}"])
        run_command(["git", "tag", "-a", tag_name, "-m", f"Release {tag_name}"])
        run_command(["git", "push", args.remote, branch])
        run_command(["git", "push", args.remote, tag_name])
        print(f"Pushed {tag_name}; GitHub Actions will build and publish the release.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(2)
