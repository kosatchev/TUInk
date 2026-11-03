package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.tui.flow.RecoveryStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryDiscoveryIntegrationTest {
    @TempDir Path directory;

    @Test
    void lookupRunsOnTheWorkerWhileTheCurrentDocumentRemainsEditable() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        var persistence = new DiscoveryPersistence();
        Thread owner = Thread.currentThread();
        try (var window = window(tasks, persistence)) {
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            assertEquals(0, persistence.discoveries.get());
            assertEquals(1, tasks.size());
            window.event(new TextInput("edited while looking "));
            assertEquals(List.of("edited while looking original"), window.active().tabSession().model().snapshotLines());
            Thread worker = new Thread(tasks.removeFirst());
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNotSame(owner, persistence.reader.get());
            assertFalse(window.promptText().contains("Restore now?"), "only the UI owner may publish the prompt");
            window.frame();
            assertTrue(window.promptText().contains("Restore now?"));
            assertEquals(0, persistence.materializations.get());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertEquals(1, persistence.releases.get());
        }
    }

    @Test
    void escapeBeforeWorkerExecutionCancelsLookupWithoutClaimingAnyDraft() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        var persistence = new DiscoveryPersistence();
        try (var window = window(tasks, persistence)) {
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            tasks.removeFirst().run();
            window.frame();
            assertEquals(0, persistence.discoveries.get());
            assertEquals(0, persistence.releases.get());
            assertFalse(window.promptText().contains("Restore now?"));
            assertTrue(window.notification.message.contains("cancelled"));
        }
    }

    @Test
    void aNewDialogKeepsItsFocusAndDisposesTheUnpublishedClaimsOnTheWorker() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        var persistence = new DiscoveryPersistence();
        try (var window = window(tasks, persistence)) {
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            window.command(TuinkCommand.HELP_KEY_HINTS);
            tasks.removeFirst().run();
            window.frame();
            assertTrue(window.active().uiState().modalState.isHelpHints());
            assertFalse(window.promptText().contains("Restore now?"));
            assertEquals(0, persistence.releases.get());
            tasks.removeFirst().run();
            assertEquals(1, persistence.releases.get());
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void shutdownDisposesAReadyClaimInsteadOfPublishingAnotherPrompt() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        var persistence = new DiscoveryPersistence();
        try (var window = window(tasks, persistence)) {
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            tasks.removeFirst().run();
            window.controller.closeDocumentIo();
            assertEquals(0, persistence.releases.get());
            tasks.removeFirst().run();
            assertEquals(1, persistence.releases.get());
            assertEquals(0, persistence.materializations.get());
        }
    }

    @Test
    void anExistingSaveKeepsItsSlotAndLookupFailureCanBeRetried() throws Exception {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        var persistence = new DiscoveryPersistence();
        try (var window = window(tasks, persistence)) {
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            String saveProgress = window.notification.message;
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            assertEquals(1, tasks.size());
            assertEquals(0, persistence.discoveries.get());
            assertTrue(window.active().documentSession().document().io().busy());
            assertEquals(saveProgress, window.notification.message,
                "the current save retains its progress status while recovery lookup is refused");
            tasks.removeFirst().run();
            window.frame();
            assertEquals("draft original", Files.readString(window.document));
            persistence.failure = new IOException("unavailable recovery directory");
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            tasks.removeFirst().run();
            window.frame();
            assertTrue(window.notification.message.contains("unavailable recovery directory"));
            assertFalse(window.promptText().contains("Restore now?"));
            persistence.failure = null;
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            tasks.removeFirst().run();
            window.frame();
            assertTrue(window.promptText().contains("Restore now?"));
            assertEquals(2, persistence.discoveries.get());
        }
    }

    private EditorWindowFixture window(ArrayDeque<Runnable> tasks, DiscoveryPersistence persistence) throws IOException {
        return new EditorWindowFixture(directory, false, List.of("original"), List.of(), persistence, tasks::addLast);
    }

    private static final class DiscoveryPersistence implements TerminalEditorApp.RecoveryPersistence {
        final AtomicInteger discoveries = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final AtomicInteger materializations = new AtomicInteger();
        final AtomicReference<Thread> reader = new AtomicReference<>();
        IOException failure;

        @Override public void save(String id, RecoverySnapshot snapshot) { }
        @Override public void delete(String id) { fail("lookup must never delete a draft"); }
        @Override public void release(String id) { releases.incrementAndGet(); }
        @Override public RecoveryStore.DiscoveryResult discoverAvailable() throws IOException {
            discoveries.incrementAndGet();
            reader.set(Thread.currentThread());
            if (failure != null) throw failure;
            return new RecoveryStore.DiscoveryResult(List.of(new RecoveryStore.PendingSnapshot() {
                @Override public String id() { return "discoverable-draft"; }
                @Override public RecoverySnapshot snapshot() {
                    materializations.incrementAndGet();
                    throw new AssertionError("metadata lookup must not materialize text");
                }
            }), List.of());
        }
    }
}
