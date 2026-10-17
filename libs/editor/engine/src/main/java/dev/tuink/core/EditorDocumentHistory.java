package dev.tuink.core;

import java.util.ArrayDeque;
import java.util.Deque;

/** One document history and savepoint chain, shared by every attached editing view. */
final class EditorDocumentHistory {
    Deque<EditorEngine.HistoryEntry> undoStack = new ArrayDeque<>();
    final Deque<EditorEngine.HistoryEntry> redoStack = new ArrayDeque<>();
    EditorEngine.EditKind lastEditKind = EditorEngine.EditKind.NONE;
    long lastEditAtNanos;
    Position lastEditCursorAfter;
    long lastEditViewId = -1;
    long nextDocumentStateId;
    long currentDocumentStateId;
    long savedDocumentStateId;
}
