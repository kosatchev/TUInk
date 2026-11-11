package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalAutoReloadTest {
    @TempDir Path directory;

    @Test void cleanFileReloadsByDefaultPreservingCursorAndManualScroll() throws Exception {
        try (var window = new EditorWindowFixture(directory, false)) {
            assertTrue(window.ui.autoReloadUnmodifiedFilesSetting);
            var source = window.active(); var state = source.state();
            source.tabSession().engine().moveCursorTo(new Position(80, 110));
            state.setTopLine(30); state.setLeftColumn(20); state.setLeftVisualColumn(20);
            source.viewportController().onRecoveredViewport(); window.frame();
            Files.writeString(window.document, String.join("\n", source.tabSession().model().snapshotLines()).replace("ROW", "NEW"));
            window.await(() -> source.tabSession().model().getLine(0).startsWith("NEW"));
            assertEquals(new Position(80, 110), state.getCursor());
            assertEquals(30, state.getTopLine()); assertEquals(20, state.getLeftVisualColumn());
            assertFalse(state.isDirty()); assertFalse(window.host.externalNotices.visible(source.documentSession().document().documentId()));
            Files.writeString(window.document, "short");
            window.await(() -> source.tabSession().model().snapshotLines().equals(List.of("short")));
            assertEquals(new Position(0, 5), state.getCursor());
            assertEquals(0, state.getTopLine()); assertEquals(0, state.getLeftVisualColumn());
        }
    }

    @Test void dirtyFilesRemainLocalAndWarningSurvivesDiskReturningToTheOriginalBytes() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var id = window.active().documentSession().document().documentId();
            Files.writeString(window.document, "remote"); window.await(() -> window.host.externalNotices.visible(id));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            Files.writeString(window.document, "original"); pump(window, 1_150);
            assertTrue(window.host.externalNotices.visible(id));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.await(() -> window.selected().uiState().modalState.isSaveOverwriteConfirm());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE)); assertTrue(window.host.externalNotices.visible(id));
            window.command(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL);
            window.await(() -> !window.documentIo.busy());
            if (window.host.externalNotices.visible(id)) window.command(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL);
            window.await(() -> !window.documentIo.busy() && !window.host.externalNotices.visible(id));
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test void turningAutoReloadOffAlwaysRequiresAnExplicitUpdateEvenForCleanFiles() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.command(TuinkCommand.SETTINGS_TOGGLE_AUTO_RELOAD_UNMODIFIED);
            assertFalse(window.ui.autoReloadUnmodifiedFilesSetting);
            var id = window.active().documentSession().document().documentId();
            Files.writeString(window.document, "external"); window.await(() -> window.host.externalNotices.visible(id));
            pump(window, 1_150); assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
            window.event(KeyStroke.of(KeyStroke.Key.F1)); window.event(new TextInput("u"));
            window.await(() -> !window.documentIo.busy() && !window.host.externalNotices.visible(id));
            assertEquals(List.of("external"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test void localEditDuringAutomaticReadPreventsAdoptionOfTheStaleResult() throws Exception {
        var pending = new ArrayDeque<Runnable>();
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), pending::add)) {
            Files.writeString(window.document, "external"); window.await(() -> !pending.isEmpty());
            var id = window.active().documentSession().document().documentId();
            window.event(new TextInput("draft ")); pending.remove().run(); window.frame();
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.active().state().isDirty()); assertTrue(window.host.externalNotices.visible(id));
            assertTrue(pending.isEmpty());
        }
    }

    @Test void frozenPreviewAutomaticallyReloadsCleanTextButProtectsRetainedLocalEdits() throws Exception {
        for (boolean dirty : List.of(false, true)) {
            try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Original"))) {
                if (dirty) window.event(new TextInput("draft "));
                window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW); window.await(() -> window.preview().ready());
                window.command(TuinkCommand.TAB_PREVIOUS); window.ui.confirmOnCloseSetting = false;
                window.command(TuinkCommand.TAB_CLOSE);
                window.await(() -> window.selected() instanceof PreviewTab p && p.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
                var subject = ((PreviewTab.ClosedSnapshot) ((PreviewTab) window.selected()).source()).subject();
                assertEquals(dirty, subject.hasLocalEdits()); Files.writeString(window.document, "# External");
                if (dirty) {
                    window.await(() -> window.host.externalNotices.visible(subject.documentId()));
                    assertEquals(List.of("draft # Original"), subject.source().text().lines());
                } else {
                    window.await(() -> subject.source().text().lines().equals(List.of("# External")));
                    assertFalse(window.host.externalNotices.visible(subject.documentId()));
                }
                assertTrue(window.tabs.isEmpty());
            }
        }
    }

    private static void pump(EditorWindowFixture window, long millis) throws Exception {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < end) { window.frame(); Thread.sleep(10); }
    }
}
