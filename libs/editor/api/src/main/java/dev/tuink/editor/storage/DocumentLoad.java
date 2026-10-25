package dev.tuink.editor.storage;

import dev.tuink.editor.document.Utf16Text;
import java.util.List;
import java.util.Objects;

/** Independent read result prepared on a worker, ready for owner publication. */
public record DocumentLoad(DocumentResource resource, List<String> lines, DocumentFileMetadata metadata,
                           ResourceVersion baseline, SaveDisposition disposition) {
    public DocumentLoad {
        Objects.requireNonNull(resource, "resource");
        lines = List.copyOf(lines);
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(disposition, "disposition");
        if (lines.isEmpty() || !metadata.isCompatibleWithLineCount(lines.size())) {
            throw new IllegalArgumentException("Loaded content and format must describe one document");
        }
        for (String line : lines) {
            Utf16Text.requireWellFormed(line, "Loaded line");
            if (line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Loaded lines must contain well-formed logical text");
            }
        }
        new ResourceBinding(resource, disposition, baseline);
        if (disposition == SaveDisposition.REPLACE_EXISTING && baseline == null) {
            throw new IllegalArgumentException("An existing resource read must include its observed version");
        }
    }
}
