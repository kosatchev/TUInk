package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real owner turns, detached disk observations, explicit decisions and document history. */
class ExternalChangeIntegrationTest {
    @TempDir Path directory;

    @Test void retargetedAliasNoticeRequiresConfirmationAndCancelPreservesBothFiles() throws Exception {
        Path first = Files.writeString(directory.resolve("first.txt"), "original");
        Path second = Files.writeString(directory.resolve("second.txt"), "other original");
        Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), first);
        try (var window = new EditorWindowFixture(directory, "alias.txt", false, List.of("original"))) {
            window.ui.autoReloadUnmodifiedFilesSetting = false;
            window.event(new TextInput("draft "));
            var id = TerminalEditorApp.documentId(window.selected());
            Files.delete(alias);
            Files.createSymbolicLink(alias, second);
            awaitNotice(window, id);
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> window.selected().uiState().modalState.isSaveOverwriteConfirm());
            assertEquals("original", Files.readString(first));
            assertEquals("other original", Files.readString(second));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertEquals(List.of("draft original"), lines(window));
            assertTrue(window.active().state().isDirty());
            assertTrue(window.host.externalNotices.visible(id));
            assertEquals("original", Files.readString(first));
            assertEquals("other original", Files.readString(second));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> window.selected().uiState().modalState.isSaveOverwriteConfirm());
            window.event(ctrl(KeyStroke.Key.S));
            window.await(() -> !window.documentIo.busy() && !window.active().state().isDirty());
            assertEquals("original", Files.readString(first));
            assertEquals("draft original", Files.readString(second));
            assertFalse(window.host.externalNotices.visible(id));
        }
    }

    @Test void diskChangesAfterConfirmationRefusePublicationAndKeepTheWarningUntilAFreshSave() throws Exception {
        var pending = new ArrayDeque<Runnable>();
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), pending::add)) {
            window.ui.autoReloadUnmodifiedFilesSetting = false; window.event(new TextInput("draft "));
            var id = TerminalEditorApp.documentId(window.selected());
            Files.writeString(window.document, "external"); awaitNotice(window, id);
            window.command(TuinkCommand.DOCUMENT_SAVE); assertEquals(1, pending.size());
            pending.remove().run(); window.frame();
            assertTrue(window.selected().uiState().modalState.isSaveOverwriteConfirm());
            window.event(ctrl(KeyStroke.Key.S)); assertEquals(1, pending.size());
            Files.writeString(window.document, "newer external version");
            pending.remove().run(); window.frame();
            assertEquals("newer external version", Files.readString(window.document));
            assertTrue(window.active().state().isDirty()); assertTrue(window.host.externalNotices.visible(id));
            assertEquals(List.of("draft original"), lines(window));
            window.command(TuinkCommand.DOCUMENT_SAVE); pending.remove().run(); window.frame();
            assertTrue(window.selected().uiState().modalState.isSaveOverwriteConfirm());
            window.event(ctrl(KeyStroke.Key.S)); pending.remove().run(); window.frame();
            assertEquals("draft original", Files.readString(window.document)); assertFalse(window.host.externalNotices.visible(id));
        }
    }

    @Test void frozenPreviewAlsoRequiresExplicitSaveConfirmationAndKeepsItsWarningOnCancel() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Original"))) {
            window.ui.autoReloadUnmodifiedFilesSetting = false;
            var preview = freeze(window); var subject = ((PreviewTab.ClosedSnapshot) preview.source()).subject();
            Files.writeString(window.document, "# External"); awaitNotice(window, subject.documentId());
            window.event(ctrl(KeyStroke.Key.S));
            window.await(() -> preview.uiState().modalState.isSaveOverwriteConfirm());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.host.externalNotices.visible(subject.documentId())); assertEquals("# External", Files.readString(window.document));
            window.event(ctrl(KeyStroke.Key.S)); window.await(() -> preview.uiState().modalState.isSaveOverwriteConfirm());
            window.event(ctrl(KeyStroke.Key.S));
            window.await(() -> !window.documentIo.busy() && !window.host.externalNotices.visible(subject.documentId()));
            assertEquals("# Original", Files.readString(window.document)); assertFalse(subject.unsavedExternalChanges());
            assertTrue(window.tabs.isEmpty());
        }
    }

    @Test void externalOverwriteConfirmationRetainsCloseAndQuitContinuations() throws Exception {
        for (boolean quit : List.of(false, true)) {
            try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
                window.command(TuinkCommand.TAB_NEW); window.command(TuinkCommand.TAB_PREVIOUS);
                window.event(new TextInput("draft ")); var id = TerminalEditorApp.documentId(window.selected());
                Files.writeString(window.document, "external"); awaitNotice(window, id);
                window.command(quit ? TuinkCommand.APP_QUIT : TuinkCommand.TAB_CLOSE);
                window.event(ctrl(KeyStroke.Key.S));
                window.await(() -> screenContains(window, "External changes will be destroyed"));
                assertEquals("external", Files.readString(window.document));
                window.event(ctrl(KeyStroke.Key.S));
                window.await(() -> !window.documentIo.busy() && (quit ? !window.controller.running() : window.tabs.size() == 1));
                assertEquals("draft original", Files.readString(window.document));
            }
        }
    }

    @Test
    void noticeHasInstructionsAndOnlyConfirmedSaveReplacesExternalText() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.autoReloadUnmodifiedFilesSetting = false;
            window.event(new TextInput("local "));
            var subject = window.active().documentSession().document();
            var id = subject.documentId();
            var cursor = window.active().state().getCursor();
            window.controller.openFolder(directory);
            window.await(() -> !window.ui.sidebar.tree.loading());
            int sidebarHeight = window.ui.sidebar.placement.bounds().height();
            window.ui.showFileHistoryNotificationsSetting = false;
            Files.writeString(window.document, "external version one");
            awaitNotice(window, id);
            var first = window.host.externalNotices.notice(id);
            int row = row(window, "File changed on disk.");
            int left = window.ui.sidebar.placement.documentBounds().x();
            assertEquals(left, window.screenRow(row).indexOf("File changed on disk."));
            assertEquals(window.host.styles.windows().notifyWarning(), window.screenStyle(row, left));
            assertEquals(sidebarHeight, window.ui.sidebar.placement.bounds().height());
            assertEquals(List.of("local original"), lines(window));
            assertTrue(window.active().state().isDirty());

            assertTrue(screenContains(window, "F1 → U"));
            assertTrue(screenContains(window, "Ctrl+S"));
            assertFalse(screenContains(window, "[Keep]"));
            assertFalse(screenContains(window, "[Update]"));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> window.selected().uiState().modalState.isSaveOverwriteConfirm());
            String prompt = java.util.stream.IntStream.range(0, window.height).mapToObj(window::screenRow)
                .map(line -> line.substring(left).strip()).collect(java.util.stream.Collectors.joining(" ")).replaceAll(" +", " ");
            assertTrue(prompt.contains("External changes will be destroyed"), prompt);
            assertEquals("external version one", Files.readString(window.document));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            pump(window, 1_150);
            assertTrue(window.host.externalNotices.visible(id), "Cancelling must retain the warning");
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> window.selected().uiState().modalState.isSaveOverwriteConfirm());
            window.event(ctrl(KeyStroke.Key.S));
            window.await(() -> !window.documentIo.busy() && !window.active().state().isDirty());
            assertEquals(cursor, window.active().state().getCursor());
            assertEquals("local original", Files.readString(window.document));
            assertFalse(window.host.externalNotices.visible(id));

            Files.writeString(window.document, "external version two");
            awaitNotice(window, id);
            assertNotSame(first.scopeId(), window.host.externalNotices.scopeId(id));
            assertEquals(List.of("local original"), lines(window));
        }
    }

    @Test
    void updateIsOneUndoStepWithACleanDiskBaselineAndHistoricalGutter() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original", "second"))) {
            window.ui.autoReloadUnmodifiedFilesSetting = false;
            var subject = window.active().documentSession().document();
            var id = subject.documentId();
            Files.writeString(window.document, "external\nchanged second");
            awaitNotice(window, id);
            window.command(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL);
            decisionComplete(window, id);
            assertEquals(List.of("external", "changed second"), lines(window));
            assertFalse(window.active().state().isDirty());
            assertEquals(window.document, JvmDocumentStorage.pathOf(subject.binding().resource()));
            window.await(() -> window.active().tabSession().engine().gutterDiffMarkerForLine(0).historical());
            assertTrue(window.active().tabSession().engine().gutterDiffMarkerForLine(1).historical());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("original", "second"), lines(window), "A single Undo restores the complete previous document");
            assertTrue(window.active().state().isDirty());
            assertEquals("external\nchanged second", Files.readString(window.document));
            window.event(ctrl(KeyStroke.Key.Y));
            assertEquals(List.of("external", "changed second"), lines(window));
            assertFalse(window.active().state().isDirty());
            assertFalse(window.host.externalNotices.visible(id));
        }
    }

    @Test
    void updatePreservesEarlierLocalUndoHistoryAndRedoReturnsToTheAcceptedVersion() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.autoReloadUnmodifiedFilesSetting = false;
            window.event(new TextInput("local "));
            var id = TerminalEditorApp.documentId(window.selected());
            Files.writeString(window.document, "external replacement");
            awaitNotice(window, id);
            window.command(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL);
            decisionComplete(window, id);
            assertEquals(List.of("external replacement"), lines(window));
            assertFalse(window.active().state().isDirty());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("local original"), lines(window));
            assertTrue(window.active().state().isDirty());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("original"), lines(window));
            window.event(ctrl(KeyStroke.Key.Y));
            assertEquals(List.of("local original"), lines(window));
            window.event(ctrl(KeyStroke.Key.Y));
            assertEquals(List.of("external replacement"), lines(window));
            assertFalse(window.active().state().isDirty());
            assertEquals("external replacement", Files.readString(window.document));
        }
    }

    @Test
    void ownAtomicSaveDoesNotProduceAnExternalWarningOnTheNextIdlePoll() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.autoReloadUnmodifiedFilesSetting = false;
            window.event(new TextInput("saved "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> !window.documentIo.busy() && !window.active().state().isDirty());
            var id = TerminalEditorApp.documentId(window.selected());
            pump(window, 1_250);
            assertEquals("saved original", Files.readString(window.document));
            assertFalse(window.host.externalNotices.visible(id));
            assertFalse(screenContains(window, "File changed on disk."));
            assertEquals(List.of("saved original"), lines(window));
        }
    }

    @Test
    void updateFromLivePreviewChangesTheSharedSourceAndSourceUndoRebuildsPreview() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Original"))) {
            window.ui.autoReloadUnmodifiedFilesSetting = false;
            var source = window.active();
            var id = source.documentSession().document().documentId();
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.preview().ready());
            var presentation = window.selected().presentationId();
            Files.writeString(window.document, "# Updated\n\nexternal body");
            awaitNotice(window, id);
            window.command(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL);
            decisionComplete(window, id);
            window.await(() -> window.preview().ready() && screenContains(window, "external body"));
            assertEquals(presentation, window.selected().presentationId());
            assertEquals(List.of("# Updated", "", "external body"), source.tabSession().model().snapshotLines());
            assertFalse(source.state().isDirty());
            window.command(TuinkCommand.TAB_PREVIOUS);
            var selectedSource = assertInstanceOf(TerminalEditorApp.EditorTab.class, window.selected());
            assertSame(source.documentSession(), selectedSource.documentSession());
            assertEquals(source.presentationId(), selectedSource.presentationId());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("# Original"), source.tabSession().model().snapshotLines());
            assertTrue(source.state().isDirty());
            window.command(TuinkCommand.TAB_NEXT);
            window.await(() -> window.preview().ready() && screenContains(window, "Original"));
            assertEquals(presentation, window.selected().presentationId());
            assertEquals(id, TerminalEditorApp.documentId(window.selected()));
            assertFalse(window.host.externalNotices.visible(id));
        }
    }

    @Test
    void frozenUpdateAndUndoRetainCloseQuitGuardsAndSaveTheBoundResource() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Original"))) {
            window.ui.autoReloadUnmodifiedFilesSetting = false;
            PreviewTab preview = freeze(window);
            var subject = ((PreviewTab.ClosedSnapshot) preview.source()).subject();
            var id = subject.documentId();
            Files.writeString(window.document, "# External");
            awaitNotice(window, id);
            window.command(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL);
            decisionComplete(window, id);
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("# Original"), subject.source().text().lines());
            assertTrue(subject.unsavedExternalChanges());
            assertTrue(window.host.toPresentationTabItems(window.context, null).getFirst().dirty());
            assertTrue(window.tabs.isEmpty());
            window.command(TuinkCommand.TAB_CLOSE);
            assertTrue(window.selected().uiState().modalState.isCloseTabConfirm());
            assertTrue(window.promptText().contains("document.md"));
            assertTrue(window.promptText().contains("Save"));
            assertFalse(window.promptText().contains("Save As"));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.APP_QUIT);
            assertTrue(window.controller.running());
            assertTrue(window.promptText().contains("Save All"));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            window.command(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL);
            decisionComplete(window, id);
            window.await(() -> window.preview().ready());
            assertEquals(List.of("# External"), subject.source().text().lines());
            assertFalse(subject.unsavedExternalChanges());
            assertTrue(subject.canUndoExternal());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("# Original"), subject.source().text().lines());
            assertTrue(subject.unsavedExternalChanges());
            window.event(ctrl(KeyStroke.Key.Y));
            assertEquals(List.of("# External"), subject.source().text().lines());
            assertFalse(subject.unsavedExternalChanges());
            window.event(ctrl(KeyStroke.Key.Z));
            window.command(TuinkCommand.TAB_CLOSE);
            assertTrue(window.selected().uiState().modalState.isCloseTabConfirm());
            window.event(ctrl(KeyStroke.Key.S));
            window.await(() -> !window.documentIo.busy() && window.context.presentation(preview.presentationId()) == null);
            assertEquals("# Original", Files.readString(window.document));
            assertTrue(window.controller.running());
            assertFalse(subject.unsavedExternalChanges());
        }
    }

    @Test
    void aNewDiskVersionDuringDecisionIsShownInsteadOfAcceptingTheUnpaintedVersion() throws Exception {
        var pending = new ArrayDeque<Runnable>();
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), pending::add)) {
            window.ui.autoReloadUnmodifiedFilesSetting = false;
            var id = TerminalEditorApp.documentId(window.selected());
            Files.writeString(window.document, "external one");
            awaitNotice(window, id);
            var shown = window.host.externalNotices.notice(id);
            window.command(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL);
            assertTrue(window.documentIo.busy());
            assertEquals(1, pending.size());
            Files.writeString(window.document, "external two");
            pending.remove().run();
            window.frame();
            assertFalse(window.documentIo.busy());
            assertEquals(List.of("original"), lines(window));
            assertTrue(window.host.externalNotices.visible(id));
            assertNotSame(shown.scopeId(), window.host.externalNotices.scopeId(id));
            window.command(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL);
            assertEquals(1, pending.size());
            pending.remove().run();
            decisionComplete(window, id);
            assertEquals(List.of("external two"), lines(window));
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void editsWhileTheDecisionReadIsPendingPreventStaleAdoptionAndRemainUndoableAfterRetry() throws Exception {
        var pending = new ArrayDeque<Runnable>();
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), pending::add)) {
            window.ui.autoReloadUnmodifiedFilesSetting = false;
            var subject = window.active().documentSession().document();
            var id = subject.documentId();
            Files.writeString(window.document, "external");
            awaitNotice(window, id);
            var expected = subject.binding();
            window.command(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL);
            assertEquals(1, pending.size());
            window.event(new TextInput("newer "));
            assertEquals(List.of("newer original"), lines(window));
            pending.remove().run();
            window.frame();
            assertFalse(window.documentIo.busy());
            assertEquals(expected, subject.binding());
            assertEquals(List.of("newer original"), lines(window));
            assertTrue(window.active().state().isDirty());
            assertTrue(window.host.externalNotices.visible(id));
            window.command(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL);
            assertEquals(1, pending.size());
            pending.remove().run();
            decisionComplete(window, id);
            assertEquals(List.of("external"), lines(window));
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("newer original"), lines(window));
        }
    }

    private static List<String> lines(EditorWindowFixture window) { return window.active().tabSession().model().snapshotLines(); }
    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }

    private static void awaitNotice(EditorWindowFixture window, DocumentId id) throws Exception {
        window.await(() -> window.host.externalNotices.visible(id));
        assertTrue(screenContains(window, "File changed on disk."));
    }

    private static void decisionComplete(EditorWindowFixture window, DocumentId id) throws Exception {
        window.await(() -> !window.documentIo.busy() && !window.host.externalNotices.visible(id));
    }

    private static void click(EditorWindowFixture window, String label) {
        int row = row(window, label), column = window.screenRow(row).indexOf(label) + 2;
        window.pointer(PointerEvent.Kind.PRESS, row, column);
        window.pointer(PointerEvent.Kind.RELEASE, row, column);
    }

    private static void pump(EditorWindowFixture window, long millis) throws Exception {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < end) { window.frame(); Thread.sleep(10); }
    }

    private static PreviewTab freeze(EditorWindowFixture window) throws Exception {
        window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
        window.await(() -> window.preview().ready());
        window.command(TuinkCommand.TAB_PREVIOUS);
        window.command(TuinkCommand.TAB_CLOSE);
        window.await(() -> window.selected() instanceof PreviewTab preview
            && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
        assertTrue(window.tabs.isEmpty());
        return (PreviewTab) window.selected();
    }

    private static boolean screenContains(EditorWindowFixture window, String text) {
        for (int row = 0; row < window.height; row++) if (window.screenRow(row).contains(text)) return true;
        return false;
    }

    private static int row(EditorWindowFixture window, String text) {
        for (int row = 0; row < window.height; row++) if (window.screenRow(row).contains(text)) return row;
        throw new AssertionError("No visible row contains " + text);
    }
}
