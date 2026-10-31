from contextlib import contextmanager
import os
from pathlib import Path
import signal
import sys
import tempfile
import termios
import unittest
from unittest.mock import patch

from run import expect_field_line, recovery_snapshot_present
from terminal import Session
from vt import Screen


class ScreenTest(unittest.TestCase):
    def test_fragmented_utf8_and_cursor_sequence(self):
        screen = Screen(3, 10)
        data = "\x1b[2;3H中e\u0301!".encode("utf-8")
        for byte in data:
            screen.feed(bytes([byte]))
        self.assertEqual([" ", " ", "中", "", "e\u0301", "!", " ", " ", " ", " "], screen.cells[1])
        self.assertEqual((1, 6), (screen.row, screen.col))
        self.assertFalse(screen.unknown)

    def test_scroll_hints_are_limited_to_region(self):
        screen = Screen(5, 4)
        screen.feed(b"\x1b[1;1H1111\x1b[2;1H2222\x1b[3;1H3333\x1b[4;1H4444\x1b[5;1H5555")
        screen.feed(b"\x1b[2;4r\x1b[1S")
        self.assertEqual(["1111", "3333", "4444", "    ", "5555"], screen.lines)
        screen.feed(b"\x1b[2T")
        self.assertEqual(["1111", "    ", "    ", "3333", "5555"], screen.lines)

    def test_wide_overwrite_and_no_autowrap(self):
        screen = Screen(2, 5)
        screen.feed("中ab".encode("utf-8"))
        screen.feed(b"\x1b[1;2HX\x1b[?7l\x1b[1;5Hyz")
        self.assertEqual(" Xabz", screen.lines[0])
        self.assertEqual("     ", screen.lines[1])

    def test_clear_resize_and_osc(self):
        screen = Screen(2, 5)
        screen.feed(b"hello\x1b[1;3H\x1b[K\x1b]0;title")
        screen.feed(b"\x1b\\\x1b[2;1Hok")
        self.assertEqual(["he   ", "ok   "], screen.lines)
        screen.resize(1, 3)
        self.assertEqual(["   "], screen.lines)
        self.assertEqual((0, 0, 0, 0), (screen.row, screen.col, screen.top, screen.bottom))
        self.assertFalse(screen.unknown)

    def test_unknown_sequences_are_reported(self):
        screen = Screen()
        screen.feed(b"\x1b[42z\x1b[?9999h")
        self.assertEqual({("42", "", "z"), ("?9999", "", "h")}, screen.unknown)


class RecoveryObservationTest(unittest.TestCase):
    def test_only_committed_generations_count_and_rotation_is_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old_generation, new_generation = "a" * 32, "b" * 32
            meta = root / "entry.meta.properties"
            old_text = root / ("entry." + old_generation + ".txt")
            new_text = root / ("entry." + new_generation + ".txt")
            new_text.write_text("complete draft", encoding="utf-8")
            self.assertFalse(recovery_snapshot_present(root, "complete draft"))
            old_text.write_text("old draft", encoding="utf-8")
            meta.write_text("textGeneration=" + old_generation + "\n", encoding="utf-8")
            original_read = Path.read_text

            def rotate_after_metadata_read(path, *args, **kwargs):
                result = original_read(path, *args, **kwargs)
                if path == meta:
                    meta.write_text("textGeneration=" + new_generation + "\n", encoding="utf-8")
                    old_text.unlink()
                return result

            with patch.object(Path, "read_text", rotate_after_metadata_read):
                self.assertFalse(recovery_snapshot_present(root, "complete draft"))
            self.assertTrue(recovery_snapshot_present(root, "complete draft"))
            self.assertFalse(recovery_snapshot_present(root, "incomplete draft"))


class SaveObservationTest(unittest.TestCase):
    @staticmethod
    def screen(session, header, notice):
        session.screen = Screen(3, 80)
        session.screen.feed((header + "\x1b[3;1H" + notice).encode("utf-8"))

    def test_published_bytes_require_a_clean_owner_acknowledgement(self):
        with tempfile.TemporaryDirectory() as directory:
            document = Path(directory) / "draft.txt"
            document.write_bytes(b"new draft")
            session = Session.__new__(Session)
            self.screen(session, "File draft.txt *", "Saved draft.txt")

            def observe(predicate, description, timeout):
                self.assertFalse(predicate(), "worker bytes and a stale Saved notice are insufficient")
                for notice in ("Checking save target", "Checking file changes", "Preparing save", "Finishing save"):
                    self.screen(session, "File draft.txt Saved previous.txt", notice + "…")
                    self.assertFalse(predicate(), "progress cannot acknowledge completion even beside old Saved text")
                self.screen(session, "File draft.txt", "Saved draft.txt")
                document.write_bytes(b"old draft")
                self.assertFalse(predicate(), "UI acknowledgement must match the expected bytes")
                document.write_bytes(b"new draft")
                self.assertTrue(predicate())

            with patch.object(session, "expect", side_effect=observe):
                session.expect_saved(document, "new draft")

    def test_previous_clean_saved_frame_cannot_acknowledge_the_next_request(self):
        with tempfile.TemporaryDirectory() as directory:
            document = Path(directory) / "draft.txt"
            document.write_bytes(b"old draft")
            session = Session.__new__(Session)
            self.screen(session, "File draft.txt", "Saved draft.txt")
            events = []

            def observe(predicate, description, timeout):
                self.assertFalse(predicate())
                if not events:
                    self.screen(session, "File draft.txt *", "Saved draft.txt")
                    events.append("dirty frame")
                else:
                    self.screen(session, "File draft.txt", "Saved draft.txt")
                    events.append("owner completion")
                self.assertTrue(predicate())

            def submit(data):
                self.assertEqual(["dirty frame"], events)
                self.assertEqual(b"\x13", data)
                document.write_bytes(b"new draft")
                events.append("worker publication")

            with patch.object(session, "expect", side_effect=observe), patch.object(session, "send", side_effect=submit):
                session.save(document, "new draft")
            self.assertEqual(["dirty frame", "worker publication", "owner completion"], events)


class FieldObservationTest(unittest.TestCase):
    @contextmanager
    def session(self, *, initial=(), frames=(), exit_at=None, deadline=10):
        clock = [0.0]
        pending = list(frames)
        session = Session.__new__(Session)
        session.screen = Screen(6, 80)
        session.deadline = deadline
        session.timeout = deadline
        session.status = None
        session.fd = -1
        session.drains = []
        session.writes = []

        def feed(rows):
            session.screen.feed(b"\x1b[2J\x1b[H")
            for row, text in enumerate(rows):
                session.screen.feed((f"\x1b[{row + 1};1H" + text).encode("utf-8"))

        def drain(seconds):
            session.drains.append(seconds)
            clock[0] = min(deadline, clock[0] + seconds)
            while pending and pending[0][0] <= clock[0]:
                _, rows = pending.pop(0)
                feed(rows)

        def poll():
            if exit_at is not None and clock[0] >= exit_at:
                session.status = 0
            return session.status

        def write(fd, data):
            self.assertEqual(-1, fd)
            session.writes.append(data)
            return len(data)

        session.drain, session.poll = drain, poll
        feed(initial)
        with patch("terminal.time.monotonic", side_effect=lambda: clock[0]), \
                patch("terminal.os.write", side_effect=write):
            yield session, clock

    def test_delayed_find_and_replace_are_observed_after_the_send_drain_has_ended(self):
        for prefix in ("Find: ", "Replace:"):
            with self.subTest(prefix=prefix), self.session(
                initial=("File draft.txt",), frames=((0.40, ("File draft.txt", prefix + "needle")),)
            ) as (session, clock):
                session.send(b"\x06")
                session.send(b"needle")
                self.assertAlmostEqual(0.36, clock[0])

                row, line = expect_field_line(session, prefix, "delayed field", contains="needle")

                self.assertEqual(1, row)
                self.assertTrue(line.startswith(prefix + "needle"))
                self.assertGreaterEqual(clock[0], 0.40)
                self.assertEqual([b"\x06", b"needle"], session.writes)
                self.assertGreater(len(session.drains), 2)

    def test_an_existing_field_must_contain_the_expected_new_value(self):
        with self.session(initial=("File draft.txt", "Find: old"),
                          frames=((0.10, ("File draft.txt", "Find: new")),)) as (session, clock):
            _, line = expect_field_line(session, "Find: ", "updated field", contains="new")

            self.assertTrue(line.startswith("Find: new"))
            self.assertGreaterEqual(clock[0], 0.10)

    def test_save_as_content_cannot_be_acknowledged_by_document_text(self):
        initial = ("File draft.txt", "Save As: ", "aaaaaaaa in the document")
        with self.session(initial=initial,
                          frames=((0.10, (initial[0], "Save As: aaaaaaaa", initial[2])),)) as (session, clock):
            self.assertIn("Save As: ", session.screen.text)
            self.assertIn("aaaaaaaa", session.screen.text)

            row, line = expect_field_line(session, "Save As: ", "pasted filename", contains="aaaaaaaa")

            self.assertEqual(1, row)
            self.assertTrue(line.startswith("Save As: aaaaaaaa"))
            self.assertGreaterEqual(clock[0], 0.10)

    def test_a_missing_field_respects_both_local_and_scenario_deadlines(self):
        for timeout, deadline, limit in ((0.12, 10, 0.12), (3, 0.10, 0.10)):
            with self.subTest(timeout=timeout, deadline=deadline), self.session(deadline=deadline) as (session, clock):
                with self.assertRaisesRegex(AssertionError, "Timed out waiting for missing field"):
                    expect_field_line(session, "Find: ", "missing field", timeout=timeout)
                self.assertGreaterEqual(clock[0], limit)
                self.assertLessEqual(clock[0], limit + 0.05)

    def test_exit_before_readiness_stops_polling(self):
        with self.session(exit_at=0.10, frames=((0.40, ("Find: too late",)),)) as (session, clock):
            with self.assertRaisesRegex(AssertionError, "Timed out waiting for exited field"):
                expect_field_line(session, "Find: ", "exited field")
            self.assertEqual(0, session.status)
            self.assertAlmostEqual(0.10, clock[0])
            self.assertEqual(2, len(session.drains))

    def test_assertions_inside_a_predicate_still_propagate_immediately(self):
        with self.session() as (session, clock):
            def broken():
                raise AssertionError("broken predicate")

            with self.assertRaisesRegex(AssertionError, "broken predicate"):
                session.expect(broken, "a valid predicate")
            self.assertEqual([], session.drains)
            self.assertEqual(0, clock[0])


class LifecycleTest(unittest.TestCase):
    def test_expected_startup_failure_keeps_overrides_in_the_child_and_restores_the_terminal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = root / "fake-startup-failure"
            executable.write_text(
                f"#!{sys.executable}\n"
                "import os, sys\n"
                "print('CHILD_VALUE=' + os.environ.get('TUINK_PTY_TEST', 'missing'), flush=True)\n"
                "sys.exit(2)\n", encoding="utf-8")
            executable.chmod(0o700)
            parent_value = os.environ.get("TUINK_PTY_TEST")
            with Session(root / "session", executable, "unused", wait_for_startup=False,
                         env_overrides={"TUINK_PTY_TEST": "child"}, terminal_baud=termios.B19200) as session:
                session.expect(lambda: session.poll() is not None, "expected startup rejection", timeout=2)
                session.drain(0.05)
                self.assertEqual(2, session.status)
                self.assertIn(b"CHILD_VALUE=child", session.log)
                self.assertEqual(parent_value, os.environ.get("TUINK_PTY_TEST"))
                session.expect_terminal_restored()

    @staticmethod
    def terminal_editor(root, corrupt_speed=False):
        executable = root / "fake-terminal-editor"
        executable.write_text(
            f"#!{sys.executable}\n"
            "import os, sys, termios, tty\n"
            "original = termios.tcgetattr(0)\n"
            "tty.setraw(0)\n"
            "sys.stdout.write('\\x1b[1;1HFile\\x1b[24;1HLn 1/1, Col 1/1'); sys.stdout.flush()\n"
            "while os.read(0, 1) != b'\\x11': pass\n"
            + ("original[4] = original[5] = termios.B0\n" if corrupt_speed else "")
            + "termios.tcsetattr(0, termios.TCSANOW, original)\n", encoding="utf-8")
        executable.chmod(0o700)
        return executable

    def test_measured_terminal_survives_child_exit_with_its_original_attributes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = self.terminal_editor(root)
            for baud in (termios.B9600, termios.B38400):
                with self.subTest(baud=baud), Session(
                        root / str(baud), executable, "unused", terminal_baud=baud) as session:
                    self.assertEqual([baud, baud], session.original_terminal_attributes[4:6])
                    session.finish()
                    session.expect_terminal_restored()
                    self.assertTrue((session.root / "terminal-attributes.json").is_file())

    def test_terminal_comparison_detects_baud_loss_despite_restored_raw_flags(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = self.terminal_editor(root, corrupt_speed=True)
            with Session(root / "session", executable, "unused", terminal_baud=termios.B9600) as session:
                session.finish()
                with self.assertRaisesRegex(AssertionError, "ispeed:.*ospeed:"):
                    session.expect_terminal_restored()

    def test_exit_predicate_is_rechecked_after_reaping(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = root / "fake-editor"
            executable.write_text(
                f"#!{sys.executable}\n"
                "import sys, time\n"
                "sys.stdout.write('\\x1b[1;1HFile\\x1b[24;1HLn 1/1, Col 1/1'); sys.stdout.flush()\n"
                "while True: time.sleep(1)\n", encoding="utf-8")
            executable.chmod(0o700)
            with Session(root / "session", executable, "unused") as session:
                os.kill(session.pid, signal.SIGKILL)
                session.expect(lambda: session.poll() is not None, "child exit", timeout=1)
                self.assertEqual(-signal.SIGKILL, session.status)

    def test_failed_startup_is_reaped_and_saved(self):
        # A harmless executable that ignores Java arguments and exits immediately.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(AssertionError, "editor startup"):
                Session(root, "/bin/false", "unused")
            self.assertTrue((root / "terminal.log").is_file())
            self.assertTrue((root / "final-screen.txt").is_file())
            with self.assertRaises(ChildProcessError):
                os.waitpid(-1, os.WNOHANG)

    def test_cleanup_kills_and_reaps_an_unresponsive_child(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = root / "fake-editor"
            executable.write_text(
                f"#!{sys.executable}\n"
                "import signal, sys, time\n"
                "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                "sys.stdout.write('\\x1b[1;1HFile\\x1b[24;1HLn 1/1, Col 1/1'); sys.stdout.flush()\n"
                "while True: time.sleep(1)\n", encoding="utf-8")
            executable.chmod(0o700)
            session = Session(root / "session", executable, "unused", terminal_baud=termios.B9600)
            slave = session.terminal_slave_fd
            session.deadline = 0
            with self.assertRaises(TimeoutError):
                session.drain()
            session.close()
            self.assertEqual(-signal.SIGKILL, session.status)
            with self.assertRaises(OSError):
                os.fstat(slave)
            with self.assertRaises(ChildProcessError):
                os.waitpid(session.pid, os.WNOHANG)


if __name__ == "__main__":
    unittest.main()
