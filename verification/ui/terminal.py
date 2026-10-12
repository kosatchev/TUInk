"""Bounded, isolated POSIX PTY sessions used by the UI regression gate."""

import errno
import fcntl
import os
from pathlib import Path
import pty
import select
import signal
import struct
import termios
import time

from vt import Screen


class Session:
    def __init__(self, root, java, classpath, document=None, home=None, mono=False):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        home = Path(home) if home else self.root / "home"
        home.mkdir(parents=True, exist_ok=True)
        self.home = home
        self.screen = Screen()
        self.log = bytearray()
        self.status = None
        self.closed = False
        self.deadline = time.monotonic() + 45
        args = [str(java), "--enable-native-access=ALL-UNNAMED", "-Xmx192m",
                f"-Duser.home={home}", "-cp", classpath, "dev.tuink.app.App", "--theme=cyan"]
        if mono:
            args.append("--no-color")
        if document:
            args.append(str(document))
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            try:
                fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
                os.chdir(self.root)
                env = dict(os.environ, TERM="xterm-256color", HOME=str(home),
                           XDG_CONFIG_HOME=str(home / ".config"), XDG_DATA_HOME=str(home / ".local/share"),
                           XDG_CACHE_HOME=str(home / ".cache"))
                # Noninteractive CI shells may inherit SIGINT as ignored.
                # The editor must install its own terminal signal handlers.
                for signum in (signal.SIGINT, signal.SIGQUIT, signal.SIGTERM):
                    signal.signal(signum, signal.SIG_DFL)
                os.execve(str(java), args, env)
            except BaseException:
                os._exit(127)
        try:
            self.expect(lambda: "File" in self.screen.lines[0] and "Ln " in self.screen.text,
                        "editor startup", timeout=10)
        except BaseException:
            self.close()
            raise

    def poll(self):
        if self.status is None:
            done, status = os.waitpid(self.pid, os.WNOHANG)
            if done:
                self.status = os.waitstatus_to_exitcode(status)
        return self.status

    def drain(self, seconds=0.18):
        end = min(self.deadline, time.monotonic() + seconds)
        if time.monotonic() >= self.deadline:
            raise TimeoutError("scenario exceeded 45 seconds")
        while time.monotonic() < end:
            if not select.select([self.fd], [], [], min(0.025, max(0, end - time.monotonic())))[0]:
                continue
            try:
                data = os.read(self.fd, 65536)
            except OSError as error:
                if error.errno == errno.EIO:
                    break
                raise
            if not data:
                break
            self.log.extend(data)
            self.screen.feed(data)

    def expect(self, predicate, description, timeout=3):
        end = min(self.deadline, time.monotonic() + timeout)
        while time.monotonic() < end:
            if predicate():
                return
            self.drain(0.05)
            if self.poll() is not None:
                if predicate():
                    return
                break
        raise AssertionError(f"Timed out waiting for {description}\n{self.screen.text}")

    def send(self, data, wait=0.18):
        if self.poll() is not None:
            raise AssertionError(f"editor exited unexpectedly: {self.status}")
        os.write(self.fd, data)
        self.drain(wait)

    def mouse(self, code, row, col, release=False):
        self.send(f'\x1b[<{code};{col + 1};{row + 1}{"m" if release else "M"}'.encode("ascii"))

    def resize(self, rows, cols):
        self.screen.resize(rows, cols)
        fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        os.kill(self.pid, signal.SIGWINCH)
        self.drain(0.3)

    def snapshot(self, name):
        (self.root / f"{name}.txt").write_text(self.screen.text, encoding="utf-8")

    def finish(self):
        self.send(b"\x11")
        self.expect(lambda: self.poll() is not None, "normal Ctrl+Q exit", timeout=5)
        if self.status != 0:
            raise AssertionError(f"normal quit exited {self.status}")

    def crash(self):
        os.kill(self.pid, signal.SIGKILL)
        self.expect(lambda: self.poll() is not None, "forced crash exit", timeout=5)
        if self.status != -signal.SIGKILL:
            raise AssertionError(f"forced crash exit status {self.status}")

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            (self.root / "terminal.log").write_bytes(self.log)
            self.snapshot("final-screen")
        finally:
            try:
                if self.poll() is None:
                    os.kill(self.pid, signal.SIGTERM)
                    end = time.monotonic() + 1
                    while self.poll() is None and time.monotonic() < end:
                        time.sleep(0.02)
                    if self.poll() is None:
                        os.kill(self.pid, signal.SIGKILL)
                        _, status = os.waitpid(self.pid, 0)
                        self.status = os.waitstatus_to_exitcode(status)
            finally:
                os.close(self.fd)

    def __enter__(self):
        return self

    def __exit__(self, kind, value, traceback):
        self.close()
        if kind is None and self.screen.unknown:
            raise AssertionError(f"unsupported terminal output: {self.screen.unknown!r}")
