package dev.tuink.render;

import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentView;
import java.util.Objects;

/** Read-only capability adapter used at the render/highlighter boundary. */
final class TextModelDocumentView implements DocumentView {
    private final TextModel model;
    private final DocumentId documentId;

    TextModelDocumentView(TextModel model) {
        this.model = Objects.requireNonNull(model, "model");
        this.documentId = new DocumentId("render-model-" + Long.toUnsignedString(model.cacheIdentity()));
    }

    @Override
    public DocumentId documentId() {
        return documentId;
    }

    @Override
    public long revision() {
        return model.revision();
    }

    @Override
    public int lineCount() {
        return model.lineCount();
    }

    @Override
    public String line(int index) {
        return model.getLine(index);
    }

    @Override
    public int firstChangedLineSince(long earlierRevision) {
        return model.firstChangedLineSince(earlierRevision);
    }
}
