package dev.tuink.editor.storage;

import java.util.Objects;

/**
 * Receipt returned only after a successful commit. Allocate the receipt and all
 * mandatory result data before publication, so allocation cannot obscure success.
 */
public record SavedDocument(DocumentResource resource, DocumentFileMetadata metadata,
                            ResourceVersion baseline, PublicationKind publicationKind) {
    public SavedDocument {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(publicationKind, "publicationKind");
    }
}
