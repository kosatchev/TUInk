"""Small screen oracle for TUInk's xterm output, not a general terminal emulator.

Only visible text/cursor geometry is observed. Unsupported control sequences are
recorded so a new presenter feature cannot silently invalidate the assertions.
"""

import codecs
import re
import unicodedata


class Screen:
    def __init__(self, rows=24, cols=80):
        self.decoder = codecs.getincrementaldecoder("utf-8")("strict")
        self.pending = ""
        self.unknown = set()
        self.autowrap = True
        self.resize(rows, cols)

    def resize(self, rows, cols):
        if rows < 1 or cols < 1:
            raise ValueError("screen dimensions must be positive")
        self.rows, self.cols = rows, cols
        self.cells = [[" "] * cols for _ in range(rows)]
        self.row = self.col = self.top = 0
        self.bottom = rows - 1
        self.wrap_pending = False

    @property
    def lines(self):
        return ["".join(row) for row in self.cells]

    @property
    def text(self):
        return "\n".join(self.lines)

    def feed(self, data):
        self.pending += self.decoder.decode(data)
        while self.pending:
            text = self.pending
            if text.startswith("\x1b["):
                match = re.match(r"\x1b\[([0-?]*)([ -/]*)([@-~])", text)
                if not match:
                    return
                self.pending = text[match.end():]
                self._csi(*match.groups())
                continue
            if text.startswith("\x1b]"):
                match = re.search(r"\x07|\x1b\\", text[2:])
                if not match:
                    return
                self.pending = text[2 + match.end():]
                continue
            if text[0] == "\x1b":
                if len(text) < 2 or (text[1] in "()" and len(text) < 3):
                    return
                count = 3 if text[1] in "()" else 2
                if text[1] not in "()=><":
                    self.unknown.add(text[:count])
                self.pending = text[count:]
                continue
            self.pending = text[1:]
            ch = text[0]
            if ch == "\r":
                self.col = 0
                self.wrap_pending = False
            elif ch == "\n":
                self._linefeed()
            elif ch == "\b":
                self.col = max(0, self.col - 1)
                self.wrap_pending = False
            elif ch == "\t":
                self.col = min(self.cols - 1, (self.col // 8 + 1) * 8)
                self.wrap_pending = False
            elif ord(ch) >= 32 and ord(ch) != 127:
                self._put(ch)

    def _linefeed(self):
        self.wrap_pending = False
        if self.row == self.bottom:
            self._scroll(1)
        else:
            self.row = min(self.rows - 1, self.row + 1)

    def _put(self, ch):
        if unicodedata.combining(ch) or ch in ("\ufe0e", "\ufe0f"):
            previous = self.col if self.wrap_pending else self.col - 1
            while previous > 0 and not self.cells[self.row][previous]:
                previous -= 1
            if previous >= 0:
                self.cells[self.row][previous] += ch
            return
        width = 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1
        if self.wrap_pending and self.autowrap:
            self.col = 0
            self._linefeed()
        if width == 2 and self.col + 1 >= self.cols:
            # TUInk replaces a clipped wide glyph; seeing one here is unsafe.
            self.unknown.add("wide glyph at final column")
            return
        # Overwriting either half of a wide cell clears its other half.
        if not self.cells[self.row][self.col] and self.col > 0:
            self.cells[self.row][self.col - 1] = " "
        if self.col + 1 < self.cols and not self.cells[self.row][self.col + 1]:
            self.cells[self.row][self.col + 1] = " "
        self.cells[self.row][self.col] = ch
        if width == 2:
            self.cells[self.row][self.col + 1] = ""
        self.wrap_pending = self.col + width >= self.cols
        self.col = min(self.cols - 1, self.col + width)

    def _scroll(self, count):
        height = self.bottom - self.top + 1
        amount = min(abs(count), height)
        region = self.cells[self.top:self.bottom + 1]
        blank = [[" "] * self.cols for _ in range(amount)]
        self.cells[self.top:self.bottom + 1] = (
            region[amount:] + blank if count > 0 else blank + region[:height - amount]
        )

    def _csi(self, raw, intermediate, command):
        if raw.startswith("?"):
            values = raw[1:].split(";")
            if command in "hl":
                if "7" in values:
                    self.autowrap = command == "h"
                if "1049" in values and command == "h":
                    self.resize(self.rows, self.cols)
                if set(values) <= {"1", "7", "12", "25", "1000", "1002", "1003", "1006", "1049", "2004"}:
                    return
            self.unknown.add((raw, intermediate, command))
            return
        # Cursor shape (SP q), SGR, terminal queries and standard mode toggles
        # affect no visible characters in the assertions below.
        if command in "mhlntcq":
            return
        values = [int(value or 0) for value in raw.split(";")] if raw else [0]
        count = values[0] or 1
        self.wrap_pending = False
        if command in "Hf":
            self.row = max(0, min(self.rows - 1, (values[0] or 1) - 1))
            self.col = max(0, min(self.cols - 1, (values[1] or 1) - 1 if len(values) > 1 else 0))
        elif command == "A":
            self.row = max(0, self.row - count)
        elif command == "B":
            self.row = min(self.rows - 1, self.row + count)
        elif command == "C":
            self.col = min(self.cols - 1, self.col + count)
        elif command == "D":
            self.col = max(0, self.col - count)
        elif command == "G":
            self.col = max(0, min(self.cols - 1, count - 1))
        elif command == "d":
            self.row = max(0, min(self.rows - 1, count - 1))
        elif command == "r":
            self.top = max(0, min(self.rows - 1, (values[0] or 1) - 1))
            self.bottom = min(self.rows - 1, (values[1] or self.rows) - 1) if len(values) > 1 else self.rows - 1
            if self.top > self.bottom:
                raise ValueError("invalid scroll region")
            self.row = self.col = 0
        elif command in "ST":
            self._scroll(count if command == "S" else -count)
        elif command == "J":
            mode = values[0]
            for row in range(self.rows):
                for col in range(self.cols):
                    if mode in (2, 3) or (mode == 0 and (row, col) >= (self.row, self.col)) or (mode == 1 and (row, col) <= (self.row, self.col)):
                        self.cells[row][col] = " "
        elif command == "K":
            mode = values[0]
            for col in range(self.cols):
                if mode == 2 or (mode == 0 and col >= self.col) or (mode == 1 and col <= self.col):
                    self.cells[self.row][col] = " "
        elif command == "P":
            count = min(count, self.cols - self.col)
            self.cells[self.row][self.col:] = self.cells[self.row][self.col + count:] + [" "] * count
        elif command == "@":
            self.cells[self.row][self.col:] = ([" "] * count + self.cells[self.row][self.col:])[:self.cols - self.col]
        else:
            self.unknown.add((raw, intermediate, command))
