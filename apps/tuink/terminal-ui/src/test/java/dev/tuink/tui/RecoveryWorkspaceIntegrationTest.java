package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.TextInput;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.TabSession;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.RecoveryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryWorkspaceIntegrationTest {
    @TempDir Path directory;

    @Test
    void restoringADraftKeepsTheExplicitStartupFileAndActivatesTheDraft() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("CLI contents"))) {
            var cli = window.active();

            assertEquals(1, window.restore(List.of(draft("recovered", null, "recovered contents"))));

            assertEquals(2, window.tabs.size());
            assertSame(cli, window.tabs.getFirst());
            assertEquals(window.document, JvmDocumentStorage.pathOf(cli.state().getCurrentResource()));
            assertEquals(List.of("CLI contents"), cli.tabSession().model().snapshotLines());
            assertFalse(cli.state().isDirty());
            assertEquals(1, window.context.activeTabIndex());
            assertEquals(List.of("recovered contents"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.active().state().isDirty());
            assertEquals("CLI contents", Files.readString(window.document));
            assertTrue(window.recoverySnapshots.containsKey("recovered"));
        }
    }

    @Test
    void restoringPreservesAnExistingDirtyTabsCursorAndUndoHistory() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("saved"))) {
            window.event(new TextInput("unsaved "));
            var original = window.active();
            var cursor = original.state().getCursor();
            var builder = original.renderPlanBuilder();

            window.restore(List.of(draft("recovered", null, "other draft")));
            window.frame();
            window.command(TuinkCommand.TAB_PREVIOUS);

            assertSame(original, window.active());
            assertSame(builder, window.context.renderPlanBuilder());
            assertEquals(cursor, original.state().getCursor());
            assertEquals(List.of("unsaved saved"), original.tabSession().model().snapshotLines());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("saved"), original.tabSession().model().snapshotLines());
            assertFalse(original.state().isDirty());
        }
    }

    @Test
    void aNamedEmptyStartupFileIsNotAPlaceholder() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of(""))) {
            var named = window.active();
            window.restore(List.of(draft("recovered", null, "draft")));

            assertEquals(2, window.tabs.size());
            assertSame(named, window.tabs.getFirst());
            assertEquals(window.document, JvmDocumentStorage.pathOf(named.state().getCurrentResource()));
        }
    }

    @Test
    void aNewNamedStartupFileIsKeptBeforeItHasBeenSaved() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("saved"))) {
            Path absent = directory.resolve("new-command-line-file.txt");
            var named = window.active();
            named.tabSession().engine().open(JvmDocumentStorage.resource(absent));

            window.restore(List.of(draft("recovered", null, "draft")));

            assertEquals(2, window.tabs.size());
            assertSame(named, window.tabs.getFirst());
            assertEquals(absent, JvmDocumentStorage.pathOf(named.state().getCurrentResource()));
            assertFalse(Files.exists(absent));
        }
    }

    @Test
    void restoringReplacesOnlyThePristineUnnamedStartupPlaceholder() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("saved"))) {
            var placeholder = window.host.newEditorTab();
            window.tabs.set(0, placeholder);
            window.context.activate(0);

            window.restore(List.of(draft("recovered", null, "draft")));

            assertEquals(1, window.tabs.size());
            assertNotSame(placeholder, window.active());
            assertEquals(0, window.context.activeTabIndex());
            assertEquals(List.of("draft"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void anEmptyUnnamedTabWithRedoHistoryIsKept() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("saved"))) {
            var original = window.host.newEditorTab();
            original.tabSession().engine().insertInput("redo me");
            original.tabSession().engine().undo();
            window.tabs.set(0, original);
            window.context.activate(0);

            window.restore(List.of(draft("recovered", null, "other draft")));

            assertEquals(2, window.tabs.size());
            assertSame(original, window.tabs.getFirst());
            original.tabSession().engine().redo();
            assertEquals(List.of("redo me"), original.tabSession().model().snapshotLines());
        }
    }

    @Test
    void separateRecoveredDraftsForTheStartupFileKeepTheirTextAndRecoveryIds() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("disk"))) {
            var cli = window.active();
            var first = draft("first-recovery", window.document, "first ");
            var second = draft("second-recovery", window.document, "second ");

            assertEquals(2, window.restore(List.of(first, second)));

            assertEquals(3, window.tabs.size(), "restoring separate unsaved drafts must not merge their text");
            assertSame(cli, window.tabs.getFirst());
            assertEquals(List.of("disk"), cli.tabSession().model().snapshotLines());
            assertEquals(List.of("first disk"), window.tabs.get(1).tabSession().model().snapshotLines());
            assertEquals(List.of("second disk"), window.tabs.get(2).tabSession().model().snapshotLines());
            assertEquals(1, window.context.activeTabIndex());
            window.recoveryManager.autosaveDirtyTabs(window.host.toDocumentSessions(window.tabs), Long.MAX_VALUE);
            assertTrue(window.recoverySnapshots.containsKey(first.id()));
            assertTrue(window.recoverySnapshots.containsKey(second.id()));
            assertEquals("disk", Files.readString(window.document));
        }
    }

    private static RecoveryStore.Entry draft(String id, Path file, String inserted) throws Exception {
        TabSession tab = TuiDocumentSession.create(new EditorClipboard()).view();
        if (file != null) {
            tab.engine().open(JvmDocumentStorage.resource(file));
        }
        tab.engine().insertInput(inserted);
        RecoverySnapshot snapshot = tab.engine().captureRecoverySnapshot();
        return new RecoveryStore.Entry(id, snapshot);
    }
}
