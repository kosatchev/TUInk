import os
from pathlib import Path
import signal
import sys
import tempfile
import unittest
from unittest.mock import patch

from run import recovery_snapshot_present
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


class LifecycleTest(unittest.TestCase):
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
            session = Session(root / "session", executable, "unused")
            session.deadline = 0
            with self.assertRaises(TimeoutError):
                session.drain()
            session.close()
            self.assertEqual(-signal.SIGKILL, session.status)
            with self.assertRaises(ChildProcessError):
                os.waitpid(session.pid, os.WNOHANG)


if __name__ == "__main__":
    unittest.main()
