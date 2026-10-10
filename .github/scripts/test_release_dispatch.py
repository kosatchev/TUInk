#!/usr/bin/env python3
"""Exercise the release workflow's actual ref guard and frozen-commit checks."""

import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")


def step_script(name):
    marker = "      - name: " + name + "\n"
    start = WORKFLOW.index(marker)
    end = WORKFLOW.find("\n      - name:", start + len(marker))
    block = WORKFLOW[start:end if end >= 0 else len(WORKFLOW)]
    return textwrap.dedent(block.split("        run: |\n", 1)[1])


GUARD_NAME = "Require a release tag from this workflow version"
GUARD = step_script(GUARD_NAME)
METADATA = step_script("Verify tag, version, commit, and release notes")


class ReleaseDispatchTest(unittest.TestCase):
    def run_guard(self, ref, workflow_ref=None, event="workflow_dispatch"):
        environment = dict(os.environ, GITHUB_REF=ref, GITHUB_EVENT_NAME=event,
                           GITHUB_WORKFLOW_REF=workflow_ref if workflow_ref is not None else
                           "owner/tuink/.github/workflows/release.yml@" + ref)
        return subprocess.run(["bash", "-c", GUARD], env=environment,
                              capture_output=True, text=True)

    def test_stable_tags_work_for_push_and_manual_dispatch(self):
        for event in ("push", "workflow_dispatch"):
            for tag in ("v0.0.0", "v0.13.1", "v0.16.0", "v1.0.0", "v10.20.300"):
                with self.subTest(event=event, tag=tag):
                    self.assertEqual(0, self.run_guard("refs/tags/" + tag, event=event).returncode)

    def test_branch_missing_and_noncanonical_refs_stop_before_checkout(self):
        for ref in ("", "refs/heads/main", "refs/heads/v0.16.0", "v0.16.0",
                    "refs/tags/v0.16", "refs/tags/v0.16.0-rc.1", "refs/tags/v00.16.0",
                    "refs/tags/tuinvoke-v0.1.0", "refs/tags/v0.16.0\n"):
            with self.subTest(ref=ref):
                result = self.run_guard(ref)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("gh workflow run release.yml --ref v0.13.1", result.stderr)

    def test_workflow_from_another_branch_or_tag_is_rejected(self):
        for workflow_ref in ("", "owner/tuink/.github/workflows/release.yml@refs/heads/main",
                             "owner/tuink/.github/workflows/release.yml@refs/tags/v0.15.0"):
            with self.subTest(workflow_ref=workflow_ref):
                self.assertNotEqual(0, self.run_guard("refs/tags/v0.16.0", workflow_ref).returncode)

    def test_tag_is_the_only_checkout_and_dispatch_source(self):
        self.assertNotIn("inputs.tag", WORKFLOW)
        self.assertNotIn("      tag:", WORKFLOW.split("permissions:", 1)[0])
        checkout = WORKFLOW.split("      - name: Check out the release tag\n", 1)[1]
        checkout = checkout.split("\n      - name:", 1)[0]
        self.assertIn("ref: ${{ github.ref }}", checkout)
        steps = WORKFLOW.split("    steps:\n", 1)[1]
        self.assertTrue(steps.startswith("      - name: " + GUARD_NAME + "\n"))

    def git(self, root, *arguments):
        return subprocess.run(["git", *arguments], cwd=root, check=True,
                              capture_output=True, text=True).stdout.strip()

    def checkout_fixture(self, root):
        root.mkdir()
        self.git(root, "init", "-q")
        self.git(root, "config", "user.name", "Release workflow test")
        self.git(root, "config", "user.email", "release@example.invalid")
        (root / "gradle.properties").write_text("version=0.13.1\n", encoding="utf-8")
        notes = root / "docs/releases/0.13.1.md"
        notes.parent.mkdir(parents=True)
        notes.write_text("Release notes\n", encoding="utf-8")
        self.git(root, "add", ".")
        self.git(root, "commit", "-qm", "Release fixture")
        self.git(root, "tag", "-a", "v0.13.1", "-m", "Release fixture")
        return self.git(root, "rev-parse", "HEAD")

    def run_metadata(self, root, event, event_sha, output_root):
        environment = dict(os.environ, RELEASE_TAG="v0.13.1", GITHUB_EVENT_NAME=event,
                           GITHUB_SHA=event_sha, GITHUB_OUTPUT=str(output_root / "output"),
                           GITHUB_ENV=str(output_root / "environment"))
        return subprocess.run(["bash", "-c", METADATA], cwd=root, env=environment,
                              capture_output=True, text=True)

    def test_metadata_matches_the_frozen_event_commit_for_both_events(self):
        for event in ("push", "workflow_dispatch"):
            with self.subTest(event=event), tempfile.TemporaryDirectory() as temporary:
                temporary = Path(temporary)
                root = temporary / "checkout"
                commit = self.checkout_fixture(root)
                result = self.run_metadata(root, event, commit, temporary)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("commit=" + commit, (temporary / "output").read_text())

    def test_annotated_tag_event_object_is_peeled_to_its_commit(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary = Path(temporary)
            root = temporary / "checkout"
            self.checkout_fixture(root)
            tag_object = self.git(root, "rev-parse", "refs/tags/v0.13.1")
            result = self.run_metadata(root, "push", tag_object, temporary)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_retargeted_tag_cannot_replace_the_event_commit_for_either_event(self):
        for event in ("push", "workflow_dispatch"):
            with self.subTest(event=event), tempfile.TemporaryDirectory() as temporary:
                temporary = Path(temporary)
                root = temporary / "checkout"
                event_commit = self.checkout_fixture(root)
                (root / "different-source.txt").write_text("A later commit\n", encoding="utf-8")
                self.git(root, "add", ".")
                self.git(root, "commit", "-qm", "Different source fixture")
                self.git(root, "tag", "-fa", "v0.13.1", "-m", "Retargeted fixture")
                result = self.run_metadata(root, event, event_commit, temporary)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((temporary / "output").exists())
                self.assertFalse((temporary / "environment").exists())


if __name__ == "__main__":
    unittest.main()
