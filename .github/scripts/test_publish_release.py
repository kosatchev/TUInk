#!/usr/bin/env python3
"""Exercise publication ordering with a fake gh process; never contact GitHub."""

import copy
import json
import subprocess
import unittest
from unittest.mock import patch

from publish_release import publish_release


def release(tag, *, draft=False, prerelease=False):
    return {"tag_name": tag, "draft": draft, "prerelease": prerelease}


class FakeGitHub:
    def __init__(self, pages, latest=None):
        self.pages = copy.deepcopy(pages)
        self.latest = latest
        self.calls = []

    def run(self, command, **options):
        self.calls.append(command)
        if command == ["gh", "api", "--paginate", "--slurp",
                       "repos/owner/tuink/releases?per_page=100"]:
            assert options == {"check": True, "text": True, "capture_output": True}
            return subprocess.CompletedProcess(command, 0, json.dumps(self.pages))
        assert command[:3] == ["gh", "release", "edit"], command
        assert command[4:6] == ["--repo", "owner/tuink"], command
        assert options == {"check": True}
        tag = command[3]
        selected = next(item for page in self.pages for item in page
                        if item["tag_name"] == tag)
        if "--draft=false" in command:
            selected["draft"] = False
        if "--latest=true" in command:
            assert not selected["draft"] and not selected["prerelease"]
            self.latest = tag
        return subprocess.CompletedProcess(command, 0)


class PublishReleaseTest(unittest.TestCase):
    def publish(self, github, tag):
        with patch("publish_release.subprocess.run", side_effect=github.run):
            return publish_release("owner/tuink", tag)

    def test_first_stable_release_is_published_as_latest(self):
        github = FakeGitHub([[release("v0.13.1", draft=True)]])
        self.assertEqual(self.publish(github, "v0.13.1"), "v0.13.1")
        self.assertEqual(github.latest, "v0.13.1")
        self.assertEqual(github.calls[-1][-2:], ["--draft=false", "--latest=true"])

    def test_backfill_preserves_highest_release_and_repairs_wrong_latest(self):
        github = FakeGitHub([[release("v0.14.0", draft=True), release("v0.16.0")]],
                            latest="v0.13.1")
        self.assertEqual(self.publish(github, "v0.14.0"), "v0.16.0")
        self.assertEqual(github.latest, "v0.16.0")
        self.assertEqual(github.calls[1][-2:], ["--draft=false", "--latest=false"])
        self.assertFalse(github.pages[0][0]["draft"])

    def test_numeric_ordering_across_pages_ignores_dates_and_unrelated_tags(self):
        github = FakeGitHub([
            [release("v0.9.9"), release("tuinvoke-v999.0.0")],
            [release("v0.10.0"), release("v0.9.10", draft=True)],
        ])
        self.assertEqual(self.publish(github, "v0.9.10"), "v0.10.0")
        self.assertEqual(github.latest, "v0.10.0")

    def test_newest_numeric_version_wins(self):
        for previous, current in [("v0.9.9", "v0.10.0"), ("v0.16.0", "v1.0.0"),
                                  ("v1.9.99", "v1.10.0"), ("v0.16.9", "v0.16.10")]:
            with self.subTest(previous=previous, current=current):
                github = FakeGitHub([[release(previous), release(current, draft=True)]])
                self.assertEqual(self.publish(github, current), current)
                self.assertEqual(github.latest, current)

    def test_future_drafts_and_prereleases_do_not_supersede_stable_release(self):
        github = FakeGitHub([[release("v1.0.0", draft=True),
                              release("v2.0.0", prerelease=True),
                              release("v3.0.0-rc.1", prerelease=True),
                              release("v0.16.0", draft=True)]])
        self.assertEqual(self.publish(github, "v0.16.0"), "v0.16.0")

    def test_both_serialized_build_completion_orders_preserve_all_releases(self):
        for order in [("v0.14.0", "v0.16.0"), ("v0.16.0", "v0.14.0")]:
            with self.subTest(order=order):
                github = FakeGitHub([[release("v0.13.1")]
                                    + [release(tag, draft=True) for tag in order]], "v0.13.1")
                for tag in order:
                    self.publish(github, tag)
                self.assertEqual(github.latest, "v0.16.0")
                self.assertTrue(all(not item["draft"] for item in github.pages[0]))

    def test_batch_backfill_preserves_every_version_in_any_completion_order(self):
        versions = [f"v0.{minor}.{patch}" for minor in range(13, 17) for patch in range(3)]
        for order in [versions, list(reversed(versions)), versions[::2] + versions[1::2]]:
            with self.subTest(order=order):
                github = FakeGitHub([[release(tag, draft=True) for tag in versions]])
                for tag in order:
                    self.publish(github, tag)
                self.assertEqual(github.latest, "v0.16.2")
                self.assertTrue(all(not item["draft"] for item in github.pages[0]))

    def test_invalid_or_missing_current_draft_never_edits_releases(self):
        bad_states = [[], [[release("v0.16.0")]],
                      [[release("v0.16.0", draft=True, prerelease=True)]],
                      [[release("v0.16.0", draft=True), release("v0.16.0", draft=True)]],
                      [[{"tag_name": "v0.16.0", "draft": True}]],
                      {"message": "API unavailable"}]
        for pages in bad_states:
            with self.subTest(pages=pages):
                github = FakeGitHub(pages)
                with self.assertRaises(ValueError):
                    self.publish(github, "v0.16.0")
                self.assertEqual(len(github.calls), 1)

    def test_invalid_tags_are_rejected_before_contacting_github(self):
        for tag in ["tuinvoke-v0.1.0", "v0.16", "v0.16.0-rc.1", "v00.16.0", "--latest"]:
            with self.subTest(tag=tag):
                github = FakeGitHub([])
                with self.assertRaises(ValueError):
                    self.publish(github, tag)
                self.assertEqual(github.calls, [])

    def test_api_failure_does_not_publish(self):
        with patch("publish_release.subprocess.run", side_effect=subprocess.CalledProcessError(1, "gh")) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                publish_release("owner/tuink", "v0.16.0")
            self.assertEqual(run.call_count, 1)

    def test_publish_failure_does_not_change_another_release(self):
        github = FakeGitHub([[release("v0.14.0", draft=True), release("v0.16.0")]])
        with patch("publish_release.subprocess.run", side_effect=[
            github.run(["gh", "api", "--paginate", "--slurp", "repos/owner/tuink/releases?per_page=100"],
                       check=True, text=True, capture_output=True),
            subprocess.CalledProcessError(1, "gh"),
        ]) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                publish_release("owner/tuink", "v0.14.0")
            self.assertEqual(run.call_count, 2)


if __name__ == "__main__":
    unittest.main()
