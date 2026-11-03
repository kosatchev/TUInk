package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.RecoveryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Confirms visible context and destructive input through the production window pipeline. */
class NarrowConfirmationIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void narrowQuitBlocksHiddenSaveAndBothDiscardShortcutsAndAllowsEscapeToCancel() throws Exception {
        try (EditorWindowFixture window = window()) {
            window.event(new TextInput("X"));
            window.resize(24, 5);
            window.command(TuinkCommand.APP_QUIT);

            assertEquals("Enlar", prompt(window));
            window.event(new TextInput("y"));
            window.event(new TextInput("n"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.event(new TextInput(" "));
            window.pointer(PointerEvent.Kind.PRESS, window.height - 2, 0);

            assertTrue(window.controller.running());
            assertEquals("alpha", Files.readString(window.document));
            assertEquals("Xalpha", window.active().tabSession().model().getLine(0));
            assertEquals("Enlar", prompt(window));

            window.event(ctrlQ());
            assertEquals("Enlar", prompt(window));
            assertTrue(window.controller.running());
            assertTrue(window.active().state().isDirty());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.promptText().isEmpty());

            window.resize(24, 80);
            window.command(TuinkCommand.APP_QUIT);
            assertTrue(prompt(window).contains("Unsaved (1)"));
            window.event(new TextInput("y"));
            assertFalse(window.controller.running());
            assertEquals("Xalpha", Files.readString(window.document));
        }
    }

    @Test
    void narrowCloseKeepsItsUnsavedTabAndAllowsSafeCancellation() throws Exception {
        try (EditorWindowFixture window = window()) {
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("UNSAVED"));
            window.resize(24, 5);
            window.command(TuinkCommand.TAB_CLOSE);

            window.event(new TextInput("Y"));
            window.event(new TextInput("N"));
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals(2, window.tabs.size());
            assertEquals("UNSAVED", window.active().tabSession().model().getLine(0));
            assertEquals("Enlar", prompt(window));
            window.event(ctrlQ());
            assertTrue(window.promptText().isEmpty());
            assertEquals(2, window.tabs.size());

            window.resize(24, 80);
            window.command(TuinkCommand.TAB_CLOSE);
            assertTrue(prompt(window).contains("--no name--"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(2, window.tabs.size(), "reopening reapplies the safe default");
        }
    }

    @Test
    void quitKeepsTheCompleteUnsavedCountAtTheMinimumNormalWidth() throws Exception {
        try (EditorWindowFixture window = window()) {
            window.event(new TextInput("X"));
            window.resize(24, 37);
            window.command(TuinkCommand.APP_QUIT);

            assertTrue(prompt(window).contains("Unsaved (1)"));
            assertTrue(prompt(window).contains("[Save]"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.controller.running());
            assertTrue(window.active().state().isDirty());
        }
    }

    @Test
    void overwriteShowsTheBasenameAndCannotProceedUntilItsQuestionFitsAgain() throws Exception {
        try (EditorWindowFixture window = window()) {
            window.event(new TextInput("X"));
            Path victim = directory.resolve("victim.txt");
            Files.writeString(victim, "ORIGINAL");
            requestOverwrite(window, victim);

            assertTrue(prompt(window).contains("victim.txt"));
            assertTrue(prompt(window).contains("Ctrl+S"));
            window.resize(24, 5);
            window.event(new TextInput("y"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals("ORIGINAL", Files.readString(victim));
            assertEquals("Enlar", prompt(window));

            window.resize(24, 80);
            assertTrue(prompt(window).contains("victim.txt"));
            window.event(new TextInput("y"));
            assertEquals("Xalpha", Files.readString(victim));
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void ordinaryWidthSupportsLongBasenamesWithBothEndsVisible() throws Exception {
        try (EditorWindowFixture window = window()) {
            window.event(new TextInput("X"));
            Path victim = directory.resolve("begin-" + "long-".repeat(40) + "-ending.txt");
            Files.writeString(victim, "ORIGINAL");
            requestOverwrite(window, victim);

            assertTrue(prompt(window).contains("begin-"));
            assertTrue(prompt(window).contains("-ending.txt"));
            assertTrue(prompt(window).contains("Ctrl+S"));
            window.event(new TextInput("y"));
            assertEquals("Xalpha", Files.readString(victim));
        }
    }

    @Test
    void cancellingANarrowOverwriteAllowsAVisibleFreshConfirmation() throws Exception {
        try (EditorWindowFixture window = window()) {
            window.event(new TextInput("X"));
            Path victim = directory.resolve("victim.txt");
            Files.writeString(victim, "ORIGINAL");
            requestOverwrite(window, victim);
            window.resize(24, 5);
            window.event(ctrlQ());

            assertEquals("ORIGINAL", Files.readString(victim));
            assertTrue(window.promptText().isEmpty());
            window.resize(24, 80);
            requestOverwrite(window, victim);
            assertTrue(prompt(window).contains("victim.txt"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals("ORIGINAL", Files.readString(victim), "reopening reapplies the safe default");
        }
    }

    @Test
    void hiddenRecoveryQuestionCannotRestoreOrDeleteSnapshotsThroughHiddenControls() throws Exception {
        TabSession source = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        source.engine().insertInput("RECOVERED DRAFT");
        RecoveryStore.Entry entry = new RecoveryStore.Entry("recovery-entry", source.engine().captureRecoverySnapshot());
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("alpha"), List.of(entry))) {
            assertTrue(prompt(window).contains("Restore now?"));
            window.resize(24, 5);

            assertEquals("Enlar", prompt(window));
            window.event(new TextInput("y"));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.pointer(PointerEvent.Kind.PRESS, window.height - 2, 0);

            assertEquals(1, window.tabs.size());
            assertTrue(window.recoverySnapshots.containsKey(entry.id()));
            assertEquals("Enlar", prompt(window));

            window.resize(24, 80);
            assertTrue(prompt(window).contains("Restore now?"));
            window.event(new TextInput("y"));
            assertEquals("RECOVERED DRAFT", window.active().tabSession().model().getLine(0));
            assertFalse(prompt(window).contains("Restore now?"));
        }
    }

    @Test
    void closeShowsTheFileIdentityDespiteALongParentPath() throws Exception {
        try (EditorWindowFixture window = window()) {
            window.event(new TextInput("X"));
            window.command(TuinkCommand.TAB_CLOSE);

            assertTrue(prompt(window).contains("document.txt"));
            assertTrue(prompt(window).contains("Ctrl+S"));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertEquals(1, window.tabs.size());
        }
    }

    private EditorWindowFixture window() throws Exception {
        return new EditorWindowFixture(directory.resolve("a-long-parent-directory-for-confirmations"),
            false, List.of("alpha"));
    }

    private static void requestOverwrite(EditorWindowFixture window, Path path) {
        window.command(TuinkCommand.DOCUMENT_SAVE_AS);
        window.filename.set(path.toString());
        window.frame();
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }

    private static KeyStroke ctrlQ() {
        return KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL);
    }

    private static String prompt(EditorWindowFixture window) {
        return window.promptText().lines().map(String::stripTrailing).collect(java.util.stream.Collectors.joining());
    }
}
