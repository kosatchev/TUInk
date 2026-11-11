#!/usr/bin/env python3
"""Collect checked, bounded PGO training profiles from the real editor on POSIX."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import datetime as dt
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import pty
import selectors
import signal
import struct
import subprocess
import sys
import termios
import time
import tty
import unittest

sys.dont_write_bytecode = True

REPOSITORY_ROOT = next(parent for parent in Path(__file__).resolve().parents
                       if (parent / 'settings.gradle').is_file() and (parent / 'gradlew').is_file())
TERMINAL_ORACLE = REPOSITORY_ROOT / "verification/ui/vt.py"
_oracle_spec = importlib.util.spec_from_file_location("tuink_pgo_vt", TERMINAL_ORACLE)
_oracle = importlib.util.module_from_spec(_oracle_spec)
_oracle_spec.loader.exec_module(_oracle)
Screen = _oracle.Screen

LANGUAGES = ("java", "python", "yaml", "rust")
PROFILE_NAMES = (*LANGUAGES, "mouse")
EXTENSIONS = {"java": "java", "python": "py", "yaml": "yaml", "rust": "rs"}
ROWS, COLS = 42, 140
SESSION_TIMEOUT = 180.0
OPERATION_TIMEOUT = 30.0
EXIT_TIMEOUT = 45.0
MERGE_TIMEOUT = 120.0
CTRL_S, CTRL_Z, CTRL_Y = b"\x13", b"\x1a", b"\x19"
CTRL_F, CTRL_G = b"\x06", b"\x07"
CTRL_X, CTRL_V, CTRL_Q = b"\x18", b"\x16", b"\x11"
ESCAPE, HOME, PAGE_UP = b"\x1b[27u", b"\x1b[H", b"\x1b[5~"
DOWN, UP, SHIFT_DOWN = b"\x1b[B", b"\x1b[A", b"\x1b[1;2B"
SAVE_PROGRESS_LABELS = ("Checking save target", "Checking file changes", "Preparing save", "Finishing save")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass(frozen=True)
class Fixture:
    data: bytes
    query: str
    marker: str
    wrap: bool


def training_corpus(language: str, chunks: int = 1600) -> Fixture:
    if language not in LANGUAGES or chunks < 1:
        raise ValueError("Expected a supported language and positive corpus size")
    query = f"pgo_training_{language}"
    marker = f"PGO_CORPUS_{language.upper()}_READY"
    comment = "#" if language in ("python", "yaml") else "//"
    lines = [f"{comment} {marker}", f"{comment} Clipboard: λ café 中🙂",
             f"{comment} Third clipboard line"]
    if language == "java":
        lines.append("public final class TrainingCorpus {")
    elif language == "rust":
        lines.append("fn training_corpus() {")
    words = ("moss", "cedar", "rain", "brook")
    for index in range(chunks):
        word = words[(index * 7 + 271) % len(words)]
        padding = word * (240 + index % 71 if index % 17 == 0 else 4 + index % 9)
        text = f"{query} λ café 中🙂 {padding}"
        if language == "java":
            if index % 23 == 0:
                lines.extend((f'    static final String BLOCK_{index} = """',
                              f"        {text} # // quoted markers", '        """;'))
            else:
                lines.append(f'\tstatic final String VALUE_{index} = "{text}";')
        elif language == "python":
            if index % 19 == 0:
                lines.extend((f"value_{index} = '''", f"{text} # literal hash", "'''"))
            else:
                lines.append(f"value_{index} = '{text}' # row {index}")
        elif language == "yaml":
            if index % 13 == 0:
                lines.extend((f'row_{index}: "{text}', "  # still quoted", '  closing"'))
            else:
                lines.append(f"row_{index}: '{text}' # row {index}")
        else:
            if index % 29 == 0:
                lines.extend(("    /* outer /* nested */", f"       {text}", "     */"))
            if index % 31 == 0:
                lines.extend((f'    let raw_{index} = r###"{text}',
                              '       /* literal */ " raw quote #', '       raw continuation"###;'))
            elif index % 7 == 0:
                lines.append(f'\tlet raw_{index} = r##"{text} " literal"##;')
            else:
                lines.append(f'\tlet value_{index} = "{text}"; // row {index}')
    if language in ("java", "rust"):
        lines.append("}")
    return Fixture(("\n".join(lines) + "\n").encode("utf-8"), query, marker,
                   language in ("java", "yaml"))


def settings_text(*, mouse: bool, wrap: bool) -> str:
    values = {
        "format": "2", "ui.showTabBar": "true", "ui.wrapLines": str(wrap).lower(),
        "ui.wrapWords": "true", "ui.readingMode": "false", "ui.confirmOnClose": "true",
        "ui.mouseMode": str(mouse).lower(), "ui.rightClickClipboard": "false",
        "ui.autoReloadUnmodifiedFiles": "true",
        "ui.highlightActiveLineNumber": "true",
        "ui.markdownOpenBothTabs": "false", "ui.markdownOpenInPreview": "false",
        "ui.markdownCodeHighlighting": "false", "ui.markdownVisualCopy": "false",
        "ui.fileHistoryNotifications": "false",
        "ui.showDocumentPath": "false",
        "ui.smartHomeEnd": "true", "ui.autoIndent": "true", "ui.autoPairs": "true",
        "ui.autoList": "true", "ui.visibleWhitespace": "true", "ui.syntaxHighlighting": "true",
        "ui.rainbowBrackets": "true", "ui.autoSelectionHighlight": "true",
        "ui.gutterDiffMode": "myers", "ui.tabWidth": "4", "ui.tabInsertMode": "auto",
        "ui.theme": "cyan",
    }
    return "".join(f"{key}={value}\n" for key, value in values.items())


def bracketed_paste(text: str) -> bytes:
    return b"\x1b[200~" + text.encode("utf-8") + b"\x1b[201~"


def mouse_drag(index: int) -> bytes:
    return f"\x1b[<32;{10 + index % 100};{3 + index % 35}M".encode("ascii")


def require_normal_exit(status: int) -> None:
    if not os.WIFEXITED(status) or os.WEXITSTATUS(status) != 0:
        raise RuntimeError(f"Editor did not exit normally with code 0: {os.waitstatus_to_exitcode(status)}")


def require_profile(path: Path) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        raise RuntimeError(f"Normal editor exit did not create a nonempty profile: {path}")


class EditorSession:
    def __init__(self, command: list[str], trace: Path, config_home: Path | None = None):
        self.deadline = time.monotonic() + SESSION_TIMEOUT
        self.status = None
        self.tail = bytearray()
        self.screen = Screen(ROWS, COLS)
        self.saves_submitted = 0
        self.output_bytes = 0
        self.trace = trace.open("xb", buffering=65536)
        try:
            self.pid, self.master = pty.fork()
        except BaseException:
            self.trace.close()
            raise
        if self.pid == 0:
            try:
                fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLS, 0, 0))
                os.environ["TERM"] = "xterm-256color"
                if config_home is not None:
                    os.environ["XDG_CONFIG_HOME"] = str(config_home)
                os.execv(command[0], command)
            except BaseException as error:
                os.write(2, f"Cannot start editor: {error}\n".encode("utf-8", "replace"))
                os._exit(127)
        self.selector = None
        try:
            tty.setraw(self.master)
            os.set_blocking(self.master, False)
            self.selector = selectors.DefaultSelector()
            self.selector.register(self.master, selectors.EVENT_READ)
        except BaseException:
            try:
                os.kill(self.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            os.waitpid(self.pid, 0)
            if self.selector is not None:
                self.selector.close()
            os.close(self.master)
            self.trace.close()
            raise

    def poll(self):
        if self.status is None:
            pid, status = os.waitpid(self.pid, os.WNOHANG)
            if pid:
                self.status = status
        return self.status

    def drain(self, timeout: float = 0.0):
        for key, _ in self.selector.select(timeout):
            try:
                chunk = os.read(key.fd, 65536)
            except BlockingIOError:
                continue
            except OSError:
                self.selector.unregister(key.fd)
                continue
            if not chunk:
                self.selector.unregister(key.fd)
                continue
            self.trace.write(chunk)
            self.screen.feed(chunk)
            self.output_bytes += len(chunk)
            self.tail.extend(chunk)
            del self.tail[:-131072]

    def check_deadline(self, deadline: float, description: str):
        if time.monotonic() >= min(deadline, self.deadline):
            raise TimeoutError(f"Timed out during {description}")

    def send(self, data: bytes):
        deadline = time.monotonic() + OPERATION_TIMEOUT
        offset = 0
        while offset < len(data):
            self.check_deadline(deadline, "input delivery")
            if self.poll() is not None:
                raise RuntimeError("Editor exited before input delivery completed")
            try:
                offset += os.write(self.master, data[offset:])
            except BlockingIOError:
                self.drain(0.001)

    def wait_for(self, predicate, description: str):
        deadline = time.monotonic() + OPERATION_TIMEOUT
        while True:
            self.drain(0)
            if self.poll() is not None:
                raise RuntimeError(f"Editor exited before {description}")
            if predicate():
                return
            self.check_deadline(deadline, description)
            self.drain(0.001)

    def wait_for_text(self, text: bytes):
        self.wait_for(lambda: text in self.tail, f"terminal marker {text!r}")

    def wait_for_cursor(self, line: int, column: int, line_count: int):
        status = f"Ln {line}/{line_count}, Col {column}/"
        self.wait_for(lambda: status in self.screen.lines[-1], f"cursor status {status}")

    def wait_for_saved_bytes(self, document: Path, expected: bytes):
        def saved():
            if ("*" in self.screen.lines[0] or "Saved " not in self.screen.text
                    or any(label in self.screen.text for label in SAVE_PROGRESS_LABELS)
                    or "Save As: " in self.screen.text):
                return False
            try:
                return document.read_bytes() == expected
            except FileNotFoundError:
                return False
        self.wait_for(saved, "clean owner save acknowledgement and exact document bytes")

    def save(self, document: Path, expected: bytes, before_save: bytes, *, require_dirty: bool = True):
        if not require_dirty and (self.saves_submitted != 0 or "Saved " in self.screen.text):
            raise ValueError("An unchanged save is only allowed before the first Saved acknowledgement")
        self.send(before_save)
        if require_dirty:
            # Navigation/selection can clear the notice without changing text.
            # Only a dirty header acknowledges a mutation before its save.
            self.wait_for(lambda: "*" in self.screen.lines[0], "a dirty document before saving")
        else:
            self.wait_for(lambda: "Saved " not in self.screen.text, "the initial unsaved-notice boundary")
        self.send(CTRL_S)
        self.saves_submitted += 1
        self.wait_for_saved_bytes(document, expected)

    def finish(self):
        self.send(CTRL_Q)
        deadline = time.monotonic() + EXIT_TIMEOUT
        while self.poll() is None:
            self.check_deadline(deadline, "normal editor shutdown")
            self.drain(0.01)
        self.drain(0)
        require_normal_exit(self.status)

    def close(self):
        if self.poll() is None:
            try:
                os.kill(self.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            _, self.status = os.waitpid(self.pid, 0)
        self.drain(0)
        self.selector.close()
        os.close(self.master)
        self.trace.close()


def train_session(binary: Path, output: Path, name: str) -> dict:
    mouse = name == "mouse"
    language = "java" if mouse else name
    fixture = training_corpus(language)
    session_dir = output / name
    session_dir.mkdir()
    document = session_dir / f"TrainingCorpus.{EXTENSIONS[language]}"
    document.write_bytes(fixture.data)
    home = session_dir / "home"
    settings = home / ".config" / "tuink" / "tuink.properties"
    settings.parent.mkdir(parents=True)
    settings.write_text(settings_text(mouse=mouse, wrap=False if mouse else fixture.wrap), encoding="utf-8")
    profile = output / "profiles" / f"{name}.iprof"
    if profile.exists():
        raise FileExistsError(f"Refusing to reuse training profile {profile}")
    command = [str(binary), '-Xmx512m', f"-Duser.home={home}", f"-XX:ProfilesDumpFile={profile}"]
    if not mouse:
        command.append("--no-mouse")
    command.append(str(document))
    record = {
        "name": name, "language": language, "command": command,
        "fixture_sha256": sha256(fixture.data), "fixture_bytes": len(fixture.data),
        "fixture_lines": fixture.data.count(b"\n"), "settings_sha256": file_sha256(settings),
        "profile": str(profile), "steps": [], "status": "running",
    }
    record_path = session_dir / "session.json"

    def checkpoint():
        record_path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")

    checkpoint()
    child = None
    try:
        child = EditorSession(command, session_dir / "terminal.bin", home / ".config")
        child.wait_for_text(fixture.marker.encode("ascii"))

        def operation(label: str, data: bytes, expected: bytes | None = None, marker: bytes | None = None,
                      *, require_dirty: bool = True):
            child.tail.clear()
            if expected is not None:
                if not data.endswith(CTRL_S):
                    raise ValueError("A checked save operation must end with Ctrl+S")
                child.save(document, expected, data[:-len(CTRL_S)], require_dirty=require_dirty)
            else:
                child.send(data)
            if marker is not None:
                child.wait_for_text(marker)
            step = {"name": label}
            if expected is not None:
                step["saved_sha256"] = sha256(expected)
            record["steps"].append(step)
            checkpoint()

        def navigate(label: str, data: bytes, line: int, column: int, expected: bytes):
            child.tail.clear()
            child.send(data)
            child.wait_for_cursor(line, column, expected.count(b"\n") + 1)
            if document.read_bytes() != expected:
                raise AssertionError("Navigation changed the saved document bytes")
            record["steps"].append({"name": label, "unchanged_sha256": sha256(expected)})
            checkpoint()

        expected = fixture.data
        if mouse:
            child.wait_for_text(b"\x1b[?1002h")
            child.wait_for_text(b"\x1b[?1006h")
            child.tail.clear()
            child.send(b"\x1b[<0;10;3M")
            for index in range(600):
                child.send(mouse_drag(index))
                child.drain(0)
                time.sleep(0.002)
            operation("mouse_release_save", b"\x1b[<0;10;3m" + CTRL_S, expected, b"Saved ", require_dirty=False)
            record["mouse_drag_events"] = 600
        else:
            operation("navigate_select_return_save", DOWN * 48 + SHIFT_DOWN * 48 + UP * 96 + HOME + CTRL_S,
                      expected, b"Saved ", require_dirty=False)
            comment = "#" if language in ("python", "yaml") else "//"
            prefix = f"{comment} PGO edit λ e\u0301 中🧪\n{comment} Second pasted line\n"
            edited = prefix.encode("utf-8") + fixture.data
            for cycle in range(12):
                operation(f"paste_save_{cycle}", bracketed_paste(prefix) + CTRL_S, edited)
                operation(f"undo_save_{cycle}", CTRL_Z + CTRL_S, fixture.data)
                operation(f"redo_save_{cycle}", CTRL_Y + CTRL_S, edited)
                operation(f"undo_restore_save_{cycle}", CTRL_Z + CTRL_S, fixture.data)
            operation("search_open", CTRL_F, marker=b"Find:")
            operation("search_query", bracketed_paste(fixture.query), marker=fixture.query.encode("ascii"))
            operation("search_next", CTRL_F, marker=b"next match")
            operation("search_previous", CTRL_G, marker=b"previous match")
            replacement = f"replacement_{language}"
            occurrences = fixture.data.count(fixture.query.encode("ascii"))
            # Ctrl+A selects field text; activate Replace all through its button.
            operation("replace_all", b"\t" * 5 + bracketed_paste(replacement) + b"\t" * 4 + b"\r",
                      marker=f"Matched {occurrences} occurrence(s); replacement applied.".encode("ascii"))
            expected = fixture.data.replace(fixture.query.encode("ascii"), replacement.encode("ascii"))
            operation("save_replacements", ESCAPE + CTRL_S, expected)
            pages = (fixture.data.count(b"\n") + len(fixture.data) // (COLS - 10)) // (ROWS - 8) + 3
            navigate("return_to_start", PAGE_UP * pages + HOME, 1, 1, expected)
            cut_prefix = b"\n".join(expected.split(b"\n")[:3]) + b"\n"
            navigate("select_three_lines", SHIFT_DOWN * 3, 4, 1, expected)
            operation("cut_three_lines_save", CTRL_X + CTRL_S, expected[len(cut_prefix):])
            operation("paste_three_lines_save", CTRL_V + CTRL_S, expected)
        child.finish()
        if document.read_bytes() != expected:
            raise AssertionError("Final saved bytes differ from the expected document")
        require_profile(profile)
        record.update({"status": "complete", "normal_exit_code": 0, "final_sha256": sha256(expected),
                       "profile_bytes": profile.stat().st_size, "profile_sha256": file_sha256(profile)})
        return record
    except BaseException as error:
        record.update({"status": "failed", "failure": f"{type(error).__name__}: {error}"})
        raise
    finally:
        if child is not None:
            child.close()
            record["terminal_output_bytes"] = child.output_bytes
        checkpoint()


def prepare_output(directory: Path):
    if directory.is_symlink():
        raise ValueError("Training output directory must not be a symlink")
    directory.mkdir(parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise FileExistsError(f"Training output directory must be empty: {directory}")
    (directory / "profiles").mkdir()


def merge_profiles(sdk: Path, output: Path, sessions: list[dict]) -> dict:
    profiles = output / "profiles"
    expected = {f"{name}.iprof" for name in PROFILE_NAMES}
    if len(sessions) != len(PROFILE_NAMES) or {session["name"] for session in sessions} != set(PROFILE_NAMES):
        raise RuntimeError("Expected exactly the five completed training sessions before merging")
    for session in sessions:
        if session["status"] != "complete" or session.get("normal_exit_code") != 0:
            raise RuntimeError(f"Session {session['name']} did not complete with normal exit 0")
        profile = profiles / f"{session['name']}.iprof"
        require_profile(profile)
        if file_sha256(profile) != session["profile_sha256"]:
            raise RuntimeError(f"Profile changed after training: {profile}")
    if {path.name for path in profiles.iterdir()} != expected:
        raise RuntimeError("Expected exactly the five completed training profiles before merging")
    for name in expected:
        require_profile(profiles / name)
    merged = output / "profile.iprof"
    if merged.exists():
        raise FileExistsError(f"Refusing to overwrite merged profile {merged}")
    command = [str(sdk / "bin" / "native-image-configure"), "merge-pgo-profiles",
               f"--input-dir={profiles}", f"--output-file={merged}"]
    environment = dict(os.environ)
    environment["JAVA_TOOL_OPTIONS"] = "-Xmx512m -XX:ActiveProcessorCount=2"
    with (output / "merge.log").open("xb") as log:
        subprocess.run(command, env=environment, stdout=log, stderr=subprocess.STDOUT,
                       timeout=MERGE_TIMEOUT, check=True)
    require_profile(merged)
    return {"file": str(merged), "bytes": merged.stat().st_size, "sha256": file_sha256(merged),
            "command": command, "java_tool_options": environment["JAVA_TOOL_OPTIONS"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--graalvm-home", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        suite = unittest.defaultTestLoader.discover(str(Path(__file__).parent), pattern="test_pgo_training.py")
        if suite.countTestCases() == 0 or not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
            raise SystemExit(1)
        return
    if os.name != "posix":
        parser.error("PGO training requires a POSIX pseudo-terminal")
    if args.binary is None or args.output_dir is None or args.graalvm_home is None:
        parser.error("--binary, --output-dir, and --graalvm-home are required")
    binary, sdk = args.binary.resolve(), args.graalvm_home.resolve()
    for executable in (binary, sdk / "bin" / "native-image-configure"):
        if not executable.is_file() or not os.access(executable, os.X_OK):
            parser.error(f"Required executable is missing or not executable: {executable}")
    prepare_output(args.output_dir)
    output = args.output_dir.resolve()
    summary = {"schema": 1, "kind": "tuink-pgo-training", "status": "running",
               "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
               "binary": str(binary), "binary_sha256": file_sha256(binary),
               "harness_sha256": file_sha256(Path(__file__).resolve()),
               "terminal_oracle_sha256": file_sha256(TERMINAL_ORACLE),
               "graalvm_home": str(sdk), "rows": ROWS, "cols": COLS,
               "generated_chunks": 1600, "runtime_max_heap_mib": 512, "sessions": []}
    summary_path = output / "training-summary.json"

    def save_summary():
        summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    save_summary()
    try:
        for name in PROFILE_NAMES:
            print(f"Training {name}", flush=True)
            summary["sessions"].append(train_session(binary, output, name))
            save_summary()
        summary["merged_profile"] = merge_profiles(sdk, output, summary["sessions"])
        summary["status"] = "complete"
    except BaseException as error:
        summary.update({"status": "failed", "failure": f"{type(error).__name__}: {error}"})
        raise
    finally:
        save_summary()
    print(f"Merged profile: {output / 'profile.iprof'}", flush=True)
    print(f"Training summary: {summary_path}", flush=True)


if __name__ == "__main__":
    main()
