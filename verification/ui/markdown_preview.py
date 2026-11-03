#!/usr/bin/env python3
"""Exercise actual Markdown preview frames in a JVM or native PTY session."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
from terminal import Session

CTRL_TAB = b'\x1b[27;5;9~'
CTRL_SHIFT_TAB = b'\x1b[27;6;9~'


EDITING_CLASSES = (
    'dev.tuink.core.EditorEngine',
    'dev.tuink.tui.TuiDocumentSession',
    'dev.tuink.editor.session.document.EditorDocumentSession',
    'dev.tuink.core.EditorState',
)


def editing_instance_counts(histogram):
    """Read exact editing-class instance counts, including optional module annotations."""
    counts = dict.fromkeys(EDITING_CLASSES, 0)
    names = '|'.join(map(re.escape, EDITING_CLASSES))
    pattern = rf'^\s*\d+:\s+(\d+)\s+\d+\s+({names})(?:\s+\([^()\r\n]+\))?\s*$'
    for match in re.finditer(pattern, histogram, re.MULTILINE):
        counts[match.group(2)] += int(match.group(1))
    return counts


def expect_editing_instances(session, jcmd, stage, expected):
    if jcmd is None:
        return
    result = subprocess.run([str(jcmd), str(session.pid), 'GC.class_histogram'],
                            capture_output=True, text=True, check=True, timeout=10)
    (session.root / f'{stage}.histogram.txt').write_text(result.stdout, encoding='utf-8')
    if result.stderr:
        (session.root / f'{stage}.jcmd-stderr.txt').write_text(result.stderr, encoding='utf-8')
    counts = editing_instance_counts(result.stdout)
    if any(count != expected for count in counts.values()):
        raise AssertionError(f'{stage}: expected {expected} live instances of each editing class; got {counts}')


def paired_captions_present(text):
    """Only the tab strip acknowledges two adjacent physical presentations."""
    lines = text.splitlines()
    if len(lines) < 2:
        return False
    tabs = lines[1]
    return (tabs.count('· Source') == 1 and tabs.count('· Preview') == 1
            and tabs.index('· Source') < tabs.index('· Preview'))


def preview_selected(text, mode='live'):
    lines = text.splitlines()
    return (bool(lines) and (f'Preview ({mode})' in lines[-1] or f'MD:{mode}' in lines[-1])
            and 'Ctrl+P' in lines[-1] and 'updating' not in lines[-1])


def source_selected(text):
    lines = text.splitlines()
    return (bool(lines) and not any(marker in lines[-1] for marker in ('Preview (', 'MD:live', 'MD:snapshot'))
            and ('Ln ' in lines[-1] or re.search(r'(?:^|\s)(?:INS|READ)\s*$', lines[-1]) is not None))


def preview_position(session):
    match = re.search(r'Row (\d+)/\d+ Col (\d+)', session.screen.lines[-1])
    if match is None:
        raise AssertionError('Preview position is missing from the status row: ' + session.screen.lines[-1])
    return tuple(map(int, match.groups()))


def expect_preview(session, description, mode='live'):
    session.expect(lambda: preview_selected(session.screen.text, mode), description, timeout=10)


def close_source_discard(session):
    session.send(b'\x10')
    session.expect(lambda: source_selected(session.screen.text), 'the paired source before closing')
    session.send(b'\x17')
    session.expect(lambda: '[Discard]' in session.screen.text, 'source close confirmation')
    session.send(b'\x1b[H\x1b[C\r')
    expect_preview(session, 'the retained snapshot after source Discard', mode='snapshot')


def fill_snapshot_filename(session, filename):
    session.send(b'\x0b')
    session.expect(lambda: 'Save As: ' in session.screen.text, 'explicit snapshot Save As')
    session.send(b'\x01\x1b[200~' + filename.encode('utf-8') + b'\x1b[201~\r')


def rendered_preview_present(text):
    """A source fence with matching labels must not acknowledge a rendered frame."""
    return (preview_selected(text)
            and 'Editor' in text and 'Preview' in text
            and '┌' in text and '┐' in text and '→' in text
            and ('⌠' in text or '∫' in text)
            and '\\frac' not in text
            and 'flowchart' not in text
            and chr(96) * 3 + 'mermaid' not in text
            and '[Mermaid:' not in text)


def scrollbar_controls(args, output):
    """Exercise real SGR track/thumb input, including capture outside the viewport."""
    document = output / 'scrollbar-controls.md'
    original = ('# Scrollbar smoke\n\n```text\n'
                + '\n'.join(f'row-{row:03d} ' + '中😀\t' * 50 + ' RIGHT-END' for row in range(100))
                + '\n```\n')
    document.write_text(original, encoding='utf-8')
    with Session(output / 'scrollbar-session', args.java, args.classpath, document,
                 native_executable=args.binary, timeout=60) as session:
        session.send(b'\x10')
        expect_preview(session, 'headerless preview with both scrollbars')
        session.expect(lambda: session.screen.lines[2].startswith('Scrollbar smoke'),
                       'content begins immediately below the tabs')

        def mouse(button, x, y, down=True):
            session.send(f'\x1b[<{button};{x + 1};{y + 1}{"M" if down else "m"}'.encode())

        def vertical_thumb():
            cells = [y for y in range(2, len(session.screen.lines) - 2)
                     if session.screen.lines[y][-1] == '█']
            if len(cells) < 2:
                raise AssertionError('Preview must paint a proportional editor-style vertical thumb')
            return cells[len(cells) // 2]

        right = len(session.screen.lines[0]) - 1
        before = preview_position(session)
        encoding_column = session.screen.lines[-1].index('ENC:')
        grab = vertical_thumb()
        mouse(0, right, grab)
        mouse(32, right, grab)
        if preview_position(session) != before:
            raise AssertionError('Stationary thumb drag changed the viewport')
        mouse(32, right, grab + 6)
        session.expect(lambda: preview_position(session)[0] > before[0], 'vertical thumb drag scrolls')
        mouse(0, right, grab + 6, False)
        mouse(0, right, vertical_thumb())
        mouse(32, right, 0)
        session.expect(lambda: preview_position(session)[0] == 1, 'captured drag above the track reaches the start')
        mouse(0, right, 0, False)
        mouse(0, right, len(session.screen.lines) - 4)
        session.expect(lambda: preview_position(session)[0] > 1, 'vertical track click pages')
        mouse(0, right, len(session.screen.lines) - 4, False)

        track = len(session.screen.lines) - 2
        cells = [x for x, cell in enumerate(session.screen.lines[track][:-1]) if cell == '█']
        if len(cells) < 2:
            raise AssertionError('Preview must paint a proportional editor-style horizontal thumb')
        mouse(0, cells[len(cells) // 2], track)
        mouse(32, right - 2, track)
        session.expect(lambda: preview_position(session)[1] > 1 and 'RIGHT-END' in session.screen.text,
                       'horizontal thumb drag reaches the wide content end')
        mouse(0, right - 2, track, False)
        row, column = preview_position(session)
        footer = session.screen.lines[-1]
        if 'Tab 2/2' not in footer or 'Ctrl+P' not in footer:
            raise AssertionError('Wide preview coordinates hid the tab count or switch shortcut: ' + footer)
        if footer.index('ENC:') != encoding_column:
            raise AssertionError('Preview metadata moved when reading coordinates changed: ' + footer)
        mouse(65, 10, 10)
        session.expect(lambda: preview_position(session)[0] > row, 'wheel remains available after releasing a thumb')
        mouse(66, 10, 10)
        session.expect(lambda: preview_position(session)[1] < column, 'horizontal wheel shares the viewport')
        session.snapshot('mouse-scrollbars')
        session.finish()
    if document.read_text(encoding='utf-8') != original:
        raise AssertionError('Scrollbar input modified Markdown source bytes')
    print(f'PASS: common Preview mouse scrollbars: {output}', flush=True)


SAMPLE_HEADINGS = (
    'Mermaid flowchart',
    'Mermaid sequence diagram',
    'Gaussian integral',
    'Roots of a quadratic equation',
    'Power tower',
    'Nested radicals',
    'Exponential series',
    'Matrix inverse',
    'Heat equation',
    'Continued fraction',
    'Formula in a math block',
)


def sample_sweep(args, output):
    """Visit the real demo's rendered sections without its intentional raw-code examples."""
    sample = args.sample.read_text(encoding='utf-8')
    text = sample.split('## Plain text and code', 1)[0]
    if any(heading not in text for heading in SAMPLE_HEADINGS):
        raise AssertionError(f'Sample is missing a required demonstration section: {args.sample}')
    document = output / 'full-sample.md'
    document.write_text(text, encoding='utf-8')
    frames = []
    with Session(output / 'sample-session', args.java, args.classpath, document,
                 native_executable=args.binary, timeout=90) as session:
        session.send(b'\x10')
        session.expect(lambda: preview_selected(session.screen.text)
                       and 'Mermaid diagrams and mathematical formulas' in session.screen.text
                       and 'updating' not in session.screen.text,
                       'complete real-sample preview', timeout=10)
        if not paired_captions_present(session.screen.text):
            raise AssertionError('The complete sample must open in adjacent Source/Preview tabs')
        previous = None
        repeats = 0
        for page in range(24):
            frame = session.screen.text
            frames.append(frame)
            session.snapshot(f'sample-page-{page:02d}')
            for forbidden in ('Preview unavailable:', '[Mermaid:', 'Formula exceeds',
                              'Unsupported TeX command', '\\frac', '\\sqrt', '\\cfrac',
                              '\\begin', 'flowchart LR', 'sequenceDiagram'):
                if forbidden in frame:
                    raise AssertionError(f'Sample section fell back to source or failed ({forbidden}):\n{frame}')
            repeats = repeats + 1 if frame == previous else 0
            # Overlapping viewport pages cover every row of atomic math/diagram
            # boxes independently of the semantic caret's navigation policy.
            if repeats >= 3:
                break
            previous = frame
            session.send(b'\x1b[<65;10;10M' * 6, wait=0.18)
        combined = '\n'.join(frames)
        missing = [heading for heading in SAMPLE_HEADINGS if heading not in combined]
        if missing:
            raise AssertionError(f'Preview sweep did not reach these sections: {missing}')
        required_glyphs = {
            'integral': ('⌠', '∫'),
            'radical': ('╲╱', '√'),
            'sum': ('⎲', '∑'),
            'partial derivative': ('∂',),
            'matrix': ('⎡',),
            'sequence reply': ('╌',),
            'directed connection': ('→',),
        }
        for description, alternatives in required_glyphs.items():
            if not any(glyph in combined for glyph in alternatives):
                raise AssertionError(f'Real sample lacks rendered {description}: {alternatives}')
        if '512' not in combined or '1.96157' not in combined:
            raise AssertionError('Nested powers or roots were not reached in the real sample')
        session.finish()
    if document.read_text(encoding='utf-8') != text:
        raise AssertionError('Preview sweep changed the demonstration source')
    print(f'PASS: complete Mermaid/math sample ({len(frames)} pages): {output}', flush=True)


def flowchart_scroll(args, output):
    """Observe both ends of the real wide flowchart in its own viewport."""
    sample = args.sample.read_text(encoding='utf-8')
    body = sample.split('```mermaid', 1)[1].split('```', 1)[0]
    document = output / 'wide-flowchart.md'
    document.write_text('```mermaid' + body + '```\n', encoding='utf-8')
    with Session(output / 'flowchart-session', args.java, args.classpath, document,
                 native_executable=args.binary, timeout=45) as session:
        session.send(b'\x10')
        session.expect(lambda: preview_selected(session.screen.text)
                       and 'Open Markdown' in session.screen.text and '┌' in session.screen.text,
                       'left side of rendered wide flowchart', timeout=10)
        session.snapshot('flowchart-left')
        left_frame = session.screen.text
        session.send(b'\x1b[<67;10;10M' * 16)
        session.expect(lambda: 'Save' in session.screen.text and '┌' in session.screen.text,
                       'right join target after horizontal scroll', timeout=10)
        session.snapshot('flowchart-right')
        if '[Mermaid:' in session.screen.text or 'flowchart LR' in session.screen.text:
            raise AssertionError('Wide flowchart fell back to source')
        session.send(b'\x1b[1;3D' * 16)
        session.expect(lambda: session.screen.text == left_frame,
                       'keyboard pan restores the original left viewport', timeout=10)
        session.send(b'\x1b[1;3C' * 16)
        session.expect(lambda: 'Save' in session.screen.text,
                       'keyboard pan reveals the same right join', timeout=10)
        session.snapshot('flowchart-keyboard-right')
        session.finish()
    print(f'PASS: wide Mermaid horizontal scroll: {output}', flush=True)


def paired_lifecycle(args, output):
    """Close a dirty source, retain its preview, then reread a changed disk file."""
    document = output / 'pair.md'
    original = '# Original\n\nRaw $x_i^2$.\n'
    external = '# Changed on disk\n\nReloaded $y^2$.\n'
    document.write_text(original, encoding='utf-8')
    with Session(output / 'pair-session', args.java, args.classpath, document,
                 native_executable=args.binary, timeout=45) as session:
        session.send(b'draft ')
        session.expect(lambda: '*' in session.screen.lines[0], 'dirty paired source')
        session.send(b'\x10')
        expect_preview(session, 'the live paired preview')
        if not paired_captions_present(session.screen.text):
            raise AssertionError('Source must immediately precede its Preview tab')
        session.snapshot('paired-live')
        expect_editing_instances(session, args.jcmd, 'paired-live', 1)
        close_source_discard(session)
        expect_editing_instances(session, args.jcmd, 'paired-snapshot', 0)
        if document.read_text(encoding='utf-8') != original:
            raise AssertionError('Discard wrote the source document')
        session.snapshot('paired-snapshot')
        document.write_text(external, encoding='utf-8')
        session.send(b'\x10')
        session.expect(lambda: source_selected(session.screen.text)
                       and 'Changed on disk' in session.screen.text
                       and paired_captions_present(session.screen.text),
                       'a new source session reread from disk beside the existing preview', timeout=10)
        session.send(b'\x1a')
        session.expect(lambda: source_selected(session.screen.text)
                       and 'Changed on disk' in session.screen.text and '*' not in session.screen.lines[0],
                       'the reopened source has no old Undo history')
        session.send(b'\x10')
        expect_preview(session, 'the refreshed live preview')
        session.expect(lambda: 'Changed on disk' in session.screen.text, 'the fresh rendered disk text')
        session.snapshot('paired-reread')
        expect_editing_instances(session, args.jcmd, 'paired-reread', 1)
        session.send(b'\x17')
        session.expect(lambda: source_selected(session.screen.text)
                       and 'Changed on disk' in session.screen.text,
                       'Ctrl+W closes only Preview and keeps its source')
        if any('· Preview' in line for line in session.screen.lines[:2]):
            raise AssertionError('The closed preview still has a tab caption')
        session.snapshot('paired-preview-closed')
        session.finish()
    if document.read_text(encoding='utf-8') != external:
        raise AssertionError('Paired navigation or preview close changed the source')
    print(f'PASS: paired source/preview lifecycle and disk reread: {output}', flush=True)


def snapshot_save_as(args, output):
    """A frozen snapshot saves explicitly and reopens the confirmed new resource."""
    document = output / 'snapshot.md'
    original = '# Snapshot source\n\n```math\n\\frac{a}{b}\n```\n'
    frozen = 'draft ' + original
    external = '# Saved target reloaded\n\nfrom disk\n'
    document.write_text(original, encoding='utf-8')
    with Session(output / 'snapshot-session', args.java, args.classpath, document,
                 native_executable=args.binary, timeout=45) as session:
        session.send(b'draft \x10')
        expect_preview(session, 'live snapshot-save setup')
        close_source_discard(session)
        session.send(b'\x0b')
        session.expect(lambda: 'Save As: ' in session.screen.text, 'snapshot Save As before cancel')
        session.send(b'\x01abandoned.md\x1b')
        session.expect(lambda: preview_selected(session.screen.text, 'snapshot')
                       and 'Save As: ' not in session.screen.text,
                       'cancel keeps the same frozen preview')
        if (session.root / 'abandoned.md').exists():
            raise AssertionError('Cancelling snapshot Save As created a file')
        document.unlink()
        session.send(b'\x13')
        session.expect(lambda: 'Save failed:' in session.screen.text
                       and preview_selected(session.screen.text, 'snapshot')
                       and 'Save As: ' not in session.screen.text,
                       'missing snapshot resource reports an ordinary save error', timeout=5)
        saved = session.root / 'saved.md'
        fill_snapshot_filename(session, 'saved.md')
        session.expect_saved(saved, frozen, 'raw frozen Markdown saved explicitly', timeout=5)
        session.expect(lambda: 'saved.md' in session.screen.lines[0]
                       and preview_selected(session.screen.text, 'snapshot'),
                       'the snapshot adopts the confirmed new filename')
        session.snapshot('snapshot-saved-as')
        saved.write_text(external, encoding='utf-8')
        session.send(b'\x10')
        session.expect(lambda: source_selected(session.screen.text)
                       and 'Saved target reloaded' in session.screen.text
                       and 'saved.md' in session.screen.lines[0]
                       and paired_captions_present(session.screen.text),
                       'Ctrl+P rereads the new Save As target into the source tab', timeout=10)
        session.snapshot('snapshot-saveas-reread')
        session.finish()
    if saved.read_text(encoding='utf-8') != external:
        raise AssertionError('Reopening the snapshot target replaced external disk content')
    print(f'PASS: frozen Save As, cancel, missing target and disk reopen: {output}', flush=True)


def code_highlighting(args, output):
    """Observe Source/Preview SGR colors and the persisted block-only preference."""
    def expect_color(payload, color, token, stage):
        if not re.search(rb'\x1b\[' + str(color).encode() + rb';[0-9;]*m' + re.escape(token), payload):
            raise AssertionError(f'{stage} did not emit syntax color {color} for {token!r}')

    def toggle_source_code(session):
        session.send(b'\x1b[15~')  # F5: Settings; Up wraps to the final MD code syntax item.
        session.expect(lambda: 'MD code syntax' in session.screen.text, 'MD code syntax setting')
        session.send(b'\x1b[A\r')

    def repaint_source(session, rows):
        before = len(session.log)
        session.resize(rows, 86)
        session.expect(lambda: source_selected(session.screen.text)
                       and 'preview-string' in session.screen.text, 'repainted Source code block')
        return bytes(session.log[before:])

    document = output / 'code-highlighting.md'
    text = ('# Code highlighting\n\n```python\n'
            'def preview_value():\n'
            '    text = "preview-string"\n'
            '    # preview-comment\n'
            '    return 731\n```\n')
    document.write_text(text, encoding='utf-8')
    with Session(output / 'code-highlighting', args.java, args.classpath, document,
                 native_executable=args.binary) as session:
        expect_color(repaint_source(session, 25), 33, b'def', 'Default Source code')
        toggle_source_code(session)
        expect_color(repaint_source(session, 26), 94, b'def', 'Enabled Source code')
        session.send(b'\x10')
        expect_preview(session, 'code block Preview')
        before = len(session.log)
        session.resize(25, 86)
        session.expect(lambda: preview_selected(session.screen.text)
                       and 'preview-string' in session.screen.text, 'repainted code block')
        repaint = bytes(session.log[before:])
        for color, token in ((94, b'def'), (92, b'"preview-string"'),
                             (90, b'# preview-comment'), (96, b'731')):
            expect_color(repaint, color, token, 'Preview')
        session.snapshot('highlighted-code')
        session.finish()
        home = session.home
    profile = home / '.config/tuink/tuink.properties'
    if 'ui.markdownCodeHighlighting=true' not in profile.read_text(encoding='utf-8'):
        raise AssertionError('Enabled MD code syntax preference was not saved')
    with Session(output / 'code-highlighting-restart', args.java, args.classpath, document, home=home,
                 native_executable=args.binary) as session:
        expect_color(repaint_source(session, 25), 94, b'def', 'Restarted Source code')
        toggle_source_code(session)
        expect_color(repaint_source(session, 26), 33, b'def', 'Disabled Source code')
        session.finish()
    if 'ui.markdownCodeHighlighting=' in profile.read_text(encoding='utf-8'):
        raise AssertionError('Default MD code syntax setting should be omitted from the profile')
    if document.read_text(encoding='utf-8') != text:
        raise AssertionError('Code highlighting modified the source')
    print(f'PASS: Source/Preview code colors and persisted MD code syntax setting: {output}', flush=True)


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--classpath', default=str(root / 'apps/tuink/launcher/build/install/tuink/lib/*'))
    parser.add_argument('--binary', type=Path)
    parser.add_argument('--jcmd', type=Path,
                        help='Use this JVM jcmd to verify editing-session retirement')
    parser.add_argument('--sample', type=Path,
                        default=root / 'apps/tuink/samples/demo_markdown_preview.md')
    parser.add_argument('--output-root', type=Path, default=root / 'build/markdown-preview-pty')
    args = parser.parse_args()
    if args.binary and args.jcmd:
        parser.error('--jcmd is available only for JVM sessions')
    args.output_root.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='native-' if args.binary else 'jvm-', dir=args.output_root))
    document = output / 'preview.md'
    text = ('# Preview smoke\n\n'
            '$$\n\\int_0^{\\infty} e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}\n$$\n\n'
            '```mermaid\nflowchart LR\n A[Editor] --> B[Preview]\n```\n')
    document.write_text(text, encoding='utf-8')
    with Session(output / 'session', args.java, args.classpath, document,
                 native_executable=args.binary, timeout=45) as session:
        session.expect(lambda: 'Tab 1/1  Ctrl+P: Preview' in session.screen.lines[-1],
                       'Markdown Source advertises Preview immediately after its single-tab count')
        session.send(b' ')
        session.expect(lambda: '*' in session.screen.lines[0], 'unsaved source edit')
        text = ' ' + text
        session.send(b'\x10')
        session.expect(lambda: rendered_preview_present(session.screen.text),
                       'rendered integral and Mermaid flowchart', timeout=10)
        session.snapshot('preview')
        if not paired_captions_present(session.screen.text):
            raise AssertionError('Ctrl+P did not create adjacent physical Source/Preview tabs')
        session.send(b'\x10')
        session.expect(lambda: source_selected(session.screen.text)
                       and paired_captions_present(session.screen.text), 'Ctrl+P selects the source but keeps both tabs')
        if 'Tab 1/2  Ctrl+P: Preview' not in session.screen.lines[-1]:
            raise AssertionError('Source switch shortcut does not follow the presentation count: '
                                 + session.screen.lines[-1])
        session.send(CTRL_TAB)
        expect_preview(session, 'Ctrl+Tab visits the physical preview tab')
        session.send(CTRL_SHIFT_TAB)
        session.expect(lambda: source_selected(session.screen.text), 'Ctrl+Shift+Tab returns to Source')
        session.send(b'\x10')
        expect_preview(session, 'Ctrl+P reuses the same existing preview')
        session.send(b'NEVER INSERT THIS')
        session.send(b'\x1a')
        session.send(b'\x13')
        session.expect_saved(document, text, 'unchanged source saved from preview', timeout=5)
        expect_preview(session, 'the accepted preview after live save')
        session.send(b'\x01\x03')
        session.expect(lambda: 'Copied Markdown preview text' in session.screen.text, 'semantic preview clipboard copy')
        session.send(b'\x0e\x16')
        session.expect(lambda: source_selected(session.screen.text) and 'flowchart' in session.screen.text,
                       'semantic clipboard pasted into a new source tab')
        copied = session.root / 'semantic.md'
        semantic = ('Preview smoke\n'
                    '\\int_0^{\\infty} e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}\n'
                    'flowchart LR\n A[Editor] --> B[Preview]\n')
        fill_snapshot_filename(session, 'semantic.md')
        session.expect_saved(copied, semantic, 'semantic copy retains raw TeX/Mermaid without boxes', timeout=5)
        session.send(b'\x17')
        session.expect(lambda: not any('semantic.md' in line for line in session.screen.lines[:2]),
                       'the clean clipboard copy tab has closed')
        if not preview_selected(session.screen.text):
            session.send(b'\x10')
        expect_preview(session, 'the original paired preview after closing the copy')
        session.resize(18, 45)
        session.expect(lambda: preview_selected(session.screen.text), 'resized preview')
        if 'Tab 2/2' not in session.screen.lines[-1] or 'Ctrl+P' not in session.screen.lines[-1]:
            raise AssertionError('Narrow preview hides its tab count or switch shortcut: ' + session.screen.lines[-1])
        session.expect(lambda: 'Preview' in session.screen.lines[1], 'active Preview caption survives the narrow width')
        session.snapshot('preview-resized')
        session.send(b'\x10')
        session.expect(lambda: source_selected(session.screen.text), 'source mode')
        session.finish()
    if document.read_text(encoding='utf-8') != text:
        raise AssertionError('Preview modified the source document')
    code_highlighting(args, output)
    sample_sweep(args, output)
    scrollbar_controls(args, output)
    flowchart_scroll(args, output)
    paired_lifecycle(args, output)
    snapshot_save_as(args, output)
    print(f'PASS: Markdown preview PTY ({"native" if args.binary else "JVM"}): {output}', flush=True)


if __name__ == '__main__':
    main()
