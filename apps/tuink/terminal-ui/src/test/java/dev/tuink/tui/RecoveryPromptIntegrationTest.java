package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.RecoveryStoreFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryPromptIntegrationTest {
    private static final String ENTRY_ID = "startup-draft";
    private static final String DRAFT = "ONLY COPY OF UNSAVED WORK";

    @TempDir Path directory;

    @Test
    void postponedDraftCanBeRestoredWithoutRestartingAndLiveDraftsAreExcluded() throws Exception {
        Path recoveryDirectory = directory.resolve("recovery-reopen");
        createSnapshot(recoveryDirectory);
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture window = new EditorWindowFixture(directory.resolve("reopen-window"), false,
                 List.of("current document"), store.discover().entries(), persistence(store))) {
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(new TextInput("live edits "));
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            assertTrue(prompt(window).contains("Unsaved drafts: 1."));
            assertTrue(prompt(window).contains("D=Delete"));
            assertTrue(prompt(window).contains("[Restore]"));
            assertTrue(prompt(window).contains("[Delete]"));
            window.event(new TextInput("y"));
            assertEquals(2, window.tabs.size());
            assertEquals(List.of(DRAFT), window.active().tabSession().model().snapshotLines());
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            assertTrue(window.notification.message.contains("No recovery drafts available"));
            assertEquals(2, window.tabs.size());
        }
    }

    @Test
    void startupAndLaterDoNotMaterializeTheOfferedDraft() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        RecoveryStore.PendingSnapshot pending = pending("deferred-draft", reads);
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false,
            List.of("CLI document"), List.of(pending))) {
            assertTrue(prompt(window).contains("Unsaved drafts:"));
            assertEquals(0, reads.get());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertEquals(0, reads.get());
            assertEquals(List.of("CLI document"), window.active().tabSession().model().snapshotLines());
            assertFalse(prompt(window).contains("Unsaved drafts:"));
        }
    }

    @Test
    void partialRestoreKeepsFailedDraftPendingAndDoesNotRestoreSuccessfulDraftTwice() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        RecoveryStore.Entry good = new RecoveryStore.Entry("good-draft", snapshot());
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false,
            List.of("CLI document"), List.of(good, pending("too-large", reads)))) {
            window.event(new TextInput("y"));
            assertEquals(1, reads.get());
            assertEquals(2, window.tabs.size());
            assertEquals(List.of(DRAFT), window.active().tabSession().model().snapshotLines());
            assertTrue(window.notification.message.contains("Could not restore 1"));
            assertTrue(prompt(window).contains("Unsaved drafts: 1."));

            window.event(new TextInput("y"));
            assertEquals(2, reads.get());
            assertEquals(2, window.tabs.size(), "a retry must only touch failed drafts");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(prompt(window).contains("Unsaved drafts:"));
            assertEquals(2, window.tabs.size());
        }
    }

    private static RecoveryStore.PendingSnapshot pending(String id, AtomicInteger reads) {
        return new RecoveryStore.PendingSnapshot() {
            @Override public String id() { return id; }
            @Override public RecoverySnapshot snapshot() throws IOException {
                reads.incrementAndGet();
                throw new IOException("snapshot exceeds available memory");
            }
        };
    }

    @ParameterizedTest
    @MethodSource("postponements")
    void laterKeepsTheDraftThroughNormalQuitAndOffersItOnTheNextLaunch(int columns, TuiInputEvent input)
        throws Exception {
        Path recoveryDirectory = directory.resolve("recovery");
        createSnapshot(recoveryDirectory);

        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture window = new EditorWindowFixture(directory.resolve("first-window"), false,
                 List.of("other document"), store.loadAll().entries(), persistence(store))) {
            if (columns == 80) {
                assertEquals(ConfirmChoice.CANCEL, window.host.confirmFocusManager().focused().orElseThrow());
                assertTrue(prompt(window).contains("[Later]"));
            }
            window.resize(24, columns);
            window.event(input);

            assertTrue(window.controller.running());
            assertFalse(prompt(window).contains("Unsaved drafts:"));
            assertEquals(ActiveSurface.ModalScope.NONE, window.controller.activeSurface().modalScope());
            assertEquals(1, window.tabs.size());
            assertEquals(List.of("other document"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.notification.message.contains("kept for next launch"));
            assertAvailableToAnotherOpenStore(recoveryDirectory, List.of(ENTRY_ID));

            // A later dirty runtime tab has its own snapshot. A normal discard-and-quit
            // must clean that snapshot without touching the postponed startup draft.
            window.resize(24, 80);
            window.event(new TextInput("new edits "));
            try (var files = Files.list(recoveryDirectory)) {
                assertEquals(2L, files.filter(path -> path.toString().endsWith(".meta.properties")).count());
            }
            window.command(TuinkCommand.APP_QUIT);
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertFalse(window.controller.running());
            assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), true));
            assertEquals("other document", Files.readString(window.document));
            assertAvailableToAnotherOpenStore(recoveryDirectory, List.of(ENTRY_ID));
        }

        try (RecoveryStore nextStore = RecoveryStoreFixtures.open(recoveryDirectory)) {
            List<RecoveryStore.Entry> entries = nextStore.loadAll().entries();
            assertEquals(1, entries.size());
            assertEquals(ENTRY_ID, entries.getFirst().id());
            assertEquals(List.of(DRAFT), entries.getFirst().snapshot().lines());
            try (EditorWindowFixture next = new EditorWindowFixture(directory.resolve("next-window"), false,
                List.of("other document"), entries, persistence(nextStore))) {
                assertTrue(prompt(next).contains("Unsaved drafts:"));
                next.event(new TextInput("y"));
                assertEquals(List.of(DRAFT), next.active().tabSession().model().snapshotLines());
                assertTrue(next.active().state().isDirty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {80, 5})
    void recoveryQuitExitsWithoutRestoringOrDeletingTheDraftAndOffersItOnTheNextLaunch(int columns) throws Exception {
        Path recoveryDirectory = directory.resolve("recovery-quit");
        createSnapshot(recoveryDirectory);
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture window = new EditorWindowFixture(directory.resolve("quit-window"), false,
                 List.of("other document"), store.discover().entries(), persistence(store))) {
            window.resize(24, columns);
            window.event(ctrlQ());

            assertFalse(window.controller.running());
            assertEquals(1, window.tabs.size(), "Quit must not materialize or restore an offered draft");
            assertEquals("other document", Files.readString(window.document));
            assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), true));
            assertAvailableToAnotherOpenStore(recoveryDirectory, List.of(ENTRY_ID));
        }
        try (RecoveryStore nextStore = RecoveryStoreFixtures.open(recoveryDirectory)) {
            var entries = nextStore.discover().entries();
            assertEquals(List.of(ENTRY_ID), entries.stream().map(RecoveryStore.PendingSnapshot::id).toList());
            try (EditorWindowFixture next = new EditorWindowFixture(directory.resolve("after-quit-window"), false,
                List.of("other document"), entries, persistence(nextStore))) {
                assertTrue(prompt(next).contains("Unsaved drafts: 1."));
                next.event(new TextInput("y"));
                assertEquals(List.of(DRAFT), next.active().tabSession().model().snapshotLines());
                assertTrue(next.active().state().isDirty());
            }
        }
    }

    @Test
    void recoveryQuitUsesTheNormalUnsavedConfirmationAndEscapePreservesLiveEditsAndOfferedCopies() throws Exception {
        Path recoveryDirectory = directory.resolve("recovery-cancel-quit");
        createSnapshot(recoveryDirectory);
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture window = new EditorWindowFixture(directory.resolve("cancel-quit-window"), false,
                 List.of("current document"), store.discover().entries(), persistence(store))) {
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(new TextInput("live edits "));
            var current = window.active();
            var liveText = current.tabSession().model().snapshotLines();
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            assertTrue(prompt(window).contains("Unsaved drafts: 1."));

            window.event(ctrlQ());

            assertTrue(window.controller.running());
            assertTrue(prompt(window).contains("Unsaved (1)"));
            assertFalse(prompt(window).contains("Unsaved drafts:"));
            assertAvailableToAnotherOpenStore(recoveryDirectory, List.of(ENTRY_ID));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.controller.running());
            assertEquals(ActiveSurface.ModalScope.NONE, window.controller.activeSurface().modalScope());
            assertEquals(current, window.active());
            assertEquals(liveText, window.active().tabSession().model().snapshotLines());
            assertTrue(current.state().isDirty());
            assertEquals("current document", Files.readString(window.document));
            assertAvailableToAnotherOpenStore(recoveryDirectory, List.of(ENTRY_ID));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savingOrDiscardingLiveEditsAfterRecoveryQuitKeepsOfferedCopies(boolean save) throws Exception {
        Path recoveryDirectory = directory.resolve("recovery-finish-quit");
        createSnapshot(recoveryDirectory);
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture window = new EditorWindowFixture(directory.resolve("finish-quit-window"), false,
                 List.of("current document"), store.discover().entries(), persistence(store))) {
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(new TextInput("live edits "));
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            assertTrue(prompt(window).contains("Unsaved drafts: 1."));
            window.event(ctrlQ());
            assertTrue(window.controller.running());
            assertTrue(prompt(window).contains("Unsaved (1)"));

            window.event(new TextInput(save ? "y" : "n"));

            assertFalse(window.controller.running());
            assertEquals(save ? "live edits current document" : "current document", Files.readString(window.document));
            assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), true));
            assertAvailableToAnotherOpenStore(recoveryDirectory, List.of(ENTRY_ID));
            assertEquals(List.of(ENTRY_ID + ".meta.properties"), metadataNames(recoveryDirectory));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyExplicitDeleteRemovesTheOfferedDraft(boolean pointer) throws Exception {
        Path recoveryDirectory = directory.resolve("recovery");
        createSnapshot(recoveryDirectory);

        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture window = new EditorWindowFixture(directory.resolve("window"), false,
                 List.of("other document"), store.loadAll().entries(), persistence(store))) {
            assertEquals(ConfirmChoice.CANCEL, window.host.confirmFocusManager().focused().orElseThrow());
            if (pointer) {
                clickDelete(window);
            } else {
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_LEFT));
                assertEquals(ConfirmChoice.DISCARD, window.host.confirmFocusManager().focused().orElseThrow());
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            }

            assertTrue(store.loadAll().entries().isEmpty());
            assertEquals(1, window.tabs.size());
            assertEquals(List.of("other document"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.controller.running());
            assertFalse(prompt(window).contains("Unsaved drafts:"));
        }
        try (RecoveryStore nextStore = RecoveryStoreFixtures.open(recoveryDirectory)) {
            assertTrue(nextStore.loadAll().entries().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"d", "D"})
    void deleteShortcutRemovesOnlyOfferedRecoveryCopiesAndKeepsLiveEditsAndOriginalFiles(String key) throws Exception {
        Path original = directory.resolve("recovered-original.txt");
        Files.writeString(original, "ORIGINAL FILE");
        TabSession recovered = TuiDocumentSession.create(new EditorClipboard()).view();
        recovered.engine().open(JvmDocumentStorage.resource(original));
        recovered.engine().insertInput("RECOVERED EDITS ");
        Path recoveryDirectory = directory.resolve("shortcut-recovery");
        try (RecoveryStore writer = RecoveryStoreFixtures.open(recoveryDirectory)) {
            writer.save(ENTRY_ID, recovered.engine().captureRecoverySnapshot());
        }
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory);
             EditorWindowFixture window = new EditorWindowFixture(directory.resolve("shortcut-window"), false,
                 List.of("current document"), store.discover().entries(), persistence(store))) {
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.controller.running());
            window.event(new TextInput("live edits "));
            var current = window.active();
            List<String> liveText = current.tabSession().model().snapshotLines();
            assertEquals(2, metadataNames(recoveryDirectory).size());
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            assertTrue(prompt(window).contains("Unsaved drafts: 1."));
            assertTrue(prompt(window).contains("D=Delete"));

            window.event(new TextInput(key));

            assertTrue(window.controller.running());
            assertFalse(prompt(window).contains("Unsaved drafts:"));
            assertEquals(ActiveSurface.ModalScope.NONE, window.controller.activeSurface().modalScope());
            assertEquals(current, window.active());
            assertEquals(liveText, window.active().tabSession().model().snapshotLines());
            assertTrue(window.active().state().isDirty());
            assertEquals("current document", Files.readString(window.document));
            assertEquals("ORIGINAL FILE", Files.readString(original));
            List<String> remaining = metadataNames(recoveryDirectory);
            assertEquals(1, remaining.size(), "the live document's own recovery copy remains");
            assertFalse(remaining.contains(ENTRY_ID + ".meta.properties"));
            window.command(TuinkCommand.DOCUMENT_RECOVER_DRAFTS);
            assertTrue(window.notification.message.contains("No recovery drafts available"));
            assertEquals(liveText, window.active().tabSession().model().snapshotLines());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialReleaseFailureKeepsItsPromptAndDraftForRetryWithoutDeletingAnyEntry(boolean quit) throws Exception {
        Path recoveryDirectory = directory.resolve("recovery");
        createSnapshot(recoveryDirectory);
        try (RecoveryStore writer = RecoveryStoreFixtures.open(recoveryDirectory)) {
            writer.save("another-draft", snapshot());
        }
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory)) {
            TerminalEditorApp.RecoveryPersistence persistence = new TerminalEditorApp.RecoveryPersistence() {
                private boolean failRelease = true;
                @Override public void save(String entryId, RecoverySnapshot snapshot) throws IOException {
                    store.save(entryId, snapshot);
                }
                @Override public void delete(String entryId) throws IOException {
                    store.delete(entryId);
                }
                @Override public void release(String entryId) throws IOException {
                    if (entryId.equals(ENTRY_ID) && failRelease) {
                        failRelease = false;
                        throw new IOException("simulated ownership release failure");
                    }
                    store.release(entryId);
                }
            };
            try (EditorWindowFixture window = new EditorWindowFixture(directory.resolve("window"), false,
                List.of("other document"), store.loadAll().entries(), persistence)) {
                window.event(quit ? ctrlQ() : KeyStroke.of(KeyStroke.Key.ESCAPE));

                assertTrue(window.controller.running(), "Failed ownership release must not quit or lose the pending draft");
                assertTrue(prompt(window).contains("Unsaved drafts: 1."));
                assertTrue(window.notification.message.contains("Failed to postpone 1"));
                assertTrue(window.notification.message.contains("simulated ownership release failure"));
                assertAvailableToAnotherOpenStore(recoveryDirectory, List.of("another-draft"));
                assertTrue(Files.exists(recoveryDirectory.resolve(ENTRY_ID + ".meta.properties")));
                assertEquals(List.of("other document"), window.active().tabSession().model().snapshotLines());

                window.event(quit ? ctrlQ() : KeyStroke.of(KeyStroke.Key.ESCAPE));

                assertEquals(!quit, window.controller.running());
                assertFalse(prompt(window).contains("Unsaved drafts:"));
                if (quit) assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), true));
                assertAvailableToAnotherOpenStore(recoveryDirectory, List.of("another-draft", ENTRY_ID));
            }
        }
    }

    private static Stream<Arguments> postponements() {
        List<Arguments> cases = new ArrayList<>();
        for (int columns : List.of(80, 12)) {
            for (TuiInputEvent input : List.of(KeyStroke.of(KeyStroke.Key.ESCAPE),
                new TextInput("n"), new TextInput("N"))) {
                cases.add(Arguments.of(columns, input));
            }
        }
        cases.add(Arguments.of(80, KeyStroke.of(KeyStroke.Key.ENTER)));
        cases.add(Arguments.of(80, new TextInput(" ")));
        return cases.stream();
    }

    private static void createSnapshot(Path recoveryDirectory) throws IOException {
        try (RecoveryStore store = RecoveryStoreFixtures.open(recoveryDirectory)) {
            store.save(ENTRY_ID, snapshot());
        }
    }

    private static RecoverySnapshot snapshot() {
        TabSession source = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        source.engine().insertInput(DRAFT);
        return source.engine().captureRecoverySnapshot();
    }

    private static KeyStroke ctrlQ() {
        return KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL);
    }

    private static void assertAvailableToAnotherOpenStore(Path recoveryDirectory, List<String> expectedIds)
        throws IOException {
        try (RecoveryStore concurrent = RecoveryStoreFixtures.open(recoveryDirectory)) {
            List<RecoveryStore.Entry> entries = concurrent.loadAll().entries();
            assertEquals(expectedIds, entries.stream().map(RecoveryStore.Entry::id).toList());
            for (RecoveryStore.Entry entry : entries) {
                assertEquals(List.of(DRAFT), entry.snapshot().lines());
            }
        }
    }

    private static TerminalEditorApp.RecoveryPersistence persistence(RecoveryStore store) {
        return new TerminalEditorApp.RecoveryPersistence() {
            @Override public void save(String entryId, RecoverySnapshot snapshot) throws IOException {
                store.save(entryId, snapshot);
            }
            @Override public void delete(String entryId) throws IOException {
                store.delete(entryId);
            }
            @Override public void release(String entryId) throws IOException {
                store.release(entryId);
            }
            @Override public RecoveryStore.DiscoveryResult discoverAvailable() throws IOException {
                return store.discoverAvailable();
            }
        };
    }

    private static String prompt(EditorWindowFixture window) {
        return window.promptText();
    }

    private static List<String> metadataNames(Path recoveryDirectory) throws IOException {
        try (var paths = Files.list(recoveryDirectory)) {
            return paths.map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".meta.properties")).sorted().toList();
        }
    }

    private static void clickDelete(EditorWindowFixture window) {
        for (int row = 0; row < window.height; row++) {
            int column = window.screenRow(row).indexOf("[Delete]");
            if (column >= 0) {
                window.pointer(PointerEvent.Kind.PRESS, row, column);
                return;
            }
        }
        throw new AssertionError("Missing visible Delete button");
    }
}
