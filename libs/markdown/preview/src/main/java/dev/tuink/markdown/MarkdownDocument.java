package dev.tuink.markdown;

import dev.tuink.editor.document.DocumentId;
import java.util.List;
import java.util.Objects;

/** Detached immutable result for one document revision. */
public record MarkdownDocument(DocumentId documentId, long revision, List<MarkdownBlock> blocks,
                               List<MarkdownDiagnostic> diagnostics, TextProjection projection) {
    public MarkdownDocument {
        Objects.requireNonNull(documentId);
        if (revision < 0) throw new IllegalArgumentException("Negative revision");
        blocks = List.copyOf(blocks);
        diagnostics = List.copyOf(diagnostics);
        Objects.requireNonNull(projection);
    }
    public MarkdownDocument(DocumentId id, long revision, List<MarkdownBlock> blocks,
                            List<MarkdownDiagnostic> diagnostics) {
        this(id, revision, blocks, diagnostics, TextProjection.from(blocks));
    }
}
