package dev.tuink.editor.document;

import java.util.UUID;

/** Stable identity of a frontend presentation, independent of any editing view. */
public record PresentationId(String value) {
    public PresentationId {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException("Presentation ID must be non-blank and at most 512 characters");
        }
    }

    public static PresentationId create() { return new PresentationId(UUID.randomUUID().toString()); }
}
