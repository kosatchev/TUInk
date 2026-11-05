"""Offline checks for PGO corpus, isolation, exit policy, and merge eligibility."""

from pathlib import Path
import re
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import pgo_training as training


class PgoTrainingTest(unittest.TestCase):
    def test_corpora_have_checked_search_targets_and_multiline_clipboard_prefix(self):
        for language in training.LANGUAGES:
            with self.subTest(language=language):
                fixture = training.training_corpus(language, 70)
                self.assertEqual(fixture, training.training_corpus(language, 70))
                self.assertIn(fixture.marker.encode(), fixture.data.splitlines()[0])
                self.assertIn("λ café 中🙂", fixture.data.decode("utf-8"))
                self.assertGreaterEqual(fixture.data.count(fixture.query.encode()), 70)
                self.assertGreater(max(map(len, fixture.data.splitlines())), training.COLS)
                self.assertTrue(fixture.data.endswith(b"\n"))
                self.assertTrue(all(len(line.decode("utf-8")) < 70 for line in fixture.data.splitlines()[:3]))
                self.assertEqual(language in ("java", "yaml"), fixture.wrap)

    def test_corpora_include_multiline_language_state(self):
        self.assertIn(b'"""', training.training_corpus("java", 2).data)
        self.assertIn(b"'''", training.training_corpus("python", 2).data)
        self.assertIn(b'\n  # still quoted\n  closing"', training.training_corpus("yaml", 2).data)
        rust = training.training_corpus("rust", 40).data
        self.assertIn(b"/* outer /* nested */", rust)
        self.assertIn(b'r##"', rust)
        self.assertIn(b'r###"', rust)
        self.assertIn(b'\n       raw continuation"###;', rust)

    def test_case_insensitive_search_replaces_exact_generated_targets(self):
        for language in training.LANGUAGES:
            with self.subTest(language=language):
                fixture = training.training_corpus(language)
                exact = fixture.data.count(fixture.query.encode("ascii"))
                self.assertNotIn(fixture.query.casefold(), fixture.marker.casefold())
                self.assertEqual(exact, fixture.data.decode("utf-8").casefold().count(fixture.query.casefold()))
                replaced = fixture.data.replace(fixture.query.encode("ascii"), b"replacement")
                self.assertEqual(0, replaced.decode("utf-8").casefold().count(fixture.query.casefold()))
                self.assertIn(fixture.marker.encode("ascii"), replaced.splitlines()[0])

    def test_reject_invalid_corpus_request(self):
        for language, chunks in (("unknown", 10), ("java", 0), ("python", -1)):
            with self.assertRaises(ValueError):
                training.training_corpus(language, chunks)

    def test_settings_use_exact_current_store_keys(self):
        source = (training.REPOSITORY_ROOT / "apps/tuink/terminal-ui/src/main/java/dev/tuink/tui/flow/AppSettingsStore.java").read_text()
        expected_keys = set(re.findall(r'private static final String KEY_\w+ = "([^"]+)";', source))
        self.assertTrue(expected_keys)
        for mouse, wrap in ((False, True), (True, False)):
            settings = training.settings_text(mouse=mouse, wrap=wrap)
            values = dict(line.split("=", 1) for line in settings.splitlines())
            self.assertEqual(expected_keys, set(values))
            self.assertEqual("2", values["format"])
            self.assertEqual(str(mouse).lower(), values["ui.mouseMode"])
            self.assertEqual(str(wrap).lower(), values["ui.wrapLines"])
            self.assertEqual("true", values["ui.confirmOnClose"])
            for key in ("ui.markdownCodeHighlighting", "ui.rightClickClipboard"):
                self.assertEqual("false", values[key], f"Baseline PGO training must keep {key} disabled")

    def test_input_protocol_preserves_unicode_and_mouse_coordinates(self):
        text = "α e\u0301 中🧪\nsecond line"
        self.assertEqual(b"\x1b[200~" + text.encode() + b"\x1b[201~", training.bracketed_paste(text))
        self.assertEqual(b"\x1b[27u", training.ESCAPE)
        for index in range(600):
            match = re.fullmatch(rb"\x1b\[<32;(\d+);(\d+)M", training.mouse_drag(index))
            self.assertIsNotNone(match)
            col, row = map(int, match.groups())
            self.assertTrue(10 <= col < training.COLS)
            self.assertTrue(3 <= row < training.ROWS - 3)

    def test_accept_only_actual_normal_exit_zero(self):
        training.require_normal_exit(0)
        for status in (1 << 8, 127 << 8, signal.SIGTERM, signal.SIGKILL):
            with self.subTest(status=status), self.assertRaises(RuntimeError):
                training.require_normal_exit(status)

    @staticmethod
    def screen(session, header, notice):
        session.screen = training.Screen(3, 80)
        session.screen.feed((header + "\x1b[3;1H" + notice).encode("utf-8"))

    def test_saved_bytes_require_a_clean_owner_acknowledgement(self):
        with tempfile.TemporaryDirectory() as temporary:
            document = Path(temporary) / "draft.txt"
            document.write_bytes(b"new draft")
            session = training.EditorSession.__new__(training.EditorSession)
            self.screen(session, "File draft.txt *", "Saved draft.txt")

            def observe(predicate, description):
                self.assertFalse(predicate(), "Worker bytes and the previous Saved notice are insufficient")
                for notice in ("Checking save target", "Checking file changes", "Preparing save", "Finishing save"):
                    self.screen(session, "File draft.txt Saved previous.txt", notice + "…")
                    self.assertFalse(predicate(), "Progress cannot acknowledge completion beside old Saved text")
                self.screen(session, "File draft.txt", "Saved draft.txt")
                document.write_bytes(b"old draft")
                self.assertFalse(predicate(), "The owner acknowledgement must match the expected bytes")
                document.write_bytes(b"new draft")
                self.assertTrue(predicate())

            with patch.object(session, "wait_for", side_effect=observe) as wait:
                session.wait_for_saved_bytes(document, b"new draft")
            wait.assert_called_once()

    def test_previous_clean_saved_frame_cannot_acknowledge_the_next_save(self):
        with tempfile.TemporaryDirectory() as temporary:
            document = Path(temporary) / "draft.txt"
            document.write_bytes(b"old draft")
            session = training.EditorSession.__new__(training.EditorSession)
            session.saves_submitted = 1
            self.screen(session, "File draft.txt", "Saved draft.txt")
            events = []

            def observe(predicate, description):
                self.assertFalse(predicate())
                if events == ["edit"]:
                    self.screen(session, "File draft.txt *", "Saved draft.txt")
                    events.append("fresh boundary")
                else:
                    self.assertEqual(["edit", "fresh boundary", "Ctrl+S", "worker publication"], events)
                    self.screen(session, "File draft.txt", "Saved draft.txt")
                    events.append("owner completion")
                self.assertTrue(predicate())

            def submit(data):
                if data == training.CTRL_Z:
                    self.assertEqual([], events)
                    events.append("edit")
                else:
                    self.assertEqual(training.CTRL_S, data)
                    self.assertEqual(["edit", "fresh boundary"], events)
                    events.append("Ctrl+S")
                    document.write_bytes(b"new draft")
                    self.screen(session, "File draft.txt *", "Finishing save…")
                    events.append("worker publication")

            with patch.object(session, "wait_for", side_effect=observe), patch.object(session, "send", side_effect=submit):
                session.save(document, b"new draft", training.CTRL_Z)
            self.assertEqual(["edit", "fresh boundary", "Ctrl+S", "worker publication", "owner completion"], events)

    def test_cleared_notice_from_selection_does_not_allow_saving_before_the_cut(self):
        session = training.EditorSession.__new__(training.EditorSession)
        session.saves_submitted = 1
        self.screen(session, "File draft.txt", "")

        def observe(predicate, description):
            self.assertFalse(predicate(), "Clearing Saved during selection is not a cut acknowledgement")
            raise TimeoutError("No dirty cut acknowledgement")

        with patch.object(session, "wait_for", side_effect=observe), patch.object(session, "send") as send:
            with self.assertRaises(TimeoutError):
                session.save(Path("unused"), b"cut draft", training.CTRL_X)
        send.assert_called_once_with(training.CTRL_X)

    def test_initial_unchanged_save_requires_explicit_mode_and_cannot_be_reused(self):
        with tempfile.TemporaryDirectory() as temporary:
            document = Path(temporary) / "draft.txt"
            document.write_bytes(b"loaded draft")
            session = training.EditorSession.__new__(training.EditorSession)
            session.saves_submitted = 0
            self.screen(session, "File draft.txt", "Loaded draft.txt")
            events = []

            def observe(predicate, description):
                if events == ["navigation"]:
                    self.assertTrue(predicate())
                else:
                    self.assertEqual(["navigation", "Ctrl+S"], events)
                    self.assertFalse(predicate(), "Unchanged bytes do not acknowledge the initial save")
                    self.screen(session, "File draft.txt", "Saved draft.txt")
                    events.append("owner completion")
                    self.assertTrue(predicate())

            def submit(data):
                if data == training.HOME:
                    self.assertEqual([], events)
                    events.append("navigation")
                else:
                    self.assertEqual(training.CTRL_S, data)
                    self.assertEqual(["navigation"], events)
                    self.screen(session, "File draft.txt", "Preparing save · Esc to cancel")
                    events.append("Ctrl+S")

            with patch.object(session, "wait_for", side_effect=observe), patch.object(session, "send", side_effect=submit):
                session.save(document, b"loaded draft", training.HOME, require_dirty=False)
            self.assertEqual(["navigation", "Ctrl+S", "owner completion"], events)
            with patch.object(session, "send") as send:
                with self.assertRaises(ValueError):
                    session.save(document, b"loaded draft", training.HOME, require_dirty=False)
                session.saves_submitted = 0
                with self.assertRaises(ValueError):
                    session.save(document, b"loaded draft", training.HOME, require_dirty=False)
                send.assert_not_called()

    def test_fresh_output_preserves_existing_files_and_refuses_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "new"
            training.prepare_output(output)
            self.assertTrue((output / "profiles").is_dir())
            original = output / "keep.iprof"
            original.write_bytes(b"previous training")
            with self.assertRaises(FileExistsError):
                training.prepare_output(output)
            self.assertEqual(b"previous training", original.read_bytes())
            empty = root / "empty"
            empty.mkdir()
            link = root / "link"
            link.symlink_to(empty, target_is_directory=True)
            with self.assertRaises(ValueError):
                training.prepare_output(link)
            self.assertEqual([], list(empty.iterdir()))
            training.prepare_output(empty)

    def test_missing_or_empty_profiles_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            profile = Path(temporary) / "java.iprof"
            with self.assertRaises(RuntimeError):
                training.require_profile(profile)
            profile.write_bytes(b"")
            with self.assertRaises(RuntimeError):
                training.require_profile(profile)
            profile.write_bytes(b"checked profile")
            training.require_profile(profile)

    def completed_profiles(self, output):
        training.prepare_output(output)
        sessions = []
        for name in training.PROFILE_NAMES:
            profile = output / "profiles" / f"{name}.iprof"
            profile.write_bytes(f"profile-{name}".encode())
            sessions.append({"name": name, "status": "complete", "normal_exit_code": 0,
                             "profile_sha256": training.file_sha256(profile)})
        return sessions

    def test_merge_is_bounded_and_places_output_outside_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "training"
            sessions = self.completed_profiles(output)

            def fake_merge(command, **kwargs):
                self.assertEqual(["merge-pgo-profiles", f"--input-dir={output / 'profiles'}",
                                  f"--output-file={output / 'profile.iprof'}"], command[1:])
                self.assertEqual("-Xmx512m -XX:ActiveProcessorCount=2", kwargs["env"]["JAVA_TOOL_OPTIONS"])
                self.assertEqual(training.MERGE_TIMEOUT, kwargs["timeout"])
                self.assertTrue(kwargs["check"])
                (output / "profile.iprof").write_bytes(b"merged profile")

            with patch.object(training.subprocess, "run", side_effect=fake_merge) as run:
                merged = training.merge_profiles(Path(temporary) / "sdk", output, sessions)
                self.assertEqual(1, run.call_count)
            self.assertEqual(str(output / "profile.iprof"), merged["file"])
            self.assertEqual(training.sha256(b"merged profile"), merged["sha256"])
            with patch.object(training.subprocess, "run") as run:
                with self.assertRaises(FileExistsError):
                    training.merge_profiles(Path(temporary) / "sdk", output, sessions)
                run.assert_not_called()

    def test_failed_incomplete_or_changed_sessions_never_reach_merge(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "training"
            sessions = self.completed_profiles(output)
            with patch.object(training.subprocess, "run") as run:
                with self.assertRaises(RuntimeError):
                    training.merge_profiles(Path(temporary), output, sessions[:-1])
                sessions[0]["status"] = "failed"
                with self.assertRaises(RuntimeError):
                    training.merge_profiles(Path(temporary), output, sessions)
                sessions[0]["status"] = "complete"
                sessions[0]["normal_exit_code"] = 1
                with self.assertRaises(RuntimeError):
                    training.merge_profiles(Path(temporary), output, sessions)
                sessions[0]["normal_exit_code"] = 0
                (output / "profiles/java.iprof").write_bytes(b"changed")
                with self.assertRaises(RuntimeError):
                    training.merge_profiles(Path(temporary), output, sessions)
                run.assert_not_called()

    def test_merge_failure_or_empty_output_is_not_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "training"
            sessions = self.completed_profiles(output)
            with patch.object(training.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "merge")):
                with self.assertRaises(subprocess.CalledProcessError):
                    training.merge_profiles(Path(temporary), output, sessions)
            output = Path(temporary) / "empty-merge-output"
            sessions = self.completed_profiles(output)
            with patch.object(training.subprocess, "run"):
                with self.assertRaises(RuntimeError):
                    training.merge_profiles(Path(temporary), output, sessions)


if __name__ == "__main__":
    unittest.main()
