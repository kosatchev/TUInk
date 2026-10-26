package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.core.DocumentEditLimitException;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorDocument;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorViewMemento;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.LineText;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.core.ViewportState;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.session.edit.WorkspaceEditValidator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EditorDocumentSnapshotPublicationTest {
    @Test
    void readingDocumentIdentityDoesNotPublishOrReadUnpublishedText() throws InterruptedException {
        Fixture fixture = fixture(128);
        DocumentSnapshot initial = fixture.session.snapshot();
        fixture.view.engine().insertChar('X');
        fixture.reads.lines.set(0);

        assertSame(initial.documentId(), fixture.session.documentId());
        AtomicReference<DocumentId> backgroundIdentity = new AtomicReference<>();
        Thread background = new Thread(() -> backgroundIdentity.set(fixture.session.documentId()));
        background.start();
        background.join();

        assertSame(initial.documentId(), backgroundIdentity.get());
        assertSame(initial, backgroundSnapshot(fixture.session));
        assertEquals(0, fixture.reads.lines.get());
        assertEquals(0, fixture.reads.foreignAccesses.get());
    }

    @Test
    void typingDefersWholeDocumentReadsUntilAnOwnerSnapshotRequest() throws InterruptedException {
        Fixture fixture = fixture(20_000);
        DocumentSnapshot initial = fixture.session.snapshot();
        fixture.reads.lines.set(0);

        for (char character : new char[] {'q', 'w', 'e', 'r', 't'}) {
            fixture.view.engine().insertChar(character);
        }

        int typingReads = fixture.reads.lines.get();
        assertTrue(typingReads < 1_000, "a few UI edits must not scan the 20,000-line document");
        assertSame(initial, backgroundSnapshot(fixture.session));
        assertEquals(typingReads, fixture.reads.lines.get(), "background reads must not copy unpublished UI edits");
        assertEquals(0, fixture.reads.foreignAccesses.get());

        DocumentSnapshot current = fixture.session.snapshot();

        assertNotSame(initial, current);
        assertEquals("qwertline 0", current.line(0));
        assertEquals("line 0", initial.line(0), "previously published snapshots remain immutable");
        int publishedReads = fixture.reads.lines.get();
        assertTrue(publishedReads - typingReads >= 20_000, "the explicit request captures immutable line references");
        assertSame(current, fixture.session.snapshot());
        assertSame(current, backgroundSnapshot(fixture.session));
        assertEquals(publishedReads, fixture.reads.lines.get(), "repeated unchanged requests reuse the publication");
        assertEquals(0, fixture.reads.foreignAccesses.get());
    }

    @Test
    void staleWorkspaceEditsRefreshUnpublishedUiChangesBeforeValidation() {
        Fixture fixture = fixture(128);
        DocumentSnapshot initial = fixture.session.snapshot();
        fixture.reads.lines.set(0);
        fixture.view.engine().insertChar('X');
        int typingReads = fixture.reads.lines.get();
        var stale = new WorkspaceEdit(initial.documentId(), initial.revision(), List.of(
            new TextEdit(new TextRange(new TextPosition(0, 0), new TextPosition(0, 1)), "rejected")
        ));

        var rejected = assertInstanceOf(WorkspaceEditResult.Rejected.class, fixture.session.applyWorkspaceEdit(stale));

        assertEquals(WorkspaceEditResult.RejectionReason.VALIDATION_FAILED, rejected.reason());
        assertTrue(rejected.violations().stream().anyMatch(violation -> violation.code() == WorkspaceEditValidator.Code.STALE_REVISION));
        assertEquals("Xline 0", rejected.currentSnapshot().line(0));
        int validationReads = fixture.reads.lines.get();
        assertEquals(128, validationReads - typingReads, "validation refreshes the snapshot once");
        assertSame(rejected.currentSnapshot(), fixture.session.snapshot());
        assertEquals(validationReads, fixture.reads.lines.get());
        assertTrue(fixture.view.engine().undo(), "the rejected provider edit adds no document history entry");
        assertEquals("line 0", fixture.view.model().getLine(0));
    }

    @Test
    void failedWorkspaceEditSnapshotCaptureKeepsThePublishedSnapshotAndBothHistoryBranches() throws InterruptedException {
        SnapshotFailureFixture fixture = snapshotFailureFixture();
        BeforeFailure before = observe(fixture);
        WorkspaceEdit proposed = replaceDocument(before.snapshot(), "replacement");
        fixture.model.failSnapshot = true;
        WorkspaceEditResult result;
        try {
            result = fixture.session.applyWorkspaceEdit(proposed);
        } catch (OutOfMemoryError failure) {
            throw new AssertionError("A failed mandatory snapshot must produce an edit-limit rejection", failure);
        } finally {
            fixture.model.failSnapshot = false;
        }

        var rejected = assertInstanceOf(WorkspaceEditResult.Rejected.class, result);
        assertEquals(WorkspaceEditResult.RejectionReason.EDIT_LIMIT_EXCEEDED, rejected.reason());
        assertSame(before.snapshot(), rejected.currentSnapshot());
        assertFailedCapturePreservesPublication(fixture, before, List.of("replacement"));
        assertBothHistoryBranchesRemainUsable(fixture, before.lines());

        var retried = assertInstanceOf(WorkspaceEditResult.Applied.class,
            fixture.session.applyWorkspaceEdit(replaceDocument(fixture.session.snapshot(), "replacement")));

        assertTrue(retried.changed());
        assertEquals(List.of("replacement"), retried.snapshot().lines());
        assertNotSame(before.snapshot(), retried.snapshot());
        assertSame(retried.snapshot(), fixture.session.snapshot());
        assertSame(retried.snapshot(), backgroundSnapshot(fixture.session));
        assertEquals(before.lines(), before.snapshot().lines(), "The prior publication remains immutable after retry");
        assertTrue(fixture.session.undo());
        assertEquals(before.lines(), fixture.session.snapshot().lines());
        assertTrue(fixture.session.redo());
        assertEquals(List.of("replacement"), fixture.session.snapshot().lines());
    }

    @Test
    void failedUndoAndRedoSnapshotCaptureKeepsThePublishedSnapshotViewsAndReplayableHistory() throws InterruptedException {
        for (boolean undoing : List.of(false, true)) {
            SnapshotFailureFixture fixture = snapshotFailureFixture();
            BeforeFailure before = observe(fixture);
            List<String> stagedLines = undoing ? fixture.initialSnapshot.lines() : fixture.redoLines;
            fixture.model.failSnapshot = true;
            try {
                assertInstanceOf(DocumentEditLimitException.class, assertThrows(Throwable.class, () -> {
                    if (undoing) fixture.session.undo();
                    else fixture.session.redo();
                }));
            } finally {
                fixture.model.failSnapshot = false;
            }

            assertFailedCapturePreservesPublication(fixture, before, stagedLines);

            assertTrue(undoing ? fixture.session.undo() : fixture.session.redo());
            DocumentSnapshot retried = fixture.session.snapshot();
            assertEquals(stagedLines, retried.lines());
            assertNotSame(before.snapshot(), retried);
            assertSame(retried, backgroundSnapshot(fixture.session));
            assertEquals(before.lines(), before.snapshot().lines(), "The refused publication never changes its predecessor");
            assertTrue(undoing ? fixture.session.redo() : fixture.session.undo());
            assertEquals(before.lines(), fixture.session.snapshot().lines());
            assertBothHistoryBranchesRemainUsable(fixture, before.lines());
        }
    }

    private static WorkspaceEdit replaceDocument(DocumentSnapshot snapshot, String replacement) {
        int last = snapshot.lineCount() - 1;
        return new WorkspaceEdit(snapshot.documentId(), snapshot.revision(), List.of(
            new TextEdit(new TextRange(new TextPosition(0, 0),
                new TextPosition(last, snapshot.line(last).length())), replacement)
        ));
    }

    private static BeforeFailure observe(SnapshotFailureFixture fixture) {
        return new BeforeFailure(fixture.session.snapshot(), fixture.model.snapshotLines(), fixture.model.revision(),
            fixture.model.contentEpoch(), fixture.view.state().viewMemento(), fixture.foreign.state().viewMemento(),
            fixture.view.engine().navigationRevision(), fixture.foreign.engine().navigationRevision(), fixture.session.status());
    }

    private static void assertFailedCapturePreservesPublication(SnapshotFailureFixture fixture, BeforeFailure before,
                                                               List<String> stagedLines) throws InterruptedException {
        assertEquals(1, fixture.model.failedSnapshots, "Only the mandatory post-mutation capture may fail");
        assertEquals(stagedLines, fixture.model.linesAtFailure, "The failure must see the staged mutation");
        assertTrue(fixture.model.revisionAtFailure > before.revision(), "Pre-validation publication must not trigger the fault");
        assertSame(before.snapshot(), backgroundSnapshot(fixture.session));
        assertSame(before.snapshot(), fixture.session.snapshot());
        assertEquals(before.lines(), before.snapshot().lines());
        assertEquals(List.of("original", "tail"), fixture.initialSnapshot.lines());
        assertEquals(before.lines(), fixture.model.snapshotLines());
        assertEquals(before.revision(), fixture.model.revision());
        assertEquals(before.epoch(), fixture.model.contentEpoch());
        assertEquals(before.view(), fixture.view.state().viewMemento());
        assertEquals(before.foreignView(), fixture.foreign.state().viewMemento());
        assertEquals(before.navigation(), fixture.view.engine().navigationRevision());
        assertEquals(before.foreignNavigation(), fixture.foreign.engine().navigationRevision());
        assertEquals(before.status(), fixture.session.status());
    }

    private static void assertBothHistoryBranchesRemainUsable(SnapshotFailureFixture fixture, List<String> beforeLines) {
        assertTrue(fixture.session.redo());
        assertEquals(fixture.redoLines, fixture.session.snapshot().lines());
        assertTrue(fixture.session.undo());
        assertEquals(beforeLines, fixture.session.snapshot().lines());
        assertTrue(fixture.session.undo());
        assertEquals(fixture.initialSnapshot.lines(), fixture.session.snapshot().lines());
        assertFalse(fixture.session.undo(), "The refused mutation must not add a history entry");
        assertTrue(fixture.session.redo());
        assertEquals(beforeLines, fixture.session.snapshot().lines());
        assertTrue(fixture.session.redo());
        assertEquals(fixture.redoLines, fixture.session.snapshot().lines());
        assertFalse(fixture.session.redo());
        assertTrue(fixture.session.undo());
        assertEquals(beforeLines, fixture.session.snapshot().lines());
    }

    private static DocumentSnapshot backgroundSnapshot(EditorDocumentSession session) throws InterruptedException {
        var result = new AtomicReference<DocumentSnapshot>();
        Thread background = new Thread(() -> result.set(session.snapshot()));
        background.start();
        background.join();
        return result.get();
    }

    private static Fixture fixture(int lines) {
        ReadCounts reads = new ReadCounts();
        CountingBuffer buffer = new CountingBuffer(reads);
        buffer.setLine(0, "line 0");
        for (int line = 1; line < lines; line++) {
            buffer.insertLine(line, "line " + line);
        }
        TextModel model = new TextModel(buffer);
        EditorState state = new EditorState();
        var layout = new LogicalEditorTextLayout(model);
        EditorEngine engine = new EditorEngine(model, state, layout, new EditorClipboard());
        TabSession view = new TabSession(model, state, layout, engine);
        EditorDocumentSession session = EditorDocumentSession.connect(CoreDocumentBackend.adopt(new DocumentId("counted-document"), view));
        return new Fixture(session, view, reads);
    }

    private record Fixture(EditorDocumentSession session, TabSession view, ReadCounts reads) {
    }

    private static SnapshotFailureFixture snapshotFailureFixture() {
        SnapshotFailureModel model = new SnapshotFailureModel();
        model.replaceLines(List.of("original", "tail"));
        EditorDocument document = new EditorDocument(model);
        EditorClipboard clipboard = new EditorClipboard();
        TabSession view = document.attach(LogicalEditorTextLayout::new, clipboard);
        TabSession foreign = document.attach(LogicalEditorTextLayout::new, clipboard);
        CoreDocumentBackend backend = CoreDocumentBackend.adopt(new DocumentId("snapshot-failure"), view);
        backend.registerView(foreign);
        EditorDocumentSession session = EditorDocumentSession.connect(backend);
        DocumentSnapshot initialSnapshot = session.snapshot();
        view.engine().insertChar('X');
        view.engine().insertNewline();
        List<String> redoLines = model.snapshotLines();
        assertTrue(view.engine().undo());
        view.engine().restoreSelection(new Position(1, 3), new Position(0, 1));
        view.state().setViewport(new ViewportState(1, 0, 2, 3));
        foreign.engine().restoreSelection(new Position(0, 9), new Position(0, 8));
        foreign.state().setViewport(new ViewportState(1, 0, 3, 4));
        session.snapshot();
        return new SnapshotFailureFixture(session, model, view, foreign, initialSnapshot, redoLines);
    }

    private record SnapshotFailureFixture(EditorDocumentSession session, SnapshotFailureModel model,
        TabSession view, TabSession foreign, DocumentSnapshot initialSnapshot, List<String> redoLines) {}

    private record BeforeFailure(DocumentSnapshot snapshot, List<String> lines, long revision, long epoch,
        EditorViewMemento view, EditorViewMemento foreignView, long navigation, long foreignNavigation,
        EditorDocumentSession.Status status) {}

    private static final class SnapshotFailureModel extends TextModel {
        private boolean failSnapshot;
        private int failedSnapshots;
        private long revisionAtFailure;
        private List<String> linesAtFailure;

        private SnapshotFailureModel() {
            super(new SimpleLineBuffer());
        }

        @Override public List<LineText> snapshotTextLines() {
            if (failSnapshot) {
                failedSnapshots++;
                revisionAtFailure = revision();
                linesAtFailure = snapshotLines();
                throw new OutOfMemoryError("mandatory session snapshot capture");
            }
            return super.snapshotTextLines();
        }
    }

    private static final class ReadCounts {
        private final Thread owner = Thread.currentThread();
        private final AtomicInteger lines = new AtomicInteger();
        private final AtomicInteger foreignAccesses = new AtomicInteger();

        private void observeAccess() {
            if (Thread.currentThread() != owner) {
                foreignAccesses.incrementAndGet();
            }
        }
    }

    private static final class CountingBuffer extends SimpleLineBuffer {
        private final ReadCounts reads;

        private CountingBuffer(ReadCounts reads) {
            this.reads = reads;
        }

        @Override
        public TextBuffer createEmptySibling() {
            return new CountingBuffer(reads);
        }

        @Override
        protected boolean supportsPreparedPublication() {
            return true;
        }

        @Override
        public int lineCount() {
            reads.observeAccess();
            return super.lineCount();
        }

        @Override
        public String getLine(int line) {
            reads.observeAccess();
            reads.lines.incrementAndGet();
            return super.getLine(line);
        }

        @Override
        public LineText lineText(int line) {
            reads.observeAccess();
            reads.lines.incrementAndGet();
            return super.lineText(line);
        }
    }
}
