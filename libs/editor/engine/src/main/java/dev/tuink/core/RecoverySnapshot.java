package dev.tuink.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
    Charset fileEncoding,
    String lineSeparator,
    Instant createdAt
) {
    public RecoverySnapshot {
        lines = lines == null ? List.of("") : List.copyOf(lines);
        cursor = cursor == null ? new Position(0, 0) : cursor;
        viewport = viewport == null ? new ViewportState(0, 0, 0, 0) : viewport;
        fileEncoding = fileEncoding == null ? StandardCharsets.UTF_8 : fileEncoding;
        lineSeparator = normalizeLineSeparator(lineSeparator);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static RecoverySnapshot capture(TextModel model, EditorState state) {
        return new RecoverySnapshot(
            model.snapshotLines(),
            state.getCursor(),
            state.viewport(),
            state.getSelectionAnchor(),
            state.isDirty(),
            state.getCurrentFile(),
            state.getFileEncoding(),
            state.getLineSeparator(),
            Instant.now()
        );
    }

    public void restore(TextModel model, EditorState state) {
        model.replaceLines(lines);
        state.setCurrentFile(currentFile);
        state.setFileEncoding(fileEncoding);
        state.setLineSeparator(lineSeparator);
        state.setViewport(viewport);
        state.setCursor(model.clamp(cursor));
        state.setSelectionAnchor(selectionAnchor);
        state.setDirty(dirty);
    }

    private static String normalizeLineSeparator(String separator) {
        if (EditorState.LINE_SEPARATOR_CRLF.equals(separator)) {
            return EditorState.LINE_SEPARATOR_CRLF;
        }
        if (EditorState.LINE_SEPARATOR_CR.equals(separator)) {
            return EditorState.LINE_SEPARATOR_CR;
        }
        return EditorState.LINE_SEPARATOR_LF;
    }
}
