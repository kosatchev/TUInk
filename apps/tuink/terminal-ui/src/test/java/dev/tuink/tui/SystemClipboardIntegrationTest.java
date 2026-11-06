package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.clipboard.ClipboardPort;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real common window route; the fake port never accesses the user's clipboard. */
class SystemClipboardIntegrationTest {
    @TempDir Path directory;

    @Test
    void successfulCopiesExportAgainButEmptyLineNoopsLeaveTheSystemClipboardAlone() throws Exception {
        Port port = new Port();
        try (var window = new EditorWindowFixture(directory, "document.txt", List.of("hello", ""), port)) {
            window.event(ctrl(KeyStroke.Key.C));
            await(window, () -> port.writes.size() == 1);
            assertEquals("hello\n", port.text);
            port.text = "external";
            window.event(ctrl(KeyStroke.Key.C));
            await(window, () -> port.writes.size() == 2);
            assertEquals("hello\n", port.text);
            window.event(KeyStroke.of(KeyStroke.Key.END, KeyStroke.Modifier.CTRL));
            port.text = "keep external";
            window.event(ctrl(KeyStroke.Key.C));
            window.event(ctrl(KeyStroke.Key.X));
            window.frame();
            assertEquals(2, port.writes.size());
            assertEquals("keep external", port.text);
        }
    }

    @Test
    void externalPasteAndFastTypingKeepTheirOrderAndDoNotEchoAnImportBackToTheSystem() throws Exception {
        Port port = new Port();
        port.text = "система 😀\nnext";
        port.holdRead = new CountDownLatch(1);
        try (var window = new EditorWindowFixture(directory, "document.txt", List.of("tail"), port)) {
            window.event(ctrl(KeyStroke.Key.V));
            assertTrue(port.readStarted.await(2, TimeUnit.SECONDS));
            window.event(KeyStroke.of(KeyStroke.Key.F9));
            window.event(new TextInput("!"));
            assertEquals(List.of("tail"), window.active().tabSession().model().snapshotLines());
            port.holdRead.countDown();
            await(window, () -> window.active().tabSession().model().lineCount() == 2);
            await(window, () -> window.active().tabSession().model().getLine(1).startsWith("next!"));
            assertEquals(List.of("система 😀", "next!tail"), window.active().tabSession().model().snapshotLines());
            assertEquals("система 😀\nnext", window.host.clipboard.text());
            assertTrue(port.writes.isEmpty());
        } finally {
            port.holdRead.countDown();
        }
    }

    @Test
    void anotherTabsSaveCanFinishWhileTheSystemPasteReadWaits() throws Exception {
        Port port = new Port();
        port.text = "pasted ";
        port.holdRead = new CountDownLatch(1);
        var fileTasks = new ArrayDeque<Runnable>();
        try (var window = new EditorWindowFixture(directory, "document.txt", List.of("original"), port,
                fileTasks::addLast)) {
            window.event(new TextInput("saved "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertTrue(window.documentIo.busy());
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("tail"));
            window.command(TuinkCommand.CURSOR_HOME);
            window.event(ctrl(KeyStroke.Key.V));
            assertTrue(port.readStarted.await(2, TimeUnit.SECONDS));

            while (!fileTasks.isEmpty()) fileTasks.removeFirst().run();
            window.frame();
            assertFalse(window.documentIo.busy());
            assertEquals("saved original", Files.readString(window.document));
            assertEquals(List.of("tail"), window.active().tabSession().model().snapshotLines());

            port.holdRead.countDown();
            await(window, () -> window.active().tabSession().model().getLine(0).equals("pasted tail"));
            assertEquals("pasted ", window.host.clipboard.text());
            assertTrue(port.writes.isEmpty());
        } finally {
            port.holdRead.countDown();
        }
    }

    @Test
    void aChangedDocumentStillRejectsThePendingPasteWithoutImportingItsClipboard() throws Exception {
        Port port = new Port();
        port.text = "must not paste";
        port.holdRead = new CountDownLatch(1);
        try (var window = new EditorWindowFixture(directory, "document.txt", List.of("tail"), port)) {
            window.event(ctrl(KeyStroke.Key.V));
            assertTrue(port.readStarted.await(2, TimeUnit.SECONDS));
            window.active().tabSession().engine().insertInput("changed ");
            window.event(ctrl(KeyStroke.Key.F));
            port.holdRead.countDown();
            await(window, () -> window.selected().uiState().searchMode);
            assertEquals(List.of("changed tail"), window.active().tabSession().model().snapshotLines());
            assertEquals("", window.host.clipboard.text());
        } finally {
            port.holdRead.countDown();
        }
    }

    @Test
    void aDocumentThatBecomesReadOnlyStillRejectsThePendingPaste() throws Exception {
        Port port = new Port();
        port.text = "must not paste";
        port.holdRead = new CountDownLatch(1);
        try (var window = new EditorWindowFixture(directory, "document.txt", List.of("tail"), port)) {
            window.event(ctrl(KeyStroke.Key.V));
            assertTrue(port.readStarted.await(2, TimeUnit.SECONDS));
            window.ui.readingModeSetting = true;
            window.event(ctrl(KeyStroke.Key.F));
            port.holdRead.countDown();
            await(window, () -> window.selected().uiState().searchMode);
            assertEquals(List.of("tail"), window.active().tabSession().model().snapshotLines());
            assertEquals("", window.host.clipboard.text());
        } finally {
            port.holdRead.countDown();
        }
    }

    @Test
    void aFieldThatLosesFocusStillRejectsThePendingPaste() throws Exception {
        Port port = new Port();
        port.text = "must not paste";
        port.holdRead = new CountDownLatch(1);
        try (var window = new EditorWindowFixture(directory, "document.txt", List.of("tail"), port)) {
            window.event(ctrl(KeyStroke.Key.F));
            window.event(ctrl(KeyStroke.Key.V));
            assertTrue(port.readStarted.await(2, TimeUnit.SECONDS));
            window.selected().uiState().searchEditorFocused = true;
            window.event(new TextInput("!"));
            port.holdRead.countDown();
            await(window, () -> window.active().tabSession().model().getLine(0).equals("!tail"));
            assertEquals("", window.query.text());
            assertEquals("", window.host.clipboard.text());
        } finally {
            port.holdRead.countDown();
        }
    }

    @Test
    void queuedFindCommandGetsItsOwnPreparedFieldBeforeFollowingText() throws Exception {
        Port port = new Port();
        port.text = "pasted ";
        port.holdRead = new CountDownLatch(1);
        try (var window = new EditorWindowFixture(directory, "document.txt", List.of("tail"), port)) {
            window.event(ctrl(KeyStroke.Key.V));
            assertTrue(port.readStarted.await(2, TimeUnit.SECONDS));
            window.event(ctrl(KeyStroke.Key.F));
            window.event(new TextInput("needle"));
            port.holdRead.countDown();
            await(window, () -> window.query.text().equals("needle"));
            assertEquals(List.of("pasted tail"), window.active().tabSession().model().snapshotLines());
        } finally {
            port.holdRead.countDown();
        }
    }

    @Test
    void previewFindAndSaveAsUseTheSameSystemClipboardWhilePreviewContentStaysReadOnly() throws Exception {
        Port port = new Port();
        try (var window = new EditorWindowFixture(directory, "document.md", List.of("# Heading", "hello"), port)) {
            window.event(ctrl(KeyStroke.Key.P));
            await(window, () -> window.selected().uiState().preview.ready());
            window.event(ctrl(KeyStroke.Key.V));
            assertEquals(0, port.reads);
            window.event(ctrl(KeyStroke.Key.F));
            port.text = "hello";
            window.event(ctrl(KeyStroke.Key.V));
            await(window, () -> window.selected().uiState().preview.searchField.text().equals("hello"));
            window.event(ctrl(KeyStroke.Key.A));
            window.event(ctrl(KeyStroke.Key.C));
            await(window, () -> port.writes.size() == 1);
            assertEquals("hello", port.text);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(ctrl(KeyStroke.Key.K));
            window.event(ctrl(KeyStroke.Key.A));
            port.text = "copy.md";
            window.event(ctrl(KeyStroke.Key.V));
            await(window, () -> window.filename.text().equals("copy.md"));
            assertEquals(List.of("# Heading", "hello"), window.tabs.getFirst().tabSession().model().snapshotLines());
        }
    }

    @Test
    void previewSemanticCopyAlsoExportsAndNativeTerminalPasteNeverReadsTheSystem() throws Exception {
        Port port = new Port();
        try (var window = new EditorWindowFixture(directory, "document.md", List.of("# Heading", "hello"), port)) {
            window.event(ctrl(KeyStroke.Key.P));
            await(window, () -> window.selected().uiState().preview.ready());
            window.event(ctrl(KeyStroke.Key.A));
            window.event(ctrl(KeyStroke.Key.C));
            await(window, () -> !port.writes.isEmpty());
            assertTrue(port.text.contains("Heading"));
            window.command(TuinkCommand.CURSOR_DOCUMENT_START);
            window.event(ctrl(KeyStroke.Key.P));
            window.event(new PasteEvent("literal", false));
            assertEquals(0, port.reads);
            assertEquals(List.of("# literalHeading", "hello"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void queuedPreviewToSourceTransitionRebindsTheEngineBeforeTheNextFrameAndInput() throws Exception {
        Port port = new Port();
        port.text = "hello";
        port.holdRead = new CountDownLatch(1);
        try (var window = new EditorWindowFixture(directory, "document.md", List.of("# Heading", "hello"), port)) {
            window.event(ctrl(KeyStroke.Key.P));
            await(window, () -> window.selected().uiState().preview.ready());
            window.event(ctrl(KeyStroke.Key.F));
            window.event(ctrl(KeyStroke.Key.V));
            assertTrue(port.readStarted.await(2, TimeUnit.SECONDS));
            window.event(ctrl(KeyStroke.Key.P));
            window.event(new TextInput("source!"));
            port.holdRead.countDown();
            await(window, () -> window.selected() instanceof TerminalEditorApp.EditorTab
                && window.active().tabSession().model().getLine(0).equals("# source!Heading"));
            assertEquals(List.of("# source!Heading", "hello"), window.active().tabSession().model().snapshotLines());
        } finally {
            port.holdRead.countDown();
        }
    }

    @Test
    void pasteThenNewTabThenTypingReplaysAgainstEachDocumentsOwnEngine() throws Exception {
        Port port = new Port();
        port.text = "original!";
        port.holdRead = new CountDownLatch(1);
        try (var window = new EditorWindowFixture(directory, "document.txt", List.of("tail"), port)) {
            var original = window.active();
            window.event(ctrl(KeyStroke.Key.V));
            assertTrue(port.readStarted.await(2, TimeUnit.SECONDS));
            window.event(ctrl(KeyStroke.Key.N));
            window.event(new TextInput("new tab"));
            port.holdRead.countDown();
            await(window, () -> window.tabs.size() == 2
                && window.active().tabSession().model().getLine(0).equals("new tab"));
            assertEquals(List.of("original!tail"), original.tabSession().model().snapshotLines());
        } finally {
            port.holdRead.countDown();
        }
    }

    @Test
    void filePickerLocationInPreviewReadsAndWritesTheSharedSystemClipboard() throws Exception {
        Port port = new Port();
        try (var window = new EditorWindowFixture(directory, "document.md", List.of("# Heading"), port)) {
            window.event(ctrl(KeyStroke.Key.P));
            await(window, () -> window.selected().uiState().preview.ready());
            window.event(ctrl(KeyStroke.Key.O));
            window.event(ctrl(KeyStroke.Key.L));
            window.event(ctrl(KeyStroke.Key.A));
            port.text = directory.resolve("other.md").toString();
            window.event(ctrl(KeyStroke.Key.V));
            await(window, () -> window.selected().uiState().openFileFocusState.location().text().equals(port.text));
            window.event(ctrl(KeyStroke.Key.A));
            window.event(ctrl(KeyStroke.Key.C));
            await(window, () -> !port.writes.isEmpty());
            assertEquals(directory.resolve("other.md").toString(), port.text);
            assertEquals(List.of("# Heading"), window.tabs.getFirst().tabSession().model().snapshotLines());
        }
    }

    @Test
    void semanticFormulaSourceAndTableTsvCopiesUseTheSystemAdapter() throws Exception {
        Port port = new Port();
        var lines = List.of("Intro $x_i^2$.", "", "| A | B |", "|---|---|", "| left | right |");
        try (var window = new EditorWindowFixture(directory, "document.md", lines, port)) {
            window.event(ctrl(KeyStroke.Key.P));
            await(window, () -> window.selected().uiState().preview.ready());
            var preview = window.selected().uiState().preview;
            String text = preview.result.document().projection().text();
            int formula = text.indexOf("x_i^2");
            preview.selection.selectRange(text, formula, formula);
            window.command(TuinkCommand.VIEW_PREVIEW_COPY_SOURCE);
            await(window, () -> port.writes.size() == 1);
            assertEquals("x_i^2", port.text);
            int table = text.indexOf("left");
            preview.selection.selectRange(text, table, table);
            window.command(TuinkCommand.VIEW_PREVIEW_COPY_TABLE_TSV);
            await(window, () -> port.writes.size() == 2);
            assertEquals("A\tB\nleft\tright", port.text);
        }
    }

    @Test
    void unavailableSystemClipboardUsesLocalTextAndKeepsTheFallbackNoticeVisible() throws Exception {
        Port port = new Port();
        try (var window = new EditorWindowFixture(directory, "document.txt", List.of("tail"), port)) {
            window.event(ctrl(KeyStroke.Key.C));
            await(window, () -> !port.writes.isEmpty());
            port.unavailable = true;
            window.event(ctrl(KeyStroke.Key.V));
            await(window, () -> window.active().tabSession().model().getLine(0).equals("tailtail"));
            assertEquals(List.of("tailtail"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.notification.message.contains("internal clipboard"));
        }
    }

    @Test
    void genuinelyEmptySystemClipboardDoesNotPasteStaleApplicationText() throws Exception {
        Port port = new Port();
        try (var window = new EditorWindowFixture(directory, "document.txt", List.of("tail"), port)) {
            window.event(ctrl(KeyStroke.Key.C));
            await(window, () -> !port.writes.isEmpty());
            window.frame();
            port.text = "";
            window.event(ctrl(KeyStroke.Key.V));
            await(window, () -> port.reads == 1 && window.host.clipboard.text().isEmpty());
            assertEquals(List.of("tail"), window.active().tabSession().model().snapshotLines());
        }
    }

    private static void await(EditorWindowFixture window, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            window.frame();
            Thread.sleep(2);
        }
        assertTrue(condition.getAsBoolean(), "clipboard operation did not complete");
        window.frame();
    }

    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }

    private static final class Port implements ClipboardPort {
        volatile String text = "";
        volatile int reads;
        volatile boolean unavailable;
        volatile CountDownLatch holdRead;
        final CountDownLatch readStarted = new CountDownLatch(1);
        final List<String> writes = new CopyOnWriteArrayList<>();
        @Override public Optional<String> readText() {
            reads++;
            readStarted.countDown();
            try {
                if (holdRead != null && !holdRead.await(3, TimeUnit.SECONDS)) return Optional.empty();
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            return unavailable ? Optional.empty() : Optional.of(text);
        }
        @Override public boolean writeText(String text) {
            this.text = text;
            writes.add(text);
            return true;
        }
    }
}
