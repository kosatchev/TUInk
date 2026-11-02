package dev.tuink.markdown;

import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;

/** Source extraction preserves editor text and normalizes only inter-line separators to LF. */
public final class MarkdownSource {
    private MarkdownSource() {}
    public static String text(DocumentView document, TextRange range) {
        TextPosition start = range.start(), end = range.end();
        if (end.line() >= document.lineCount()
            || start.utf16Offset() > document.lineText(start.line()).length()
            || end.utf16Offset() > document.lineText(end.line()).length())
            throw new IllegalArgumentException("Range outside Markdown source");
        StringBuilder out = new StringBuilder();
        for (int line = start.line(); line <= end.line(); line++) {
            if (line != start.line()) out.append('\n');
            CharSequence text = document.lineText(line);
            out.append(text, line == start.line() ? start.utf16Offset() : 0,
                line == end.line() ? end.utf16Offset() : text.length());
        }
        return out.toString();
    }
}
