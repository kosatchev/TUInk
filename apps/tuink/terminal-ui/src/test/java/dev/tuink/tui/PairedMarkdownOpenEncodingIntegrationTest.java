package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.format.DocumentEncoding;
import dev.tuink.editor.format.DocumentFormatSpec;
import dev.tuink.editor.format.LineEndingPolicy;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PairedMarkdownOpenEncodingIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(value = DocumentEncoding.class, names = {"CP866", "KOI8_R", "UTF16LE_BOM"})
    void reopeningFrozenSourceKeepsItsDiskDecoder(DocumentEncoding encoding) throws Exception {
        // Leading NUL makes UTF-16LE ambiguous with UTF-32 under automatic detection.
        String text = encoding == DocumentEncoding.UTF16LE_BOM ? "\0# Привет" : "# Привет";
        byte[] body = text.getBytes(encoding.charset());
        byte[] bytes = body;
        if (encoding == DocumentEncoding.UTF16LE_BOM) {
            bytes = new byte[body.length + 2]; bytes[0] = (byte) 0xff; bytes[1] = (byte) 0xfe;
            System.arraycopy(body, 0, bytes, 2, body.length);
        }
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("setup"))) {
            Files.write(window.document, bytes);
            DocumentOpenMode mode = switch (encoding) {
                case CP866 -> DocumentOpenMode.CP866;
                case KOI8_R -> DocumentOpenMode.KOI8_R;
                default -> DocumentOpenMode.UTF_16LE;
            };
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(window.document), mode);
            window.frame();
            PairedMarkdownSaveIntegrationTest.open(window);
            window.event(ctrl(KeyStroke.Key.P)); window.command(TuinkCommand.TAB_CLOSE);
            window.await(() -> window.selected() instanceof PreviewTab p && p.source() instanceof PreviewTab.ClosedSnapshot);
            reopen(window);
            assertEquals(List.of(text), window.active().tabSession().model().snapshotLines());
            assertEquals(encoding.charset(), window.active().documentSession().document().metadata().charset());
            assertArrayEquals(bytes, Files.readAllBytes(window.document));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "CP866, UTF8, false, false", "CP866, UTF8, true, false",
        "UTF8, CP866, false, false", "UTF8, CP866, true, false",
        "CP866, UTF8, false, true", "CP866, UTF8, true, true",
        "UTF8, CP866, false, true", "UTF8, CP866, true, true"
    })
    void hiddenPreviewReopensTheConfirmedEncodingAfterSave(DocumentEncoding original, DocumentEncoding saved,
                                                           boolean saveAs, boolean undoFormat) throws Exception {
        String text = "# Привет";
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("setup"))) {
            Files.write(window.document, text.getBytes(original.charset()));
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(window.document),
                original == DocumentEncoding.CP866 ? DocumentOpenMode.CP866 : DocumentOpenMode.UTF_8);
            window.frame();
            var preview = PairedMarkdownSaveIntegrationTest.open(window);
            var shown = preview.uiState().preview.sourceForFreeze();
            window.event(ctrl(KeyStroke.Key.P));
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.event(new TextInput("saved "));
            changeFormat(window, saved);
            window.frame();
            Path target = saveAs ? directory.resolve("converted.md") : window.document;
            if (saveAs) PairedMarkdownSaveIntegrationTest.saveAs(window, target);
            else {
                window.command(TuinkCommand.DOCUMENT_SAVE);
                window.await(() -> !window.documentIo.busy());
            }
            assertArrayEquals(("saved " + text).getBytes(saved.charset()), Files.readAllBytes(target));
            if (undoFormat) {
                window.command(TuinkCommand.EDIT_UNDO);
                assertEquals(original.charset(), window.active().documentSession().document().metadata().charset());
            }
            window.command(TuinkCommand.TAB_CLOSE);
            if (undoFormat) {
                assertTrue(window.selected().uiState().modalState.isCloseTabConfirm());
                window.event(KeyStroke.of(KeyStroke.Key.HOME));
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            }
            window.await(() -> window.selected() instanceof PreviewTab p && p.source() instanceof PreviewTab.ClosedSnapshot);
            var frozen = PairedMarkdownSaveIntegrationTest.closed((PreviewTab) window.selected()).source();
            assertSame(shown.text(), frozen.text());
            assertSame(shown.metadata(), frozen.metadata());
            assertEquals(target, JvmDocumentStorage.pathOf(frozen.binding().resource()));
            assertEquals(saved == DocumentEncoding.UTF8 ? DocumentOpenMode.UTF_8 : DocumentOpenMode.CP866, frozen.reopenMode());
            reopen(window);
            assertEquals(List.of("saved " + text), window.active().tabSession().model().snapshotLines());
            assertEquals(saved.charset(), window.active().documentSession().document().metadata().charset());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyAConfirmedSnapshotSaveChangesTheReopenDecoder(boolean saveSnapshot) throws Exception {
        String text = "# Привет";
        byte[] original = text.getBytes(DocumentEncoding.CP866.charset());
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("setup"))) {
            Files.write(window.document, original);
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(window.document), DocumentOpenMode.CP866);
            changeFormat(window, DocumentEncoding.UTF8);
            window.frame();
            PairedMarkdownSaveIntegrationTest.freezeDirtySource(window);
            if (saveSnapshot) {
                window.command(TuinkCommand.DOCUMENT_SAVE);
                window.await(() -> !window.documentIo.busy());
            }
            reopen(window);
            assertEquals(List.of(text), window.active().tabSession().model().snapshotLines());
            var expected = saveSnapshot ? DocumentEncoding.UTF8 : DocumentEncoding.CP866;
            assertEquals(expected.charset(), window.active().documentSession().document().metadata().charset());
            assertArrayEquals(text.getBytes(expected.charset()), Files.readAllBytes(window.document));
        }
    }

    @Test
    void undoingASavedConversionDoesNotChangeTheDiskDecoder() throws Exception {
        String text = "# Привет";
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("setup"))) {
            Files.write(window.document, text.getBytes(DocumentEncoding.CP866.charset()));
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(window.document), DocumentOpenMode.CP866);
            changeFormat(window, DocumentEncoding.UTF8);
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> !window.documentIo.busy());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(DocumentEncoding.CP866.charset(), window.active().documentSession().document().metadata().charset());
            PairedMarkdownSaveIntegrationTest.freezeDirtySource(window);
            reopen(window);
            assertEquals(List.of(text), window.active().tabSession().model().snapshotLines());
            assertEquals(DocumentEncoding.UTF8.charset(), window.active().documentSession().document().metadata().charset());
        }
    }

    private static void changeFormat(EditorWindowFixture window, DocumentEncoding encoding) throws Exception {
        var session = window.active().documentSession();
        var prepared = session.document().prepareFormatChange(session.viewId(),
            new DocumentFormatSpec(encoding, LineEndingPolicy.KEEP));
        session.document().completeFormatChange(prepared, prepared.perform());
    }

    private static void reopen(EditorWindowFixture window) throws Exception {
        window.event(ctrl(KeyStroke.Key.P));
        window.await(() -> !window.documentIo.busy());
        assertInstanceOf(TerminalEditorApp.EditorTab.class, window.selected(), window.notification.message);
    }

    @Test
    void frozenPreviewCanChooseAnExplicitDecoderAndOpenAnotherFileWithoutManufacturingAnEditor() throws Exception {
        Path legacy = directory.resolve("legacy.md");
        Files.write(legacy, "Привет".getBytes(DocumentEncoding.WINDOWS_1251.charset()));
        try (var window = new EditorWindowFixture(directory, false, List.of("# Original"))) {
            window.event(new TextInput("draft "));
            window.event(ctrl(KeyStroke.Key.P));
            window.await(() -> window.preview().ready());
            var preview = (PreviewTab) window.selected();
            window.event(ctrl(KeyStroke.Key.P));
            window.ui.confirmOnCloseSetting = true;
            window.event(ctrl(KeyStroke.Key.W));
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.tabs.isEmpty());
            assertNull(window.active());
            window.command(TuinkCommand.DOCUMENT_OPEN_ENCODING);
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertTrue(window.selected().uiState().modalState.isDocumentFormat());
            assertTrue(window.tabs.isEmpty());
            var draft = window.selected().uiState().formatDialog;
            int target = draft.encodings.indexOf(DocumentEncoding.WINDOWS_1251);
            for (int index = 0; index < target; index++) window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.command(TuinkCommand.DOCUMENT_FORMAT_APPLY);
            assertTrue(window.selected().uiState().openFileMode);
            assertEquals(DocumentOpenMode.WINDOWS_1251, window.selected().uiState().pickerOpenMode);
            window.event(ctrl(KeyStroke.Key.L));
            window.event(new TextInput(legacy.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy());
            assertEquals(List.of("Привет"), window.active().tabSession().model().snapshotLines());
            assertEquals(1, window.tabs.size());
            assertTrue(window.context.presentations().stream().anyMatch(tab -> tab.presentationId().equals(preview.presentationId())));
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
}
