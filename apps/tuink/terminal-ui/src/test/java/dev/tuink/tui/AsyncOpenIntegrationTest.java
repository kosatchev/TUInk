package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsyncOpenIntegrationTest {
    @TempDir Path directory;

    @Test
    void detachedReadAllowsEditingAndTabNavigationUntilTheOwnerPublishesTheDocument() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            Path selected = Files.writeString(directory.resolve("z-selected.txt"), "loaded contents");
            var original = window.active().tabSession();
            requestOpen(window, selected);
            assertTrue(window.documentIo.busy());
            assertEquals(1, window.tabs.size());
            window.event(new TextInput("draft "));
            assertEquals(List.of("draft original"), original.model().snapshotLines());
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("other draft"));
            var other = window.active().tabSession();

            scenario.executor.runNextOnWorker();
            assertEquals(2, window.tabs.size(), "the reader cannot publish a tab from its worker");
            assertSame(other, window.active().tabSession());
            assertTrue(window.documentIo.busy());
            window.frame();

            assertFalse(window.documentIo.busy());
            assertEquals(3, window.tabs.size());
            var loaded = window.active().tabSession();
            assertEquals(selected, JvmDocumentStorage.pathOf(loaded.state().getCurrentResource()));
            assertEquals(List.of("loaded contents"), loaded.model().snapshotLines());
            assertFalse(loaded.state().isDirty());
            assertTrue(original.state().isDirty());
            assertEquals(List.of("other draft"), other.model().snapshotLines());
            // Attaching another view succeeds only on the document's creator
            // thread: this also checks that construction stayed on the owner.
            assertNotNull(loaded.engine().document().attach(LogicalEditorTextLayout::new, new EditorClipboard()));
        }
    }

    @Test
    void aFileOpenedThroughAnAliasDuringTheReadIsReusedWithItsDraftAndHistory() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            Path selected = Files.writeString(directory.resolve("z-selected.txt"), "disk");
            requestOpen(window, selected);
            Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), selected.getFileName());
            window.command(TuinkCommand.TAB_NEW);
            var existing = window.active().tabSession();
            existing.engine().openExisting(JvmDocumentStorage.resource(alias));
            window.frame();
            window.event(new TextInput("draft "));
            long revision = existing.model().revision();
            var cursor = existing.state().getCursor();
            scenario.executor.runNextOnWorker();
            assertEquals(2, window.tabs.size());
            window.frame();

            assertEquals(2, window.tabs.size());
            assertSame(existing, window.active().tabSession());
            assertEquals(alias, JvmDocumentStorage.pathOf(existing.state().getCurrentResource()));
            assertEquals(revision, existing.model().revision());
            assertEquals(cursor, existing.state().getCursor());
            assertEquals(List.of("draft disk"), existing.model().snapshotLines());
            assertTrue(existing.state().isDirty());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("disk"), existing.model().snapshotLines());
            assertFalse(existing.state().isDirty());
        }
    }

    @Test
    void failedReadRestoresThePickerAndKeepsTheActiveDraft() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            Path selected = Files.writeString(directory.resolve("z-selected.txt"), "soon missing");
            requestOpen(window, selected);
            window.event(new TextInput("draft "));
            var original = window.active().tabSession();
            var cursor = original.state().getCursor();
            Files.delete(selected);
            scenario.executor.runNextOnWorker();
            window.frame();

            assertFalse(window.documentIo.busy());
            assertEquals(1, window.tabs.size());
            assertSame(original, window.active().tabSession());
            assertEquals(cursor, original.state().getCursor());
            assertEquals(List.of("draft original"), original.model().snapshotLines());
            assertTrue(IntStream.range(0, window.height)
                .anyMatch(row -> window.screenRow(row).contains("Open File")));
            assertTrue(IntStream.range(0, window.height)
                .anyMatch(row -> window.screenRow(row).contains("file no longer exists")));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(new TextInput("after "));
            assertEquals(List.of("draft after original"), original.model().snapshotLines());
        }
    }

    @Test
    void closingDrainsTheReadWithoutPublishingALateTab() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            Path selected = Files.writeString(directory.resolve("z-selected.txt"), "loaded contents");
            requestOpen(window, selected);
            window.event(new TextInput("draft "));
            var original = window.active().tabSession();
            scenario.executor.runNextOnWorker();
            assertEquals(1, window.tabs.size());

            window.controller.closeDocumentIo();

            assertFalse(window.documentIo.busy());
            assertEquals(1, window.tabs.size());
            assertSame(original, window.active().tabSession());
            assertEquals(List.of("draft original"), original.model().snapshotLines());
            window.frame();
            assertEquals(1, window.tabs.size(), "later owner turns cannot publish the drained read");
        }
    }

    @Test
    void completingAnOpenDoesNotRetargetAnotherDocumentsSaveAsAndCloseContinuation() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            Path selected = Files.writeString(directory.resolve("z-selected.txt"), "opened contents");
            requestOpen(window, selected);
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("unsaved second"));
            var second = window.active().tabSession();
            window.command(TuinkCommand.TAB_CLOSE);
            window.event(new TextInput("y"));
            Path target = directory.resolve("second.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));

            scenario.executor.runNextOnWorker();
            window.frame();
            assertEquals(3, window.tabs.size());
            assertSame(second, window.active().tabSession());
            assertEquals(target.toString(), window.filename.text());
            assertTrue(second.state().isDirty());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.documentIo.busy());
            scenario.executor.runNextOnWorker();
            window.frame();

            assertTrue(window.documentIo.busy(), "target inspection must queue a separate write");
            assertFalse(Files.exists(target), "inspection must not publish the target");
            assertEquals(3, window.tabs.size(), "the close continuation waits for the write");
            assertSame(second, window.active().tabSession());
            assertTrue(second.state().isDirty());
            scenario.executor.runNextOnWorker();
            window.frame();

            assertEquals("unsaved second", Files.readString(target));
            assertEquals("opened contents", Files.readString(selected));
            assertEquals(2, window.tabs.size());
            assertFalse(window.tabs.stream().anyMatch(tab -> tab.tabSession() == second));
            assertEquals(selected, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(List.of("opened contents"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void completingAnOpenKeepsTheDocumentOwnedByAnIndependentCloseConfirmationActive() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            Path selected = Files.writeString(directory.resolve("z-selected.txt"), "opened contents");
            requestOpen(window, selected);
            window.event(new TextInput("draft "));
            var original = window.active().tabSession();
            window.command(TuinkCommand.TAB_CLOSE);
            scenario.executor.runNextOnWorker();
            window.frame();

            assertEquals(2, window.tabs.size());
            assertSame(original, window.active().tabSession());
            assertTrue(original.state().isDirty());
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(1, window.tabs.size());
            assertEquals(selected, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals("original", Files.readString(window.document));
        }
    }

    @Test
    void aFailedOpenWaitsForTheIndependentSaveAndCloseFlowBeforeRestoringItsPicker() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            Path selected = Files.writeString(directory.resolve("z-selected.txt"), "soon missing");
            requestOpen(window, selected);
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("unsaved second"));
            var second = window.active().tabSession();
            window.command(TuinkCommand.TAB_CLOSE);
            window.event(new TextInput("y"));
            Path target = directory.resolve("second.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            Files.delete(selected);
            scenario.executor.runNextOnWorker();
            window.frame();

            assertSame(second, window.active().tabSession());
            assertEquals(target.toString(), window.filename.text());
            assertFalse(IntStream.range(0, window.height)
                .anyMatch(row -> window.screenRow(row).contains("Open File")));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.documentIo.busy());
            scenario.executor.runNextOnWorker();
            window.frame();

            assertTrue(window.documentIo.busy(), "target inspection must queue a separate write");
            assertFalse(Files.exists(target), "inspection must not publish the target");
            assertEquals(2, window.tabs.size(), "the close continuation waits for the write");
            assertSame(second, window.active().tabSession());
            assertTrue(second.state().isDirty());
            assertFalse(IntStream.range(0, window.height)
                .anyMatch(row -> window.screenRow(row).contains("Open File")),
                "the failed open stays deferred until the save-and-close flow finishes");
            scenario.executor.runNextOnWorker();
            window.frame();

            assertEquals("unsaved second", Files.readString(target));
            assertEquals(1, window.tabs.size());
            assertFalse(window.tabs.stream().anyMatch(tab -> tab.tabSession() == second));
            assertTrue(IntStream.range(0, window.height)
                .anyMatch(row -> window.screenRow(row).contains("Open File")));
            assertTrue(IntStream.range(0, window.height)
                .anyMatch(row -> window.screenRow(row).contains("file no longer exists")));
        }
    }

    @Test
    void anEncodingChoiceWaitsForTheIndependentDocumentPromptToFinish() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            Path selected = Files.write(directory.resolve("z-selected.txt"),
                new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0, 65, 0, 0, 0});
            requestOpen(window, selected);
            window.event(new TextInput("draft "));
            var original = window.active().tabSession();
            window.command(TuinkCommand.TAB_CLOSE);
            scenario.executor.runNextOnWorker();
            window.frame();

            assertSame(original, window.active().tabSession());
            assertTrue(original.state().isDirty());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.screenRow(window.height - 2).contains("UTF-16LE"));
            window.event(new TextInput("y"));
            assertTrue(window.documentIo.busy());
            scenario.executor.runNextOnWorker();
            window.frame();
            assertEquals(2, window.tabs.size());
            assertEquals(selected, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(List.of("\0A\0"), window.active().tabSession().model().snapshotLines());
            assertTrue(original.state().isDirty());
            assertEquals(List.of("draft original"), original.model().snapshotLines());
        }
    }

    @Test
    void shutdownDiscardsADeferredOpenFailureWithoutPublishingItOnLaterOwnerTurns() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            Path selected = Files.writeString(directory.resolve("z-selected.txt"), "soon missing");
            requestOpen(window, selected);
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.TAB_CLOSE);
            Files.delete(selected);
            scenario.executor.runNextOnWorker();
            window.frame();
            window.controller.closeDocumentIo();
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.frame();

            assertEquals(1, window.tabs.size());
            assertTrue(window.active().state().isDirty());
            assertFalse(IntStream.range(0, window.height)
                .anyMatch(row -> window.screenRow(row).contains("Open File")));
        }
    }

    private Scenario scenario() throws Exception {
        ControlledExecutor executor = new ControlledExecutor();
        return new Scenario(new EditorWindowFixture(directory, false, List.of("original"), executor), executor);
    }

    private static void requestOpen(EditorWindowFixture window, Path selected) throws Exception {
        window.command(TuinkCommand.DOCUMENT_OPEN);
        window.await(() -> !window.tree.loading());
        window.event(KeyStroke.of(KeyStroke.Key.END));
        assertEquals(selected, window.tree.state().selectedId().path());
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }

    private record Scenario(EditorWindowFixture window, ControlledExecutor executor) implements AutoCloseable {
        @Override public void close() {
            executor.finishQueued();
            window.close();
        }
    }

    private static final class ControlledExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override public void execute(Runnable task) { tasks.add(task); }

        void runNextOnWorker() throws InterruptedException {
            Thread worker = new Thread(tasks.removeFirst(), "test-document-reader");
            worker.start();
            worker.join(5_000L);
            assertFalse(worker.isAlive(), "detached reader did not finish");
        }

        void finishQueued() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }
}
