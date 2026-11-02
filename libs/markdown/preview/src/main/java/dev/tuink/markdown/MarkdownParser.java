package dev.tuink.markdown;

import dev.tuink.editor.document.DocumentSnapshot;

/** Parser provider boundary. Background callers supply immutable snapshots only. */
public interface MarkdownParser {
    MarkdownDocument parse(DocumentSnapshot source, ParseLimits limits, ParseControl control);
    default MarkdownDocument parse(DocumentSnapshot source) { return parse(source, ParseLimits.DEFAULT, ParseControl.NONE); }
}
