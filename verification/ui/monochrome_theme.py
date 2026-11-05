#!/usr/bin/env python3
"""Check Monochrome's neutral terminal output, active preferences and theme round trip in a private PTY."""
import argparse
from pathlib import Path
import re
import tempfile
from terminal import Session
from markdown_preview import preview_selected, source_selected

F3, F5 = b'\x1bOR', b'\x1b[15~'
DOCUMENT = ('# Monochrome probe\n\nneedle (pair) [array]\n\n> quote marker\n\n```python\n'
            'def greet(value):\n    return value + 7\n```\n\n$$\n\\frac{a}{b}\n$$\n\n'
            '```mermaid\nflowchart LR\nA[BEGIN] --> B[END]\n```\n')


def _extended_color(mode, channels, raw):
    if mode == 5 and len(channels) == 1 and 0 <= channels[0] <= 255:
        return ('indexed', channels[0])
    if mode == 2 and len(channels) == 3 and all(0 <= value <= 255 for value in channels):
        return ('rgb', *channels)
    raise AssertionError('Unsupported extended SGR color: ' + raw.decode())


def _sgr_operations(raw):
    """Decode color arguments as one operation, including colon RGB color-space fields."""
    parameters = raw.split(b';')
    index = 0
    while index < len(parameters):
        parameter = parameters[index]
        if b':' in parameter:
            fields = parameter.split(b':')
            code = int(fields[0] or 0)
            if code in (38, 48, 58):
                if len(fields) < 3:
                    raise AssertionError('Unsupported extended SGR color: ' + raw.decode())
                mode = int(fields[1] or 0)
                channels = fields[2:]
                if mode == 2 and len(channels) == 4:
                    channels = channels[1:]  # Optional color-space ID, commonly empty or zero.
                if any(not value for value in channels):
                    raise AssertionError('Unsupported extended SGR color: ' + raw.decode())
                yield code, _extended_color(mode, [int(value) for value in channels], raw)
            else:
                yield code, None
        else:
            code = int(parameter or 0)
            color = None
            if code in (38, 48, 58):
                if index + 1 >= len(parameters):
                    raise AssertionError('Unsupported extended SGR color: ' + raw.decode())
                mode = int(parameters[index + 1] or 0)
                count = 1 if mode == 5 else 3 if mode == 2 else 0
                if count == 0 or index + 1 + count >= len(parameters):
                    raise AssertionError('Unsupported extended SGR color: ' + raw.decode())
                channels = [int(value or 0) for value in parameters[index + 2:index + 2 + count]]
                color = _extended_color(mode, channels, raw)
                index += count + 1
            yield code, color
        index += 1


def _neutral_color(color):
    if color[0] == 'ansi':
        return color[1] in (0, 7, 8, 15)
    if color[0] == 'rgb':
        return color[1] == color[2] == color[3]
    index = color[1]
    if index in (0, 7, 8, 15) or 232 <= index <= 255:
        return True
    if 16 <= index < 232:
        cube = index - 16
        return cube // 36 == cube // 6 % 6 == cube % 6
    return False


def _basic_color(code):
    if 30 <= code <= 37:
        return ('ansi', code - 30)
    if 40 <= code <= 47:
        return ('ansi', code - 40)
    if 90 <= code <= 97:
        return ('ansi', code - 90 + 8)
    if 100 <= code <= 107:
        return ('ansi', code - 100 + 8)
    return None


def chromatic_sgr(data):
    """Keep this diagnostic helper public: report every explicitly chromatic SGR color."""
    found = []
    for raw in re.findall(rb'\x1b\[([0-9;:]*)m', data):
        for code, extended in _sgr_operations(raw):
            color = extended if extended is not None else _basic_color(code)
            if color is not None and not _neutral_color(color):
                found.append(raw.decode())
    return found


def _sgr_states(data):
    """Yield the effective foreground, background and bold state after each whole command."""
    foreground, background, bold = None, None, False
    for raw in re.findall(rb'\x1b\[([0-9;:]*)m', data):
        for code, extended in _sgr_operations(raw):
            if code == 0:
                foreground, background, bold = None, None, False
            elif code == 1:
                bold = True
            elif code == 22:
                bold = False
            elif code == 39:
                foreground = None
            elif code == 49:
                background = None
            elif code == 38:
                foreground = extended
            elif code == 48:
                background = extended
            elif 30 <= code <= 37 or 90 <= code <= 97:
                foreground = _basic_color(code)
            elif 40 <= code <= 47 or 100 <= code <= 107:
                background = _basic_color(code)
        yield raw.decode(), foreground, background, bold


def default_bold_sgr(data):
    """Default foreground plus bold may become a configured bright color in real terminals."""
    return [raw for raw, foreground, _, bold in _sgr_states(data) if bold and foreground is None]


def gray_code_bold_sgr(data):
    """Recognize the code keyword palette in ANSI16, indexed and truecolor terminals."""
    foregrounds = {('ansi', 15), ('indexed', 253), ('rgb', 218, 218, 218)}
    backgrounds = {('ansi', 0), ('indexed', 236), ('rgb', 48, 48, 48)}
    return [raw for raw, foreground, background, bold in _sgr_states(data)
            if bold and foreground in foregrounds and background in backgrounds]


def assert_monochrome_output(data, stage):
    chromatic = chromatic_sgr(data)
    if chromatic:
        raise AssertionError(stage + ' chromatic SGR: ' + repr(chromatic[:10]))
    default_bold = default_bold_sgr(data)
    if default_bold:
        raise AssertionError(stage + ' DEFAULT foreground with bold: ' + repr(default_bold[:10]))
    if not gray_code_bold_sgr(data):
        raise AssertionError(stage + ' did not emit an explicit gray bold keyword on the code background')


def locate(session, label):
    session.expect(lambda: label in session.screen.text, label)
    return next((row, line.index(label)) for row, line in enumerate(session.screen.lines) if label in line)


def click(session, label):
    row, column = locate(session, label)
    session.mouse(0, row, column + 2)
    session.mouse(0, row, column + 2, release=True)


def checkbox(session, label):
    for row, line in enumerate(session.screen.lines):
        if label in line and ('[x]' in line or '[ ]' in line):
            return row, line.index(label), '[x]' in line
    return None


def toggle_checkbox(session, menu, label, before):
    session.send(menu)
    session.expect(lambda: checkbox(session, label) is not None, label + ' checkbox')
    if checkbox(session, label)[2] != before:
        raise AssertionError('Unexpected initial checkbox value: ' + label)
    click(session, label)
    session.expect(lambda: checkbox(session, label) is None, 'active menu action ' + label)
    session.send(menu)
    session.expect(lambda: checkbox(session, label) is not None, label + ' updated checkbox')
    if checkbox(session, label)[2] == before:
        raise AssertionError('Mouse activation did not change ' + label)
    session.snapshot('toggle-' + label.lower().replace(' ', '-') + ('-off' if before else '-on'))
    session.send(b'\x1b', 0.7)


def select_and_find(session, stage):
    session.send(b'\x01')
    session.send(b'\x06')
    session.expect(lambda: 'Find: ' in session.screen.text, stage + ' Find field')
    session.send(b'\x01needle')
    session.expect(lambda: any('Find: needle' in line for line in session.screen.lines), stage + ' literal query')
    session.snapshot(stage + '-selection-and-find')
    session.send(b'\x1b', 0.7)


def cycle(session, expected):
    session.send(F3)
    locate(session, 'Theme: ')
    boundary = len(session.log)
    click(session, 'Theme: ')
    session.expect(lambda: 'Theme: ' + expected in session.screen.text
                   and 'Syntax highlighting' not in session.screen.text, 'theme ' + expected)
    return boundary


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--classpath', default=str(root / 'apps/tuink/launcher/build/install/tuink/lib/*'))
    parser.add_argument('--binary', type=Path)
    parser.add_argument('--output-root', type=Path, default=root / 'build/monochrome-theme-pty')
    args = parser.parse_args()
    args.output_root.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='monochrome-', dir=args.output_root)).resolve()
    document = output / 'monochrome.md'
    document.write_text(DOCUMENT, encoding='utf-8')
    with Session(output / 'session', args.java, args.classpath, document,
                 options=['--theme=monochrome'], native_executable=args.binary, timeout=90) as session:
        session.resize(36, 120)
        session.expect(lambda: source_selected(session.screen.text), 'Monochrome source')
        for label in ('Syntax highlighting', 'Rainbow brackets'):
            toggle_checkbox(session, F3, label, True)
            toggle_checkbox(session, F3, label, False)
        toggle_checkbox(session, F5, 'MD code syntax', False)
        select_and_find(session, 'source')
        session.send(b'\x10')
        session.expect(lambda: preview_selected(session.screen.text) and 'BEGIN' in session.screen.text
                       and 'quote marker' in session.screen.text and '\\frac' not in session.screen.text,
                       'rendered Monochrome code, formula, diagram and quote', timeout=10)
        select_and_find(session, 'preview')
        initial = bytes(session.log)
        assert_monochrome_output(initial, 'Initial Monochrome')
        (output / 'initial-monochrome.log').write_bytes(initial)
        cyan = cycle(session, 'Cyan')
        if not chromatic_sgr(bytes(session.log[cyan:])):
            raise AssertionError('Cyan did not restore chromatic output')
        cycle(session, 'Blue')
        returned = cycle(session, 'Monochrome')
        session.send(b'\x1b[1;5H')  # Collapse the Preview selection and expose the keyword palette again.
        session.expect(lambda: preview_selected(session.screen.text) and 'def greet' in session.screen.text,
                       'Preview code after the return to Monochrome')
        select_and_find(session, 'returned-preview')
        session.send(b'\x10')
        session.expect(lambda: source_selected(session.screen.text), 'Source after the return to Monochrome')
        select_and_find(session, 'returned-monochrome')
        session.finish()
        fresh = bytes(session.log[returned:])
        assert_monochrome_output(fresh, 'Returned Monochrome')
        (output / 'returned-monochrome.log').write_bytes(fresh)
        settings = session.config_home / 'tuink/tuink.properties'
    saved = dict(line.split('=', 1) for line in settings.read_text(encoding='iso-8859-1').splitlines()
                 if '=' in line and not line.startswith(('#', '!')))
    if (saved.get('ui.syntaxHighlighting') != 'true' or saved.get('ui.rainbowBrackets') != 'true'
            or saved.get('ui.markdownCodeHighlighting') != 'true'):
        raise AssertionError('Monochrome mouse toggles did not persist the enabled highlighting preferences')
    if document.read_text(encoding='utf-8') != DOCUMENT:
        raise AssertionError('Monochrome verification changed the source document')
    print(f'PASS Monochrome neutral output, active mouse toggles, theme round trip and saved preferences: {output}', flush=True)


if __name__ == '__main__':
    main()
