#!/usr/bin/env python3
"""Check clipboard exchange through a private helper and a real terminal UI."""
import argparse
import os
from pathlib import Path
import sys
import tempfile

from terminal import Session
from markdown_preview import preview_selected, source_selected


RMB_LABEL = 'Right-click: copy/paste'


def locate(session, text):
    session.expect(lambda: text in session.screen.text, text)
    for row, line in enumerate(session.screen.lines):
        if text in line:
            return row, line.index(text)
    raise AssertionError('Missing displayed text: ' + text)


def right_click(session, row, column):
    session.mouse(2, row, column)
    session.mouse(2, row, column, release=True)


def select_token(session, line, token):
    row, column = locate(session, line)
    start = column + line.index(token)
    session.mouse(0, row, start)
    session.mouse(32, row, start + len(token))
    session.mouse(0, row, start + len(token), release=True)
    return row, start + len(token)


def toggle_right_click(session, expected):
    session.send(b'\x1b[15~')  # F5: Settings; activate the item with the mouse.
    row, column = locate(session, RMB_LABEL)
    if ('[x]' in session.screen.lines[row][:column]) != expected:
        raise AssertionError('Unexpected right-click preference: ' + session.screen.lines[row])
    session.mouse(0, row, column + 2)
    session.mouse(0, row, column + 2, release=True)
    session.expect(lambda: RMB_LABEL not in session.screen.text, 'the mouse setting command closes Settings')


def field_right_click(session, clipboard, prefix, value):
    row, column = locate(session, prefix)
    column += len(prefix) + 1
    session.send(b'\x01\x7f')  # Empty the focused field without touching document text.
    clipboard.write_text(value, encoding='utf-8')
    right_click(session, row, column)
    session.expect(lambda: prefix + value in session.screen.text, prefix + 'right-click paste')
    session.send(b'\x01')
    right_click(session, row, column)
    session.expect(lambda: clipboard.read_text(encoding='utf-8') == value, prefix + 'right-click copy')
    clipboard.write_text('_tail', encoding='utf-8')
    right_click(session, row, column)
    session.expect(lambda: prefix + value + '_tail' in session.screen.text,
                   prefix + 'copy clears selection before the following paste')


def right_click_clipboard(args, output, env, clipboard):
    home = output / 'right-click-home'
    document = output / 'right-click.md'
    original = '# RMB clipboard\ncopy_token target\npaste_anchor\n'
    expected = '# RMB clipboard\ncopy_token pasted target\npaste_anchor\n'
    document.write_text(original, encoding='utf-8')
    with Session(output / 'right-click-enable', args.java, args.classpath, document, home=home,
                 native_executable=args.binary, env_overrides=env, timeout=60) as session:
        row, column = select_token(session, 'copy_token target', 'copy_token')
        clipboard.write_text('disabled sentinel', encoding='utf-8')
        right_click(session, row, column)
        session.drain(0.3)
        if clipboard.read_text(encoding='utf-8') != 'disabled sentinel':
            raise AssertionError('Right-click copied while its default-off preference was disabled')
        session.send(b'\x03')
        session.expect(lambda: clipboard.read_text(encoding='utf-8') == 'copy_token',
                       'disabled right-click leaves the Source selection available for Ctrl+C')
        toggle_right_click(session, expected=False)
        row, column = select_token(session, 'copy_token target', 'copy_token')
        clipboard.write_text('enabled sentinel', encoding='utf-8')
        right_click(session, row, column)
        session.expect(lambda: clipboard.read_text(encoding='utf-8') == 'copy_token',
                       'Source right-click copies its selection to the platform helper')
        clipboard.write_text(' pasted', encoding='utf-8')
        right_click(session, row, column)
        session.expect(lambda: 'copy_token pasted target' in session.screen.text,
                       'Source right-click clears selection and then pastes at the current caret')
        session.save(document, expected, 'right-click paste is saved')
        session.send(b'\x06')
        field_right_click(session, clipboard, 'Find: ', 'source_query')
        session.send(b'\x1b', 0.7)
        session.expect(lambda: 'Find: ' not in session.screen.text, 'Source Find closes')
        session.send(b'\x0b')
        field_right_click(session, clipboard, 'Save As: ', 'right-click-copy.md')
        session.send(b'\x1b', 0.7)
        session.expect(lambda: 'Save As: ' not in session.screen.text, 'Save As cancels')
        session.snapshot('right-click-source-and-fields')
        session.finish()
    settings = home / '.config/tuink/tuink.properties'
    if 'ui.rightClickClipboard=true' not in settings.read_text(encoding='iso-8859-1'):
        raise AssertionError('The enabled right-click preference was not saved')
    with Session(output / 'right-click-restart', args.java, args.classpath, document, home=home,
                 native_executable=args.binary, env_overrides=env, timeout=60) as session:
        session.send(b'\x1b[15~')
        row, column = locate(session, RMB_LABEL)
        if '[x]' not in session.screen.lines[row][:column]:
            raise AssertionError('The right-click preference did not survive restart')
        session.send(b'\x1b', 0.7)
        session.send(b'\x10')
        session.expect(lambda: preview_selected(session.screen.text), 'Preview after restart')
        session.send(b'\x01')
        row, column = locate(session, 'copy_token pasted target')
        right_click(session, row, column + 2)
        session.expect(lambda: 'copy_token pasted target' in clipboard.read_text(encoding='utf-8')
                       and 'paste_anchor' in clipboard.read_text(encoding='utf-8'), 'Preview semantic right-click copy')
        clipboard.write_text('must not enter Preview', encoding='utf-8')
        right_click(session, row, column + 2)
        session.drain(0.3)
        if (clipboard.read_text(encoding='utf-8') != 'must not enter Preview'
                or 'must not enter Preview' in session.screen.text or document.read_text(encoding='utf-8') != expected):
            raise AssertionError('Readonly Preview right-click pasted or retained its copied selection')
        session.send(b'\x06')
        field_right_click(session, clipboard, 'Find: ', 'preview_query')
        session.send(b'\x1b', 0.7)
        session.expect(lambda: 'Find: ' not in session.screen.text, 'Preview Find closes')
        toggle_right_click(session, expected=True)
        session.send(b'\x01')
        row, column = locate(session, 'copy_token pasted target')
        clipboard.write_text('disabled Preview sentinel', encoding='utf-8')
        right_click(session, row, column + 2)
        session.drain(0.3)
        if clipboard.read_text(encoding='utf-8') != 'disabled Preview sentinel':
            raise AssertionError('Disabled Preview right-click mutated the clipboard')
        session.send(b'\x03')
        session.expect(lambda: 'paste_anchor' in clipboard.read_text(encoding='utf-8'),
                       'disabled Preview right-click leaves its selection available for Ctrl+C')
        session.snapshot('right-click-preview-and-disabled')
        session.finish()
    if 'ui.rightClickClipboard=' in settings.read_text(encoding='iso-8859-1'):
        raise AssertionError('The default-off optional preference was not omitted after disabling')
    if document.read_text(encoding='utf-8') != expected or any(output.rglob('right-click-copy.md*')):
        raise AssertionError('Field or readonly right-click changed a document unexpectedly')


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--classpath', default=str(root / 'apps/tuink/launcher/build/install/tuink/lib/*'))
    parser.add_argument('--binary', type=Path)
    parser.add_argument('--output-root', type=Path, default=root / 'build/clipboard-pty')
    args = parser.parse_args()
    args.output_root.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='clipboard-', dir=args.output_root)).resolve()
    helpers = output / 'helpers'
    helpers.mkdir()
    clipboard = output / 'clipboard.txt'
    clipboard.write_text('', encoding='utf-8')
    helper_source = f'#!{sys.executable}\n' + '''import os, pathlib, sys, time
p = pathlib.Path(os.environ['TUINK_TEST_CLIPBOARD'])
if pathlib.Path(sys.argv[0]).name in ('pbpaste', 'wl-paste') or any(a in sys.argv for a in ('-o', '-out', '--output')):
    time.sleep(0.15)
    sys.stdout.buffer.write(p.read_bytes())
else:
    p.write_bytes(sys.stdin.buffer.read())
'''
    for name in ('xclip', 'xsel', 'wl-copy', 'wl-paste', 'pbcopy', 'pbpaste'):
        helper = helpers / name
        helper.write_text(helper_source, encoding='utf-8')
        helper.chmod(0o700)
    document = output / 'example.md'
    document.write_text('# Heading\nneedle\n', encoding='utf-8')
    env = {'PATH': str(helpers) + os.pathsep + os.environ.get('PATH', ''),
           'DISPLAY': ':99', 'WAYLAND_DISPLAY': '',
           'SSH_CONNECTION': '', 'SSH_CLIENT': '', 'SSH_TTY': '',
           'TUINK_TEST_CLIPBOARD': str(clipboard)}
    with Session(output / 'session', args.java, args.classpath, document,
                 native_executable=args.binary, env_overrides=env, timeout=45) as session:
        session.send(b'\x03')
        session.expect(lambda: clipboard.read_text(encoding='utf-8') == '# Heading\n',
                       'copy from source reaches the platform helper')
        clipboard.write_text('external 😀 ', encoding='utf-8')
        session.send(b'\x16\x1b[20~\x1b[20~!', wait=0.02)  # Two ignored F9 events before typing.
        session.expect(lambda: 'external 😀 !# Heading' in session.screen.text,
                       'external paste precedes fast typing')
        session.send(b'\x13')
        session.expect(lambda: document.read_text(encoding='utf-8').startswith('external 😀 !# Heading'),
                       'pasted system text is saved')
        session.send(b'\x10')
        session.expect(lambda: preview_selected(session.screen.text), 'Markdown Preview')
        session.send(b'\x01\x03')
        session.expect(lambda: 'needle' in clipboard.read_text(encoding='utf-8'),
                       'semantic preview copy reaches the same platform helper')
        session.send(b'\x06')
        clipboard.write_text('needle', encoding='utf-8')
        session.send(b'\x16')
        session.expect(lambda: 'Find: needle' in session.screen.text, 'system paste in Preview Find')
        session.send(b'\x1b', 0.7)
        session.send(b'\x10')
        session.expect(lambda: source_selected(session.screen.text), 'return to source')
        clipboard.write_text('', encoding='utf-8')
        before = document.read_bytes()
        session.send(b'\x16')
        session.send(b'\x13')
        if document.read_bytes() != before:
            raise AssertionError('Empty system clipboard pasted stale local text')
        session.finish()
    right_click_clipboard(args, output, env, clipboard)
    print(f'PASS system clipboard: Source/Preview/fields, input order, empty text, optional right-click and restart: {output}', flush=True)


if __name__ == '__main__':
    main()
