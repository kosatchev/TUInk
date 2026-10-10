#!/usr/bin/env python3
"""Publish a verified draft without making an older TUInk version Latest.

The workflow's shared publication concurrency group must cover the entire job:
the release listing and edits form one transaction relative to other publishers.
"""

import argparse
import json
import re
import subprocess


STABLE_TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")


def stable_version(tag):
    match = STABLE_TAG.fullmatch(tag)
    return tuple(map(int, match.groups())) if match else None


def publish_release(repo, tag):
    if stable_version(tag) is None:
        raise ValueError("Expected a stable TUInk tag vMAJOR.MINOR.PATCH")
    response = subprocess.run(
        ["gh", "api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"],
        check=True, text=True, capture_output=True,
    )
    pages = json.loads(response.stdout)
    if not isinstance(pages, list) or not all(isinstance(page, list) for page in pages):
        raise ValueError("Expected paginated GitHub release arrays")
    releases = [release for page in pages for release in page]
    for release in releases:
        if (not isinstance(release, dict)
                or not isinstance(release.get("tag_name"), str)
                or type(release.get("draft")) is not bool
                or type(release.get("prerelease")) is not bool):
            raise ValueError("Incomplete GitHub release metadata")
    current = [release for release in releases if release["tag_name"] == tag]
    if len(current) != 1 or not current[0]["draft"] or current[0]["prerelease"]:
        raise ValueError("Expected exactly one verified stable draft for " + tag)

    candidates = [tag] + [
        release["tag_name"] for release in releases
        if not release["draft"] and not release["prerelease"]
        and stable_version(release["tag_name"]) is not None
    ]
    latest = max(candidates, key=stable_version)
    subprocess.run(
        ["gh", "release", "edit", tag, "--repo", repo, "--draft=false",
         "--latest=" + str(latest == tag).lower()],
        check=True,
    )
    if latest != tag:
        # Also repair an earlier accidental Latest selection when backfilling.
        subprocess.run(
            ["gh", "release", "edit", latest, "--repo", repo, "--latest=true"],
            check=True,
        )
    return latest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    arguments = parser.parse_args()
    latest = publish_release(arguments.repo, arguments.tag)
    print(f"Published {arguments.tag}; Latest is {latest}")


if __name__ == "__main__":
    main()
