#!/usr/bin/env python3
"""Open every complex Mermaid sample in a real JVM or native terminal session."""
import argparse
from pathlib import Path
import re
import tempfile

from terminal import Session
from markdown_preview import preview_selected


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--classpath', default=str(root / 'apps/tuink/launcher/build/install/tuink/lib/*'))
    parser.add_argument('--binary', type=Path)
    parser.add_argument('--sample', type=Path, default=root / 'apps/tuink/samples/demo_mermaid_complex.md')
    parser.add_argument('--output-root', type=Path, default=root / 'build/mermaid-preview-pty')
    args = parser.parse_args()
    text = args.sample.read_text(encoding='utf-8')
    blocks = re.findall(r'^```mermaid\n(.*?)\n```$', text, re.MULTILINE | re.DOTALL)
    if len(blocks) != 20:
        raise AssertionError('Expected 18 supported samples followed by two documented unsupported samples')
    args.output_root.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='mermaid-', dir=args.output_root)).resolve()
    for index, source in enumerate(blocks, 1):
        folder = output / f'case-{index:02d}'
        folder.mkdir()
        document = folder / 'diagram.md'
        contents = '```mermaid\n' + source + '\n```\n'
        document.write_text(contents, encoding='utf-8')
        expected_diagnostic = index >= 19
        with Session(folder / 'session', args.java, args.classpath, document,
                     native_executable=args.binary, timeout=30) as session:
            session.resize(44, 180)
            session.send(b'\x10')
            session.expect(lambda: preview_selected(session.screen.text), 'completed Mermaid preview', timeout=10)
            if ('[Mermaid:' in session.screen.text) != expected_diagnostic:
                raise AssertionError(f'Unexpected diagnostic state in sample {index}:\n{session.screen.text}')
            if 'Preview unavailable:' in session.screen.text:
                raise AssertionError(f'Sample {index} failed the complete document preview')
            session.snapshot('diagnostic' if expected_diagnostic else 'rendered')
            session.finish()
        if document.read_text(encoding='utf-8') != contents:
            raise AssertionError(f'Sample {index} changed while being viewed')
        print(f'PASS Mermaid {index:02d}: ' + ('expected diagnostic' if expected_diagnostic else 'rendered'), flush=True)
    headings = set(re.findall(r'^#{2,3} (.+)$', text, re.MULTILINE))
    seen = set()
    with Session(output / 'complete-sample', args.java, args.classpath, args.sample.resolve(),
                 native_executable=args.binary, timeout=60) as session:
        session.resize(44, 180)
        session.send(b'\x10')
        session.expect(lambda: preview_selected(session.screen.text), 'complete Mermaid sample', timeout=10)
        previous = None
        repeats = 0
        for page in range(96):
            frame = session.screen.text
            if 'Preview unavailable:' in frame:
                raise AssertionError('The complete Mermaid document exceeded its preview budget')
            seen.update(heading for heading in headings if heading in frame)
            session.snapshot(f'page-{page:02d}')
            repeats = repeats + 1 if frame == previous else 0
            if repeats >= 2:
                break
            previous = frame
            session.send(b'\x1b[<65;10;10M' * 8)
        if headings - seen:
            raise AssertionError(f'Incomplete sample sweep: {sorted(headings - seen)}')
        session.finish()
    if args.sample.read_text(encoding='utf-8') != text:
        raise AssertionError('The complete sample source changed while being viewed')
    print(f'PASS complete Mermaid document: {len(seen)} headings', flush=True)
    print(f'PASS: 18 Mermaid diagrams and two expected diagnostics: {output}', flush=True)


if __name__ == '__main__':
    main()
