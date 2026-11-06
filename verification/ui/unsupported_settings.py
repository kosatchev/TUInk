#!/usr/bin/env python3
"""Verify unsupported settings choices in an isolated JVM or native terminal session."""
import argparse
from pathlib import Path
import tempfile

from markdown_preview import preview_selected
from run import format5_snapshot, recovery_prompt
from terminal import Session


QUESTION = 'Unsupported settings. Replace invalid values with defaults?'
PROFILE = '''format=2
ui.showTabBar=true
ui.wrapLines=true
ui.wrapWords=true
ui.readingMode=false
ui.confirmOnClose=true
ui.mouseMode=true
ui.markdownOpenBothTabs=false
ui.markdownOpenInPreview=false
ui.highlightActiveLineNumber=true
ui.smartHomeEnd=true
ui.autoIndent=true
ui.autoPairs=true
ui.autoList=true
ui.visibleWhitespace=true
ui.syntaxHighlighting=true
ui.rainbowBrackets=true
ui.autoSelectionHighlight=true
ui.gutterDiffMode=myers
ui.tabWidth=invalid
ui.tabInsertMode=auto
ui.theme=unsupported
'''


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text(encoding='iso-8859-1').splitlines()
                if '=' in line and not line.startswith(('#', '!')))


def click(session, text):
    session.expect(lambda: text in session.screen.text, text)
    row, column = next((row, line.index(text)) for row, line in enumerate(session.screen.lines) if text in line)
    session.mouse(0, row, column + 1)
    session.mouse(0, row, column + 1, release=True)


def toggle_whitespace(session):
    session.send(b'\x1bOR')
    click(session, 'Visible tabs/spaces')
    session.expect(lambda: 'Visible tabs/spaces' not in session.screen.text, 'settings action closes the menu')


class Checks:
    def __init__(self, args, output):
        self.args, self.output = args, output
        self.document = output / 'original.md'
        self.original = '# Original document\n\nkeep this text\n'
        self.document.write_text(self.original, encoding='utf-8')

    def profile(self, name, text=PROFILE):
        home = self.output / (name + '-home')
        path = home / '.config/tuink/tuink.properties'
        path.parent.mkdir(parents=True)
        path.write_text(text, encoding='iso-8859-1')
        return home, path

    def session(self, name, home, options=()):
        return Session(self.output / name, self.args.java, self.args.classpath,
                       self.document, home=home, options=options,
                       native_executable=self.args.binary, timeout=60)

    def expect_prompt(self, session):
        session.resize(30, 120)
        session.expect(lambda: QUESTION in session.screen.text
                       and all(button in session.screen.text for button in ('[Fix]', '[Delete]', '[Later]')),
                       'complete unsupported settings confirmation')

    def fix(self):
        home, path = self.profile('fix')
        with self.session('fix', home, ('--theme=monochrome',)) as session:
            self.expect_prompt(session)
            click(session, '[Fix]')
            session.expect(lambda: 'Unsupported settings.' not in session.screen.text, 'Fix completes')
            saved = properties(path)
            assert saved['ui.wrapLines'] == 'true', saved
            assert saved['ui.tabWidth'] == '4' and saved['ui.theme'] == 'cyan', saved
            session.send(b'\x1bOR')
            session.expect(lambda: 'Theme: Monochrome' in session.screen.text, 'temporary CLI theme survives Fix')
            session.send(b'\x1b', 0.7)
            toggle_whitespace(session)
            session.finish()
        saved = properties(path)
        assert saved['ui.visibleWhitespace'] == 'false', saved
        assert saved['ui.theme'] == 'cyan', 'Fix persisted a temporary CLI override'

    def later(self):
        for name, key in (('later-default', b'\r'), ('later-no', b'n'), ('later-escape', b'\x1b')):
            home, path = self.profile(name)
            original = path.read_bytes()
            with self.session(name, home) as session:
                self.expect_prompt(session)
                session.send(key, 0.7)
                session.expect(lambda: 'Unsupported settings.' not in session.screen.text, 'Later dismisses the prompt')
                assert session.poll() is None, 'Later exited the application'
                toggle_whitespace(session)
                session.expect(lambda: 'Settings changes apply only to this session.' in session.screen.text,
                               'temporary preferences warning')
                session.send(b'\x10')
                session.expect(lambda: preview_selected(session.screen.text), 'Preview after postponing settings')
                session.finish()
            assert path.read_bytes() == original, 'Later or ordinary preferences rewrote the unsupported profile'

    def delete(self):
        home, path = self.profile('delete')
        with self.session('delete', home) as session:
            self.expect_prompt(session)
            session.send(b'd')
            session.expect(lambda: 'Unsupported settings.' not in session.screen.text and not path.exists(),
                           'Delete removes the profile and applies defaults')
            toggle_whitespace(session)
            session.finish()
        saved = properties(path)
        assert saved['format'] == '2' and saved['ui.wrapLines'] == 'false', saved
        assert saved['ui.visibleWhitespace'] == 'false', 'Delete did not enable subsequent guarded preference saves'

    def whole_file_defaults(self):
        for name, text in (('unknown-format', PROFILE.replace('format=2', 'format=999')),
                           ('malformed-properties', 'format=2\nbroken=\\uZZZZ\n')):
            home, path = self.profile(name, text)
            with self.session(name, home) as session:
                self.expect_prompt(session)
                session.send(b'y')
                session.expect(lambda: 'Unsupported settings.' not in session.screen.text, 'Fix resets the whole file')
                saved = properties(path)
                assert saved['format'] == '2' and saved['ui.wrapLines'] == 'false', saved
                assert saved['ui.tabWidth'] == '4' and saved['ui.theme'] == 'cyan', saved
                session.finish()

    def conflict(self):
        for key in (b'y', b'd'):
            name = 'conflict-' + key.decode()
            home, path = self.profile(name)
            with self.session(name, home) as session:
                self.expect_prompt(session)
                external = b'format=999\nexternal=keep these bytes\n'
                path.write_bytes(external)
                session.send(key, 0.7)
                session.expect(lambda: QUESTION in session.screen.text and 'Settings file changed' in session.screen.text,
                               'conflict keeps the prompt open and explains the refusal')
                assert session.poll() is None, 'A conflict exited the editor'
                assert path.read_bytes() == external, 'The attempted settings action overwrote an external change'
                session.send(b'\x1b', 0.7)
                session.expect(lambda: 'Unsupported settings.' not in session.screen.text, 'Later remains available after a conflict')
                session.finish()
            assert path.read_bytes() == external, 'Exit overwrote externally changed settings'

    def recovery_queue(self):
        for quit_from_settings in (False, True):
            name = 'queued-quit' if quit_from_settings else 'queued-later'
            home, path = self.profile(name)
            original = path.read_bytes()
            meta, payload = format5_snapshot(home, self.document, self.original.encode(), 'queued recovered text\n')
            metadata, text = meta.read_bytes(), payload.read_bytes()
            with self.session(name, home) as session:
                self.expect_prompt(session)
                assert not recovery_prompt(session), 'Recovery appeared before the settings response'
                if quit_from_settings:
                    session.send(b'\x11')
                    session.expect(lambda: session.poll() is not None, 'Ctrl+Q exits without opening queued recovery', timeout=5)
                    assert session.status == 0
                else:
                    session.send(b'n', 0.7)
                    session.expect(lambda: recovery_prompt(session, 1), 'recovery follows Later')
                    session.send(b'\x1b', 0.7)
                    session.expect(lambda: not recovery_prompt(session), 'recovery postponed')
                    session.finish()
            assert path.read_bytes() == original, 'Queued recovery changed unsupported settings'
            assert meta.read_bytes() == metadata and payload.read_bytes() == text, 'Quit lost a queued recovery copy'

    def run(self):
        for check in (self.fix, self.later, self.delete, self.whole_file_defaults, self.conflict, self.recovery_queue):
            check()
            assert self.document.read_text(encoding='utf-8') == self.original, 'Settings actions changed document text'
            print('PASS unsupported settings ' + check.__name__, flush=True)


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--classpath', default=str(root / 'apps/tuink/launcher/build/install/tuink/lib/*'))
    parser.add_argument('--binary', type=Path)
    parser.add_argument('--output-root', type=Path, default=root / 'build/unsupported-settings-pty')
    args = parser.parse_args()
    args.output_root.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='unsupported-', dir=args.output_root)).resolve()
    Checks(args, output).run()
    print(f'PASS unsupported settings JVM/native terminal checks: {output}', flush=True)


if __name__ == '__main__':
    main()
