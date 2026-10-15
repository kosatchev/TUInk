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


class Regressions:
    def __init__(self, root, java, classpath):
        self.root, self.java, self.classpath = root, java, classpath

    def session(self, name, document=None, **kwargs):
        return Session(self.root / name, self.java, self.classpath, document, **kwargs)

    def fixture(self, name, text):
        path = self.root / (name + ".txt")
        path.write_text(text, encoding="utf-8")
        return path

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
            session.send(b"\x1b", 0.4)
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
                session.send(b"\x1b", 0.4)
                assert field_line(session) == before, session.screen.text
                session.send(b"\x1b", 0.4)
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
        with self.session("recovery-restore", document, home=home) as session:
            session.expect(lambda: "Recovery snapshots found:" in session.screen.text, "recovery confirmation")
            session.send(b"y")
            session.send(b"\x13")
            assert document.read_text(encoding="utf-8") == text + original, session.screen.text
            session.finish()
        assert not list((home / ".config/tuink/recovery").glob("*.meta.properties")), "normal saved exit left recovery entries"


SCENARIOS = ("reading", "focus", "field", "resize", "tabs", "history", "gutter-idle", "paste", "recovery")


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
