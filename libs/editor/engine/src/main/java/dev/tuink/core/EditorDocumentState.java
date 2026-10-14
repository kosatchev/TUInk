package dev.tuink.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Mutable file and savepoint state shared by all views of a document. */
public final class EditorDocumentState {
    long documentOwnerId;
    boolean dirty;
    Path currentFile;
    SaveDisposition saveDisposition = SaveDisposition.CREATE_NEW;
    Charset fileEncoding = StandardCharsets.UTF_8;
    String lineSeparator = EditorState.LINE_SEPARATOR_LF;
}
