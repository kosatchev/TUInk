#!/usr/bin/env python3
"""Check clipboard exchange through a private helper and a real terminal UI."""
import argparse
import os
from pathlib import Path
import sys
import tempfile

from terminal import Session
from markdown_preview import preview_selected, source_selected


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
    helper = helpers / 'xclip'
    helper.write_text(f'#!{sys.executable}\n' + '''import os, pathlib, sys, time
p = pathlib.Path(os.environ['TUINK_TEST_CLIPBOARD'])
if '-o' in sys.argv or '-out' in sys.argv:
    time.sleep(0.15)
    sys.stdout.buffer.write(p.read_bytes())
else:
    p.write_bytes(sys.stdin.buffer.read())
''', encoding='utf-8')
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
    print(f'PASS system clipboard: source, preview, Find, input order, empty text: {output}', flush=True)


if __name__ == '__main__':
    main()
