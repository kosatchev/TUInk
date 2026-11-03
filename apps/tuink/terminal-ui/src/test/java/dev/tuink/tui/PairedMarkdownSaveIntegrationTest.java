package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PairedMarkdownSaveIntegrationTest {
    @TempDir Path directory;

    @Test void frozenCtrlSSavesRawMarkdownWithBomMixedEolAndFinalEmptyLineWithoutAnEditor() throws Exception {
        List<String> lines = List.of("# Формулы", "", "```math", "\\frac{a}{b}", "```", "",
            "```mermaid", "flowchart LR", " A-->B", "```", "", "```text", "tab\tand trailing  ", "```", "");
        StringBuilder raw = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) raw.append(switch (index % 3) { case 0 -> "\r"; case 1 -> "\r\n"; default -> "\n"; });
            raw.append(lines.get(index));
        }
        try (var window = new EditorWindowFixture(directory, false, List.of("setup"))) {
            Files.write(window.document, utf16le(raw.toString()));
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(window.document));
            window.frame();
            window.event(new TextInput("draft "));
            var preview = freezeDirtySource(window);
            FrozenDocumentSource captured = closed(preview).source();
            assertEquals("", captured.text().line(captured.text().lineCount() - 1));
            window.event(ctrl(KeyStroke.Key.S));
            window.await(() -> !window.documentIo.busy() && window.notification.message.startsWith("Saved"));
            assertArrayEquals(utf16le("draft " + raw), Files.readAllBytes(window.document));
            assertTrue(window.tabs.isEmpty()); assertNull(window.active());
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertSame(captured.text(), closed((PreviewTab) window.selected()).source().text());
        }
    }

    @Test void missingFrozenTargetReportsAnOrdinaryErrorThenExplicitSaveAsEnablesDiskReopen() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var oldDocument = window.active().documentSession().document().documentId();
            window.event(new TextInput("draft "));
            var preview = freezeDirtySource(window);
            var captured = closed(preview).source();
            Files.delete(window.document);
            window.event(ctrl(KeyStroke.Key.S));
            window.await(() -> !window.documentIo.busy());
            assertTrue(window.notification.message.startsWith("Save failed:"), window.notification.message);
            assertTrue(window.selected().uiState().modalState.isNone(), "Missing files must not silently open Save As");
            assertSame(captured.text(), closed((PreviewTab) window.selected()).source().text());
            assertTrue(window.tabs.isEmpty());

            Path recovered = directory.resolve("recovered.md");
            saveAs(window, recovered);
            assertEquals("draft original", Files.readString(recovered));
            assertEquals(recovered, JvmDocumentStorage.pathOf(closed((PreviewTab) window.selected()).source().binding().resource()));
            assertTrue(window.tabs.isEmpty()); assertEquals(preview.presentationId(), window.selected().presentationId());
            window.event(ctrl(KeyStroke.Key.P));
            window.await(() -> window.selected() instanceof TerminalEditorApp.EditorTab);
            assertNotEquals(oldDocument, window.active().documentSession().document().documentId());
            assertEquals(recovered, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertEquals(preview.presentationId(), window.context.presentations().get(1).presentationId());
        }
    }

    @Test void unnamedSnapshotCanSaveAsWithoutRecreatingAnEditingSession() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(ctrl(KeyStroke.Key.N));
            var unnamed = window.active();
            window.event(ctrl(KeyStroke.Key.TAB));
            window.command(TuinkCommand.TAB_CLOSE);
            assertEquals(unnamed.presentationId(), window.selected().presentationId());
            assertNull(window.active().state().getCurrentResource());
            window.event(new TextInput("unnamed $x_i^2$ trailing  "));
            var preview = freezeDirtySource(window);
            assertNull(closed(preview).source().binding().resource());
            Path target = directory.resolve("unnamed.md");
            saveAs(window, target);
            assertEquals("unnamed $x_i^2$ trailing  ", Files.readString(target));
            assertTrue(window.tabs.isEmpty()); assertNull(window.active());
            assertEquals(preview.presentationId(), window.selected().presentationId());
        }
    }

    @Test void livePreviewSaveAsRenamesBothTabsWithoutChangingTheSessionHistoryOrSelection() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var source = window.active(); var session = source.documentSession();
            var sourceId = source.presentationId(); var sourceView = session.viewId();
            var sourceCursor = source.state().getCursor(); long revision = source.tabSession().model().revision();
            var preview = open(window);
            Path target = directory.resolve("renamed.md");
            saveAs(window, target);
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertEquals(preview.generation() + 1, ((PreviewTab) window.selected()).generation());
            assertTrue(window.selected().uiState().saveAsInput.isEmpty());
            assertTrue(window.selected().uiState().modalState.isNone());
            assertTrue(window.selected().uiState().notification.message.startsWith("Saved "));
            assertSame(session, window.context.source(sourceView).documentSession());
            assertEquals(sourceCursor, source.state().getCursor());
            assertEquals(revision, source.tabSession().model().revision());
            assertEquals("draft original", Files.readString(target));
            var captions = window.host.toPresentationTabItems(window.context, null);
            assertEquals(target, captions.get(0).file()); assertEquals(target, captions.get(1).file());
            window.event(ctrl(KeyStroke.Key.P));
            assertEquals(sourceId, window.selected().presentationId());
            assertSame(session, window.active().documentSession());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test void cancellingSnapshotSaveAsKeepsItsResourceSourceAndPresentation() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var preview = freezeDirtySource(window); var captured = closed(preview).source();
            Path abandoned = directory.resolve("abandoned.md");
            window.event(ctrl(KeyStroke.Key.K));
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            window.event(ctrl(KeyStroke.Key.A)); window.event(new TextInput(abandoned.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.selected().uiState().modalState.isNone());
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertSame(captured, closed((PreviewTab) window.selected()).source());
            assertTrue(window.tabs.isEmpty()); assertFalse(Files.exists(abandoned));
            assertEquals("original", Files.readString(window.document));
        }
    }

    @Test void closingAnEditedHiddenSourceFreezesTheLastShownVersionRatherThanTheNewerBuffer() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("shown version"))) {
            var preview = open(window); var shown = preview.uiState().preview.sourceForFreeze();
            window.event(ctrl(KeyStroke.Key.P));
            window.event(new TextInput("unshown "));
            assertEquals(List.of("unshown shown version"), window.active().tabSession().model().snapshotLines());
            window.command(TuinkCommand.TAB_CLOSE); discard(window);
            window.await(() -> window.tabs.isEmpty());
            assertSame(shown.text(), closed((PreviewTab) window.selected()).source().text());
            assertEquals(List.of("shown version"), closed((PreviewTab) window.selected()).source().text().lines());
        }
    }

    static PreviewTab open(EditorWindowFixture window) throws InterruptedException {
        window.event(ctrl(KeyStroke.Key.P));
        var preview = assertInstanceOf(PreviewTab.class, window.selected());
        window.await(() -> window.preview().ready()); return preview;
    }
    static PreviewTab freezeDirtySource(EditorWindowFixture window) throws InterruptedException {
        var preview = open(window);
        window.event(ctrl(KeyStroke.Key.P)); window.command(TuinkCommand.TAB_CLOSE); discard(window);
        window.await(() -> window.selected() instanceof PreviewTab selected && selected.source() instanceof PreviewTab.ClosedSnapshot);
        window.await(() -> window.preview().ready());
        assertEquals(preview.presentationId(), window.selected().presentationId());
        return (PreviewTab) window.selected();
    }
    static void saveAs(EditorWindowFixture window, Path target) throws InterruptedException {
        window.event(ctrl(KeyStroke.Key.K));
        assertTrue(window.selected().uiState().modalState.isSaveAs());
        window.event(ctrl(KeyStroke.Key.A)); window.event(new TextInput(target.toString()));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        window.await(() -> !window.documentIo.busy() && Files.exists(target));
        assertTrue(window.selected().uiState().modalState.isNone());
    }
    private static void discard(EditorWindowFixture window) {
        assertTrue(window.selected().uiState().modalState.isCloseTabConfirm());
        window.event(KeyStroke.of(KeyStroke.Key.HOME)); window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }
    static PreviewTab.ClosedSnapshot closed(PreviewTab preview) { return assertInstanceOf(PreviewTab.ClosedSnapshot.class, preview.source()); }
    static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
    private static byte[] utf16le(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE); byte[] bytes = new byte[body.length + 2];
        bytes[0] = (byte) 0xFF; bytes[1] = (byte) 0xFE; System.arraycopy(body, 0, bytes, 2, body.length); return bytes;
    }
}
