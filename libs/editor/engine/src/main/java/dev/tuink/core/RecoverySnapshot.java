package dev.tuink.core;

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
    FileBaseline fileBaseline,
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
        if (fileBaseline != null
            && (currentFile == null || saveDisposition != SaveDisposition.REPLACE_EXISTING)) {
            throw new IllegalArgumentException("A file baseline requires an existing current file");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static RecoverySnapshot capture(TextModel model, EditorState state) {
        return prepare(model, state).materialize();
    }

    /** Capture immutable text blocks and view fields without flattening document lines. */
    public static Prepared prepare(TextModel model, EditorState state) {
        // Autosave can run after an edit and before the next frame adjusts the
        // viewport. Keep the checkpoint valid without changing the live view.
        ViewportState viewport = state.viewport();
        int topLine = Math.min(viewport.topLine(), model.lineCount() - 1);
        if (topLine != viewport.topLine()) {
            viewport = new ViewportState(topLine, 0, viewport.leftColumn(), viewport.leftVisualColumn());
        }
        return new Prepared(
            model.snapshotTextLines(),
            state.getCursor(),
            viewport,
            state.getSelectionAnchor(),
            state.isDirty(),
            state.getCurrentFile(),
            state.getSaveDisposition(),
            model.fileMetadata(),
            state.getCurrentFile() != null && state.getSaveDisposition() == SaveDisposition.REPLACE_EXISTING
                ? model.fileBaseline() : null,
            Instant.now()
        );
    }

    /** An owner-thread capture which may be materialized by an I/O worker. */
    public record Prepared(
        List<LineText> lines,
        Position cursor,
        ViewportState viewport,
        Position selectionAnchor,
        boolean dirty,
        Path currentFile,
        SaveDisposition saveDisposition,
        DocumentFileMetadata fileMetadata,
        FileBaseline fileBaseline,
        Instant createdAt
    ) {
        public Prepared {
            lines = List.copyOf(lines);
        }

        public RecoverySnapshot materialize() {
            return new RecoverySnapshot(lines.stream().map(LineText::toString).toList(), cursor, viewport,
                selectionAnchor, dirty, currentFile, saveDisposition, fileMetadata, fileBaseline, createdAt);
        }
    }

    public void restore(TextModel model, EditorState state) {
        model.replaceLines(lines, fileMetadata);
        model.restoreFileBaseline(fileBaseline);
        state.setCurrentFile(currentFile);
        state.setSaveDisposition(saveDisposition);
        state.setFileEncoding(fileMetadata.charset());
        state.setLineSeparator(fileMetadata.defaultLineSeparator());
        state.setViewport(viewport);
        state.setCursor(model.clamp(cursor));
        state.setSelectionAnchor(selectionAnchor);
        state.setDirty(dirty);
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
