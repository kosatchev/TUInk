package dev.tuink.compat;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorDocument;
import dev.tuink.core.LineText;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.diff.LineDiff;
import dev.tuink.editor.document.ImmutableText;

/** Compiled with only a Gradle dependency on core; its API dependencies must be exported. */
final class PortableCoreApiProbe {
    static ImmutableText text() {
        return LineText.of("external consumer").immutableText();
    }

    static EditorDocument document() {
        EditorDocument document = new EditorDocument(new TextModel(new SimpleLineBuffer()));
        document.attach(LogicalEditorTextLayout::new, new EditorClipboard(), LineDiff.none());
        return document;
    }
}
