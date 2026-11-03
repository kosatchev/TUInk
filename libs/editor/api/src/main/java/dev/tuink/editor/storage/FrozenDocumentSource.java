package dev.tuink.editor.storage;

import dev.tuink.editor.document.DocumentSnapshot;

import java.util.Objects;

/**
 * Exact immutable source text and file state captured together by a document owner.
 * A trailing empty logical line retains the final separator described by metadata.
 * The envelope owns no mutable editor, history, view, or platform filesystem object.
 * The reopen decoder describes the baseline bytes, independently of unsaved output-format changes.
 */
public record FrozenDocumentSource(DocumentSnapshot text, DocumentFileMetadata metadata, ResourceBinding binding,
                                   DocumentOpenMode reopenMode) {
    public FrozenDocumentSource(DocumentSnapshot text, DocumentFileMetadata metadata, ResourceBinding binding) {
        this(text, metadata, binding, DocumentOpenMode.DETECT);
    }

    public FrozenDocumentSource {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(reopenMode, "reopenMode");
        if (!metadata.isCompatibleWithLineCount(text.lineCount())) {
            throw new IllegalArgumentException("Source text and file metadata must describe the same logical lines");
        }
    }

    /** Updates a confirmed resource association without replacing the accepted source or its format. */
    public FrozenDocumentSource withBinding(ResourceBinding nextBinding) {
        return withBinding(nextBinding, reopenMode);
    }

    /** Updates the confirmed disk state while retaining the accepted source and its output format. */
    public FrozenDocumentSource withBinding(ResourceBinding nextBinding, DocumentOpenMode nextReopenMode) {
        Objects.requireNonNull(nextBinding, "nextBinding");
        Objects.requireNonNull(nextReopenMode, "nextReopenMode");
        return binding.equals(nextBinding) && reopenMode == nextReopenMode ? this
            : new FrozenDocumentSource(text, metadata, nextBinding, nextReopenMode);
    }
}
