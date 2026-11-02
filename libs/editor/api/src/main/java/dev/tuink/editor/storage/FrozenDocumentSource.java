package dev.tuink.editor.storage;

import dev.tuink.editor.document.DocumentSnapshot;

import java.util.Objects;

/**
 * Exact immutable source text and file state captured together by a document owner.
 * A trailing empty logical line retains the final separator described by metadata.
 * The envelope owns no mutable editor, history, view, or platform filesystem object.
 */
public record FrozenDocumentSource(DocumentSnapshot text, DocumentFileMetadata metadata, ResourceBinding binding) {
    public FrozenDocumentSource {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(binding, "binding");
        if (!metadata.isCompatibleWithLineCount(text.lineCount())) {
            throw new IllegalArgumentException("Source text and file metadata must describe the same logical lines");
        }
    }

    /** Updates a confirmed resource association without replacing the accepted source or its format. */
    public FrozenDocumentSource withBinding(ResourceBinding nextBinding) {
        Objects.requireNonNull(nextBinding, "nextBinding");
        return binding.equals(nextBinding) ? this : new FrozenDocumentSource(text, metadata, nextBinding);
    }
}
