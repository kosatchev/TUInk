package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EditorEngineRecoveryAtomicityTest {
    @Test
    void baselineAllocationFailureAfterStagingPreservesDraftResourceAndBothHistoryBranches() throws Exception {
        Fixture fixture = fixture();
        RecoverySnapshot recovery = replacement(fixture.storage, true);
        assertFailedRestorePreservesEverything(fixture, recovery, () -> fixture.model.failSnapshot = true,
            () -> fixture.model.failSnapshot = false);
        assertEquals(1, fixture.model.failedSnapshots, "Failure must occur after staging the replacement");
    }

    @Test
    void bufferReservationFailurePreservesDraftResourceAndBothHistoryBranches() throws Exception {
        Fixture fixture = fixture();
        RecoverySnapshot recovery = replacement(fixture.storage, false);
        assertFailedRestorePreservesEverything(fixture, recovery, () -> fixture.failReservation.set(true),
            () -> fixture.failReservation.set(false));
    }

    @Test
    void foreignViewClampFailureAfterStagingPreservesTheDraftAndAllowsRestoreAgain() throws Exception {
        Fixture fixture = fixture();
        RecoverySnapshot recovery = replacement(fixture.storage, true);
        FailingClampViewState foreign = (FailingClampViewState) fixture.other.state();
        long revision = fixture.model.revision();
        int calls = fixture.storage.calls();

        assertFailedRestorePreservesEverything(fixture, recovery, () -> foreign.beforeFailure = () -> {
            assertEquals(recovery.lines(), fixture.model.snapshotLines(),
                "Foreign view preparation must follow recovery text staging");
            assertEquals(recovery.fileMetadata(), fixture.model.fileMetadata());
            assertEquals(recovery.fileBaseline(), fixture.model.fileBaseline());
            assertEquals(revision + 1, fixture.model.revision());
        }, () -> foreign.beforeFailure = null);

        assertEquals(1, foreign.failedClamps, "The injected failure must occur in foreign view preparation");
        fixture.engine.restoreRecoverySnapshot(recovery);

        assertEquals(recovery.lines(), fixture.model.snapshotLines());
        assertEquals(recovery.fileMetadata(), fixture.model.fileMetadata());
        assertEquals(recovery.fileBaseline(), fixture.model.fileBaseline());
        assertSame(recovery.currentResource(), fixture.engine.state().getCurrentResource());
        assertEquals(recovery.saveDisposition(), fixture.engine.state().getSaveDisposition());
        assertEquals(recovery.cursor(), fixture.engine.state().getCursor());
        assertEquals(recovery.selectionAnchor(), fixture.engine.state().getSelectionAnchor());
        assertEquals(recovery.viewport(), fixture.engine.state().viewport());
        assertEquals(new Position(2, 1), foreign.getCursor());
        assertEquals(new Position(2, 1), foreign.getSelectionAnchor());
        assertTrue(fixture.engine.state().isDirty());
        assertFalse(fixture.engine.undo(), "Successful retry must start a fresh recovery history");
        assertFalse(fixture.engine.redo());
        fixture.engine.insertChar('X');
        assertTrue(fixture.engine.undo());
        assertEquals(recovery.lines(), fixture.model.snapshotLines());
        assertTrue(fixture.engine.state().isDirty(), "Undo must return to the dirty recovery savepoint");
        assertTrue(fixture.engine.redo());
        assertTrue(fixture.engine.undo());
        assertEquals(recovery.lines(), fixture.model.snapshotLines());
        assertEquals(calls, fixture.storage.calls(), "Neither failed nor successful restore may contact storage");
    }

    @Test
    void successfulRestorePublishesMetadataOriginAndForeignViewClampsWithTheCorrectSavepoint() throws Exception {
        for (boolean dirty : new boolean[] {false, true}) {
            Fixture fixture = fixture();
            RecoverySnapshot recovery = replacement(fixture.storage, dirty);
            ViewportState otherViewport = fixture.other.state().viewport();
            long revision = fixture.model.revision();
            long recoveryRevision = fixture.engine.recoveryRevision();
            int calls = fixture.storage.calls();
            byte[] originalBytes = fixture.storage.bytes("original.txt");
            byte[] replacementBytes = fixture.storage.bytes("replacement.txt");
            int captures = fixture.model.snapshots;

            fixture.engine.restoreRecoverySnapshot(recovery);

            assertEquals(recovery.lines(), fixture.model.snapshotLines());
            assertEquals(recovery.fileMetadata(), fixture.model.fileMetadata());
            assertEquals(recovery.fileBaseline(), fixture.model.fileBaseline());
            assertSame(recovery.currentResource(), fixture.engine.state().getCurrentResource());
            assertEquals(recovery.saveDisposition(), fixture.engine.state().getSaveDisposition());
            assertEquals(recovery.fileMetadata().charset(), fixture.engine.state().getFileEncoding());
            assertEquals(recovery.fileMetadata().defaultLineSeparator(), fixture.engine.state().getLineSeparator());
            assertEquals(recovery.cursor(), fixture.engine.state().getCursor());
            assertEquals(recovery.selectionAnchor(), fixture.engine.state().getSelectionAnchor());
            assertEquals(recovery.viewport(), fixture.engine.state().viewport());
            assertEquals(dirty, fixture.engine.state().isDirty());
            assertEquals(dirty, fixture.other.state().isDirty());
            assertEquals(new Position(2, 1), fixture.other.state().getCursor());
            assertEquals(new Position(2, 1), fixture.other.state().getSelectionAnchor());
            assertEquals(otherViewport, fixture.other.state().viewport());
            assertEquals(revision + 1, fixture.model.revision());
            assertEquals(recoveryRevision + 2, fixture.engine.recoveryRevision(),
                "One text revision and one document publication advance the recovery token");
            assertEquals(captures + 1, fixture.model.snapshots,
                "The immutable diff baseline is captured once before publication");
            assertSame(fixture.engine.document().baselines.openLines, fixture.engine.document().baselines.savedLines);
            assertFalse(fixture.engine.undo(), "Restore starts a fresh document history");
            assertFalse(fixture.engine.redo());
            assertEquals(calls, fixture.storage.calls(), "Restore must not contact storage");
            assertArrayEquals(originalBytes, fixture.storage.bytes("original.txt"));
            assertArrayEquals(replacementBytes, fixture.storage.bytes("replacement.txt"));

            fixture.engine.insertChar('X');
            assertTrue(fixture.engine.undo());
            assertEquals(recovery.lines(), fixture.model.snapshotLines());
            assertEquals(dirty, fixture.engine.state().isDirty(), "Undo must return to the restored savepoint");
        }
    }

    private static void assertFailedRestorePreservesEverything(Fixture fixture, RecoverySnapshot recovery,
                                                               Runnable arm, Runnable disarm) {
        RecoverySnapshot before = fixture.engine.captureRecoverySnapshot();
        EditorViewMemento otherBefore = fixture.other.state().viewMemento();
        long otherNavigation = fixture.other.engine().navigationRevision();
        long revision = fixture.model.revision();
        long epoch = fixture.model.contentEpoch();
        long navigation = fixture.engine.navigationRevision();
        long recoveryRevision = fixture.engine.recoveryRevision();
        EditorDocumentHistory history = fixture.engine.document().history;
        var undo = history.undoStack;
        var redo = history.redoStack;
        long currentState = history.currentDocumentStateId;
        long nextState = history.nextDocumentStateId;
        long savedState = history.savedDocumentStateId;
        EditorEngine.EditKind coalescing = history.lastEditKind;
        long coalescingAt = history.lastEditAtNanos;
        Position coalescingCursor = history.lastEditCursorAfter;
        long coalescingView = history.lastEditViewId;
        var baseline = fixture.engine.document().baselines.openLines;
        var savedBaseline = fixture.engine.document().baselines.savedLines;
        long baselineGeneration = fixture.engine.document().baselines.generation;
        int calls = fixture.storage.calls();
        byte[] originalBytes = fixture.storage.bytes("original.txt");
        byte[] replacementBytes = fixture.storage.bytes("replacement.txt");

        arm.run();
        try {
            assertThrows(DocumentEditLimitException.class, () -> fixture.engine.restoreRecoverySnapshot(recovery));
        } finally {
            disarm.run();
        }

        assertEquals(before.lines(), fixture.model.snapshotLines());
        assertEquals(before.fileMetadata(), fixture.model.fileMetadata());
        assertEquals(before.fileBaseline(), fixture.model.fileBaseline());
        assertSame(before.currentResource(), fixture.engine.state().getCurrentResource());
        assertEquals(before.saveDisposition(), fixture.engine.state().getSaveDisposition());
        assertEquals(before.fileMetadata().charset(), fixture.engine.state().getFileEncoding());
        assertEquals(before.fileMetadata().defaultLineSeparator(), fixture.engine.state().getLineSeparator());
        assertEquals(before.dirty(), fixture.engine.state().isDirty());
        assertEquals(before.cursor(), fixture.engine.state().getCursor());
        assertEquals(before.selectionAnchor(), fixture.engine.state().getSelectionAnchor());
        assertEquals(before.viewport(), fixture.engine.state().viewport());
        assertEquals(otherBefore, fixture.other.state().viewMemento());
        assertEquals(otherNavigation, fixture.other.engine().navigationRevision());
        assertEquals(revision, fixture.model.revision());
        assertEquals(epoch, fixture.model.contentEpoch());
        assertEquals(navigation, fixture.engine.navigationRevision());
        assertEquals(recoveryRevision, fixture.engine.recoveryRevision());
        assertSame(undo, history.undoStack);
        assertSame(redo, history.redoStack);
        assertEquals(currentState, history.currentDocumentStateId);
        assertEquals(nextState, history.nextDocumentStateId);
        assertEquals(savedState, history.savedDocumentStateId);
        assertEquals(coalescing, history.lastEditKind);
        assertEquals(coalescingAt, history.lastEditAtNanos);
        assertEquals(coalescingCursor, history.lastEditCursorAfter);
        assertEquals(coalescingView, history.lastEditViewId);
        assertSame(baseline, fixture.engine.document().baselines.openLines);
        assertSame(savedBaseline, fixture.engine.document().baselines.savedLines);
        assertEquals(baselineGeneration, fixture.engine.document().baselines.generation);
        assertEquals("workspace clipboard", fixture.clipboard.text());
        assertEquals(calls, fixture.storage.calls());
        assertArrayEquals(originalBytes, fixture.storage.bytes("original.txt"));
        assertArrayEquals(replacementBytes, fixture.storage.bytes("replacement.txt"));
        assertTrue(fixture.engine.redo());
        assertEquals(fixture.redoLines, fixture.model.snapshotLines());
        assertTrue(fixture.engine.undo());
        assertEquals(before.lines(), fixture.model.snapshotLines());
        assertTrue(fixture.engine.undo());
        assertFalse(fixture.engine.state().isDirty(), "The original loaded savepoint remains reachable");
    }

    private static Fixture fixture() throws Exception {
        MemoryDocumentStorage storage = new MemoryDocumentStorage();
        storage.put("original.txt", "\uFEFFalpha\r\nbeta\nthird\rfourth\nfifth\nsixth".getBytes(StandardCharsets.UTF_8));
        AtomicBoolean failReservation = new AtomicBoolean();
        FailingSnapshotModel model = new FailingSnapshotModel(new ReservationBuffer(failReservation));
        EditorClipboard clipboard = new EditorClipboard();
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model), clipboard);
        engine.openPrepared(storage.load(storage.resource("original.txt"), DocumentOpenMode.DETECT, false));
        engine.insertChar('X');
        engine.insertNewline();
        List<String> redoLines = model.snapshotLines();
        assertTrue(engine.undo());
        engine.restoreSelection(new Position(1, 3), new Position(1, 1));
        engine.state().setViewport(new ViewportState(1, 1, 2, 3));
        FailingClampViewState otherState = new FailingClampViewState(engine.document().state);
        EditorTextLayout otherLayout = new LogicalEditorTextLayout(model);
        EditorEngine otherEngine = new EditorEngine(engine.document(), otherState, otherLayout, clipboard);
        TabSession other = new TabSession(model, otherState, otherLayout, otherEngine);
        other.engine().restoreSelection(new Position(5, 4), new Position(4, 3));
        other.state().setViewport(new ViewportState(4, 1, 7, 8));
        clipboard.setText("workspace clipboard");
        return new Fixture(engine, model, storage, clipboard, failReservation, other, redoLines);
    }

    private static RecoverySnapshot replacement(MemoryDocumentStorage storage, boolean dirty) throws Exception {
        storage.put("replacement.txt", "\uFEFF😀new\r\nshort\nz".getBytes(StandardCharsets.UTF_16LE));
        DocumentResource resource = storage.resource("replacement.txt");
        var loaded = storage.load(resource, DocumentOpenMode.DETECT, false);
        DocumentFileMetadata metadata = loaded.metadata();
        return new RecoverySnapshot(loaded.lines(), new Position(1, 2), new ViewportState(1, 2, 3, 4),
            new Position(0, 2), dirty, resource, SaveDisposition.REPLACE_EXISTING, metadata,
            loaded.baseline(), Instant.parse("2026-10-21T00:00:00Z"));
    }

    private record Fixture(EditorEngine engine, FailingSnapshotModel model, MemoryDocumentStorage storage,
                           EditorClipboard clipboard, AtomicBoolean failReservation, TabSession other,
                           List<String> redoLines) {}

    private static final class FailingSnapshotModel extends TextModel {
        private boolean failSnapshot;
        private int snapshots;
        private int failedSnapshots;
        private FailingSnapshotModel(TextBuffer buffer) { super(buffer); }
        @Override public List<LineText> snapshotTextLines() {
            snapshots++;
            if (failSnapshot) {
                failedSnapshots++;
                throw new OutOfMemoryError("restored diff baseline");
            }
            return super.snapshotTextLines();
        }
    }

    private static final class FailingClampViewState extends EditorState {
        private Runnable beforeFailure;
        private int failedClamps;

        private FailingClampViewState(EditorDocumentState document) {
            super(document, new EditorViewState());
        }

        @Override public Position getCursor() {
            if (beforeFailure != null) {
                beforeFailure.run();
                failedClamps++;
                throw new OutOfMemoryError("recovery foreign view clamp preparation");
            }
            return super.getCursor();
        }
    }

    private static final class ReservationBuffer extends SimpleLineBuffer {
        private final AtomicBoolean fail;
        private ReservationBuffer(AtomicBoolean fail) { this.fail = fail; }
        @Override public TextBuffer createEmptySibling() { return new ReservationBuffer(fail); }
        @Override protected boolean supportsPreparedPublication() { return true; }
        @Override protected void reserveLines(int required) {
            if (fail.get()) throw new OutOfMemoryError("restore buffer reservation");
            super.reserveLines(required);
        }
    }
}
