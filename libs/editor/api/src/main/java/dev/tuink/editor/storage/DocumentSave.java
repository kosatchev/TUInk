package dev.tuink.editor.storage;

import dev.tuink.editor.document.ImmutableText;
import java.util.List;
import java.util.Objects;

/** Immutable captured save input. A worker never reads the live document. */
public record DocumentSave(DocumentResource resource, List<ImmutableText> lines, DocumentFileMetadata metadata,
                           ResourceVersion baseline, SaveDisposition disposition) {
    public DocumentSave {
        Objects.requireNonNull(resource, "resource");
        lines = List.copyOf(lines);
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(disposition, "disposition");
        if (lines.isEmpty() || !metadata.isCompatibleWithLineCount(lines.size())) {
            throw new IllegalArgumentException("Save content and format must describe one document");
        }
        for (ImmutableText line : lines) {
            if (!line.isWellFormedUtf16() || line.containsLineSeparator()) {
                throw new IllegalArgumentException("Saved lines must contain well-formed logical text");
            }
        }
    }
}
