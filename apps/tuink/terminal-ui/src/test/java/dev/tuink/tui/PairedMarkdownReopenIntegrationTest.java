package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static dev.tuink.tui.PairedMarkdownSaveIntegrationTest.ctrl;
import static dev.tuink.tui.PairedMarkdownSaveIntegrationTest.closed;
import static dev.tuink.tui.PairedMarkdownSaveIntegrationTest.open;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PairedMarkdownReopenIntegrationTest {
    @TempDir Path directory;

    @Test void missingReadKeepsTheSnapshotSelectionAndPresentationAccessible() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("original needle"))) {
            var preview = freezeClean(window); var captured = closed(preview).source();
            window.event(ctrl(KeyStroke.Key.F)); window.event(new TextInput("needle"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER)); window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            var selection = window.preview().selection.snapshot();
            int top = window.preview().top;
            Files.delete(window.document);
            window.event(ctrl(KeyStroke.Key.P)); window.await(() -> !window.documentIo.busy());
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertTrue(window.tabs.isEmpty()); assertEquals(1, window.context.presentations().size());
            assertSame(captured, closed((PreviewTab) window.selected()).source());
            assertEquals(selection, window.preview().selection.snapshot()); assertEquals(top, window.preview().top);
            assertFalse(window.notification.message.isBlank());
            assertEquals("needle", window.preview().selectedText());
        }
    }

    @Test void reopeningFromDiskNavigatesToThePreviewPosition() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false,
                List.of("# First", "", "Second needle paragraph"))) {
            freezeClean(window);
            window.event(ctrl(KeyStroke.Key.F)); window.event(new TextInput("needle"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER)); window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_LEFT));
            window.event(ctrl(KeyStroke.Key.P));
            window.await(() -> window.selected() instanceof TerminalEditorApp.EditorTab && !window.documentIo.busy());
            assertEquals(new Position(2, 7), window.active().state().getCursor());
            assertEquals(1, window.tabs.size()); assertEquals(2, window.context.presentations().size());
            assertFalse(window.active().state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"symlink", "hardlink"})
    void anAlreadyOpenDirtyAliasIsActivatedWithoutReloadingOrLosingHistory(String kind) throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("original"))) {
            var frozen = freezeClean(window);
            Path alias = directory.resolve("alias.md");
            if (kind.equals("symlink")) Files.createSymbolicLink(alias, window.document.getFileName());
            else Files.createLink(alias, window.document);
            window.event(ctrl(KeyStroke.Key.N));
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(alias)); window.frame();
            window.event(new TextInput("dirty "));
            var editor = window.active(); var session = editor.documentSession();
            long revision = editor.tabSession().model().revision();
            select(window, frozen.presentationId()); window.await(() -> window.preview().ready());
            window.event(ctrl(KeyStroke.Key.P));
            assertEquals(editor.presentationId(), window.selected().presentationId());
            assertSame(session, window.active().documentSession());
            assertEquals(1, window.tabs.size()); assertEquals(2, window.context.presentations().size());
            assertEquals(frozen.presentationId(), window.context.presentations().get(1).presentationId());
            assertInstanceOf(PreviewTab.LiveSource.class, ((PreviewTab) window.context.presentation(frozen.presentationId())).source());
            assertEquals(List.of("dirty original"), window.active().tabSession().model().snapshotLines());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertEquals(new Position(0, 0), window.active().state().getCursor()); assertTrue(window.active().state().isDirty());
            assertEquals("original", Files.readString(window.document));
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test void reopeningIntoAnExistingPairRetiresTheDuplicatePreviewInsteadOfCreatingAnotherPair() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("original"))) {
            var oldPreview = freezeClean(window);
            window.event(ctrl(KeyStroke.Key.N));
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(window.document)); window.frame();
            window.event(new TextInput("dirty "));
            var editor = window.active(); var existing = open(window);
            assertEquals(3, window.context.presentations().size());
            select(window, oldPreview.presentationId()); window.event(ctrl(KeyStroke.Key.P));
            assertEquals(editor.presentationId(), window.selected().presentationId());
            assertEquals(2, window.context.presentations().size()); assertEquals(1, window.tabs.size());
            assertNull(window.context.presentation(oldPreview.presentationId()));
            assertTrue(oldPreview.uiState().preview.closed);
            assertEquals(existing.presentationId(), window.context.presentations().get(1).presentationId());
            assertEquals(List.of("dirty original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test void hiddenReopenCompletionMayAttachItsSourceButCannotStealTheNewTabsFocus() throws Exception {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("original"), queue::add)) {
            var preview = freezeClean(window);
            window.event(ctrl(KeyStroke.Key.P)); assertTrue(window.documentIo.busy()); assertEquals(1, queue.size());
            window.event(ctrl(KeyStroke.Key.N));
            var selected = window.selected().presentationId();
            runNext(queue); window.frame();
            assertFalse(window.documentIo.busy());
            assertEquals(selected, window.selected().presentationId());
            assertEquals(2, window.tabs.size()); assertEquals(3, window.context.presentations().size());
            var linked = (PreviewTab) window.context.presentation(preview.presentationId());
            var live = assertInstanceOf(PreviewTab.LiveSource.class, linked.source());
            assertEquals(linked.groupId(), window.context.source(live.viewId()).groupId());
            assertEquals(linked.presentationId(), window.context.presentations().get(1).presentationId());
        } finally { drain(queue); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExistingPairUsesItsOwnViewWhenAnotherViewIsTheFirstResourceMatch(boolean viaMenu) throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false,
                List.of("# First", "", "# Second needle", "", "tail"))) {
            var frozen = freezeClean(window);
            window.event(ctrl(KeyStroke.Key.N));
            var first = window.active();
            first.tabSession().engine().openExisting(JvmDocumentStorage.resource(window.document));
            window.frame(); window.event(new TextInput("dirty "));
            var secondSession = first.documentSession().attachView(window.host.clipboard);
            var paired = new TerminalEditorApp.EditorTab(secondSession,
                secondSession.newRenderPlanBuilder(window.host.resolveHighlighter(window.document)));
            window.context.addAll(List.of(paired));
            window.context.activate(secondSession.viewId()); window.frame();
            var existing = open(window);
            first.documentSession().navigate(new Position(0, 1), false);
            secondSession.navigate(new Position(4, 2), false);
            var firstCursor = first.state().getCursor();
            long revision = first.tabSession().model().revision();
            assertSame(first.documentSession().document(), secondSession.document());
            assertEquals(0, WorkspaceDocuments.findOpenFile(window.host.toTabSessions(window.tabs), -1, window.document));
            assertEquals(secondSession.viewId(), ((PreviewTab.LiveSource) existing.source()).viewId());

            select(window, frozen.presentationId());
            window.event(ctrl(KeyStroke.Key.F)); window.event(new TextInput("needle"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER)); window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_LEFT));
            if (viaMenu) {
                window.event(KeyStroke.of(KeyStroke.Key.F3));
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            }
            else window.event(ctrl(KeyStroke.Key.P));

            assertEquals(paired.presentationId(), window.selected().presentationId());
            assertNull(window.context.presentation(frozen.presentationId()));
            assertTrue(frozen.uiState().preview.closed);
            assertEquals(List.of(first.presentationId(), paired.presentationId(), existing.presentationId()),
                window.context.presentations().stream().map(WindowTab::presentationId).toList());
            assertEquals(firstCursor, first.state().getCursor(), "The first alias match is not the existing pair's source");
            assertEquals(new Position(2, 9), paired.state().getCursor());
            assertEquals(revision, first.tabSession().model().revision());
            assertTrue(paired.state().isDirty());
            window.event(ctrl(KeyStroke.Key.P));
            assertEquals(existing.presentationId(), window.selected().presentationId());
            window.event(ctrl(KeyStroke.Key.P));
            assertEquals(paired.presentationId(), window.selected().presentationId());
        }
    }

    @Test void cancellingAQueuedReadThenRetryingCannotPublishTheCancelledAttempt() throws Exception {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("original"), queue::add)) {
            var preview = freezeClean(window); var captured = closed(preview).source();
            window.event(ctrl(KeyStroke.Key.P)); assertEquals(1, queue.size());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.documentIo.busy()); assertTrue(window.tabs.isEmpty());
            assertSame(captured, closed((PreviewTab) window.selected()).source());
            window.event(ctrl(KeyStroke.Key.P)); assertEquals(2, queue.size());
            runNext(queue); window.frame();
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertTrue(window.tabs.isEmpty()); assertTrue(window.documentIo.busy());
            runNext(queue); window.frame();
            assertFalse(window.documentIo.busy()); assertEquals(1, window.tabs.size());
            assertInstanceOf(TerminalEditorApp.EditorTab.class, window.selected());
            assertEquals(2, window.context.presentations().size());
            assertEquals(preview.presentationId(), window.context.presentations().get(1).presentationId());
        } finally { drain(queue); }
    }

    @Test void aClosedPreviewCannotBeResurrectedByItsQueuedReadOrDisplaceANewerPreview() throws Exception {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("original"), queue::add)) {
            var old = freezeClean(window);
            window.event(ctrl(KeyStroke.Key.P)); assertEquals(1, queue.size());
            window.command(TuinkCommand.TAB_CLOSE);
            assertNull(window.context.presentation(old.presentationId()));
            assertTrue(old.uiState().preview.closed); assertFalse(window.documentIo.busy());
            var replacementSource = window.selected();
            window.event(new TextInput("# Replacement"));
            window.event(ctrl(KeyStroke.Key.P));
            assertSame(replacementSource, window.selected(), "the replacement draft must first acquire a Markdown filename");
            Path replacementFile = directory.resolve("replacement.md");
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            window.command(TuinkCommand.EDIT_SELECT_ALL); window.event(new TextInput(replacementFile.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(2, queue.size(), "the cancelled read is still queued before Save As inspection");
            var cancelledRead = queue.getFirst();
            var inspection = window.documentIo.pendingOperation();
            // Save As inspects first, then owner acceptance submits a separate
            // write. Complete both while keeping the abandoned read queued
            // until the newer Preview exists, preserving the stale-publication race.
            runLast(queue); window.frame();
            assertNull(window.active().state().getCurrentResource(), "inspection is not a saved binding receipt");
            assertTrue(window.documentIo.busy());
            assertNotSame(inspection, window.documentIo.pendingOperation());
            assertEquals(DocumentIoController.Kind.SAVE, window.documentIo.pendingOperation().kind());
            assertEquals(2, queue.size(), "the owner submitted the approved write after inspection");
            assertSame(cancelledRead, queue.getFirst());
            runLast(queue); window.frame();
            assertEquals(replacementFile, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals("# Replacement", Files.readString(replacementFile));
            assertFalse(window.documentIo.busy());
            assertEquals(1, queue.size()); assertSame(cancelledRead, queue.getFirst());
            var replacement = open(window);
            assertNotEquals(old.presentationId(), replacement.presentationId());
            runNext(queue); window.frame();
            assertEquals(replacement.presentationId(), window.selected().presentationId());
            assertNull(window.context.presentation(old.presentationId()));
            assertEquals(1, window.tabs.size()); assertEquals(2, window.context.presentations().size());
        } finally { drain(queue); }
    }

    private static PreviewTab freezeClean(EditorWindowFixture window) throws InterruptedException {
        var preview = open(window);
        window.event(ctrl(KeyStroke.Key.P)); window.command(TuinkCommand.TAB_CLOSE);
        window.await(() -> window.selected() instanceof PreviewTab selected && selected.source() instanceof PreviewTab.ClosedSnapshot);
        window.await(() -> window.preview().ready()); return (PreviewTab) window.selected();
    }
    private static void select(EditorWindowFixture window, PresentationId id) {
        for (int step = 0; step <= window.context.presentations().size(); step++) {
            if (window.selected().presentationId().equals(id)) return;
            window.event(ctrl(KeyStroke.Key.TAB));
        }
        fail("Presentation is not reachable by Ctrl+Tab: " + id);
    }
    private static void runNext(ArrayDeque<Runnable> queue) throws InterruptedException {
        assertFalse(queue.isEmpty());
        Thread worker = new Thread(queue.remove(), "paired-preview-test-read");
        worker.start(); worker.join(5_000);
        assertFalse(worker.isAlive(), "Queued immutable resource read did not finish");
    }
    private static void runLast(ArrayDeque<Runnable> queue) throws InterruptedException {
        assertFalse(queue.isEmpty());
        Thread worker = new Thread(queue.removeLast(), "paired-preview-test-save");
        worker.start(); worker.join(5_000);
        assertFalse(worker.isAlive(), "Queued immutable Save As phase did not finish");
    }
    private static void drain(ArrayDeque<Runnable> queue) { while (!queue.isEmpty()) queue.remove().run(); }
}
