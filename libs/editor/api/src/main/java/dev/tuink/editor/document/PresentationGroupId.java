package dev.tuink.editor.document;

import java.util.UUID;

/** Frontend grouping identity that can outlive a document's editing session. */
public record PresentationGroupId(String value) {
    public PresentationGroupId {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException("Presentation group ID must be non-blank and at most 512 characters");
        }
    }

    public static PresentationGroupId create() { return new PresentationGroupId(UUID.randomUUID().toString()); }
}
