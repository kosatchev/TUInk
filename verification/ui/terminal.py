"""Bounded, isolated POSIX PTY sessions used by the UI regression gate."""

import errno
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import signal
import struct
import termios
import time

from vt import Screen

SAVE_PROGRESS_LABELS = ("Checking save target", "Checking file changes", "Preparing save", "Finishing save")


def measured_pty_fork(baud):
    """Retain the slave so exit cannot destroy the terminal being compared."""
    master, slave = os.openpty()
    try:
        attributes = termios.tcgetattr(slave)
        attributes[4] = attributes[5] = baud
        termios.tcsetattr(slave, termios.TCSANOW, attributes)
        before = termios.tcgetattr(slave)
        pid = os.fork()
    except BaseException:
        os.close(master)
        os.close(slave)
        raise
    if pid == 0:
        try:
            os.close(master)
            os.setsid()
            fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
            for target in (0, 1, 2):
                os.dup2(slave, target)
            if slave > 2:
                os.close(slave)
        except BaseException:
            os._exit(127)
        return 0, -1, None, None
    return pid, master, slave, before


class Session:
    def __init__(self, root, java, classpath, document=None, home=None, mono=False, options=None,
                 term="xterm-256color", max_heap_mb=192, config_home=None, timeout=45,
                 terminal_baud=None, jline_provider=None, wait_for_startup=True,
                 env_overrides=None, jvm_properties=None, native_executable=None):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        home = Path(home) if home else self.root / "home"
        home.mkdir(parents=True, exist_ok=True)
        self.home = home
        self.config_home = Path(config_home) if config_home is not None else home / ".config"
        self.screen = Screen()
        self.log = bytearray()
        self.status = None
        self.closed = False
        self.timeout = timeout
        self.deadline = time.monotonic() + timeout
        self.terminal_slave_fd = None
        self.original_terminal_attributes = None
        args = [str(java), "--enable-native-access=ALL-UNNAMED", f"-Xmx{max_heap_mb}m",
                f"-Duser.home={home}"]
        if jline_provider is not None:
            args.append(f"-Dorg.jline.terminal.provider={jline_provider}")
        for key, value in (jvm_properties or {}).items():
            args.append(f"-D{key}={value}")
        args.extend(("-cp", classpath, "dev.tuink.app.App"))
        executable = str(java)
        if native_executable is not None:
            executable = str(Path(native_executable).resolve())
            args = [executable]
        args.extend(("--theme=cyan",) if options is None else options)
        if mono:
            args.append("--no-color")
        if document:
            args.append(str(document))
        if terminal_baud is None:
            self.pid, self.fd = pty.fork()
        else:
            self.pid, self.fd, self.terminal_slave_fd, self.original_terminal_attributes = measured_pty_fork(
                terminal_baud)
        if self.pid == 0:
            try:
                fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
                os.chdir(self.root)
                env = dict(os.environ, TERM=term, HOME=str(home),
                           XDG_CONFIG_HOME=str(self.config_home), XDG_DATA_HOME=str(home / ".local/share"),
                           XDG_CACHE_HOME=str(home / ".cache"))
                env.update(env_overrides or {})
                # Noninteractive CI shells may inherit SIGINT as ignored.
                # The editor must install its own terminal signal handlers.
                for signum in (signal.SIGINT, signal.SIGQUIT, signal.SIGTERM):
                    signal.signal(signum, signal.SIG_DFL)
                os.execve(executable, args, env)
            except BaseException:
                os._exit(127)
        if not wait_for_startup:
            return
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
            raise TimeoutError(f"scenario exceeded {self.timeout:g} seconds")
        while time.monotonic() < end:
            if not select.select([self.fd], [], [], min(0.025, max(0, end - time.monotonic())))[0]:
                continue
            if not self._read_output():
                break

    def _read_output(self):
        try:
            data = os.read(self.fd, 65536)
        except BlockingIOError:
            return True
        except OSError as error:
            if error.errno == errno.EIO:
                return False
            raise
        if not data:
            return False
        self.log.extend(data)
        self.screen.feed(data)
        return True

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
        self._write_input(data)
        self.drain(wait)

    def _write_input(self, data):
        # A large blocking write can fill the input queue while the child is
        # blocked redrawing into its full output queue. Service both directions
        # until every input byte is accepted, within the same scenario deadline.
        remaining = memoryview(data)
        blocking = os.get_blocking(self.fd)
        os.set_blocking(self.fd, False)
        try:
            while remaining:
                if self.poll() is not None:
                    raise AssertionError(f"editor exited unexpectedly: {self.status}")
                available = self.deadline - time.monotonic()
                if available <= 0:
                    raise TimeoutError(f"scenario exceeded {self.timeout:g} seconds")
                readable, writable, _ = select.select([self.fd], [self.fd], [], min(0.025, available))
                if readable:
                    self._read_output()
                if writable:
                    try:
                        written = os.write(self.fd, remaining[:65536])
                    except (BlockingIOError, InterruptedError):
                        continue
                    if written == 0:
                        raise OSError(errno.EIO, "PTY accepted no input")
                    remaining = remaining[written:]
        finally:
            os.set_blocking(self.fd, blocking)

    def save(self, document, expected, description="completed save", timeout=3):
        """Wait for the UI owner to acknowledge a save before sending more edits.

        Atomic file publication happens on a worker before the UI unlocks the
        document. A previous Saved notice is not an acknowledgement of this
        request: first observe its dirty frame or the old notice disappearing.
        Callers send their editing keys separately before entering this barrier.
        """
        self.expect(lambda: "*" in self.screen.lines[0] or "Saved " not in self.screen.text,
                    "a fresh save acknowledgement boundary", timeout=max(timeout, 5))
        self.send(b"\x13")
        self.expect_saved(document, expected, description, timeout=timeout)

    def expect_saved(self, document, expected, description="completed save", timeout=3):
        expected_bytes = expected.encode("utf-8") if isinstance(expected, str) else expected

        def completed():
            # Both acknowledgements matter: bytes alone precede completion,
            # while a stale notice can survive the following dirty frame.
            if ("Saved " not in self.screen.text
                    or any(label in self.screen.text for label in SAVE_PROGRESS_LABELS)
                    or "*" in self.screen.lines[0] or "Save As: " in self.screen.text):
                return False
            try:
                return Path(document).read_bytes() == expected_bytes
            except FileNotFoundError:
                return False

        self.expect(completed, description, timeout=timeout)

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

    def expect_terminal_restored(self):
        """Compare every termios field with its measured pre-exec value."""
        if self.terminal_slave_fd is None:
            raise RuntimeError("terminal_baud is required to retain the measured terminal")
        if self.poll() is None:
            raise AssertionError("terminal attributes must be checked after the child exits")
        restored = termios.tcgetattr(self.terminal_slave_fd)
        before = self.original_terminal_attributes
        (self.root / "terminal-attributes.json").write_text(json.dumps(
            {"before": before, "after": restored},
            default=lambda value: value.hex(), indent=2) + "\n", encoding="utf-8")
        names = ("iflag", "oflag", "cflag", "lflag", "ispeed", "ospeed", "cc")
        changed = [f"{name}: {old!r} -> {new!r}"
                   for name, old, new in zip(names, before, restored) if old != new]
        if changed:
            raise AssertionError("Terminal attributes were not restored: " + "; ".join(changed))

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
                try:
                    os.close(self.fd)
                finally:
                    if getattr(self, "terminal_slave_fd", None) is not None:
                        os.close(self.terminal_slave_fd)
                        self.terminal_slave_fd = None

    def __enter__(self):
        return self

    def __exit__(self, kind, value, traceback):
        self.close()
        if kind is None and self.screen.unknown:
            raise AssertionError(f"unsupported terminal output: {self.screen.unknown!r}")
