"""Exercise benchmark shutdown against real PTY children without a native SDK."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import render_benchmark as benchmark


FAKE_EDITOR = r'''import json
import os
from pathlib import Path
import signal
import sys
import tty

mode = MODE
if mode == "ignore-term":
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
elif mode == "term-zero":
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
tty.setraw(0)
home = next(arg.split("=", 1)[1] for arg in sys.argv if arg.startswith("-Duser.home="))
profile = next(arg.split("=", 1)[1] for arg in sys.argv if arg.startswith("--profile-file="))
Path(sys.argv[0] + ".state").write_text(json.dumps({"pid": os.getpid(), "home": home,
                                                  "config_home": os.environ.get("XDG_CONFIG_HOME")}))
Path(profile).write_text("frame=30 build=1.00ms raster=1.00ms diff=1.00ms search=0.00ms total=3.00ms\n")
while True:
    if b"\x11" in os.read(0, 1024):
        if mode == "normal":
            sys.exit(0)
        if mode == "crash":
            sys.exit(7)
        if mode == "signal":
            os.kill(os.getpid(), signal.SIGKILL)
'''

BENCHMARK_WORKER = r'''
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, sys.argv[1])
import render_benchmark as benchmark

root = Path(sys.argv[2])
benchmark.EXIT_TIMEOUT = 0.5
benchmark.TERMINATE_TIMEOUT = 0.1
benchmark.KILL_TIMEOUT = 0.5
fixture = root / "fixture.java"
settings = root / "settings.properties"
benchmark.create_fixture(fixture, 100)
benchmark.create_settings_fixture(settings)
try:
    elapsed = benchmark.run_profile(
        root / "editor", fixture, None, settings, root / "profile.log",
        "down", 30, 42, 140, 0.1, 0.001, 0.02, 1.0,
    )
    result = {"accepted": True, "elapsed": elapsed}
except Exception as error:
    result = {"accepted": False, "error_type": type(error).__name__, "error": str(error)}
state = json.loads((root / "editor.state").read_text())
result["home_removed"] = not Path(state["home"]).exists()
result["profile_isolated"] = state["config_home"] == str(Path(state["home"]) / ".config")
try:
    os.waitpid(state["pid"], os.WNOHANG)
    result["reaped"] = False
except ChildProcessError:
    result["reaped"] = True
print(json.dumps(result))
'''


class RenderBenchmarkLifecycleTest(unittest.TestCase):
    def test_only_normal_exit_zero_is_accepted(self):
        benchmark.require_normal_exit(0)
        for status in (7 << 8, signal.SIGTERM, signal.SIGKILL):
            with self.subTest(status=status), self.assertRaises(RuntimeError):
                benchmark.require_normal_exit(status)

    def run_editor(self, mode):
        with tempfile.TemporaryDirectory(prefix="tuink-render-lifecycle-") as temporary:
            root = Path(temporary)
            editor = root / "editor"
            editor.write_text(f"#!{sys.executable}\n" + FAKE_EDITOR.replace("MODE", repr(mode)))
            editor.chmod(0o755)
            process = subprocess.Popen(
                [sys.executable, "-c", BENCHMARK_WORKER, str(Path(__file__).parent), str(root)],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                env=dict(os.environ, XDG_CONFIG_HOME=str(root / "foreign-config")),
            )
            child_reaped = False
            try:
                stdout, stderr = process.communicate(timeout=5)
                self.assertEqual(0, process.returncode, stderr)
                result = json.loads(stdout)
                child_reaped = result["reaped"]
                self.assertTrue(result["reaped"], result)
                self.assertTrue(result["home_removed"], result)
                self.assertTrue(result["profile_isolated"], result)
                return result
            except subprocess.TimeoutExpired:
                self.fail(f"benchmark teardown hung for child mode {mode}")
            finally:
                state_file = root / "editor.state"
                if not child_reaped and state_file.exists():
                    child_pid = json.loads(state_file.read_text())["pid"]
                    try:
                        os.kill(child_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                if process.poll() is None:
                    process.kill()
                process.communicate(timeout=2)

    def test_normal_quit_with_complete_profile_passes(self):
        result = self.run_editor("normal")
        self.assertTrue(result["accepted"], result)

    def test_nonzero_quit_with_complete_profile_fails(self):
        result = self.run_editor("crash")
        self.assertFalse(result["accepted"], result)
        self.assertEqual("RuntimeError", result["error_type"])
        self.assertIn("exited with code 7", result["error"])

    def test_signal_exit_with_complete_profile_fails(self):
        result = self.run_editor("signal")
        self.assertFalse(result["accepted"], result)
        self.assertEqual("RuntimeError", result["error_type"])
        self.assertIn("terminated by signal", result["error"])

    def test_forced_termination_with_exit_zero_still_fails(self):
        result = self.run_editor("term-zero")
        self.assertFalse(result["accepted"], result)
        self.assertEqual("TimeoutError", result["error_type"])
        self.assertIn("did not exit after Ctrl+Q", result["error"])

    def test_child_ignoring_quit_and_sigterm_is_killed_and_reaped(self):
        result = self.run_editor("ignore-term")
        self.assertFalse(result["accepted"], result)
        self.assertEqual("TimeoutError", result["error_type"])
        self.assertIn("did not exit after Ctrl+Q", result["error"])


class RenderBenchmarkProvenanceTest(unittest.TestCase):
    def git(self, root, *arguments):
        return subprocess.run(
            ["git", *arguments], cwd=root, check=True, capture_output=True,
        )

    def init_checkout(self, root):
        self.git(root, "init", "-q")
        self.git(root, "config", "user.name", "Benchmark Test")
        self.git(root, "config", "user.email", "benchmark@example.invalid")
        (root / "source.java").write_text("original source\n")
        (root / ".gitignore").write_text("build/\n")
        self.git(root, "add", ".")
        self.git(root, "commit", "-qm", "fixture")

    def write_receipt(self, binary, source):
        release = 'JAVA_VERSION="25.0.4"\nIMPLEMENTOR="Oracle Corporation"\n'
        receipt = {
            "schema": 1, "kind": "tuink-native-build",
            "built_at_utc": "2026-01-02T03:04:05Z",
            "binary_sha256": benchmark.sha256(binary),
            "inputs": {"source": source, "build_args": ["-O3", "-march=compatibility"],
                       "jvm_args": [], "exclude_config_args": [], "system_properties": {},
                       "environment_overrides_sha256": hashlib.sha256(b"{}").hexdigest(),
                       "main_class": "dev.tuink.app.App", "image_name": "tuink",
                       "compiler_environment": {"NATIVE_IMAGE_OPTIONS": None, "JAVA_TOOL_OPTIONS": None,
                                                "JDK_JAVA_OPTIONS": None},
                       "flags": {name: False for name in ("debug", "fallback", "quick_build", "shared_library",
                                                          "use_fat_jar", "pgo_instrument")},
                       "classpath": {"app.jar": "d" * 64}, "input_files": {"app.jar": "d" * 64},
                       "sdk": {"home": "/sdk", "release": release,
                               "release_sha256": hashlib.sha256(release.encode()).hexdigest(),
                               "native_image_sha256": "b" * 64}},
        }
        path = Path(str(binary) + ".build.json")
        path.write_text(json.dumps(receipt))
        return path

    def test_source_digest_distinguishes_edits_in_an_already_dirty_tree_and_untracked_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.init_checkout(root)
            clean = benchmark.source_state(root)
            self.assertFalse(clean["dirty"])
            (root / "source.java").write_text("first dirty source\n")
            first = benchmark.source_state(root)
            (root / "source.java").write_text("second dirty source\n")
            second = benchmark.source_state(root)
            (root / "new.java").write_text("new source\n")
            untracked = benchmark.source_state(root)
            (root / "build").mkdir()
            (root / "build" / "output").write_text("ignored output\n")
            self.assertEqual(untracked, benchmark.source_state(root))
            self.assertEqual(clean["commit"], untracked["commit"])
            self.assertTrue(first["dirty"] and second["dirty"] and untracked["dirty"])
            self.assertEqual(4, len({state["tree_sha256"] for state in (clean, first, second, untracked)}))

    def test_linked_worktree_uses_its_own_source_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            root = parent / "checkout"
            root.mkdir()
            self.init_checkout(root)
            worktree = parent / "linked worktree"
            self.git(root, "worktree", "add", "-q", "--detach", str(worktree), "HEAD")
            self.assertTrue((worktree / ".git").is_file())
            clean = benchmark.source_state(root)
            self.assertEqual(clean, benchmark.source_state(worktree))
            (worktree / "source.java").write_text("worktree edit\n")
            dirty = benchmark.source_state(worktree)
            self.assertTrue(dirty["dirty"])
            self.assertEqual(clean["commit"], dirty["commit"])
            self.assertNotEqual(clean["tree_sha256"], dirty["tree_sha256"])
            self.assertEqual(clean, benchmark.source_state(root))

    def test_checkout_root_can_be_accessed_through_a_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            root = parent / "checkout"
            root.mkdir()
            self.init_checkout(root)
            alias = parent / "checkout alias"
            alias.symlink_to(root, target_is_directory=True)
            self.assertEqual(benchmark.source_state(root), benchmark.source_state(alias))
            self.assertIsNotNone(benchmark.source_state(alias)["commit"])

    def test_source_export_without_git_has_unknown_origin(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "source.java").write_text("exported source\n")
            unknown = {"commit": None, "dirty": None, "tree_sha256": None}
            self.assertEqual(unknown, benchmark.source_state(root))
            (root / "source.java").write_text("changed exported source\n")
            self.assertEqual(unknown, benchmark.source_state(root))

    def test_nested_source_exports_do_not_borrow_parent_checkout_provenance(self):
        for ignored in (False, True):
            with self.subTest(ignored=ignored), tempfile.TemporaryDirectory() as temporary:
                parent = Path(temporary)
                self.init_checkout(parent)
                root = parent / "build" / "export" if ignored else parent / "export"
                root.mkdir(parents=True)
                (root / "source.java").write_text("exported source\n")
                self.assertFalse((root / ".git").exists())
                unknown = {"commit": None, "dirty": None, "tree_sha256": None}
                self.assertEqual(unknown, benchmark.source_state(root))
                (root / "source.java").write_text("changed exported source\n")
                self.assertEqual(unknown, benchmark.source_state(root))
                binary = root / "tuink"
                binary.write_bytes(b"compiled exported source")
                self.write_receipt(binary, benchmark.source_state(root))
                provenance = benchmark.build_provenance(
                    binary, benchmark.sha256(binary), benchmark.source_state(root)
                )
                self.assertEqual("hash-matched", provenance["status"])
                self.assertEqual(unknown, provenance["build"]["inputs"]["source"])
                self.assertIsNone(provenance["source_matches_run_checkout"])

    def test_hash_matched_old_binary_keeps_its_build_source_after_dirty_and_untracked_edits(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.init_checkout(root)
            built_from = benchmark.source_state(root)
            binary = root / "build" / "tuink"
            binary.parent.mkdir()
            binary.write_bytes(b"compiled original")
            self.write_receipt(binary, built_from)
            matching = benchmark.build_provenance(binary, benchmark.sha256(binary), built_from)
            self.assertEqual("hash-matched", matching["status"])
            self.assertTrue(matching["source_matches_run_checkout"])
            (root / "source.java").write_text("dirty source\n")
            (root / "new.java").write_text("untracked source\n")
            result = benchmark.build_provenance(binary, benchmark.sha256(binary), benchmark.source_state(root))
            self.assertEqual("hash-matched", result["status"])
            self.assertFalse(result["source_matches_run_checkout"])
            self.assertEqual(built_from, result["build"]["inputs"]["source"])

    def test_missing_stale_and_malformed_receipts_never_borrow_the_checkout_commit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "tuink"
            binary.write_bytes(b"binary one")
            checkout = {"commit": "e" * 40, "dirty": False, "tree_sha256": "c" * 64}
            missing = benchmark.build_provenance(binary, benchmark.sha256(binary), checkout)
            self.assertEqual("unknown", missing["status"])
            self.assertNotIn("build", missing)
            receipt = self.write_receipt(binary, checkout)
            binary.write_bytes(b"another binary")
            stale = benchmark.build_provenance(binary, benchmark.sha256(binary), checkout)
            self.assertEqual("unknown", stale["status"])
            self.assertIn("does not match", stale["reason"])
            self.assertNotIn("build", stale)
            receipt.write_text("{}")
            self.assertEqual("unknown", benchmark.build_provenance(binary, benchmark.sha256(binary), checkout)["status"])

    def test_incomplete_or_invalid_compiler_receipts_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            binary = Path(temporary) / "tuink"
            binary.write_bytes(b"compiled")
            source = {"commit": "e" * 40, "dirty": False, "tree_sha256": "c" * 64}
            path = self.write_receipt(binary, source)
            complete = json.loads(path.read_text())
            for field in ("classpath", "input_files", "jvm_args", "flags", "main_class", "sdk"):
                receipt = json.loads(json.dumps(complete))
                del receipt["inputs"][field]
                path.write_text(json.dumps(receipt))
                with self.subTest(missing=field):
                    result = benchmark.build_provenance(binary, benchmark.sha256(binary), source)
                    self.assertEqual("unknown", result["status"])
                    self.assertNotIn("build", result)
            complete["inputs"]["classpath"]["app.jar"] = "not a hash"
            path.write_text(json.dumps(complete))
            self.assertEqual("unknown", benchmark.build_provenance(binary, benchmark.sha256(binary), source)["status"])

    def test_all_effective_cli_timings_and_shutdown_limits_are_retained(self):
        args = argparse.Namespace(rows=23, cols=90, frames=60, repeats=2, fixture_lines=234,
                                  startup_delay=0.25, event_delay=0.003,
                                  quiet_period=0.17, completion_timeout=4.2)
        with mock.patch.dict(os.environ, {"COLORTERM": "truecolor", "LANG": "C.UTF-8", "LC_ALL": "C", "TZ": "UTC"}):
            params = benchmark.run_parameters(args, ["down"])
        self.assertEqual({"COLORTERM": "truecolor", "LANG": "C.UTF-8", "LC_ALL": "C", "TZ": "UTC"},
                         params["inherited_environment"])
        for name, expected in {"startup_delay_s": 0.25, "event_delay_s": 0.003,
                               "quiet_period_s": 0.17, "completion_timeout_s": 4.2,
                               "exit_timeout_s": benchmark.EXIT_TIMEOUT,
                               "terminate_timeout_s": benchmark.TERMINATE_TIMEOUT,
                               "kill_timeout_s": benchmark.KILL_TIMEOUT}.items():
            self.assertEqual(expected, params[name], name)
        self.assertEqual(["down"], params["scenarios"])
        self.assertEqual(234, params["fixture_lines"])

    def test_cli_summary_reports_unknown_binary_origin_separately_from_its_checkout(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "tuink"
            binary.write_bytes(b"fake executable")
            binary.chmod(0o755)

            def fake_profile(**kwargs):
                kwargs["profile_path"].write_text(
                    "frame=30 build=1.00ms raster=1.00ms diff=1.00ms search=0.00ms total=3.00ms\n"
                )
                return 0.2

            argv = ["render_benchmark.py", "--binary", str(binary), "--output-root", str(root / "runs"),
                    "--frames", "30", "--repeats", "1", "--scenarios", "down",
                    "--startup-delay", "0.25", "--event-delay", "0.003",
                    "--quiet-period", "0.17", "--completion-timeout", "4.2"]
            checkout = {"commit": "harness-checkout", "dirty": True, "tree_sha256": "c" * 64}
            with mock.patch.object(sys, "argv", argv), mock.patch.object(benchmark, "run_profile", fake_profile), \
                mock.patch.object(benchmark, "source_state", return_value=checkout), \
                mock.patch("builtins.print"):
                benchmark.main()
            summary = json.loads(next((root / "runs").glob("*/summary.json")).read_text())
            metadata = summary["metadata"]
            self.assertEqual(2, summary["schema"])
            self.assertNotIn("commit", metadata)
            self.assertEqual(checkout, metadata["run_checkout"])
            self.assertEqual("unknown", metadata["build_provenance"]["status"])
            self.assertEqual(0.25, metadata["startup_delay_s"])
            self.assertEqual(0.17, metadata["quiet_period_s"])
            self.assertEqual(4.2, metadata["completion_timeout_s"])
            self.assertEqual(benchmark.sha256(Path(benchmark.__file__)), metadata["harness_sha256"])
            markdown = next((root / "runs").glob("*/summary.md")).read_text()
            self.assertIn("Binary build origin: unknown", markdown)
            self.assertNotIn("Binary build commit: `harness-checkout`", markdown)


if __name__ == "__main__":
    unittest.main()
