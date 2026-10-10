#!/usr/bin/env python3
"""Exercise archive scripts entirely in temporary paths, without real sudo."""

from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess
import tempfile
import unittest


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = next(parent for parent in Path(__file__).resolve().parents
                       if (parent / 'settings.gradle').is_file() and (parent / 'gradlew').is_file())


class ArchiveInstallTest(unittest.TestCase):
    def setUp(self):
        properties = (PROJECT_DIR / "gradle.properties").read_text(encoding="utf-8")
        self.release_version = re.search(r"(?m)^version=([0-9]+[.][0-9]+[.][0-9]+)$", properties)[1]
        self.documentation_ref = "0123456789abcdef0123456789abcdef01234567"
        self.temporary = tempfile.TemporaryDirectory(prefix="tuink-archive-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.archive = self.root / "archive"
        self.install_dir = self.root / "opt" / "tuink"
        self.link = self.root / "bin" / "tuink"
        self.archive.mkdir()
        self.link.parent.mkdir()
        tools = self.root / "tools"
        tools.mkdir()
        temporary_files = self.root / "tmp"
        temporary_files.mkdir()
        sudo = tools / "sudo"
        sudo.write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "for argument in \"$@\"; do\n"
            "  if [[ $argument == /* && $argument != \"${TUINK_TEST_ROOT}/\"* ]]; then\n"
            "    echo \"Path escaped test sandbox: $argument\" >&2\n"
            "    exit 98\n"
            "  fi\n"
            "done\n"
            "exec \"$@\"\n",
            encoding="utf-8",
        )
        sudo.chmod(0o755)
        self.environment = {
            **os.environ,
            "PATH": f"{tools}:{os.environ['PATH']}",
            "TMPDIR": str(temporary_files),
            "TUINK_TEST_ROOT": str(self.root),
        }
        for name in ("install.sh", "uninstall.sh"):
            source = (SCRIPT_DIR / name).read_text(encoding="utf-8")
            source = source.replace("@TUINK_DOCUMENTATION_REF@", self.documentation_ref)
            source = source.replace(
                'INSTALL_DIR="/opt/tuink"', f'INSTALL_DIR="{self.install_dir}"'
            )
            source = source.replace(
                'LINK_PATH="/usr/local/bin/${LINK_NAME}"', f'LINK_PATH="{self.link}"'
            ).replace('LINK_PATH="/usr/local/bin/tuink"', f'LINK_PATH="{self.link}"')
            self.assertNotIn('INSTALL_DIR="/opt/tuink"', source)
            self.assertNotIn('LINK_PATH="/usr/local/bin/', source)
            (self.archive / name).write_text(source, encoding="utf-8")
        self.binary = self.archive / "tuink"
        self.binary.write_text("#!/bin/sh\necho original\n", encoding="utf-8")
        self.binary.chmod(0o755)
        for name in ("README.md", "LICENSE", "THIRD_PARTY_NOTICES.md"):
            shutil.copyfile(PROJECT_DIR / name, self.archive / name)
        shutil.copytree(PROJECT_DIR / "licenses", self.archive / "licenses")

    def run_script(self, name, success=True):
        result = subprocess.run(
            ["bash", str(self.archive / name)],
            env=self.environment,
            cwd=self.root,
            capture_output=True,
            text=True,
            timeout=10,
        )
        if success:
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        else:
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("Refusing", result.stderr)
        return result

    def test_install_preserves_archive_and_provides_online_readme(self):
        # Exercise every supported link kind independently of README content.
        (self.archive / "README.md").write_text(
            "![Menus](docs/assets/tuink-menus.gif)\n"
            "![Tabs](docs/assets/tuink-tabs.gif)\n"
            "[ADRs](docs/adr/)\n"
            "[Native toolchain](docs/building.md#native-toolchain)\n"
            "[Benchmarks](verification/performance/README.md)\n"
            "[Contributing](CONTRIBUTING.md)\n"
            "[Security](SECURITY.md)\n"
            "[License](LICENSE)\n"
            "[Notices](THIRD_PARTY_NOTICES.md)\n",
            encoding="utf-8",
        )
        original = (self.archive / "README.md").read_bytes()
        self.run_script("install.sh")
        self.assertEqual(original, (self.archive / "README.md").read_bytes())
        readme = (self.install_dir / "README.md").read_text(encoding="utf-8")
        expected = {
            f"https://raw.githubusercontent.com/kosatchev/tuink/{self.documentation_ref}/docs/assets/tuink-menus.gif",
            f"https://raw.githubusercontent.com/kosatchev/tuink/{self.documentation_ref}/docs/assets/tuink-tabs.gif",
            f"https://github.com/kosatchev/tuink/tree/{self.documentation_ref}/docs/adr/",
            f"https://github.com/kosatchev/tuink/blob/{self.documentation_ref}/docs/building.md#native-toolchain",
            f"https://github.com/kosatchev/tuink/blob/{self.documentation_ref}/verification/performance/README.md",
            f"https://github.com/kosatchev/tuink/blob/{self.documentation_ref}/CONTRIBUTING.md",
            f"https://github.com/kosatchev/tuink/blob/{self.documentation_ref}/SECURITY.md",
        }
        for link in expected:
            self.assertIn(f"]({link})", readme)
        self.assertNotRegex(readme, r"\]\((?:docs/|verification/performance/|CONTRIBUTING\.md|SECURITY\.md)")
        self.assertIn("](LICENSE)", readme)
        self.assertIn("](THIRD_PARTY_NOTICES.md)", readme)
        self.assertEqual(self.install_dir / "tuink", self.link.readlink())
        self.assertEqual(0o644, (self.install_dir / "README.md").stat().st_mode & 0o777)
        self.assertEqual([], list((self.root / "tmp").iterdir()))

    def test_release_document_link_does_not_depend_on_main(self):
        # Model a release-only document which does not exist on the default branch.
        remote = self.root / "remote"
        released = remote / self.documentation_ref / "docs/releases/unreleased.md"
        released.parent.mkdir(parents=True)
        released.write_text("# Unreleased\n", encoding="utf-8")
        (remote / "main").mkdir()
        # A development build can also be newer than its own version tag.
        (remote / f"v{self.release_version}").mkdir()
        (self.archive / "README.md").write_text(
            "[Unreleased changes](docs/releases/unreleased.md#changes)\n",
            encoding="utf-8",
        )
        self.run_script("install.sh")
        expected = (
            "[Unreleased changes](https://github.com/kosatchev/tuink/blob/"
            f"{self.documentation_ref}/docs/releases/unreleased.md#changes)\n"
        )
        self.assertEqual(expected, (self.install_dir / "README.md").read_text())
        self.assertTrue(released.is_file())
        self.assertFalse((remote / "main" / "docs/releases/unreleased.md").exists())
        self.assertFalse((remote / f"v{self.release_version}" / "docs/releases/unreleased.md").exists())

    def test_invalid_or_unexpanded_documentation_version_refuses_before_install(self):
        script = self.archive / "install.sh"
        original = script.read_text(encoding="utf-8")
        installed_ref = f"DOCUMENTATION_REF='{self.documentation_ref}'"
        self.assertIn(installed_ref, original)
        for invalid in (
            "@TUINK_DOCUMENTATION_REF@", "main", "v1.2", "v1.2.3/../../main",
            "v1.2.3#fragment", "v1.2.3&main", "v1.2.3\nmain",
            "v$(touch bad-ref)", "0123456", "A" * 40,
        ):
            with self.subTest(ref=invalid):
                script.write_text(
                    original.replace(installed_ref, f"DOCUMENTATION_REF='{invalid}'"),
                    encoding="utf-8",
                )
                result = self.run_script("install.sh", success=False)
                self.assertIn("invalid or unset archive documentation reference", result.stderr)
                self.assertFalse(self.install_dir.exists())
                self.assertFalse(self.link.is_symlink())
                self.assertFalse((self.root / "bad-ref").exists())
                self.assertEqual([], list((self.root / "tmp").iterdir()))

    def test_install_does_not_execute_binary_to_determine_documentation_version(self):
        executed = self.root / "binary-executed"
        self.binary.write_text(f"#!/bin/sh\ntouch '{executed}'\nexit 99\n")
        self.run_script("install.sh")
        self.assertFalse(executed.exists())
        readme = (self.install_dir / "README.md").read_text(encoding="utf-8")
        self.assertIn(f"/{self.documentation_ref}/docs/", readme)

    def test_source_archive_release_tag_reference_is_supported(self):
        script = self.archive / "install.sh"
        script.write_text(script.read_text().replace(
            f"DOCUMENTATION_REF='{self.documentation_ref}'",
            f"DOCUMENTATION_REF='v{self.release_version}'",
        ))
        self.run_script("install.sh")
        readme = (self.install_dir / "README.md").read_text(encoding="utf-8")
        self.assertIn(f"/v{self.release_version}/docs/", readme)

    def test_replace_marked_install_and_uninstall_twice(self):
        self.run_script("install.sh")
        self.binary.write_text("#!/bin/sh\necho replacement\n", encoding="utf-8")
        self.run_script("install.sh")
        self.assertEqual(self.binary.read_bytes(), (self.install_dir / "tuink").read_bytes())
        self.run_script("uninstall.sh")
        self.assertFalse(self.install_dir.exists())
        self.assertFalse(self.link.is_symlink())
        self.run_script("uninstall.sh")

    def test_markerless_install_is_preserved(self):
        self.install_dir.mkdir(parents=True)
        foreign = self.install_dir / "notes.txt"
        foreign.write_text("keep me", encoding="utf-8")
        self.run_script("install.sh", success=False)
        self.run_script("uninstall.sh", success=False)
        self.assertEqual("keep me", foreign.read_text(encoding="utf-8"))
        self.assertEqual([foreign], list(self.install_dir.iterdir()))

    def test_unknown_marker_is_preserved(self):
        self.install_dir.mkdir(parents=True)
        marker = self.install_dir / ".tuink-archive-install"
        marker.write_text("unknown-format\n", encoding="utf-8")
        self.run_script("install.sh", success=False)
        self.run_script("uninstall.sh", success=False)
        self.assertEqual("unknown-format\n", marker.read_text(encoding="utf-8"))

    def test_foreign_launcher_is_preserved(self):
        self.link.write_text("foreign executable", encoding="utf-8")
        self.run_script("install.sh", success=False)
        self.assertEqual("foreign executable", self.link.read_text(encoding="utf-8"))
        self.assertFalse(self.install_dir.exists())

    def test_symlinked_install_dir_is_rejected_without_touching_target(self):
        outside = self.root / "foreign"
        outside.mkdir()
        self.install_dir.parent.mkdir()
        self.install_dir.symlink_to(outside, target_is_directory=True)
        self.run_script("install.sh", success=False)
        self.run_script("uninstall.sh", success=False)
        self.assertEqual([], list(outside.iterdir()))

    def test_foreign_launcher_symlink_is_preserved(self):
        outside = self.root / "foreign"
        outside.write_text("keep me", encoding="utf-8")
        self.link.symlink_to(outside)
        self.run_script("install.sh", success=False)
        self.assertEqual(outside, self.link.readlink())
        self.assertEqual("keep me", outside.read_text(encoding="utf-8"))

    def test_uninstall_preserves_unexpected_entries(self):
        self.run_script("install.sh")
        foreign = self.install_dir / "notes.txt"
        foreign.write_text("keep me", encoding="utf-8")
        self.run_script("uninstall.sh", success=False)
        self.assertEqual("keep me", foreign.read_text(encoding="utf-8"))
        self.assertTrue(self.link.is_symlink())
        self.assertTrue((self.install_dir / "tuink").is_file())

    def test_installed_readme_symlink_does_not_write_through(self):
        self.run_script("install.sh")
        outside = self.root / "foreign"
        outside.write_text("keep me", encoding="utf-8")
        readme = self.install_dir / "README.md"
        readme.unlink()
        readme.symlink_to(outside)
        self.run_script("install.sh")
        self.assertFalse(readme.is_symlink())
        self.assertEqual("keep me", outside.read_text(encoding="utf-8"))
        self.run_script("uninstall.sh")
        self.assertEqual("keep me", outside.read_text(encoding="utf-8"))

    def test_managed_binary_or_script_symlink_to_foreign_directory_is_rejected(self):
        self.run_script("install.sh")
        original_binary = (self.install_dir / "tuink").read_bytes()
        for name in ("tuink", "install.sh", "uninstall.sh"):
            with self.subTest(name=name):
                destination = self.install_dir / name
                original = destination.read_bytes()
                outside = self.root / f"foreign-{name}"
                outside.mkdir()
                sentinel = outside / "keep.txt"
                sentinel.write_text("keep me")
                destination.unlink()
                destination.symlink_to(outside, target_is_directory=True)
                self.run_script("install.sh", success=False)
                self.assertEqual([sentinel], list(outside.iterdir()))
                self.assertEqual("keep me", sentinel.read_text())
                self.assertEqual(outside, destination.readlink())
                destination.unlink()
                destination.write_bytes(original)
        self.assertEqual(original_binary, (self.install_dir / "tuink").read_bytes())

    def test_license_bundle_is_installed_exactly_and_removed(self):
        source = self.archive / "licenses"
        self.run_script("install.sh")
        marker, *manifest = (self.install_dir / ".tuink-license-files").read_text().splitlines()
        self.assertEqual("# tuink-license-files-v1", marker)
        expected = {path.relative_to(source).as_posix() for path in source.rglob("*") if path.is_file()}
        self.assertEqual(expected, set(manifest))
        for relative in manifest:
            installed = self.install_dir / "licenses" / relative
            self.assertEqual((source / relative).read_bytes(), installed.read_bytes())
            self.assertEqual(0o644, installed.stat().st_mode & 0o777)
        self.run_script("uninstall.sh")
        self.assertFalse(self.install_dir.exists())

    def test_foreign_license_files_survive_replacement_and_uninstall(self):
        self.run_script("install.sh")
        foreign = self.install_dir / "licenses" / "oracle-graalvm-25.0.4" / "personal-notes.txt"
        foreign.write_text("keep me", encoding="utf-8")
        self.run_script("install.sh")
        self.run_script("uninstall.sh")
        self.assertEqual("keep me", foreign.read_text())
        self.assertFalse(self.link.is_symlink())
        self.assertFalse((self.install_dir / "tuink").exists())
        self.assertFalse((self.install_dir / ".tuink-archive-install").exists())
        self.assertFalse((self.install_dir / ".tuink-license-files").exists())
        self.assertEqual([foreign], list((self.install_dir / "licenses").rglob("*.txt")))

    def test_archive_license_symlink_escape_is_rejected_before_install(self):
        outside = self.root / "foreign"
        outside.write_text("keep me")
        source = self.archive / "licenses" / "oracle-graalvm-25.0.4" / "LICENSE.txt"
        source.unlink()
        source.symlink_to(outside)
        self.run_script("install.sh", success=False)
        self.assertFalse(self.install_dir.exists())
        self.assertEqual("keep me", outside.read_text())

    def test_foreign_empty_license_directories_survive_uninstall(self):
        self.run_script("install.sh")
        foreign = self.install_dir / "licenses" / "oracle-graalvm-25.0.4" / "personal" / "empty"
        foreign.mkdir(parents=True)
        self.run_script("uninstall.sh")
        self.assertTrue(foreign.is_dir())
        self.assertEqual([], list(foreign.iterdir()))
        self.assertFalse(self.link.is_symlink())
        self.assertFalse((self.install_dir / "tuink").exists())

    def test_installed_license_directory_escape_is_rejected_without_mutation(self):
        self.run_script("install.sh")
        outside = self.root / "foreign"
        outside.mkdir()
        target = self.install_dir / "licenses" / "oracle-graalvm-25.0.4" / "legal" / "java.base"
        shutil.rmtree(target)
        target.symlink_to(outside, target_is_directory=True)
        original_binary = (self.install_dir / "tuink").read_bytes()
        self.binary.write_text("replacement")
        self.run_script("install.sh", success=False)
        self.run_script("uninstall.sh", success=False)
        self.assertEqual([], list(outside.iterdir()))
        self.assertEqual(original_binary, (self.install_dir / "tuink").read_bytes())
        self.assertTrue(self.link.is_symlink())

    def test_license_manifest_traversal_is_rejected_before_uninstall(self):
        self.run_script("install.sh")
        outside = self.install_dir / "foreign.txt"
        outside.write_text("keep me")
        manifest = self.install_dir / ".tuink-license-files"
        manifest.write_text("# tuink-license-files-v1\n../foreign.txt\n")
        self.run_script("uninstall.sh", success=False)
        self.assertEqual("keep me", outside.read_text())
        self.assertTrue(self.link.is_symlink())
        self.assertTrue((self.install_dir / "tuink").exists())

    def test_unknown_license_manifest_is_preserved(self):
        self.run_script("install.sh")
        manifest = self.install_dir / ".tuink-license-files"
        manifest.write_text("unknown-format\n")
        self.run_script("install.sh", success=False)
        self.run_script("uninstall.sh", success=False)
        self.assertEqual("unknown-format\n", manifest.read_text())
        self.assertTrue(self.link.is_symlink())
        self.assertTrue((self.install_dir / "tuink").exists())

    def test_checked_in_notice_hashes(self):
        for bundle_name in ("oracle-graalvm-25.0.4", "musl-1.2.6", "zlib-1.3.2"):
            bundle = PROJECT_DIR / "licenses" / bundle_name
            for line in (bundle / "SHA256SUMS").read_text().splitlines():
                expected, relative = line.split("  ", 1)
                self.assertEqual(expected, hashlib.sha256((bundle / relative).read_bytes()).hexdigest(), relative)


if __name__ == "__main__":
    unittest.main()
