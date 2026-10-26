package dev.tuink.editor.recovery;

import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.ResourceVersion;
import dev.tuink.editor.storage.SaveDisposition;
import java.time.Instant;
import java.util.List;

/** Immutable draft data for owner restore; frontend scroll/layout state belongs to its host. */
public interface RecoveryDocument {
    List<String> lines();
    TextPosition caret();
    TextPosition anchor();
    boolean dirty();
    DocumentResource currentResource();
    SaveDisposition saveDisposition();
    DocumentFileMetadata fileMetadata();
    ResourceVersion fileBaseline();
    Instant createdAt();
}
