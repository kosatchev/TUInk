package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.format.DocumentEncoding;
import dev.tuink.editor.format.DocumentFormatSummary;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentFormatIntegrationTest {
    @TempDir Path directory;

    @Test
    void arrowsChangeTheDraftAndApplyIsOneUndoableFormatChangeWithoutWriting() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("one", "two"))) {
            window.command(TuinkCommand.DOCUMENT_FORMAT);
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertEquals(DocumentEncoding.UTF8_BOM, window.active().uiState().formatDialog.specification().encoding());
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertTrue(window.active().uiState().modalState.isNone());
            var session = window.active().documentSession().document();
            assertEquals(DocumentEncoding.UTF8_BOM, session.formatSummary().encoding());
            assertEquals(DocumentFormatSummary.LineEnding.CRLF, session.formatSummary().lineEnding());
            assertTrue(window.active().state().isDirty());
            assertEquals("one\ntwo", Files.readString(window.document));
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(DocumentEncoding.UTF8, session.formatSummary().encoding());
            assertEquals(DocumentFormatSummary.LineEnding.LF, session.formatSummary().lineEnding());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void cancelAndCompactResizeKeepDraftAndRestoreTheFieldFocus() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.command(TuinkCommand.DOCUMENT_FORMAT);
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.resize(8, 35);
            assertTrue(window.active().uiState().modalState.isDocumentFormat());
            window.resize(24, 80);
            assertEquals(DocumentFormatDialogView.Target.EOL,
                window.active().uiState().formatDialog.focus.focused().orElseThrow());
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.frame();
            assertEquals(DocumentFormatDialogView.Target.APPLY,
                window.active().uiState().formatDialog.focus.focused().orElseThrow());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.active().state().isDirty());
            assertEquals(DocumentEncoding.UTF8, window.active().documentSession().document().formatSummary().encoding());
        }
    }

    @Test
    void mixedFooterUsesExactHitRegionsAndFocusesTheClickedField() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Files.writeString(window.document, "a\r\nb\nc\r");
            window.active().tabSession().engine().open(JvmDocumentStorage.resource(window.document));
            window.ui.mouseModeSetting = true;
            window.ui.mouseModeAvailable = true;
            window.frame();
            String footer = window.screenRow(window.height - 1);
            assertTrue(footer.contains("EOL:Mixed"), footer);
            window.pointer(PointerEvent.Kind.PRESS, window.height - 1, footer.indexOf("EOL:") + 2);
            assertEquals(DocumentFormatDialogView.Target.EOL,
                window.active().uiState().formatDialog.focus.focused().orElseThrow());
            window.command(TuinkCommand.DOCUMENT_FORMAT_APPLY);
            assertFalse(window.active().state().isDirty(), "Keep is a no-op on the existing mixed format");
            assertEquals("a\r\nb\nc\r", Files.readString(window.document));
        }
    }

    @Test
    void unrepresentableCharacterKeepsTextHistoryAndPreviousFormat() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("😀"))) {
            window.command(TuinkCommand.DOCUMENT_FORMAT);
            for (int i = 0; i < 4; i++) window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.command(TuinkCommand.DOCUMENT_FORMAT_APPLY);
            assertTrue(window.active().uiState().modalState.isDocumentFormat());
            assertTrue(window.active().uiState().formatDialog.error.contains("U+1F600"));
            assertTrue(window.active().uiState().formatDialog.error.contains("1251"));
            assertEquals(DocumentEncoding.UTF8, window.active().documentSession().document().formatSummary().encoding());
            assertEquals(List.of("😀"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void dirtyReopenRequiresAnExplicitDiscardAndFailureKeepsTheDraft() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_REOPEN_ENCODING);
            window.command(TuinkCommand.DOCUMENT_FORMAT_APPLY);
            var draft = window.active().uiState().formatDialog;
            assertTrue(draft.confirmDiscard);
            assertEquals(DocumentFormatDialogView.Target.CANCEL, draft.focus.focused().orElseThrow());
            assertFalse(window.documentIo.busy());
            Files.write(window.document, new byte[] {(byte) 0xff});
            window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.active().uiState().modalState.isDocumentFormat());
            assertNotNull(draft.error);
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void explicitReopenUsesOriginalBytesRatherThanActivatingTheAlreadyOpenTab() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("old"))) {
            Files.write(window.document, "Привет".getBytes(DocumentEncoding.CP866.charset()));
            window.command(TuinkCommand.DOCUMENT_REOPEN_ENCODING);
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            for (int i = 0; i < 5; i++) window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.command(TuinkCommand.DOCUMENT_FORMAT_APPLY);
            assertEquals(1, window.tabs.size());
            assertEquals(List.of("Привет"), window.active().tabSession().model().snapshotLines());
            assertEquals(DocumentEncoding.CP866, window.active().documentSession().document().formatSummary().encoding());
            assertFalse(window.active().state().isDirty());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("Привет"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void openWithEncodingKeepsItsDecoderInThePickerAndNormalOpenResetsIt() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.command(TuinkCommand.DOCUMENT_OPEN_ENCODING);
            window.event(KeyStroke.of(KeyStroke.Key.END));
            window.command(TuinkCommand.DOCUMENT_FORMAT_APPLY);
            assertTrue(window.active().uiState().openFileMode);
            assertEquals(DocumentOpenMode.KOI8_R, window.active().uiState().pickerOpenMode);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.DOCUMENT_OPEN);
            assertEquals(DocumentOpenMode.DETECT, window.active().uiState().pickerOpenMode);
        }
    }

    @Test
    void asyncApplyStaysWithItsOwnerAndCancelledWorkCannotPublish() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), tasks::addLast)) {
            var owner = window.active();
            window.command(TuinkCommand.DOCUMENT_FORMAT);
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.command(TuinkCommand.DOCUMENT_FORMAT_APPLY);
            assertTrue(window.documentIo.busy());
            window.command(TuinkCommand.TAB_NEW);
            var other = window.active();
            window.event(new TextInput("other draft"));
            tasks.removeFirst().run();
            window.frame();
            assertSame(other, window.active());
            assertEquals(DocumentEncoding.UTF8_BOM, owner.documentSession().document().formatSummary().encoding());
            assertEquals(List.of("other draft"), other.tabSession().model().snapshotLines());
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.DOCUMENT_FORMAT);
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.command(TuinkCommand.DOCUMENT_FORMAT_APPLY);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            tasks.removeFirst().run();
            window.frame();
            assertEquals(DocumentEncoding.UTF8_BOM, owner.documentSession().document().formatSummary().encoding());
            assertFalse(window.documentIo.busy());
        }
    }

    @Test
    void thePaintedCloseCellCancelsAndScrollbarCaptureReleasesOutsideThePanel() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.mouseModeSetting = true;
            window.ui.mouseModeAvailable = true;
            window.resize(11, 44);
            window.command(TuinkCommand.DOCUMENT_FORMAT);
            var draft = window.active().uiState().formatDialog;
            var placement = DocumentFormatDialogView.place(draft, new Rect(0, 0, 44, 11), true);
            Rect track = placement.encodings().scrollbarBounds().orElseThrow();
            window.pointer(PointerEvent.Kind.PRESS, track.y(), track.x());
            assertNotNull(draft.encodingList.drag());
            window.pointer(PointerEvent.Kind.DRAG, 10, 43);
            assertTrue(draft.encodingList.firstVisibleIndex() > 0);
            window.pointer(PointerEvent.Kind.RELEASE, 10, 43);
            assertNull(draft.encodingList.drag());
            window.pointer(PointerEvent.Kind.DRAG, track.y(), track.x());
            assertNull(draft.encodingList.drag(), "a released drag must not resume on re-entry");
            Rect dialog = placement.bounds();
            window.pointer(PointerEvent.Kind.PRESS, dialog.y(), dialog.rightExclusive() - 3);
            assertTrue(window.active().uiState().modalState.isNone());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void aRetargetedSymlinkCannotReopenAnotherDocumentsPhysicalFile() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            Path otherFile = Files.writeString(directory.resolve("other.txt"), "other");
            Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), window.document.getFileName());
            var first = window.active();
            first.tabSession().engine().open(JvmDocumentStorage.resource(alias));
            window.command(TuinkCommand.TAB_NEW);
            window.active().tabSession().engine().open(JvmDocumentStorage.resource(otherFile));
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(new TextInput("draft "));
            Files.delete(alias);
            Files.createSymbolicLink(alias, otherFile.getFileName());
            window.command(TuinkCommand.DOCUMENT_REOPEN_ENCODING);
            window.command(TuinkCommand.DOCUMENT_FORMAT_APPLY);
            window.command(TuinkCommand.DOCUMENT_FORMAT_APPLY);

            assertEquals(List.of("draft original"), first.tabSession().model().snapshotLines());
            assertTrue(first.state().isDirty());
            assertTrue(first.uiState().formatDialog.error.contains("another tab"));
            assertEquals("other", Files.readString(otherFile));
        }
    }

    @Test
    void pendingValidationBlocksEditsAndPointerClosingAcrossViewsOfItsDocument() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), tasks::addLast)) {
            window.ui.showTabBarSetting = true;
            window.ui.mouseModeSetting = true;
            window.ui.mouseModeAvailable = true;
            var first = window.active().documentSession();
            var second = first.attachView(window.host.clipboard);
            window.context.addAll(List.of(new TerminalEditorApp.EditorTab(second,
                second.newRenderPlanBuilder(line -> List.of()))));
            window.frame();
            window.command(TuinkCommand.DOCUMENT_FORMAT);
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.command(TuinkCommand.DOCUMENT_FORMAT_APPLY);
            window.command(TuinkCommand.TAB_NEXT);
            window.event(new TextInput("must not appear"));
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("original"), second.view().model().snapshotLines());
            String tabs = window.screenRow(1);
            int firstClose = tabs.indexOf(" x]") + 1;
            int secondClose = tabs.lastIndexOf(" x]") + 1;
            assertTrue(secondClose > firstClose && firstClose > 0, tabs);
            window.pointer(PointerEvent.Kind.PRESS, 1, firstClose);
            window.pointer(PointerEvent.Kind.RELEASE, 1, firstClose);
            window.pointer(PointerEvent.Kind.PRESS, 1, secondClose);
            window.pointer(PointerEvent.Kind.RELEASE, 1, secondClose);
            assertEquals(2, window.tabs.size());
            assertTrue(window.documentIo.busy());
            tasks.removeFirst().run();
            window.frame();
            assertEquals(DocumentEncoding.UTF8_BOM, second.document().formatSummary().encoding());
            window.event(new TextInput("allowed "));
            assertEquals(List.of("allowed original"), first.view().model().snapshotLines());
        }
    }
}
