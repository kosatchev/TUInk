#!/usr/bin/env python3
"""Run observable editor regressions against an installed JVM distribution."""

import argparse
import hashlib
import json
import os
from pathlib import Path

REPOSITORY_ROOT = next(parent for parent in Path(__file__).resolve().parents
                       if (parent / 'settings.gradle').is_file() and (parent / 'gradlew').is_file())
import re
import shutil
import subprocess
import sys
import tempfile
import time
import traceback

from terminal import Session


def status(session):
    match = re.search(r"Ln \d+/\d+, Col \d+/\d+", session.screen.text)
    if not match:
        raise AssertionError(session.screen.text)
    return match[0]


def document_rows(session):
    rows = [line for line in session.screen.lines if re.search(r"(?:ROW|\b)\d{4}\|", line)]
    if not rows:
        raise AssertionError(session.screen.text)
    return rows


def field_line(session):
    for row, line in enumerate(session.screen.lines):
        if line.startswith("Find: "):
            return row, line
    raise AssertionError(session.screen.text)


def recovery_snapshot_present(directory, expected):
    for meta in directory.glob("*.meta.properties"):
        try:
            generation = re.search(r"(?m)^textGeneration=([0-9a-f]{32})$", meta.read_text(encoding="utf-8"))
            if not generation:
                raise AssertionError("missing recovery text generation: " + str(meta))
            text = meta.with_name(meta.name.removesuffix(".meta.properties") + "." + generation[1] + ".txt")
            if text.read_text(encoding="utf-8") == expected:
                return True
        except FileNotFoundError:
            # Autosave can commit a new generation between reading metadata
            # and its old text. The next bounded poll reads the new generation.
            continue
    return False


def format5_snapshot(home, document, original, text):
    """The actual v0.13.0 schema: display paths, CSV separators, no platform/byte size."""
    directory = home / ".config/tuink/recovery"
    directory.mkdir(parents=True, mode=0o700)
    identity, generation = "release-format5", "a" * 32
    fields = {
        "format": "5", "createdAtEpochMillis": "0", "textGeneration": generation,
        "dirty": "true", "currentFile.present": "true", "currentFile": str(document),
        "saveDisposition": "REPLACE_EXISTING", "fileEncoding": "UTF-8", "bomKind": "UTF_8",
        "defaultLineSeparator": "CRLF", "lineSeparators": "CRLF",
        "fileBaseline.present": "true", "fileBaseline.target": str(document),
        "fileBaseline.sha256": hashlib.sha256(original).hexdigest(),
        "cursor.line": "0", "cursor.col": "0", "viewport.topLine": "0",
        "viewport.topWrapSegment": "0", "viewport.leftColumn": "0", "viewport.leftVisualColumn": "0",
        "selection.present": "false", "selection.line": "0", "selection.col": "0",
    }

    def escape(value):
        # java.util.Properties.store(OutputStream) escapes UTF-16 code units.
        units = value.encode("utf-16-be")
        result = []
        for index in range(0, len(units), 2):
            unit = int.from_bytes(units[index:index + 2], "big")
            if unit < 0x20 or unit > 0x7e:
                result.append("\\u%04x" % unit)
            else:
                character = chr(unit)
                result.append(("\\" if character in "\\=:#! " else "") + character)
        return "".join(result)

    meta = directory / (identity + ".meta.properties")
    payload = directory / (identity + "." + generation + ".txt")
    meta.write_text("".join(key + "=" + escape(value) + "\n" for key, value in fields.items()), encoding="ascii")
    payload.write_text(text, encoding="utf-8")
    meta.chmod(0o600)
    payload.chmod(0o600)
    return meta, payload


class Regressions:
    def __init__(self, root, java, classpath):
        self.root, self.java, self.classpath = root, java, classpath

    def session(self, name, document=None, **kwargs):
        return Session(self.root / name, self.java, self.classpath, document, **kwargs)

    def fixture(self, name, text):
        path = self.root / (name + ".txt")
        path.write_text(text, encoding="utf-8")
        return path

    def cli_path_identity(self):
        directory = self.root / "cli-path-files"
        directory.mkdir()
        raw = Path(os.fsdecode(os.fsencode(directory) + b"/legacy-\xff.txt"))
        neighbour = directory / "legacy-�.txt"
        raw.write_text("raw original", encoding="utf-8")
        neighbour.write_text("unicode original", encoding="utf-8")
        for arguments in ((str(raw),), ("--", str(raw)), (str(neighbour),),
                          ("--profile-file", str(raw))):
            result = subprocess.run([self.java, "--enable-native-access=ALL-UNNAMED", "-Xmx64m",
                                     "-cp", self.classpath, "dev.tuink.app.App", *arguments],
                                    cwd=directory, capture_output=True, timeout=10)
            assert result.returncode == 2, result.stderr.decode("utf-8", "replace")
            assert b"U+FFFD" in result.stderr, result.stderr
            assert raw.read_text(encoding="utf-8") == "raw original"
            assert neighbour.read_text(encoding="utf-8") == "unicode original"
        for name, path, options, expected in (
            ("cli-path-raw", raw, ["--file-uri=" + raw.as_uri()], "raw original"),
            ("cli-path-unicode", neighbour, ["--file-uri", neighbour.as_uri()], "unicode original"),
        ):
            with self.session(name, options=options) as session:
                session.expect(lambda: expected in session.screen.text, "the exact file URI target")
                session.send(b"X\x13")
                session.expect(lambda: path.read_text(encoding="utf-8") == "X" + expected,
                               "saving the exact file URI target")
                session.finish()
        assert raw.read_text(encoding="utf-8") == "Xraw original"
        assert neighbour.read_text(encoding="utf-8") == "Xunicode original"

    def save_as_retarget(self):
        directory = self.root / "save-as-retarget-files"
        directory.mkdir()
        first, second, alias = (directory / name for name in ("first.txt", "second.txt", "alias.txt"))
        first.write_text("first original", encoding="utf-8")
        second.write_text("second original", encoding="utf-8")
        alias.symlink_to(first.name)
        with self.session("save-as-retarget", alias) as session:
            session.send(b"draft ")
            alias.unlink()
            alias.symlink_to(second.name)
            session.send(b"\x13")
            session.expect(lambda: "File changed outside TUInk" in session.screen.text, "normal save conflict")
            assert first.read_text(encoding="utf-8") == "first original"
            assert second.read_text(encoding="utf-8") == "second original"
            session.send(b"\x0b\x01\x1b[200~" + str(second).encode() + b"\x1b[201~\r")
            session.expect(lambda: "Overwrite" in session.screen.text, "confirmation for the newly chosen target")
            session.send(b"y")
            session.expect(lambda: second.read_text(encoding="utf-8") == "draft first original",
                           "explicitly replacing the other physical file")
            session.finish()
        assert first.read_text(encoding="utf-8") == "first original"
        assert alias.is_symlink() and alias.resolve() == second

    def field_edit_caret(self):
        original = "original"
        document = self.fixture("field-edit-caret", original)
        initial, expected = "👩‍x💻TAIL", "👩‍💻BTAIL"
        with self.session("field-edit-caret", document) as session:
            session.send(b"\x06" + initial.encode())
            session.send(b"\x1b[H\x1b[C\x1b[3~B")
            session.expect(lambda: expected in field_line(session)[1], "field caret after a newly joined emoji")
            session.send(b"\x1b[27u\x0b\x01" + initial.encode())
            session.send(b"\x1b[H\x1b[C\x1b[3~B\r")
            target = session.root / expected
            session.expect(lambda: target.is_file() and target.read_text(encoding="utf-8") == original,
                           "the same caret policy in Save As")
            assert not (session.root / ("B👩‍💻TAIL")).exists()
            session.finish()
        assert document.read_text(encoding="utf-8") == original

    def field_memory_growth(self):
        document = self.fixture("field-memory-growth", "original")
        payload = b"\x1b[200~" + ("界" * 1_048_576).encode() + b"\x1b[201~"
        with self.session("field-memory-growth", document, max_heap_mb=128) as session:
            session.send(b"draft \x06" + b"\t" * 5)
            accepted = 0
            for _ in range(8):
                before = next(line for line in session.screen.lines if line.startswith("Replace:"))
                session.send(payload, 0.3)
                if "Text is too large; nothing changed" in session.screen.text:
                    after = next(line for line in session.screen.lines if line.startswith("Replace:"))
                    assert after == before, session.screen.text
                    assert accepted > 0, "a 1 Mi-character field must fit before growth reaches the limit"
                    break
                accepted += 1
            else:
                raise AssertionError("dense field growth never reached its memory admission limit")
            session.send(b"\x01\x7fOK")
            session.expect(lambda: "Replace: OK" in session.screen.text, "editing after a refused field paste")
            session.send(b"\x1b[27u\x13")
            session.expect(lambda: document.read_text(encoding="utf-8") == "draft original",
                           "saving the draft after a refused field paste")
            session.finish()
        assert b"OutOfMemoryError" not in session.log

    def identical_replace_budget(self):
        original = "z" * 2_700_000 + "a" * 66
        document = self.fixture("identical-replace-budget", original)
        with self.session("identical-replace-budget", document) as session:
            session.send(b"\x06\x1b[200~" + b"a" * 64 + b"?a\x1b[201~")
            session.send(b"\t" * 3 + b" " + b"\t" * 2 + b"\x1b[200~" + b"a" * 66 + b"\x1b[201~")
            session.expect(lambda: "Replace: aaaaa" in session.screen.text, "identical replacement text", timeout=10)
            session.expect(lambda: "Find 1" in session.screen.text, "the wildcard count")
            before = status(session)
            session.send(b"\x12")
            session.expect(lambda: "Search is too complex" in session.screen.text, "bounded replacement refusal")
            assert status(session) == before, session.screen.text
            session.send(b"\x1b[27u\x1a\x13")
            session.finish()
        assert document.read_text(encoding="utf-8") == original

    def reading(self):
        original = "\n".join(f"ROW{i:04d}|" + " ".join(f"C{j:03d}|" for j in range(50)) for i in range(120))
        document = self.fixture("reading", original)
        with self.session("reading", document) as session:
            session.send(b"\x1bs")
            session.send(b"\r")
            session.expect(lambda: "READ" in session.screen.text, "reading mode")
            session.mouse(65, 10, 15)
            session.mouse(65, 10, 15)
            session.mouse(67, 10, 15)
            before, caret = document_rows(session), status(session)
            assert "0006|" in before[0], session.screen.text
            for name, key in (
                ("cut", b"\x18"), ("paste", b"\x16"), ("undo", b"\x1a"),
                ("redo", b"\x19"), ("backspace", b"\x7f"), ("delete", b"\x1b[3~"),
                ("newline", b"\r"), ("tab", b"\t"), ("text", b"q"),
                ("bracketed-paste", b"\x1b[200~paste\x1b[201~"),
            ):
                session.send(key)
                assert document_rows(session) == before, (name, session.screen.text)
                assert status(session) == caret, (name, session.screen.text)
                assert "Reading mode: editing is disabled" in session.screen.text, (name, session.screen.text)
            session.send(b"\x1b[C")
            assert "0000|" in document_rows(session)[0], session.screen.text
            session.finish()
        assert document.read_text(encoding="utf-8") == original

    def focus(self):
        original = "\n".join(f"ROW{i:04d}|abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ" for i in range(80))
        document = self.fixture("focus", original)
        with self.session("focus", document) as session:
            session.mouse(0, 3, 15)
            session.send(b"\x06")
            session.send(b"needle")
            caret, field = status(session), field_line(session)
            session.mouse(32, 10, 40)
            assert status(session) == caret, session.screen.text
            assert field_line(session) == field, session.screen.text
            session.mouse(0, 10, 40, True)
            session.send(b"X")
            assert "Find: needleX" in session.screen.text, session.screen.text
            assert status(session) == caret, session.screen.text
            session.mouse(0, 4, 15)
            session.mouse(32, 6, 30)
            assert status(session) != caret, session.screen.text
            session.mouse(0, 6, 30, True)
            session.send(b"\x1b", 0.7)
            session.finish()
        assert document.read_text(encoding="utf-8") == original

    def field(self):
        for mono in (False, True):
            with self.session("field-mono" if mono else "field-color", mono=mono) as session:
                session.send(b"\x06")
                session.send(("0123456789" * 15).encode("utf-8"))
                session.send(b"\x1b[H")
                row, _ = field_line(session)
                session.mouse(0, row, 8)
                session.mouse(32, row, 79)
                session.mouse(0, row, 79, True)
                before = field_line(session)
                session.send(b"\x03")
                assert field_line(session) == before, session.screen.text
                session.send(b"\x1be")
                session.send(b"\x1b", 0.7)
                assert field_line(session) == before, session.screen.text
                session.send(b"\x1b", 0.7)
                session.finish()

    def resize(self):
        original = " ".join(f"T{i:04d}" for i in range(1200)) + "\n" + "\n".join("last line " + str(i) for i in range(40))
        document = self.fixture("resize", original)
        with self.session("resize", document) as session:
            session.send(b"\x1bOR")  # F3: View menu
            session.send(b"\x1b[B")
            session.send(b"\r")
            assert "Wrap lines enabled" in session.screen.text, session.screen.text
            for _ in range(4):
                session.mouse(65, 10, 20)

            def top_tokens():
                line = next(line for line in session.screen.lines[1:] if re.search(r"T\d{4}", line))
                return re.findall(r"T\d{4}", line)

            anchor = top_tokens()[0]
            for rows, cols in ((24, 120), (24, 48), (30, 100), (12, 80)):
                session.resize(rows, cols)
                assert anchor in top_tokens(), (anchor, session.screen.text)
                assert "Ln 1/" in session.screen.text and "Col 1/" in session.screen.text, session.screen.text
            session.finish()
        assert document.read_text(encoding="utf-8") == original

    def large_wrap(self):
        # Session caps the JVM heap at 192 MiB. Resizing must retain compact
        # row-count indices instead of every offscreen line's wrap-start list.
        original = "word\n" * 400_000
        document = self.fixture("large-wrap", original)
        with self.session("large-wrap", document) as session:
            session.send(b"\x1bOR\x1b[B\r")
            session.expect(lambda: "Wrap lines enabled" in session.screen.text, "enabled wrapping")
            for columns in (81, 82, 83, 84):
                session.resize(24, columns)
                session.expect(lambda: "Ln 1/400001" in session.screen.text, "large wrapped document after resize")
            session.finish()
        assert document.read_text(encoding="utf-8") == original

    def tabs(self):
        original = "\n".join(f"ROW{i:04d}|" + "abcdef " * 30 for i in range(100))
        document = self.fixture("tabs", original)
        with self.session("tabs", document) as session:
            for _ in range(3):
                session.mouse(65, 10, 15)
            before = document_rows(session)
            session.send(b"\x13")  # Save
            assert document_rows(session) == before, session.screen.text
            session.send(b"\x03")  # Copy
            assert document_rows(session) == before, session.screen.text
            session.send(b"\x0e")  # New tab
            assert "Tab 2/2" in session.screen.text, session.screen.text
            session.send(b"\x1b[27;5;9~")  # Ctrl+Tab (xterm modifyOtherKeys)
            session.expect(lambda: bool(re.search(r"ROW\d{4}\|", session.screen.text)), "return to document tab")
            assert document_rows(session)[0] == before[0], session.screen.text
            session.finish()
        assert document.read_text(encoding="utf-8") == original

    def history(self):
        document = self.fixture("history", "base\n")
        with self.session("history", document) as session:
            session.send("draft 中e\u0301".encode("utf-8"))
            session.send(b"\x1a")
            session.send(b"\x13")
            assert document.read_text(encoding="utf-8") == "base\n"
            session.send(b"\x19")
            session.send(b"\x13")
            assert document.read_text(encoding="utf-8") == "draft 中e\u0301base\n"
            session.finish()

    def grapheme_edit_caret(self):
        document = self.fixture("grapheme-search-edit", "e\u0301x")
        with self.session("grapheme-search-edit", document) as session:
            session.send(b"\x06e\r\x1b[27u")
            session.send(b"AB\x13")
            session.expect(lambda: document.read_text(encoding="utf-8") == "A\u0301Bx",
                           "typing after a partial grapheme search match")
            session.finish()
        document = self.fixture("grapheme-join-edit", "e\n\u0301x")
        with self.session("grapheme-join-edit", document) as session:
            session.send(b"\x1b[F\x1b[3~B\x13")
            session.expect(lambda: document.read_text(encoding="utf-8") == "e\u0301Bx",
                           "typing after joining a combining mark onto the previous line")
            session.finish()

    def selection_autoscroll(self):
        original = "\n".join(f"ROW{i:04d}|" + "abcdefgh " * 60 for i in range(200))
        document = self.fixture("selection-autoscroll", original)
        with self.session("selection-autoscroll", document) as session:
            session.mouse(0, 5, 15)
            session.mouse(32, 22, 15)
            first = document_rows(session)[0]
            session.expect(lambda: document_rows(session)[0] != first, "stationary vertical selection scroll")
            session.mouse(0, 22, 15, True)
            stopped = document_rows(session)
            session.drain(0.2)
            assert document_rows(session) == stopped, session.screen.text

            session.send(b"\x1b[1;5H")  # Ctrl+Home
            session.mouse(0, 5, 15)
            session.mouse(32, 5, 78)
            first = session.screen.lines[5]
            session.expect(lambda: session.screen.lines[5] != first, "stationary horizontal selection scroll")
            session.mouse(0, 5, 78, True)
            stopped = session.screen.lines[5]
            session.drain(0.2)
            assert session.screen.lines[5] == stopped, session.screen.text

            session.send(b"\x1b[1;5H")
            session.mouse(0, 5, 15)
            session.mouse(32, 22, 15)
            session.send(b"\x06")
            stopped = document_rows(session)
            session.drain(0.2)
            assert document_rows(session) == stopped, session.screen.text
            session.send(b"\x1b[27u")
            session.send(b"\x1b[1;5H")
            session.mouse(0, 5, 15)
            session.mouse(32, 22, 15)
            session.resize(25, 81)
            stopped = document_rows(session)
            session.drain(0.2)
            assert document_rows(session) == stopped, session.screen.text
            session.finish()
        assert document.read_text(encoding="utf-8") == original

    def long_copy_name(self):
        directory = self.root / "long-copy-name-files"
        directory.mkdir()
        document = directory / ("n" * 251 + ".txt")
        document.write_text("original", encoding="utf-8")
        document.chmod(0o444)
        occupied = directory / ("n" * 246 + "-copy.txt")
        occupied.write_text("occupied", encoding="utf-8")
        expected = directory / ("n" * 244 + "-copy-1.txt")
        with self.session("long-copy-name", document) as session:
            session.send(b"edited \x13")
            session.expect(lambda: "Save As:" in session.screen.text, "read-only file copy prompt")
            session.send(b"\r")
            session.expect(lambda: expected.is_file() and expected.read_text(encoding="utf-8") == "edited original",
                           "saving a copy of a maximum-length filename")
            assert len(os.fsencode(expected.name)) == 255
            session.finish()
        assert document.read_text(encoding="utf-8") == "original"
        assert occupied.read_text(encoding="utf-8") == "occupied"

    def fragmented_keys(self):
        for name, prefix, suffix, gap in (
            ("escape", b"\x1b", b"[C", 0.3),
            ("csi", b"\x1b[", b"C", 1.0),
            ("ss3", b"\x1bO", b"C", 1.0),
        ):
            document = self.fixture("fragmented-" + name, "AB")
            with self.session("fragmented-" + name, document) as session:
                # Complete an observable edit/undo before splitting input, so the
                # initial JVM startup cannot buffer both chunks before decoding.
                session.send(b"Z\x1a\x13")
                session.expect(lambda: "Saved" in session.screen.text, "ready input loop")
                session.send(prefix, gap)
                session.send(suffix + b"X\x13")
                session.expect(lambda: document.read_text(encoding="utf-8") == "AXB",
                               "fragmented arrow moves the cursor without inserting its bytes")
                session.finish()

        document = self.fixture("fragmented-search", "AB")
        with self.session("fragmented-search", document) as session:
            session.send(b"\x06needle\x1b[H")
            session.expect(lambda: "Find: needle" in session.screen.text, "search field before split key")
            for prefix, suffix, gap in ((b"\x1b", b"[C", 0.3), (b"\x1b[", b"C", 1.0)):
                session.send(prefix, gap)
                session.send(suffix)
                assert "Find: needle" in session.screen.text, "fragmented arrow closed search"
            session.send(b"X")
            session.expect(lambda: "Find: neXedle" in session.screen.text, "arrow stays inside search")
            assert document.read_text(encoding="utf-8") == "AB"
            session.send(b"\x1b", 0.7)
            session.expect(lambda: "Find: " not in session.screen.text, "standalone Escape closes search")
            session.finish()
        assert document.read_text(encoding="utf-8") == "AB"

        for name, opening in (
            ("document", b""), ("search", b"\x06needle"),
            ("save-as", b"\x0b"), ("menu", b"\x1bOP"),
        ):
            document = self.fixture("escape-before-arrow-" + name, "AB")
            with self.session("escape-before-arrow-" + name, document) as session:
                session.send(b"Z\x1a\x13")
                session.expect(lambda: "Saved" in session.screen.text, "ready input loop")
                if opening:
                    session.send(opening)
                session.send(b"\x1b", 0.1)
                session.send(b"\x1b[CX\x13")
                session.expect(lambda: document.read_text(encoding="utf-8") == "AXB",
                               "Escape dismisses the overlay before the complete arrow moves the document cursor")
                assert "Find: " not in session.screen.text, session.screen.text
                assert "Save As: " not in session.screen.text, session.screen.text
                session.finish()

        document = self.fixture("double-escape", "AB")
        with self.session("double-escape", document) as session:
            session.send(b"Z\x1a\x13")
            session.expect(lambda: "Saved" in session.screen.text, "ready input loop")
            session.send(b"\x06needle\x1bi")
            session.expect(lambda: "Find: needle" in session.screen.text and "New" in session.screen.lines[2],
                           "File menu above the search panel")
            session.send(b"\x1b", 0.1)
            session.send(b"\x1b", 0.7)
            session.expect(lambda: "Find: " not in session.screen.text,
                           "two Escape presses close the menu and then search")
            assert document.read_text(encoding="utf-8") == "AB"
            session.finish()

        for name, prefix in (("csi", b"\x1b["), ("ss3", b"\x1bO"),
                             ("overlong-csi", b"\x1b[" + b"1" * 70)):
            document = self.fixture("control-cancels-" + name, "AB")
            with self.session("control-cancels-" + name, document) as session:
                session.send(b"X\x13")
                session.expect(lambda: document.read_text(encoding="utf-8") == "XAB", "ready input loop")
                session.send(b"Y")
                session.send(prefix, 1.0)
                session.send(b"\x13")
                session.expect(lambda: document.read_text(encoding="utf-8") == "XYAB",
                               "Ctrl+S cancels an incomplete terminal sequence and saves")
                session.send(prefix, 1.0)
                session.finish()

    def navigation(self):
        original = "Ae\u0301😀\nsecond\nlast"
        document = self.fixture("navigation", original)
        with self.session("navigation", document) as session:
            session.send(b"\x1b[1;5F")  # Ctrl+End
            assert status(session).startswith("Ln 3/3, Col 5/"), session.screen.text
            session.send(b"\x1b[1;5H")  # Ctrl+Home
            assert status(session).startswith("Ln 1/3, Col 1/"), session.screen.text
            session.send(b"\x1b[1;6FX\x13")  # Ctrl+Shift+End
            assert document.read_text(encoding="utf-8") == "X", session.screen.text
            session.send(b"\x1a\x13")
            assert document.read_text(encoding="utf-8") == original

            session.send(b"\x1b[1;5F\x1b[1;2HZ\x13")  # End, Shift+Home
            assert document.read_text(encoding="utf-8") == "Ae\u0301😀\nsecond\nZ", session.screen.text
            session.send(b"\x1a\x13\x1b[1;5H\x1b[1;2FY\x13")  # Home, Shift+End
            assert document.read_text(encoding="utf-8") == "Y\nsecond\nlast", session.screen.text
            session.send(b"\x1a\x13\x1b[1;5F\x1b[1;6HW\x13")  # Ctrl+Shift+Home
            assert document.read_text(encoding="utf-8") == "W", session.screen.text
            session.send(b"\x1a\x13")
            assert document.read_text(encoding="utf-8") == original
            session.finish()

    def quit_confirmation(self):
        original = "base\n"
        document = self.fixture("quit-confirmation", original)
        with self.session("quit-confirmation", document) as session:
            session.send(b"draft ")
            recovery = session.home / ".config/tuink/recovery"
            session.expect(lambda: recovery_snapshot_present(recovery, "draft " + original), "autosaved draft")
            session.send(b"\x11")
            session.expect(lambda: "Unsaved (1)" in session.screen.text, "quit confirmation")
            session.send(b"\x11")
            session.expect(lambda: "Unsaved (1)" not in session.screen.text, "cancelled repeated Ctrl+Q")
            assert session.poll() is None, "repeated Ctrl+Q discarded the draft"
            assert document.read_text(encoding="utf-8") == original
            assert recovery_snapshot_present(recovery, "draft " + original)
            session.send(b"\x11")
            session.expect(lambda: "Unsaved (1)" in session.screen.text, "quit confirmation before saving")
            session.send(b"y")
            session.expect(lambda: session.poll() is not None, "Y saves before quitting", timeout=5)
            assert session.status == 0, session.screen.text
        assert document.read_text(encoding="utf-8") == "draft " + original
        assert not list(recovery.glob("*.meta.properties")), "saved quit left a recovery entry"

    def gutter_idle(self):
        # A large fixture gives the gutter's coalescing window enough room
        # between observing one completed diff and delivering the next edit.
        original = "\n".join(f"LINE{i:05d}| unchanged" for i in range(20_000))
        document = self.fixture("gutter-idle", original)
        with self.session("gutter-idle", document, mono=True) as session:
            def document_row(text):
                return next((line for line in session.screen.lines if text in line), "")

            # Start outside the initial diff window, then observe the exact
            # frame that first paints a modification marker. A short drain
            # avoids adding the ordinary expect/send polling delay here.
            session.drain(0.4)
            session.send(b"X", 0)
            deadline = time.monotonic() + 3
            while not document_row("XLINE00000|").startswith("~") and time.monotonic() < deadline:
                session.drain(0.001)
            assert document_row("XLINE00000|").startswith("~"), session.screen.text

            session.send(b"\x1b[BY", 0)
            session.expect(lambda: bool(document_row("LYINE00001|")), "the second rapid edit")
            # No input follows this edit: deferred diff work must schedule
            # its own frame instead of waiting for another key or mouse event.
            session.expect(lambda: document_row("LYINE00001|").startswith("~"),
                           "the deferred gutter marker while idle")
            session.snapshot("idle-modification-marker")

            session.send(b"\x1a", 0)
            session.expect(lambda: bool(document_row("LINE00001|")), "undo of the second edit")
            session.expect(lambda: not document_row("LINE00001|").startswith("~"),
                           "the undone gutter marker clearing while idle")
            assert document_row("XLINE00000|").startswith("~"), session.screen.text
            session.snapshot("idle-undo-marker-cleared")
            session.send(b"\x13")
            session.finish()
        assert document.read_text(encoding="utf-8") == "X" + original

    def paste(self):
        for name, original, inserted in (("open", "", "("), ("close", ")", ")")):
            document = self.fixture("paste-" + name, original)
            with self.session("paste-" + name, document) as session:
                session.send(b"\x1b[200~" + inserted.encode("utf-8") + b"\x1b[201~")
                session.send(b"\x13")
                assert document.read_text(encoding="utf-8") == inserted + original
                session.finish()
        document = self.fixture("paste-history", "")
        with self.session("paste-history", document) as session:
            session.send(b"a\x1b[200~PASTED\x1b[201~b")
            for expected in ("aPASTED", "a", ""):
                session.send(b"\x1a\x13")
                assert document.read_text(encoding="utf-8") == expected
            session.finish()

    def settings(self):
        document = self.fixture("settings", "abcdefghijklmnopqrstuvwxyz\nsecond line\n")
        original = document.read_bytes()
        home = self.root / "settings-home"
        path = home / ".config/tuink/tuink.properties"
        overrides = ("--read", "--no-mouse", "--theme=light")
        with self.session("settings-cli-only", document, home=home, options=overrides) as session:
            assert "READ" in session.screen.text
            session.send(b"X")
            assert "Reading mode: editing is disabled" in session.screen.text
            session.finish()
        assert not path.exists(), "temporary CLI options created saved preferences"

        def preferences():
            return dict(line.split("=", 1) for line in path.read_text().splitlines()
                        if line and not line.startswith("#"))

        with self.session("settings-unrelated", document, home=home, options=overrides) as session:
            session.send(b"\x1bOR\r")  # F3, first View item: Show Tab Bar
            assert "Tab bar hidden" in session.screen.text, session.screen.text
            session.finish()
        saved = preferences()
        assert saved["ui.showTabBar"] == "false", saved
        assert saved["ui.readingMode"] == "false", saved
        assert saved["ui.mouseMode"] == "true", saved
        assert saved["ui.theme"] == "cyan", saved

        with self.session("settings-explicit", document, home=home, options=overrides) as session:
            # Two explicit changes return to the CLI value, which is now a preference.
            session.send(b"\x1bs\r\x1bs\r")
            assert "Reading mode enabled" in session.screen.text, session.screen.text
            session.finish()
        saved = preferences()
        assert saved["ui.readingMode"] == "true", saved
        assert saved["ui.mouseMode"] == "true", saved
        assert saved["ui.theme"] == "cyan", saved
        unchanged = path.read_bytes()
        with self.session("settings-next-launch", document, home=home, options=()) as session:
            assert "READ" in session.screen.text, session.screen.text
            session.mouse(0, 2, 15)
            assert "Col 1/" not in status(session), session.screen.text
            session.finish()
        assert path.read_bytes() == unchanged, "unchanged preferences were rewritten"
        assert document.read_bytes() == original

        # Dotfile managers commonly symlink the final settings file. Menu
        # changes must update the target while retaining that link.
        linked_home = self.root / "settings-linked-home"
        linked_path = linked_home / ".config/tuink/tuink.properties"
        linked_path.parent.mkdir(parents=True)
        dotfile = self.root / "dotfiles/tuink.properties"
        dotfile.parent.mkdir()
        dotfile.write_bytes(path.read_bytes())
        linked_path.symlink_to(os.path.relpath(dotfile, linked_path.parent))
        with self.session("settings-linked", document, home=linked_home, options=()) as session:
            session.send(b"\x1bOR\r")
            session.expect(lambda: "Tab bar shown" in session.screen.text, "changed linked settings")
            session.finish()
        assert linked_path.is_symlink(), "saving preferences replaced the dotfile symlink"
        saved_linked = dict(line.split("=", 1) for line in dotfile.read_text().splitlines()
                            if line and not line.startswith("#"))
        assert saved_linked["ui.showTabBar"] == "true", saved_linked
        assert saved_linked["ui.readingMode"] == "true", saved_linked
        assert document.read_bytes() == original

    def settings_conflict(self):
        document = self.fixture("settings-conflict", "original\n")
        original = document.read_bytes()
        home = self.root / "settings-conflict-home"
        path = home / ".config/tuink/tuink.properties"

        # Generate the current complete schema through an ordinary menu change.
        # This also verifies that saving unchanged-on-disk preferences still works.
        with self.session("settings-conflict-seed", document, home=home, options=()) as session:
            session.send(b"\x1bOR\x1b[B\r")  # F3, Wrap Lines
            session.expect(lambda: "Wrap lines enabled" in session.screen.text, "changed initial preferences")
            session.finish()
        seeded = path.read_bytes()
        assert b"ui.wrapLines=true\n" in seeded, seeded
        assert b"ui.tabWidth=4\n" in seeded, seeded

        for route in ("regular", "symlink"):
            target = path
            session_home = home
            if route == "symlink":
                session_home = self.root / "settings-conflict-linked-home"
                target = session_home / ".config/tuink/tuink.properties"
                target.parent.mkdir(parents=True)
                dotfile = self.root / "settings-conflict-dotfiles/tuink.properties"
                dotfile.parent.mkdir()
                dotfile.write_bytes(seeded)
                target.symlink_to(os.path.relpath(dotfile, target.parent))
                link_contents = target.readlink()

            with self.session("settings-conflict-" + route, target, home=session_home, options=()) as session:
                baseline = target.read_bytes()
                assert baseline == seeded
                manual = baseline.replace(b"ui.tabWidth=4\n", b"ui.tabWidth=2\n", 1)
                assert manual != baseline, "manual edit did not change a semantic setting"
                manual += b"# manually edited in TUInk\n"
                session.send(b"\x01\x1b[200~" + manual + b"\x1b[201~")
                assert target.read_bytes() == baseline, "editing settings saved the document before Ctrl+S"

                session.send(b"\x1bOR\x1b[B\r")
                session.expect(lambda: "Wrap lines disabled" in session.screen.text, "changed in-memory preferences")
                session.send(b"\x13")
                session.expect(lambda: "Saved " in session.screen.text, "saved document during settings conflict")
                assert target.read_bytes() == manual, "Ctrl+S did not preserve the manual settings edit: " + route
                assert document.read_bytes() == original
                if route == "symlink":
                    assert target.is_symlink() and target.readlink() == link_contents

                session.finish()
                assert target.read_bytes() == manual, "normal quit overwrote manually saved settings: " + route
                session.drain(0.1)
                transcript = session.log.decode("utf-8", errors="replace").lower()
                assert "settings file changed" in transcript, session.screen.text
                assert "preferences" in transcript and "not saved" in transcript, session.screen.text

            assert target.read_bytes() == manual, "normal quit overwrote manually saved settings: " + route
            if route == "symlink":
                assert target.is_symlink() and target.readlink() == link_contents
                assert dotfile.read_bytes() == manual
        assert document.read_bytes() == original

    def recovery(self):
        original = "original\n"
        document = self.fixture("recovery", original)
        home = self.root / "recovery-home"
        text = "unsaved 中e\u0301 "
        with self.session("recovery-crash", document, home=home) as session:
            session.send(text.encode("utf-8"))
            directory = home / ".config/tuink/recovery"
            session.expect(lambda: recovery_snapshot_present(directory, text + original),
                           "autosaved draft before crash", timeout=5)
            session.crash()
        assert document.read_text(encoding="utf-8") == original
        for name, key in (("escape", b"\x1b"), ("quit", b"\x11"), ("no", b"n"), ("default", b"\r")):
            with self.session("recovery-later-" + name, document, home=home) as session:
                session.expect(lambda: "Restore now?" in session.screen.text, "recovery confirmation")
                session.send(key, 0.7)
                session.expect(lambda: "Restore now?" not in session.screen.text, "postponed recovery")
                assert session.poll() is None, "postponing recovery exited the editor"
                assert recovery_snapshot_present(directory, text + original), "postponing recovery lost the draft"
                assert document.read_text(encoding="utf-8") == original
                session.finish()
            assert recovery_snapshot_present(directory, text + original), "normal exit deleted a postponed draft"
        with self.session("recovery-later-owner", document, home=home) as owner:
            owner.expect(lambda: "Restore now?" in owner.screen.text, "recovery confirmation")
            owner.send(b"\x1b", 0.7)
            owner.expect(lambda: "Restore now?" not in owner.screen.text, "postponed recovery")
            with self.session("recovery-restore", document, home=home) as session:
                session.expect(lambda: "Restore now?" in session.screen.text, "recovery in a concurrent instance")
                session.send(b"y")
                session.send(b"\x13")
                assert document.read_text(encoding="utf-8") == text + original, session.screen.text
                session.finish()
            owner.finish()
        assert not list((home / ".config/tuink/recovery").glob("*.meta.properties")), "normal saved exit left recovery entries"

    def recovery_format5(self):
        original = b"\xef\xbb\xbforiginal\r\nlast"
        recovered = "draft 中e\u0301 original\nlast"
        document = self.root / "format5 #100% 中.txt"
        document.write_bytes(original)
        home = self.root / "format5-home"
        meta, payload = format5_snapshot(home, document, original, recovered)
        metadata_before, text_before = meta.read_bytes(), payload.read_bytes()
        with self.session("format5-later", home=home) as session:
            session.expect(lambda: "Recovery snapshots: 1." in session.screen.text, "release draft offered")
            session.send(b"\x1b", 0.7)
            session.expect(lambda: "Restore now?" not in session.screen.text, "release draft postponed")
            session.finish()
        assert meta.read_bytes() == metadata_before and payload.read_bytes() == text_before
        with self.session("format5-restore", home=home) as session:
            session.expect(lambda: "Recovery snapshots: 1." in session.screen.text, "release draft offered again")
            session.send(b"y")
            session.expect(lambda: "draft 中" in session.screen.text, "release draft restored")
            assert meta.read_bytes() == metadata_before and payload.read_bytes() == text_before
            assert document.read_bytes() == original
            session.send(b"X")
            session.expect(lambda: "format=8" in meta.read_text(encoding="ascii")
                           and recovery_snapshot_present(meta.parent, "X" + recovered),
                           "ordinary autosave upgrades the restored draft", timeout=5)
            session.send(b"\x13")
            expected = b"\xef\xbb\xbf" + ("X" + recovered).replace("\n", "\r\n").encode("utf-8")
            session.expect(lambda: document.read_bytes() == expected, "saved release draft preserves BOM and CRLF")
            session.finish()

        # The old display string cannot distinguish an invalid Unix byte from a
        # real U+FFFD filename. Recovery must never overwrite either neighbour.
        ambiguous = self.root / "format5-ambiguous-\ufffd.txt"
        raw = self.root / os.fsdecode(b"format5-ambiguous-\xff.txt")
        ambiguous.write_bytes(original)
        raw.write_bytes(original)
        detached_home = self.root / "format5-ambiguous-home"
        format5_snapshot(detached_home, ambiguous, original, recovered)
        copy = self.root / "format5-recovered-copy.txt"
        with self.session("format5-ambiguous", home=detached_home) as session:
            session.expect(lambda: "Recovery snapshots: 1." in session.screen.text, "ambiguous release draft offered")
            session.send(b"y\x13")
            session.expect(lambda: "Save As:" in session.screen.text, "ambiguous draft requires a new filename")
            assert ambiguous.read_bytes() == original and raw.read_bytes() == original
            session.send(b"\x01\x1b[200~" + str(copy).encode("utf-8") + b"\x1b[201~\r")
            expected = b"\xef\xbb\xbf" + recovered.replace("\n", "\r\n").encode("utf-8")
            session.expect(lambda: copy.is_file() and copy.read_bytes() == expected, "safe recovered copy saved")
            session.finish()
        assert ambiguous.read_bytes() == original and raw.read_bytes() == original

    def recovery_memory(self):
        document = self.fixture("recovery-memory", "explicitly opened document")
        home = self.root / "recovery-memory-home"
        directory = home / ".config/tuink/recovery"
        with self.session("recovery-memory-seed", document, home=home) as session:
            session.send(b"draft ")
            session.expect(lambda: recovery_snapshot_present(directory, "draft explicitly opened document"),
                           "committed recovery seed", timeout=5)
            session.crash()
        meta = next(directory.glob("*.meta.properties"))
        template = meta.read_text(encoding="iso-8859-1")
        generation = re.search(r"(?m)^textGeneration=([0-9a-f]{32})$", template)[1]
        old_text = meta.with_name(meta.name.removesuffix(".meta.properties") + "." + generation + ".txt")
        for index in range(4):
            # Keep the actual writer's schema and coordinates. Each enlarged text
            # is valid UTF-8 with the same single logical line as the seed.
            identity = f"large-draft-{index}"
            (directory / (identity + ".meta.properties")).write_text(template, encoding="iso-8859-1")
            with (directory / (identity + "." + generation + ".txt")).open("wb") as output:
                block = b"a" * 8192
                for _ in range(1280):
                    output.write(block)
        meta.unlink()
        old_text.unlink()

        def fingerprints():
            result = {}
            for path in sorted(directory.iterdir()):
                if path.suffix == ".txt" or path.name.endswith(".meta.properties"):
                    with path.open("rb") as source:
                        result[path.name] = hashlib.file_digest(source, "sha256").hexdigest()
            return result

        before = fingerprints()
        for restore in (False, True):
            with self.session("recovery-memory-restore" if restore else "recovery-memory-later",
                              document, home=home, max_heap_mb=64) as session:
                session.expect(lambda: "Recovery snapshots: 4." in session.screen.text, "all large drafts offered")
                if restore:
                    session.send(b"y")
                    session.expect(lambda: "Could not restore 4" in session.screen.text, "bounded restore error")
                    assert "Restore now?" in session.screen.text, session.screen.text
                session.send(b"\x1b", 0.7)
                session.expect(lambda: "Restore now?" not in session.screen.text, "postponed large drafts")
                assert "explicitly opened document" in session.screen.text, session.screen.text
                session.finish()
            assert fingerprints() == before, "failed or postponed recovery changed persisted drafts"
        assert document.read_text(encoding="utf-8") == "explicitly opened document"

    def linux_keys(self):
        original = "linux console input must preserve this document\n"
        document = self.fixture("linux-keys", original)
        with self.session("linux-keys", document, term="linux") as session:
            for suffix, menu_text in ((b"A", "Save As..."), (b"B", "Toggle Line Comment"),
                                      (b"C", "Wrap Lines"), (b"D", "Previous Tab"),
                                      (b"E", "Confirm on close")):
                session.send(b"\x1b[[" + suffix)
                session.expect(lambda: menu_text in session.screen.text, "Linux function-key menu " + str(suffix))
                session.send(b"\x1b", 0.7)
            session.send(b"\x06")
            session.send(b"needle")
            before = field_line(session)
            caret = status(session)
            session.send(b"\x1b\t")  # kcbt from the Linux console's terminfo.
            session.send(b"\x1b[C")
            assert field_line(session) == before, "Shift+Tab did not move focus out of the query field"
            assert status(session) != caret, "backwards traversal from Find must focus the document"
            session.send(b"\x1b", 0.7)
            session.send(b"\x13")
            session.finish()
        assert document.read_text(encoding="utf-8") == original, "function-key bytes leaked into document text"

    def enhanced_text(self):
        document = self.fixture("enhanced-text", "")
        expected = "A!Ж😀B?Ё𐐀"
        payload = b"".join(f"\x1b[{ord(ch)};2u".encode("ascii") for ch in expected[:4])
        payload += b"".join(f"\x1b[27;2;{ord(ch)}~".encode("ascii") for ch in expected[4:])
        with self.session("enhanced-text", document) as session:
            session.send(payload)
            session.expect(lambda: expected in session.screen.text, "Shift-only enhanced document input")
            session.send(b"\x13")
            session.send(b"\x06")
            session.send(payload)
            session.expect(lambda: "Find: " + expected in session.screen.text,
                           "Shift-only enhanced text-field input")
            # Command modifiers must not insert the printable character.
            before = field_line(session)
            session.send(b"\x1b[1046;6u\x1b[27;10;128512~")
            assert field_line(session) == before, session.screen.text
            session.send(b"\x1b", 0.7)
            session.finish()
        assert document.read_text(encoding="utf-8") == expected

    def long_unicode_edit(self):
        document = self.root / "long-unicode.txt"
        with document.open("wb") as output:
            for _ in range(11 * 128):
                output.write(b"a" * 8192)
            output.write("Ā".encode("utf-8"))
        before = hashlib.sha256(document.read_bytes()).hexdigest()
        with self.session("long-unicode-edit", document) as session:
            session.send(b"\x1b[C")
            session.send(b"b")
            session.send(b"\x1b[C")
            session.expect(lambda: "Col 4/" in status(session), "navigation after a Unicode-line edit")
            session.send(b"\x06")
            session.send(b"?")
            session.send(b"\t\t\t")
            session.send(b" ")
            session.expect(lambda: "*:x" in session.screen.text, "wildcard search enabled")
            session.send(b"\x06")
            session.expect(lambda: "Col 5/" in status(session), "wildcard match in a long Unicode line")
            session.send(b"\x1b", 0.7)
            session.send(b"\x1a")
            session.finish()
        assert hashlib.sha256(document.read_bytes()).hexdigest() == before

    def save_as_workspace(self):
        document = self.fixture("save-as-workspace", "original")
        target = self.root / "separate-draft.txt"
        with self.session("save-as-workspace", document) as session:
            session.send(b"\x0e")
            session.send(b"source draft")
            session.send(b"\x0b")
            session.send(b"\x01")
            session.send(str(document).encode("utf-8"))
            session.send(b"\r")
            session.expect(lambda: "already open in tab 1" in session.screen.text, "open-document conflict")
            assert "Save As: " in session.screen.text, session.screen.text
            assert document.read_text(encoding="utf-8") == "original"
            session.send(b"\x01")
            session.send(str(target).encode("utf-8"))
            session.send(b"\r")
            session.expect(lambda: target.is_file() and target.read_text(encoding="utf-8") == "source draft",
                           "saving the draft under a different name")
            session.send(b"\x1b[5;5~")
            session.send(b"\x13")
            session.expect(lambda: "Saved " in session.screen.text, "saving the original tab")
            assert document.read_text(encoding="utf-8") == "original"
            session.finish()

    def settings_memory(self):
        document = self.fixture("settings-memory", "original")
        home = self.root / "settings-memory-home"
        settings = home / ".config/tuink/tuink.properties"

        def toggle_reading(session):
            session.send(b"\x1bs")
            session.send(b"\r")

        with self.session("settings-memory-seed", document, home=home) as session:
            toggle_reading(session)
            session.finish()
        original_settings = settings.read_bytes()
        huge_size = 2 ** 31
        with self.session("settings-memory-growth", document, home=home, max_heap_mb=64) as session:
            toggle_reading(session)
            with settings.open("r+b") as output:
                output.truncate(huge_size)
            session.finish()
            assert "menu preferences from this session were not saved" in session.log.decode("utf-8", "replace")
        assert settings.stat().st_size == huge_size

        with self.session("settings-memory-startup", document, home=home, max_heap_mb=64) as session:
            assert "Maximum size is 64 KiB" in session.log.decode("utf-8", "replace")
            toggle_reading(session)
            session.finish()
        assert settings.stat().st_size == huge_size
        with settings.open("rb") as source:
            assert source.read(len(original_settings)) == original_settings
        assert document.read_text(encoding="utf-8") == "original"

    def syntax_memory(self):
        for size_mb, heap_mb in ((3, 64), (10, 192)):
            document = self.root / f"syntax-memory-{size_mb}.csv"
            with document.open("wb") as output:
                for _ in range(size_mb * 128):
                    output.write(b"1," * 4096)
            before = hashlib.sha256(document.read_bytes()).hexdigest()
            with self.session(f"syntax-memory-{size_mb}", document, max_heap_mb=heap_mb) as session:
                session.send(b"x")
                session.send(b"\x1a")
                session.send(b"\x1b[F")
                session.expect(lambda: f"Col {document.stat().st_size + 1}/" in status(session),
                               "end of a densely highlighted CSV line")
                session.resize(24, 120)
                session.send(b"\x1bOR")
                session.send(b"\x1b[B")
                session.send(b"\r")
                session.expect(lambda: "Wrap lines enabled" in session.screen.text,
                               "wrapping a densely highlighted CSV line")
                session.send(b"\x1b[H")
                session.resize(30, 60)
                session.finish()
            assert hashlib.sha256(document.read_bytes()).hexdigest() == before

    def unix_path_recovery(self):
        case = self.root / "unix-path-picker"
        case.mkdir()
        document = case / os.fsdecode(b"z-legacy-\xff.txt")
        neighbour = case / "z-legacy-\ufffd.txt"
        home = self.root / "unix-path-home"
        recovery = home / ".config/tuink/recovery"
        document.write_bytes(b"original")
        with Session(case, self.java, self.classpath, home=home) as session:
            session.send(b"\x0f")
            session.expect(lambda: "z-legacy-" in session.screen.text, "raw Unix filename in picker")
            session.send(b"\x1b[F")
            session.send(b"\r")
            session.expect(lambda: "original" in session.screen.text, "opening raw Unix filename")
            session.send(b"first ")
            session.send(b"\x13")
            session.expect(lambda: document.read_bytes() == b"first original", "saving raw Unix filename")
            assert not neighbour.exists(), "save created a different filename"
            session.send(b"\x0b")
            session.expect(lambda: "%FF" in session.screen.text, "lossless Save As filename")
            session.send(b"\r")
            session.expect(lambda: "Save As: " not in session.screen.text, "Save As keeps the original path")
            assert not neighbour.exists(), "Save As created a different filename"
            neighbour.write_bytes(b"first original")
            session.send(b"second ")
            session.expect(lambda: recovery_snapshot_present(recovery, "first second original"),
                           "recovery snapshot with raw Unix filename", timeout=5)
            session.crash()
        with self.session("unix-path-restore", home=home) as session:
            session.expect(lambda: "Restore now?" in session.screen.text, "raw-path recovery offered")
            session.send(b"y")
            session.expect(lambda: "first second original" in session.screen.text, "raw-path draft restored")
            session.send(b"\x13")
            session.expect(lambda: document.read_bytes() == b"first second original",
                           "recovered save uses the original path")
            assert neighbour.read_bytes() == b"first original", "recovered save overwrote a different file"
            session.finish()

    def literal_indentation(self):
        for name, original, expected in (
            ("payload.py", 'payload = """\nif ready:\ndone\n"""\n',
             'payload = """\nif ready:\n\ndone\n"""\n'),
            ("Main.java", "/*\n    prose {\n*/\n", "/*\n    prose {\n    \n*/\n"),
            ("values.yaml", "description: |\n  - literal text\n  next line",
             "description: |\n  - literal text\n  \n  next line"),
            ("folded.yml", "description: >\n  - literal text\n  next line",
             "description: >\n  - literal text\n  \n  next line"),
            ("quoted.yaml", 'description: "\n  - literal text\n  next line"',
             'description: "\n  - literal text\n  \n  next line"'),
        ):
            document = self.root / name
            document.write_text(original, encoding="utf-8")
            with self.session("literal-indentation-" + name, document) as session:
                session.send(b"\x1b[B\x1b[F\r")
                session.send(b"\x13")
                session.expect(lambda: document.read_text(encoding="utf-8") == expected,
                               "newline copies whitespace without syntax or list continuation inside a literal")
                session.send(b"\x1a\x13")
                session.expect(lambda: document.read_text(encoding="utf-8") == original,
                               "undo restores the original multiline content")
                session.finish()

    def field_memory(self):
        original = "a" * (2 * 1024 * 1024)
        document = self.fixture("field-memory", original)
        with self.session("field-memory", document, max_heap_mb=128) as session:
            for key in (b"\x01", b"\x03", b"\x06", b"\x16"):
                session.send(key)  # Copy the document, then paste into Find.
            session.expect(lambda: "aaaaaaaa" in field_line(session)[1], "large clipboard in Find")
            for key in (b"\x1b[H", b"\x1b[F"):
                session.send(key)
                assert "aaaaaaaa" in field_line(session)[1], session.screen.text
            session.send(b"\x01\x7f")
            row = next(index for index, line in enumerate(session.screen.lines) if line.startswith("Replace:"))
            session.mouse(0, row, 10)
            session.mouse(0, row, 10, True)
            session.send(b"\x16")
            session.expect(lambda: "aaaaaaaa" in session.screen.lines[row], "large clipboard in Replace")
            session.send(b"\x01\x7f\x1b", 0.7)
            session.send(b"\x0b\x01\x16")  # The same clipboard can enter Save As.
            session.expect(lambda: "Save As: " in session.screen.text and "aaaaaaaa" in session.screen.text,
                           "large clipboard in Save As")
            session.send(b"\x1b", 0.7)
            session.send(b"\x13")
            session.expect(lambda: "Saved " in session.screen.text, "saving after large field pastes")
            session.finish()
        assert document.read_text(encoding="utf-8") == original

    def document_caption(self):
        directory = self.root / ("long-project-" + "p" * 70) / ("nested-" + "n" * 40)
        directory.mkdir(parents=True)
        document = directory / ("very-long-document-" + "b" * 100 + ".txt")
        document.write_text("original", encoding="utf-8")
        with self.session("document-caption", document) as session:
            assert ".txt" in session.screen.lines[0], session.screen.text
            assert "*" not in session.screen.lines[0], session.screen.text
            session.send(b"draft ")
            for columns in (80, 60, 120):
                session.resize(24, columns)
                assert ".txt" in session.screen.lines[0], session.screen.text
                assert "*" in session.screen.lines[0], session.screen.text
            session.send(b"\x0e")
            session.send(b"\x1b[5;5~")
            session.expect(lambda: "Tab 1/2" in session.screen.text, "return to the dirty document")
            for columns in (80, 60, 120):
                session.resize(24, columns)
                assert ".txt" in session.screen.lines[1], session.screen.text
                assert "*" in session.screen.lines[1], session.screen.text
            session.send(b"\x13")
            session.expect(lambda: "*" not in session.screen.lines[0] + session.screen.lines[1],
                           "saving clears both document status markers")
            close_column = session.screen.lines[1].index(" x]") + 1
            session.mouse(0, 1, close_column)
            session.mouse(0, 1, close_column, True)
            session.expect(lambda: "--no name--" in session.screen.lines[0]
                           and "Tab " not in session.screen.lines[-1]
                           and status(session) == "Ln 1/1, Col 1/0",
                           "closing the shortened document tab")
            session.finish()
        assert document.read_text(encoding="utf-8") == "draft original"

    def save_name_suggestion(self):
        case = self.root / "save-name-suggestion"
        case.mkdir()
        reserved = case / "untitled.txt"
        existing = case / "untitled-1.txt"
        dangling = case / "untitled-2.txt"
        suggested = case / "untitled-3.txt"
        existing.write_text("existing", encoding="utf-8")
        dangling.symlink_to("missing-target")
        with Session(case, self.java, self.classpath, document=reserved) as session:
            session.send(b"\x0e")
            session.send(b"new draft")
            session.send(b"\x13")
            session.expect(lambda: "untitled-3.txt" in session.screen.text,
                           "suggestion skips a tab reservation, a file and a dangling symlink")
            session.send(b"\r")
            session.expect(lambda: suggested.is_file() and suggested.read_text(encoding="utf-8") == "new draft",
                           "accepting the automatically suggested filename")
            assert not reserved.exists(), "saving consumed another tab's reserved filename"
            assert existing.read_text(encoding="utf-8") == "existing"
            assert dangling.is_symlink()
            session.finish()

    def clipboard_memory(self):
        with self.session("clipboard-memory", max_heap_mb=64) as session:
            session.send(b"seed\x01\x03\x01")
            for _ in range(30):
                session.send(b"\x1b[200~" + b"a" * 1_048_575 + b"\n\x1b[201~", wait=0.12)
            session.send(b"\x01")
            before = status(session)
            for command in (b"\x03", b"\x18"):
                session.send(command)
                session.expect(lambda: "Not enough memory to copy this text; clipboard unchanged" in session.screen.text,
                               "clipboard admission error without leaving the editor")
                assert status(session) == before, session.screen.text
            session.snapshot("clipboard-refused")
            session.send(b"\x0e\x16")
            session.expect(lambda: status(session) == "Ln 1/1, Col 5/4"
                           and any(re.search(r"\b1 seed\s*$", line) for line in session.screen.lines),
                           "the previous clipboard is still usable after refused Copy and Cut")
            session.send(b"\x11")
            session.expect(lambda: "Unsaved (2)" in session.screen.text, "both drafts remain dirty")
            row = next(index for index, text in enumerate(session.screen.lines) if "[Discard" in text)
            column = session.screen.lines[row].index("[Discard") + 2
            session.mouse(0, row, column)
            session.expect(lambda: session.poll() is not None, "explicit discard and normal quit", timeout=5)
            assert session.status == 0

    def cpp_raw_indentation(self):
        for prefix in ("R", "u8R"):
            document = self.root / (prefix + ".cpp")
            document.write_text('auto text = ' + prefix + '\"tag(\n{\n)tag\";\n', encoding="utf-8")
            with self.session("cpp-raw-" + prefix, document) as session:
                session.send(b"\x1b[B\x1b[F\r\x13")
                expected = 'auto text = ' + prefix + '\"tag(\n{\n\n)tag\";\n'
                session.expect(lambda: document.read_text(encoding="utf-8") == expected,
                               "Enter inside a C++ raw string preserves its literal content")
                session.finish()

    def paste_memory(self):
        original = "saved original"
        draft = "UNSAVED IMPORTANT TEXT"
        document = self.fixture("paste-memory", original)
        with self.session("paste-memory", document, max_heap_mb=32) as session:
            # A literal paste is one undo step, independent of typing coalescing.
            session.send(b"\x01\x1b[200~" + draft.encode("ascii") + b"\x1b[201~\x01")
            session.send(b"\x1b[200~" + b"x\n" * 524288 + b"\x1b[201~")
            session.expect(lambda: "Replacement result is too large" in session.screen.text,
                           "rejecting an oversized paste without leaving the editor")
            session.send(b"\x13")
            session.expect(lambda: document.read_text(encoding="utf-8") == draft,
                           "the selected draft survives the rejected paste")
            session.send(b"\x1a\x13")
            session.expect(lambda: document.read_text(encoding="utf-8") == original,
                           "undo history survives the rejected paste")
            session.finish()

    def recovery_lock_cleanup(self):
        home = self.root / "lock-home"
        directory = home / ".config/tuink/recovery"
        directory.mkdir(parents=True)
        for index in range(2):
            with self.session("lock-clean-" + str(index), home=home) as session:
                session.finish()
            assert not [path for path in directory.glob("*.lock") if path.name != ".namespace.lock"], list(directory.iterdir())
        document = self.fixture("lock-dirty", "original")
        with self.session("lock-dirty", document, home=home) as session:
            session.send(b"draft ")
            session.expect(lambda: recovery_snapshot_present(directory, "draft original"),
                           "an actual background snapshot is committed", timeout=5)
            session.send(b"\x13")
            session.expect(lambda: document.read_text(encoding="utf-8") == "draft original", "saving the draft")
            session.finish()
        assert not [path for path in directory.glob("*.lock") if path.name != ".namespace.lock"], list(directory.iterdir())

    def xdg_profile(self):
        home = self.root / "xdg-home"
        config = self.root / "separate config"
        document = self.fixture("xdg-profile", "original")
        recovery = config / "tuink/recovery"
        with self.session("xdg-profile", document, home=home, config_home=config) as session:
            session.send(b"draft ")
            session.expect(lambda: recovery_snapshot_present(recovery, "draft original"),
                           "recovery uses the selected XDG profile", timeout=5)
            session.send(b"\x13")
            session.expect(lambda: document.read_text(encoding="utf-8") == "draft original", "saving the draft")
            session.send(b"\x1bOR\r")  # Explicitly change Show Tab Bar so preferences are saved.
            session.finish()
        assert (config / "tuink/tuink.properties").is_file()
        assert not (home / ".config").exists(), "the default profile was touched"

    def replacement_memory(self):
        original = "a" * 500_000
        document = self.fixture("replacement-memory", original)
        with self.session("replacement-memory", document, max_heap_mb=64) as session:
            session.send(b"\x06a")
            row = next(index for index, line in enumerate(session.screen.lines) if line.startswith("Replace:"))
            session.mouse(0, row, 10)
            session.mouse(0, row, 10, True)
            session.send(b"\x1b[200~" + b"b" * 64 + b"\x1b[201~")
            assert "bbbbbbbb" in session.screen.lines[row], session.screen.text
            column = session.screen.lines[row].index("Replace all") + 2
            session.mouse(0, row, column)
            session.mouse(0, row, column, True)
            session.expect(lambda: "Replacement result is too large" in session.screen.text,
                           "bounded rejection of an expanding replacement")
            session.snapshot("replacement-rejected")
            session.send(b"\x1b", 0.7)
            session.send(b"\x1a\x13")
            session.expect(lambda: "Saved " in session.screen.text, "saving after a rejected replacement")
            assert document.read_text(encoding="utf-8") == original
            session.send(b"z\x1a\x13")
            session.finish()
        assert document.read_text(encoding="utf-8") == original


SCENARIOS = ("reading", "focus", "field", "resize", "large-wrap", "tabs", "history", "grapheme-edit-caret", "selection-autoscroll", "long-copy-name", "navigation", "fragmented-keys", "quit-confirmation", "gutter-idle", "paste", "settings", "settings-conflict", "recovery", "recovery-format5", "recovery-memory", "linux-keys", "enhanced-text", "long-unicode-edit", "save-as-workspace", "settings-memory", "syntax-memory", "unix-path-recovery", "literal-indentation", "field-memory", "document-caption", "save-name-suggestion", "replacement-memory", "paste-memory", "recovery-lock-cleanup", "xdg-profile", "cli-path-identity", "save-as-retarget", "field-edit-caret", "field-memory-growth", "identical-replace-budget", "clipboard-memory", "cpp-raw-indentation")


def classpath_manifest(classpath):
    """Catch a concurrently rebuilt install layout rather than testing mixed jars."""
    paths = []
    for entry in classpath.split(os.pathsep):
        if entry.endswith("*"):
            paths.extend(sorted(Path(entry[:-1]).glob("*.jar")))
        else:
            paths.append(Path(entry))
    if not paths or any(not path.is_file() for path in paths):
        raise ValueError("classpath must contain built JARs; run :app:installDist first")
    return {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default=os.environ.get("JAVA_HOME", "") + "/bin/java" if os.environ.get("JAVA_HOME") else "java")
    parser.add_argument("--classpath", default=str(REPOSITORY_ROOT / "apps/tuink/launcher/build/install/tuink/lib/*"))
    parser.add_argument("--output-root", type=Path, default=REPOSITORY_ROOT / "build/ui-regression")
    parser.add_argument("scenarios", nargs="*", choices=SCENARIOS)
    args = parser.parse_args()
    java = shutil.which(args.java)
    if not java:
        parser.error(f"Java executable not found: {args.java}")
    args.classpath = os.pathsep.join(str(Path(entry).resolve()) for entry in args.classpath.split(os.pathsep))
    before = classpath_manifest(args.classpath)
    args.output_root.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix="run-", dir=args.output_root.resolve()))
    regression = Regressions(root, str(Path(java).resolve()), args.classpath)
    failed = []
    for name in args.scenarios or SCENARIOS:
        try:
            getattr(regression, name.replace("-", "_"))()
            print("PASS " + name, flush=True)
        except Exception:
            failed.append(name)
            traceback.print_exc()
    if before != classpath_manifest(args.classpath):
        failed.append("classpath-changed-during-run")
    (root / "result.json").write_text(json.dumps({"failed": failed, "scenarios": args.scenarios or SCENARIOS}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"failed": failed, "logs": str(root)}), flush=True)
    return bool(failed)


if __name__ == "__main__":
    sys.exit(main())
