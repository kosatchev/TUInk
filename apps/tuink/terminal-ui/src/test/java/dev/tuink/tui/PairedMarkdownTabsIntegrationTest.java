package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real keyboard/frame pipeline over the one presentation registry; no shadow editing view. */
class PairedMarkdownTabsIntegrationTest {
    @TempDir Path directory;

    @Test void ctrlPAndOrdinaryTabNavigationReuseOneAdjacentPairAcrossOpenAndNew() throws Exception {
        Path otherFile = Files.writeString(directory.resolve("z-other.md"), "# Another document\n");
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Original", "", "body"))) {
            var editor = window.active();
            var preview = openPreview(window);
            PresentationId previewId = preview.presentationId();
            assertPair(window, editor, previewId);

            window.event(ctrl(KeyStroke.Key.P));
            assertEquals(editor.presentationId(), window.selected().presentationId());
            assertTrue(window.screenRow(1).contains("· Source"), window.screenRow(1));
            assertTrue(window.screenRow(1).contains("· Preview"), window.screenRow(1));
            assertFalse(window.preview().active);
            window.event(ctrl(KeyStroke.Key.P));
            assertEquals(previewId, window.selected().presentationId());
            assertEquals(2, window.context.presentations().size());
            window.event(ctrl(KeyStroke.Key.TAB));
            assertEquals(editor.presentationId(), window.selected().presentationId());
            window.event(ctrl(KeyStroke.Key.TAB));
            assertEquals(previewId, window.selected().presentationId());
            window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT));
            assertEquals(editor.presentationId(), window.selected().presentationId());

            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.END));
            assertEquals(otherFile, window.tree.state().selectedId().path());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> window.active() != null && otherFile.equals(
                JvmDocumentStorage.pathOf(window.active().state().getCurrentResource())));
            assertPair(window, editor, previewId);
            window.event(ctrl(KeyStroke.Key.N));
            assertPair(window, editor, previewId);
            assertEquals(1, window.context.presentations().stream().filter(PreviewTab.class::isInstance).count());

            int count = window.context.presentations().size();
            for (int step = 0; step < count; step++) {
                int next = (window.context.activePresentationIndex() + 1) % count;
                var expected = window.context.presentations().get(next).presentationId();
                window.event(ctrl(KeyStroke.Key.TAB));
                assertEquals(expected, window.selected().presentationId());
            }
        }
    }

    @Test void closingPreviewPreservesTheDirtyEditorAndItsUndoHistory() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var editor = window.active(); var view = editor.documentSession().viewId();
            long revision = editor.tabSession().model().revision();
            var preview = openPreview(window);
            window.command(TuinkCommand.TAB_CLOSE);
            assertEquals(editor.presentationId(), window.selected().presentationId());
            assertEquals(1, window.context.presentations().size());
            assertEquals(1, window.tabs.size());
            assertTrue(editor.state().isDirty()); assertEquals(revision, editor.tabSession().model().revision());
            assertTrue(editor.documentSession().document().ownsView(view));
            assertNull(window.context.presentation(preview.presentationId()));
            assertNull(window.context.previewFor(view));
            assertTrue(preview.uiState().preview.closed);
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("original"), editor.tabSession().model().snapshotLines());
        }
    }

    @Test void tabsMenuSelectsPresentationsBeyondTheEditingDocumentCount() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Original"))) {
            var source = window.active();
            var preview = openPreview(window);
            window.event(ctrl(KeyStroke.Key.P));
            window.command(TuinkCommand.MENU_TABS);
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_UP));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(preview.presentationId(), window.selected().presentationId());
            window.command(TuinkCommand.MENU_TABS);
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_UP));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_UP));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(source.presentationId(), window.selected().presentationId());
        }
    }

    @Test void cancellingSourceCloseLeavesTheDirtyPairAndItsIdentitiesUnchanged() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var editor = window.active(); var preview = openPreview(window);
            var documentId = editor.documentSession().document().documentId();
            window.event(ctrl(KeyStroke.Key.P));
            window.command(TuinkCommand.TAB_CLOSE);
            assertTrue(window.selected().uiState().modalState.isCloseTabConfirm());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.selected().uiState().modalState.isNone());
            assertPair(window, editor, preview.presentationId());
            assertEquals(documentId, window.active().documentSession().document().documentId());
            assertTrue(window.active().state().isDirty());
            assertInstanceOf(PreviewTab.LiveSource.class, ((PreviewTab) window.context.presentation(preview.presentationId())).source());
            assertEquals("original", Files.readString(window.document));
        }
    }

    @Test void reopeningAFrozenPreviewReadsDiskIntoANewSessionBesideTheSamePreview() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("original"))) {
            var original = window.active();
            var oldDocument = original.documentSession().document().documentId();
            var oldView = original.documentSession().viewId();
            var preview = openPreview(window);
            window.event(ctrl(KeyStroke.Key.P));
            window.command(TuinkCommand.TAB_CLOSE);
            window.await(() -> window.context.source(oldView) == null);
            assertNull(window.active());
            Files.writeString(window.document, "changed on disk\n");
            window.event(ctrl(KeyStroke.Key.P));
            window.await(() -> window.selected() instanceof TerminalEditorApp.EditorTab);
            var reopened = window.active();
            assertNotEquals(oldDocument, reopened.documentSession().document().documentId());
            assertNotEquals(oldView, reopened.documentSession().viewId());
            assertEquals(List.of("changed on disk", ""), reopened.tabSession().model().snapshotLines());
            assertFalse(reopened.state().isDirty());
            assertPair(window, reopened, preview.presentationId());
            assertEquals(2, window.context.presentations().size());
            var live = assertInstanceOf(PreviewTab.LiveSource.class,
                ((PreviewTab) window.context.presentation(preview.presentationId())).source());
            assertEquals(reopened.documentSession().viewId(), live.viewId());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("changed on disk", ""), reopened.tabSession().model().snapshotLines(),
                "Closing the old editor must not transfer its Undo history into the reopened session");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingSourceSavesOrDiscardsNormallyAndLeavesOnlyAnEngineFreeSnapshot(boolean save) throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var editor = window.active(); var session = editor.documentSession().document();
            var view = editor.documentSession().viewId();
            var preview = openPreview(window);
            var accepted = preview.uiState().preview.sourceForFreeze();
            window.event(ctrl(KeyStroke.Key.P));
            window.command(TuinkCommand.TAB_CLOSE);
            assertTrue(window.selected().uiState().modalState.isCloseTabConfirm());
            if (save) window.event(new TextInput("Y"));
            else {
                window.event(KeyStroke.of(KeyStroke.Key.HOME));
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            }
            window.await(() -> window.context.source(view) == null);
            assertTrue(window.tabs.isEmpty()); assertNull(window.active());
            assertFalse(session.ownsView(view)); assertNull(session.workspace());
            assertEquals(1, window.context.presentations().size());
            var frozen = assertInstanceOf(PreviewTab.class, window.selected());
            assertEquals(preview.presentationId(), frozen.presentationId());
            var snapshot = assertInstanceOf(PreviewTab.ClosedSnapshot.class, frozen.source());
            assertSame(accepted.text(), snapshot.source().text());
            assertSame(accepted.metadata(), snapshot.source().metadata());
            assertEquals(List.of("draft original"), snapshot.source().text().lines());
            assertEquals(save ? "draft original" : "original", Files.readString(window.document));
            window.await(() -> window.preview().ready());
            assertTrue(window.screenRow(window.height - 1).contains("Preview (snapshot)"));
            assertTrue(window.screenRow(window.preview().contentBounds().y()).contains("draft original"));

            window.command(TuinkCommand.TAB_CLOSE);
            assertTrue(window.controller.running());
            assertEquals(1, window.context.presentations().size());
            assertInstanceOf(TerminalEditorApp.EditorTab.class, window.selected());
            assertTrue(window.selected().uiState().modalState.isNone(), "Discarded snapshot closure must not ask again");
            assertNotEquals(editor.presentationId(), window.selected().presentationId());
            assertNull(window.context.presentation(frozen.presentationId()));
            assertTrue(frozen.uiState().preview.closed);
        }
    }

    private static PreviewTab openPreview(EditorWindowFixture window) throws InterruptedException {
        window.event(ctrl(KeyStroke.Key.P));
        var preview = assertInstanceOf(PreviewTab.class, window.selected());
        window.await(() -> window.preview().ready());
        return preview;
    }
    private static void assertPair(EditorWindowFixture window, TerminalEditorApp.EditorTab source, PresentationId preview) {
        var presentations = window.context.presentations();
        int editorIndex = java.util.stream.IntStream.range(0, presentations.size())
            .filter(index -> presentations.get(index).presentationId().equals(source.presentationId())).findFirst().orElse(-1);
        assertTrue(editorIndex >= 0);
        assertTrue(editorIndex + 1 < presentations.size());
        assertEquals(preview, presentations.get(editorIndex + 1).presentationId());
        assertEquals(source.groupId(), presentations.get(editorIndex + 1).groupId());
    }
    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
}
