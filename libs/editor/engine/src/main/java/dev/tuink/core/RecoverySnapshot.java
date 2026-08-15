package dev.tuink.core;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Full document/state checkpoint for autosave/restore.
 */
public record RecoverySnapshot(
    List<String> lines,
    Position cursor,
    ViewportState viewport,
    Position selectionAnchor,
    boolean dirty,
    Path currentFile,
    SaveDisposition saveDisposition,
    DocumentFileMetadata fileMetadata,
    Instant createdAt
) {
    public RecoverySnapshot {
        lines = LogicalDocumentLines.copyOfOrEmptyDocument(lines);
        cursor = cursor == null ? new Position(0, 0) : cursor;
        viewport = viewport == null ? new ViewportState(0, 0, 0, 0) : viewport;
        fileMetadata = fileMetadata == null
            ? DocumentFileMetadata.newDocument(lines.size())
            : fileMetadata;
        if (!fileMetadata.isCompatibleWithLineCount(lines.size())) {
            throw new IllegalArgumentException("File metadata does not match recovery document lines");
        }
        requirePositionInDocument(cursor, lines, "cursor");
        if (selectionAnchor != null) {
            requirePositionInDocument(selectionAnchor, lines, "selection anchor");
        }
        if (viewport.topLine() >= lines.size()) {
            throw new IllegalArgumentException("Viewport top line is outside recovery document");
        }
        currentFile = currentFile == null ? null : currentFile.toAbsolutePath().normalize();
        saveDisposition = saveDisposition == null
            ? (currentFile == null ? SaveDisposition.CREATE_NEW : SaveDisposition.REPLACE_EXISTING)
            : saveDisposition;
        if (currentFile == null && saveDisposition != SaveDisposition.CREATE_NEW) {
            throw new IllegalArgumentException(
                "A recovery snapshot without a current path cannot replace an existing file"
            );
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /** Compatibility constructor for callers that already own an existing path. */
    public RecoverySnapshot(
        List<String> lines,
        Position cursor,
        ViewportState viewport,
        Position selectionAnchor,
        boolean dirty,
        Path currentFile,
        DocumentFileMetadata fileMetadata,
        Instant createdAt
    ) {
        this(
            lines,
            cursor,
            viewport,
            selectionAnchor,
            dirty,
            currentFile,
            currentFile == null ? SaveDisposition.CREATE_NEW : SaveDisposition.REPLACE_EXISTING,
            fileMetadata,
            createdAt
        );
    }

    public static RecoverySnapshot capture(TextModel model, EditorState state) {
        return new RecoverySnapshot(
            model.snapshotLines(),
            state.getCursor(),
            state.viewport(),
            state.getSelectionAnchor(),
            state.isDirty(),
            state.getCurrentFile(),
            state.getSaveDisposition(),
            model.fileMetadata(),
            Instant.now()
        );
    }

    public void restore(TextModel model, EditorState state) {
        model.replaceLines(lines, fileMetadata);
        state.setCurrentFile(currentFile);
        state.setSaveDisposition(saveDisposition);
        state.setFileEncoding(fileMetadata.charset());
        state.setLineSeparator(fileMetadata.defaultLineSeparator());
        state.setViewport(viewport);
        state.setCursor(model.clamp(cursor));
        state.setSelectionAnchor(selectionAnchor);
        state.setDirty(dirty);
    }

    public Charset fileEncoding() {
        return fileMetadata.charset();
    }

    public String lineSeparator() {
        return fileMetadata.defaultLineSeparator();
    }

    private static void requirePositionInDocument(
        Position position,
        List<String> lines,
        String field
    ) {
        if (position.line() >= lines.size() || position.col() > lines.get(position.line()).length()) {
            throw new IllegalArgumentException(field + " is outside recovery document");
        }
        String line = lines.get(position.line());
        int offset = position.col();
        if (offset > 0
            && offset < line.length()
            && Character.isHighSurrogate(line.charAt(offset - 1))
            && Character.isLowSurrogate(line.charAt(offset))) {
            throw new IllegalArgumentException(field + " splits a UTF-16 surrogate pair");
        }
    }
}
