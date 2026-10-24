package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.LineText;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextBuffer;
import dev.tuink.core.TextModel;
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
        EditorDocumentSession session = CoreSessionBridge.adopt(new DocumentId("counted-document"), view);
        return new Fixture(session, view, reads);
    }

    private record Fixture(EditorDocumentSession session, TabSession view, ReadCounts reads) {
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
