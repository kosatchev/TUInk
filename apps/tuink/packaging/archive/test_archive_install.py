#!/usr/bin/env python3
"""Exercise archive scripts entirely in temporary paths, without real sudo."""

from pathlib import Path
import hashlib
import os
import re
import shutil
import socket
import stat
import subprocess
import tempfile
import unittest


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = next(parent for parent in SCRIPT_DIR.parents
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
        self.sudo_log = self.root / "sudo.log"
        sudo.write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "for argument in \"$@\"; do\n"
            "  if [[ $argument == /* && $argument != \"${TUINK_TEST_ROOT}/\"* ]]; then\n"
            "    echo \"Path escaped test sandbox: $argument\" >&2\n"
            "    exit 98\n"
            "  fi\n"
            "done\n"
            'printf "%s\\n" "$*" >> "${TUINK_TEST_SUDO_LOG}"\n'
            "exec \"$@\"\n",
            encoding="utf-8",
        )
        sudo.chmod(0o755)
        self.environment = {
            **os.environ,
            "PATH": f"{tools}:{os.environ['PATH']}",
            "TMPDIR": str(temporary_files),
            "TUINK_TEST_ROOT": str(self.root),
            "TUINK_TEST_SUDO_LOG": str(self.sudo_log),
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

    def installation_state(self):
        """Snapshot without following links or opening FIFOs and other special files."""
        paths = [self.install_dir, *self.install_dir.rglob("*"), self.link]
        state = {}
        for path in paths:
            if not path.exists() and not path.is_symlink():
                continue
            metadata = path.lstat()
            payload = None
            if stat.S_ISLNK(metadata.st_mode):
                payload = path.readlink()
            elif stat.S_ISREG(metadata.st_mode):
                payload = path.read_bytes()
            state[path] = (metadata.st_mode, metadata.st_mtime_ns, payload)
        return state

    def assert_refused_without_mutation(self, script):
        before = self.installation_state()
        sudo_before = self.sudo_log.read_bytes()
        self.run_script(script, success=False)
        self.assertEqual(before, self.installation_state())
        self.assertEqual(sudo_before, self.sudo_log.read_bytes(), "refusal must precede sudo calls")

    def replace_license_bundle(self, files):
        source = self.archive / "licenses"
        shutil.rmtree(source)
        source.mkdir()
        for relative, text in files.items():
            path = source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")

    def test_install_preserves_archive_and_provides_online_readme(self):
        # Exercise every supported link kind independently of README content.
        (self.archive / "README.md").write_text(
            "![Menus](docs/assets/tuink-menus.gif)\n"
            "![Tabs](docs/assets/tuink-tabs.gif)\n"
            "[ADRs](docs/adr/)\n"
            "[Native toolchain](docs/building.md#native-toolchain)\n"
            "[Benchmarks](verification/performance/README.md)\n"
            "[Applications](apps/README.md)\n"
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
            f"https://github.com/kosatchev/tuink/blob/{self.documentation_ref}/apps/README.md",
            f"https://github.com/kosatchev/tuink/blob/{self.documentation_ref}/CONTRIBUTING.md",
            f"https://github.com/kosatchev/tuink/blob/{self.documentation_ref}/SECURITY.md",
        }
        for link in expected:
            self.assertIn(f"]({link})", readme)
        self.assertNotRegex(readme, r"\]\((?:docs/|apps/|verification/|CONTRIBUTING\.md|SECURITY\.md)")
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

    def test_full_notice_bundle_upgrades_through_glibc_and_musl(self):
        self.run_script("install.sh")
        foreign = self.install_dir / "licenses/musl-1.2.6/personal.txt"
        foreign.write_text("keep my note", encoding="utf-8")
        for target in ("glibc", "musl", "glibc"):
            with self.subTest(target=target):
                source = self.archive / "licenses"
                shutil.rmtree(source)
                source.mkdir()
                selected = ["oracle-graalvm-25.0.4"]
                if target == "musl":
                    selected += ["musl-1.2.6", "zlib-1.3.2"]
                for bundle in selected:
                    shutil.copytree(PROJECT_DIR / "licenses" / bundle, source / bundle)
                expected = {
                    path.relative_to(source).as_posix(): path.read_bytes()
                    for path in source.rglob("*") if path.is_file()
                }
                self.run_script("install.sh")
                manifest = (self.install_dir / ".tuink-license-files").read_text().splitlines()
                self.assertEqual("# tuink-license-files-v1", manifest[0])
                self.assertEqual(sorted(expected), manifest[1:])
                for relative, contents in expected.items():
                    self.assertEqual(contents, (self.install_dir / "licenses" / relative).read_bytes())
                self.assertEqual("keep my note", foreign.read_text())
                if target == "glibc":
                    self.assertFalse((self.install_dir / "licenses/musl-1.2.6/COPYRIGHT").exists())
                    self.assertFalse((self.install_dir / "licenses/zlib-1.3.2").exists())
                self.assertEqual(expected, {
                    path.relative_to(source).as_posix(): path.read_bytes()
                    for path in source.rglob("*") if path.is_file()
                }, "installation must preserve the extracted archive")
        self.run_script("uninstall.sh")
        self.assertEqual([foreign], [path for path in self.install_dir.rglob("*") if path.is_file()])
        self.assertFalse(self.link.is_symlink())

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

    def test_nonregular_top_level_destinations_refuse_upgrade_and_uninstall_before_mutation(self):
        self.run_script("install.sh")
        self.binary.write_text("replacement must not be installed", encoding="utf-8")
        names = ("tuink", "install.sh", "uninstall.sh", "README.md", "LICENSE", "THIRD_PARTY_NOTICES.md")
        kinds = ("directory", "directory-link", "fifo", "fifo-link", "socket", "device-link")
        for kind in kinds:
            for name in names:
                with self.subTest(kind=kind, name=name):
                    destination = self.install_dir / name
                    backup = self.root / "managed-file-backup"
                    foreign = self.root / "foreign-target"
                    destination.rename(backup)
                    listener = None
                    try:
                        target = foreign if kind.endswith("-link") else destination
                        if kind.startswith("directory"):
                            target.mkdir()
                            (target / "keep.txt").write_text("keep me", encoding="utf-8")
                        elif kind.startswith("fifo"):
                            os.mkfifo(target)
                        elif kind == "socket":
                            listener = socket.socket(socket.AF_UNIX)
                            listener.bind(str(target))
                        elif kind == "device-link":
                            target = Path("/dev/null")
                            self.assertTrue(stat.S_ISCHR(target.stat().st_mode))
                        if kind.endswith("-link"):
                            destination.symlink_to(target)
                        for script in ("install.sh", "uninstall.sh"):
                            self.assert_refused_without_mutation(script)
                        if kind.startswith("directory"):
                            self.assertEqual("keep me", (target / "keep.txt").read_text())
                        elif kind.startswith("fifo"):
                            self.assertTrue(stat.S_ISFIFO(target.lstat().st_mode))
                    finally:
                        if listener is not None:
                            listener.close()
                        if destination.is_dir() and not destination.is_symlink():
                            shutil.rmtree(destination)
                        elif destination.exists() or destination.is_symlink():
                            destination.unlink()
                        if foreign.is_dir():
                            shutil.rmtree(foreign)
                        elif foreign.exists():
                            foreign.unlink()
                        backup.rename(destination)

    def test_missing_and_safe_symlink_top_level_files_can_be_repaired_and_uninstalled(self):
        names = ("tuink", "install.sh", "uninstall.sh", "README.md", "LICENSE", "THIRD_PARTY_NOTICES.md")
        foreign = self.root / "foreign-file"
        foreign.write_text("keep me", encoding="utf-8")
        for kind in ("missing", "regular-link", "dangling-link"):
            with self.subTest(kind=kind):
                self.run_script("install.sh")
                for operation in ("install.sh", "uninstall.sh"):
                    for name in names:
                        destination = self.install_dir / name
                        destination.unlink()
                        if kind != "missing":
                            target = foreign if kind == "regular-link" else self.root / "absent-target"
                            destination.symlink_to(target)
                    self.run_script(operation)
                    self.assertEqual("keep me", foreign.read_text())
                    self.assertFalse((self.root / "absent-target").exists())
                    if operation == "install.sh":
                        for name in names:
                            self.assertTrue((self.install_dir / name).is_file())
                            self.assertFalse((self.install_dir / name).is_symlink())
                self.assertFalse(self.install_dir.exists())
                self.assertFalse(self.link.is_symlink())

    def test_ownership_metadata_remains_regular_and_required_before_any_mutation(self):
        self.run_script("install.sh")
        self.binary.write_text("replacement must not be installed", encoding="utf-8")
        foreign = self.root / "foreign-marker"
        for name in (".tuink-archive-install", ".tuink-license-files"):
            destination = self.install_dir / name
            for kind in ("missing", "directory", "fifo", "regular-link", "dangling-link"):
                with self.subTest(kind=kind, name=name):
                    backup = self.root / "metadata-backup"
                    destination.rename(backup)
                    try:
                        if kind == "directory":
                            destination.mkdir()
                        elif kind == "fifo":
                            os.mkfifo(destination)
                        elif kind == "regular-link":
                            foreign.write_bytes(backup.read_bytes())
                            destination.symlink_to(foreign)
                        elif kind == "dangling-link":
                            destination.symlink_to(self.root / "absent-target")
                        for script in ("install.sh", "uninstall.sh"):
                            self.assert_refused_without_mutation(script)
                        if kind == "regular-link":
                            self.assertEqual(backup.read_bytes(), foreign.read_bytes())
                    finally:
                        if destination.is_dir() and not destination.is_symlink():
                            destination.rmdir()
                        elif destination.exists() or destination.is_symlink():
                            destination.unlink()
                        backup.rename(destination)

    def test_replacing_license_subtree_allows_uninstall_and_fresh_reinstall(self):
        self.replace_license_bundle({
            "old-sdk/legal/runtime/LICENSE": "old license",
            "old-sdk/missing/deep/NOTICE": "already removed license",
        })
        self.run_script("install.sh")
        old = self.install_dir / "licenses" / "old-sdk"
        (old / "missing/deep/NOTICE").unlink()
        self.replace_license_bundle({"new-sdk/legal/runtime/LICENSE": "new license"})
        self.run_script("install.sh")
        self.assertFalse(old.exists(), "upgrade must prune former managed empty parent directories")
        self.assertEqual("new license", (self.install_dir / "licenses/new-sdk/legal/runtime/LICENSE").read_text())
        self.run_script("uninstall.sh")
        self.assertFalse(self.install_dir.exists())
        self.run_script("install.sh")
        self.run_script("uninstall.sh")
        self.assertFalse(self.install_dir.exists())

    def test_replacing_license_subtree_preserves_foreign_descendants_and_siblings(self):
        self.replace_license_bundle({
            "old-sdk/legal/runtime/LICENSE": "old license",
            "old-sdk/obsolete/legal/NOTICE": "obsolete notice",
        })
        self.run_script("install.sh")
        licenses = self.install_dir / "licenses"
        foreign = licenses / "old-sdk/legal/runtime/personal/keep.txt"
        foreign.parent.mkdir()
        foreign.write_text("keep me", encoding="utf-8")
        foreign_empty = licenses / "old-sdk/legal/runtime/foreign-empty"
        foreign_empty.mkdir()
        foreign_sibling = licenses / "foreign-sibling/empty"
        foreign_sibling.mkdir(parents=True)
        self.replace_license_bundle({"new-sdk/legal/runtime/LICENSE": "new license"})
        self.run_script("install.sh")
        self.assertFalse((licenses / "old-sdk/obsolete").exists())
        self.assertFalse((licenses / "old-sdk/legal/runtime/LICENSE").exists())
        for operation in (None, "uninstall.sh"):
            if operation is not None:
                self.run_script(operation)
            self.assertEqual("keep me", foreign.read_text())
            self.assertTrue(foreign_empty.is_dir())
            self.assertTrue(foreign_sibling.is_dir())
            self.assertEqual([], list(foreign_empty.iterdir()))
            self.assertEqual([], list(foreign_sibling.iterdir()))
        self.assertFalse((licenses / "new-sdk").exists())
        self.assertFalse((self.install_dir / ".tuink-archive-install").exists())
        self.assertFalse(self.link.is_symlink())

    def test_license_file_directory_transitions_refuse_upgrade_before_mutation(self):
        for old_path, new_path in (("bundle/LICENSE", "bundle/LICENSE/NOTICE"),
                                   ("bundle/LICENSE/NOTICE", "bundle/LICENSE")):
            with self.subTest(old=old_path, new=new_path):
                self.replace_license_bundle({old_path: "installed license"})
                self.run_script("install.sh")
                self.binary.write_text("replacement must not be installed", encoding="utf-8")
                self.replace_license_bundle({new_path: "replacement license"})
                self.assert_refused_without_mutation("install.sh")
                self.run_script("uninstall.sh")
                self.assertFalse(self.install_dir.exists())

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
