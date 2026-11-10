package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExplorerEncodingContinuationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelFromExplorerReturnsToItsSourceOrPreviewWithoutOpeningAFilePicker(boolean preview) throws Exception {
        Path target = ambiguousFile();
        try (var window = window(preview)) {
            var origin = window.selected();
            var source = window.tabs.getFirst();
            openFromExplorer(window, target);

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertSame(origin, window.selected());
            assertTrue(origin.uiState().modalState.isNone());
            assertFalse(origin.uiState().openFileMode,
                "Cancelling an explorer read must not open an unrelated file picker");
            assertTrue(window.ui.sidebar.visible);
            assertEquals(1, window.tabs.size());
            assertEquals(List.of("original"), source.tabSession().model().snapshotLines());
            assertFalse(source.state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitExplorerReadFailureRetainsItsOriginWithoutOpeningAFilePicker(boolean preview) throws Exception {
        Path target = ambiguousFile();
        try (var window = window(preview)) {
            var origin = window.selected();
            var source = window.tabs.getFirst();
            openFromExplorer(window, target);
            Files.delete(target);

            window.event(new TextInput("y"));

            assertSame(origin, window.selected());
            assertTrue(origin.uiState().modalState.isNone());
            assertFalse(origin.uiState().openFileMode,
                "The explorer origin must survive the explicit encoding read and its I/O error");
            assertTrue(window.ui.sidebar.visible);
            assertEquals(1, window.tabs.size());
            assertEquals(List.of("original"), source.tabSession().model().snapshotLines());
            assertFalse(source.state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitExplorerEncodingChoiceOpensAndFocusesTheRequestedDocument(boolean preview) throws Exception {
        Path target = ambiguousFile();
        byte[] bytes = Files.readAllBytes(target);
        try (var window = window(preview)) {
            var origin = window.selected();
            var source = window.tabs.getFirst();
            openFromExplorer(window, target);

            window.event(new TextInput("y"));

            assertEquals(2, window.tabs.size());
            assertEquals(target, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(List.of("\0A\0"), window.active().tabSession().model().snapshotLines());
            assertEquals(StandardCharsets.UTF_16LE, window.active().state().getFileEncoding());
            assertFalse(window.ui.sidebar.focused);
            assertEquals(ActiveSurface.Focus.CONTENT, window.controller.activeSurface().focus());
            assertTrue(origin.uiState().modalState.isNone());
            assertFalse(origin.uiState().openFileMode);
            assertEquals(List.of("original"), source.tabSession().model().snapshotLines());
            assertFalse(source.state().isDirty());
            assertArrayEquals(bytes, Files.readAllBytes(target));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryFilePickerEncodingCancelStillReturnsToTheSamePickerSelection(boolean preview) throws Exception {
        Path target = ambiguousFile();
        try (var window = window(preview)) {
            var origin = window.selected();
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.END));
            assertEquals(target, window.tree.state().selectedId().path());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(origin.uiState().modalState.isOpenUtf16Confirm());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertSame(origin, window.selected());
            assertTrue(origin.uiState().modalState.isNone());
            assertTrue(origin.uiState().openFileMode,
                "The normal file picker must retain its existing cancellation behavior");
            assertEquals(target, window.tree.state().selectedId().path());
            assertEquals(1, window.tabs.size());
            assertEquals(List.of("original"), window.tabs.getFirst().tabSession().model().snapshotLines());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitEncodingReadDoesNotStealFocusFromANewerFindField(boolean preview) throws Exception {
        Path target = ambiguousFile();
        var pending = new ArrayDeque<Runnable>();
        try (var window = window(preview, pending::add)) {
            var origin = window.selected();
            window.controller.openFolder(directory);
            window.await(() -> !window.ui.sidebar.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.END));
            assertEquals(target, window.ui.sidebar.placement.tree().selectedEntry().orElseThrow().path());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(1, pending.size());
            pending.removeFirst().run();
            window.frame();
            assertTrue(origin.uiState().modalState.isOpenUtf16Confirm());
            window.event(new TextInput("y"));
            assertTrue(window.documentIo.busy());
            assertEquals(1, pending.size());
            window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
            window.event(new TextInput("original"));

            pending.removeFirst().run();
            window.frame();

            assertFalse(window.documentIo.busy());
            assertEquals(2, window.tabs.size());
            assertSame(origin, window.selected(),
                "The explicit encoding continuation must respect a later focus request");
            assertTrue(origin.uiState().modalState.isNone());
            assertFalse(origin.uiState().openFileMode);
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            window.event(new TextInput(" query"));
            assertEquals("original query", preview ? window.preview().searchQuery : window.query.text());
            assertEquals(List.of("original"), window.tabs.getFirst().tabSession().model().snapshotLines());
            assertEquals(List.of("\0A\0"), window.tabs.getLast().tabSession().model().snapshotLines());
        }
    }

    private Path ambiguousFile() throws Exception {
        return Files.write(directory.resolve("z-ambiguous.txt"),
            new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0, 65, 0, 0, 0});
    }

    private EditorWindowFixture window(boolean preview) throws Exception {
        return window(preview, Runnable::run);
    }

    private EditorWindowFixture window(boolean preview, Executor executor) throws Exception {
        var window = new EditorWindowFixture(directory, "document.md", false, List.of("original"), executor);
        try {
            if (preview) {
                window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
                window.await(() -> window.preview().ready());
                assertTrue(window.selected() instanceof PreviewTab);
            }
            return window;
        } catch (Exception | AssertionError failure) {
            window.close();
            throw failure;
        }
    }

    private void openFromExplorer(EditorWindowFixture window, Path target) throws Exception {
        window.controller.openFolder(directory);
        window.await(() -> !window.ui.sidebar.tree.loading());
        window.event(KeyStroke.of(KeyStroke.Key.END));
        assertEquals(target, window.ui.sidebar.placement.tree().selectedEntry().orElseThrow().path());
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        assertTrue(window.selected().uiState().modalState.isOpenUtf16Confirm(),
            "The fixture must reach the actual ambiguous encoding confirmation");
        assertFalse(window.selected().uiState().openFileMode);
    }
}
