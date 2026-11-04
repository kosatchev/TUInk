package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.ui.SaveAsBarView;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewPresentationViewTest {
    @TempDir Path directory;

    @Test void closedSnapshotPreparesAndRendersWithoutAnyOpenSourceOrResourceAccess() {
        try (var fixture = new Fixture(false, directory.resolve("missing.md"))) {
            assertTrue(fixture.context.tabs().isEmpty());
            assertNull(fixture.context.active());
            var frame = fixture.prepare(UiModalState.none(), 24, 80);
            assertEquals(1, frame.contentTopRow());
            assertNull(frame.layers().tabBar());
            assertTrue(frame.surface().topBar().text().contains("READ"));
            assertTrue(frame.surface().bottomBar().contains("snapshot"));
            assertTrue(frame.surface().bottomBar().contains("Preview (snapshot)"));
            assertTrue(frame.surface().bottomBar().contains("Ctrl+P: Source"));
            assertFalse(fixture.ui.readingModeSetting, "preview readonly state is not a persisted reading preference");
            assertTrue(fixture.context.tabs().isEmpty());
            var presented = fixture.host.new RenderFrameController(true,
                new dev.tuiframe.jline.presentation.ScreenPresenter(new PrintWriter(new StringWriter()), TerminalCapabilities.DEFAULT))
                .renderPreviewFrameIfNeeded(frame);
            assertEquals(24, presented.cells().rows());
            assertSame(fixture.preview, fixture.context.activePresentation());
        }
    }

    @Test void livePairUsesPresentationOrderForTheTabBarAndRetainsSourceOwnership() {
        try (var fixture = new Fixture(true, directory.resolve("README.md"))) {
            var frame = fixture.prepare(UiModalState.none(), 24, 80);
            assertEquals(2, frame.contentTopRow());
            assertNotNull(frame.layers().tabBar());
            assertEquals(2, fixture.host.toPresentationTabItems(fixture.context, null).size());
            assertEquals("Source", fixture.host.toPresentationTabItems(fixture.context, null).getFirst().qualifier());
            assertEquals("Preview", fixture.host.toPresentationTabItems(fixture.context, null).getLast().qualifier());
            assertSame(fixture.editor, fixture.context.active());
            assertTrue(frame.surface().bottomBar().contains("live"));
            assertEquals(1, fixture.context.activePresentationIndex());
            assertTrue(fixture.editor.documentSession().document().ownsView(fixture.editor.documentSession().viewId()));
        }
    }

    @Test void frozenSaveAsAndNarrowOverwriteReuseSharedWidgetsAndSafeFocus() {
        try (var fixture = new Fixture(false, directory.resolve("missing.md"))) {
            fixture.preview.uiState().saveAsInput.set("new.md");
            var saveAs = fixture.prepare(UiModalState.saveAs(), 24, 32);
            assertNotNull(saveAs.layers().saveAs());
            assertTrue(saveAs.layers().saveAsMode());
            assertEquals(SaveAsBarView.Target.FIELD, fixture.preview.uiState().saveAsFocusManager.focused().orElseThrow());
            assertEquals("new.md", fixture.preview.uiState().saveAsInput.text());
            var confirm = fixture.prepare(UiModalState.saveAs().confirmOverwrite(directory.resolve("complete-name.md")), 24, 18);
            assertNotNull(confirm.layers().confirm());
            assertTrue(confirm.layers().confirm().decisionsAvailable());
            assertEquals(ConfirmChoice.CANCEL, fixture.preview.uiState().confirmFocusManager.focused().orElseThrow());
            assertEquals(24 - 1 - confirm.surface().inlinePrompt().height(), confirm.preview().bounds().bottomExclusive());
            assertTrue(confirm.surface().inlinePrompt().text().lines().map(String::stripTrailing)
                .collect(java.util.stream.Collectors.joining()).contains("complete-name.md"));
            assertTrue(fixture.context.tabs().isEmpty());
        }
    }

    @Test void globalQuestionsKeepTheirDefaultAndRetainedPreviewQueryDoesNotPaintAsFocusedBehindThem() {
        try (var fixture = new Fixture(false, directory.resolve("snapshot.md"))) {
            fixture.preview.uiState().preview.searchVisible = true;
            fixture.preview.uiState().preview.searchField.set("needle");
            fixture.preview.uiState().preview.setSearchQuery("needle");
            var frame = fixture.prepare(UiModalState.recoveryConfirm(), 24, 80);
            assertNotNull(frame.layers().confirm());
            assertEquals(ConfirmChoice.CANCEL, fixture.preview.uiState().confirmFocusManager.focused().orElseThrow());
            assertNull(frame.preview().searchInput());
            assertTrue(frame.preview().searchVisible(), "the retained Find row keeps the same content geometry");
            assertNull(MarkdownPreviewPainter.cursor(frame.preview()), "the modal hides the query caret");
            assertTrue(fixture.preview.uiState().preview.searchVisible, "retained query resumes when the modal closes");
            assertEquals("needle", fixture.preview.uiState().preview.searchField.text());
            var help = fixture.prepare(UiModalState.helpHints(), 24, 80);
            assertNotNull(help.layers().help());
            assertNull(help.layers().confirm());
            assertFalse(help.layers().contentFocused());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TerminalEditorApp host = new TerminalEditorApp();
        final TerminalEditorApp.EditorTab editor;
        final TerminalEditorApp.SessionContext context;
        final PreviewTab preview;
        final FrozenDocumentSource source;
        final UiSessionState ui = new UiSessionState();
        final TopMenuController menu = new TopMenuController();
        final CommandRouter router = new CommandRouter();
        final MarkdownPreviewService service = new MarkdownPreviewService(() -> {});
        final TerminalEditorApp.NotificationState notice = host.new NotificationState();

        Fixture(boolean live, Path resource) {
            host.styles = UiStyles.forTheme(UiStyles.Theme.CYAN);
            editor = host.newEditorTab();
            source = editor.documentSession().document().freezeSource().withBinding(
                new ResourceBinding(JvmDocumentStorage.resource(resource), SaveDisposition.CREATE_NEW, null));
            context = new TerminalEditorApp.SessionContext(List.of(editor), 0);
            var initial = new PreviewTab(editor);
            context.addPreview(initial, editor.documentSession().viewId());
            if (live) preview = initial;
            else {
                preview = initial.freeze(source);
                context.updatePreview(preview);
                context.remove(editor.documentSession().viewId());
            }
            context.activatePresentation(preview.presentationId());
            var uiState = preview.uiState();
            host.bindTabUi(() -> uiState);
            ui.showTabBarSetting = true;
            ui.gutterDiffModeSetting = GutterDiffMode.MYERS;
            ui.tabInsertModeSetting = TabInsertMode.AUTO;
            ui.tabWidthSetting = 4;
        }

        PreviewFrameComposition prepare(UiModalState modal, int rows, int cols) {
            return PreviewPresentationView.prepare(new PreviewPresentationView.Input(
                new WindowChrome.Input(host, context, ui, TestSurfaces.source(ui).withKind(ActiveSurface.Kind.LIVE_PREVIEW), preview.uiState(), preview.uiState(), modal, notice,
                    1, rows, cols, menu, router, new WindowChrome.TabStrip()), preview, source, service, false));
        }

        @Override public void close() {
            preview.uiState().close();
            editor.uiState().close();
            service.close();
        }
    }
}
