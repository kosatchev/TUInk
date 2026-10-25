package dev.tuink.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.SaveDisposition;

/** Mutable file and savepoint state shared by all views of a document. */
public final class EditorDocumentState {
    long documentOwnerId;
    boolean dirty;
    DocumentResource currentResource;
    SaveDisposition saveDisposition = SaveDisposition.CREATE_NEW;
    Charset fileEncoding = StandardCharsets.UTF_8;
    String lineSeparator = EditorState.LINE_SEPARATOR_LF;
}
