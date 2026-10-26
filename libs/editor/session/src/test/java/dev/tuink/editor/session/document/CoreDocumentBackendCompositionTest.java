package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorDocument;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.TabSession;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.WorkspaceEdit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CoreDocumentBackendCompositionTest {
    private static final DocumentId ID = new DocumentId("shared-document");

    @Test
    void adoptingFromAnotherThreadCannotReassignTheDocumentMutationOwner() throws InterruptedException {
        TabSession original = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
        original.engine().insertInput("owned");
        var failure = new AtomicReference<Throwable>();
        Thread foreign = new Thread(() -> {
            try {
                EditorDocumentSession.connect(CoreDocumentBackend.adopt(ID, original));
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        foreign.start();
        foreign.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals("owned", original.model().getLine(0));
        assertTrue(original.engine().undo());
        assertEquals("", original.model().getLine(0));
    }

    @Test
    void frontendEditsAndPortableCommandsUseTheSameDocumentRevisionAndHistory() {
        Composition composition = composition(List.of("alpha", "beta"));
        EditorDocumentSession session = composition.session();
        TabSession first = composition.view();
        TabSession second = first.engine().document().attach(LogicalEditorTextLayout::new, new EditorClipboard());
        composition.backend().registerView(second);
        first.engine().moveCursorTo(new Position(0, 2));
        first.state().setTopLine(1);
        second.engine().moveCursorTo(new Position(1, 1));
        first.engine().insertInput("X");

        var published = session.snapshot();

        assertEquals(List.of("alXpha", "beta"), published.lines());
        assertSame(published, session.snapshot());
        assertSame(published, session.snapshot(), "unchanged frames must not copy the document");
        assertSame(first.model(), second.model());
        assertNotSame(first.state(), second.state());
        assertEquals(new Position(1, 1), second.state().getCursor());
        assertTrue(session.undo());
        assertEquals(List.of("alpha", "beta"), first.model().snapshotLines());
        assertFalse(first.engine().undo(), "portable undo must consume the same single history entry");
        assertTrue(second.engine().redo());
        assertEquals(List.of("alXpha", "beta"), session.snapshot().lines());
    }

    @Test
    void adoptingAnExistingViewPreservesItsUndoHistoryAndDirtyState() {
        TabSession original = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
        original.engine().insertInput("existing edits");
        original.engine().moveCursorTo(new Position(0, 3));
        var owner = original.engine().document();

        EditorDocumentSession session = EditorDocumentSession.connect(CoreDocumentBackend.adopt(ID, original));
        TabSession attached = original.engine().document().attach(LogicalEditorTextLayout::new, new EditorClipboard());

        assertSame(owner, attached.engine().document());
        assertTrue(attached.state().isDirty());
        assertEquals(new Position(0, 3), original.state().getCursor());
        assertEquals(List.of("existing edits"), session.snapshot().lines());
        assertTrue(session.undo());
        assertEquals(List.of(""), attached.model().snapshotLines());
        assertFalse(original.engine().undo());
        assertFalse(original.state().isDirty());
    }

    @Test
    void workspaceValidationObservesUnpublishedFrontendEditsBeforeCheckingItsRevision() {
        Composition composition = composition(List.of("alpha"));
        EditorDocumentSession session = composition.session();
        var initial = session.snapshot();
        TabSession view = composition.view();
        view.engine().insertInput("X");
        WorkspaceEdit stale = new WorkspaceEdit(ID, initial.revision(), List.of(
            new TextEdit(new TextRange(new TextPosition(0, 0), new TextPosition(0, 1)), "bad")
        ));

        var rejected = assertInstanceOf(WorkspaceEditResult.Rejected.class, session.applyWorkspaceEdit(stale));

        assertEquals(WorkspaceEditResult.RejectionReason.VALIDATION_FAILED, rejected.reason());
        assertEquals(List.of("Xalpha"), rejected.currentSnapshot().lines());
        assertEquals(List.of("Xalpha"), view.model().snapshotLines());
        assertTrue(session.undo());
        assertEquals(List.of("alpha"), session.snapshot().lines());
    }

    @Test
    void workspaceAndUiEditsShareAnAtomicUndoChainAndBothViewStatesStayIndependent() {
        Composition composition = composition(List.of("alpha", "beta"));
        EditorDocumentSession session = composition.session();
        TabSession view = composition.view();
        view.engine().moveCursorTo(new Position(1, 2));
        view.state().setTopLine(1);
        var viewBefore = view.state().viewMemento();
        assertInstanceOf(WorkspaceEditResult.Applied.class, session.applyWorkspaceEdit(new WorkspaceEdit(ID,
            session.snapshot().revision(), List.of(new TextEdit(
                new TextRange(new TextPosition(0, 0), new TextPosition(0, 5)), "A"
            )))));
        assertEquals(viewBefore, view.state().viewMemento());
        view.engine().insertInput("X");
        session.snapshot();

        assertTrue(view.engine().undo());
        assertEquals(List.of("A", "beta"), view.model().snapshotLines());
        assertTrue(view.engine().undo());
        assertEquals(List.of("alpha", "beta"), session.snapshot().lines());
        assertFalse(session.undo());
    }

    private static Composition composition(List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        EditorDocument document = new EditorDocument(model);
        TabSession logical = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        CoreDocumentBackend backend = CoreDocumentBackend.adopt(ID, logical);
        TabSession view = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        backend.registerView(view);
        return new Composition(backend, EditorDocumentSession.connect(backend), view);
    }

    private record Composition(CoreDocumentBackend backend, EditorDocumentSession session, TabSession view) {}

}
