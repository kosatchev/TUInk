package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class MarkdownPreviewEligibilityIntegrationTest {
    @TempDir Path directory;
    private enum EntryPoint { KEYBOARD, BOUND_COMMAND, PROGRAMMATIC, MOUSE_MENU }
    private static final List<String> MARKDOWN_NAMES = List.of("notes.md", "notes.markdown", "notes.mdown", "notes.mkd", "notes.mkdn",
        "NOTES.MD", "NOTES.MARKDOWN", "NOTES.MDOWN", "NOTES.MKD", "NOTES.MKDN");
    private static final List<String> OTHER_NAMES = List.of("notes.txt", "Main.java", "main.py", "README", "notes.md.txt");

    @ParameterizedTest
    @MethodSource("namedAdmissions")
    void everyEntryPointUsesTheSameFileEligibility(String name, boolean markdown, EntryPoint entry) throws Exception {
        try (var window = window(entry + "-" + name, name)) {
            var source = window.selected();
            boolean allowed = window.controller.activeSurface().allows(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            var menu = TopMenuView.build(window.ui, List.of(), -1, "Cyan", new CommandRouter(window.keymap),
                dev.tuiframe.widget.MenuBar.State.open(TopMenuController.VIEW_INDEX), window.controller.activeSurface());
            var item = menu.menus().get(TopMenuController.VIEW_INDEX).entries().stream()
                .filter(value -> value instanceof dev.tuiframe.widget.MenuBar.Item<?> candidate
                    && ((CommandInvocation) candidate.value()).id().equals(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW.id()))
                .map(value -> (dev.tuiframe.widget.MenuBar.Item<?>) value).findFirst().orElseThrow();
            openPreview(window, entry);
            assertAll(
                () -> assertEquals(markdown, allowed, "the dispatcher capability follows the bound file"),
                () -> assertEquals(markdown, item.enabled(), "the menu uses that same capability"),
                () -> assertEquals(markdown, window.selected() instanceof PreviewTab),
                () -> assertEquals(markdown ? 2 : 1, window.context.presentations().size()));
            if (markdown) {
                window.await(() -> window.preview().ready());
                window.event(ctrlP());
                assertSame(source, window.selected());
            } else {
                assertSame(source, window.selected());
                assertFalse(source.uiState().preview.active);
                assertNull(source.uiState().preview.pending);
                assertNull(source.uiState().preview.result);
            }
        }
    }

    static Stream<Arguments> namedAdmissions() {
        return Stream.concat(MARKDOWN_NAMES.stream().map(name -> new Object[] {name, true}),
            OTHER_NAMES.stream().map(name -> new Object[] {name, false}))
            .flatMap(values -> Stream.of(EntryPoint.values()).map(entry -> Arguments.of(values[0], values[1], entry)));
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void unnamedSourceDoesNotBecomeMarkdownFromItsContent(EntryPoint entry) throws Exception {
        try (var window = window("unnamed-" + entry, "initial.txt")) {
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("# Markdown-looking draft **bold**"));
            var draft = window.selected();
            int count = window.context.presentations().size();
            assertNull(window.active().state().getCurrentResource());
            openPreview(window, entry);
            assertAll(
                () -> assertSame(draft, window.selected()),
                () -> assertEquals(count, window.context.presentations().size()),
                () -> assertFalse(window.controller.activeSurface().allows(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW)));
        }
    }

    @Test void sourceSaveAsEnablesPreviewOnlyAfterTheMarkdownReceiptAndDisablesItAfterRenamingBack() throws Exception {
        try (var window = window("source-rename", "initial.txt")) {
            Path markdown = window.document.resolveSibling("renamed.md");
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            window.command(TuinkCommand.EDIT_SELECT_ALL); window.event(new TextInput(markdown.toString()));
            assertFalse(window.controller.activeSurface().allows(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW),
                "a filename draft is not the bound resource");
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> markdown.equals(JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()))
                && !window.documentIo.busy() && window.selected().uiState().modalState.isNone());
            assertTrue(window.controller.activeSurface().allows(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW));
            window.event(ctrlP()); window.await(() -> window.preview().ready());
            var preview = window.selected();
            window.event(ctrlP());
            Path text = markdown.resolveSibling("renamed.txt");
            saveAs(window, text);
            var retained = window.context.presentation(preview.presentationId());
            assertNotNull(retained, "renaming must retain the existing presentation");
            assertSame(preview.uiState(), retained.uiState());
            assertFalse(retained.uiState().preview.closed);
            assertFalse(window.controller.activeSurface().allows(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW));
            window.event(ctrlP());
            assertInstanceOf(TerminalEditorApp.EditorTab.class, window.selected(), "a non-Markdown Source cannot reactivate even an existing Preview");
            window.command(TuinkCommand.TAB_NEXT);
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertSame(preview.uiState(), window.selected().uiState());
            assertTrue(window.controller.activeSurface().allows(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW));
            window.event(ctrlP());
            assertInstanceOf(TerminalEditorApp.EditorTab.class, window.selected());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void savingAnExistingPreviewWithAnotherExtensionPreservesItAndAlwaysAllowsReturningToSource(ActiveSurface.Kind kind) throws Exception {
        try (var window = window("preview-rename-" + kind, "initial.md")) {
            window.event(ctrlP()); window.await(() -> window.preview().ready());
            if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) {
                window.event(ctrlP()); window.command(TuinkCommand.TAB_CLOSE);
                window.await(() -> window.selected() instanceof PreviewTab p && p.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
            }
            var preview = assertInstanceOf(PreviewTab.class, window.selected());
            var sourceText = preview.uiState().preview.sourceForFreeze().text();
            Path target = window.document.resolveSibling("saved.txt");
            saveAs(window, target);
            assertEquals(preview.presentationId(), window.selected().presentationId());
            assertSame(sourceText, window.preview().sourceForFreeze().text());
            assertTrue(window.controller.activeSurface().allows(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW));
            window.event(ctrlP());
            window.await(() -> window.selected() instanceof TerminalEditorApp.EditorTab
                && target.equals(JvmDocumentStorage.pathOf(window.active().state().getCurrentResource())) && !window.documentIo.busy());
            assertNotNull(window.context.presentation(preview.presentationId()));
            assertFalse(window.controller.activeSurface().allows(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW));
            var source = window.selected(); window.event(ctrlP());
            assertSame(source, window.selected());
        }
    }

    @Test void automaticOpeningKeepsBothIndependentPreferencesAndUsesOnlyFileClassification() {
        for (boolean both : List.of(false, true)) for (boolean inPreview : List.of(false, true)) {
            for (String name : MARKDOWN_NAMES) {
                var policy = MarkdownOpenPolicy.forNewFile(Path.of(name), false, both, inPreview);
                assertEquals(both || inPreview, policy.createPreview(), name);
                assertEquals(both || !inPreview, policy.keepSource(), name);
                assertEquals(inPreview, policy.focusPreview(), name);
                assertEquals(MarkdownOpenPolicy.SOURCE, MarkdownOpenPolicy.forNewFile(Path.of(name), true, both, inPreview));
            }
            for (String name : OTHER_NAMES) assertEquals(MarkdownOpenPolicy.SOURCE,
                MarkdownOpenPolicy.forNewFile(Path.of(name), false, both, inPreview), name);
            assertEquals(MarkdownOpenPolicy.SOURCE, MarkdownOpenPolicy.forNewFile(null, false, both, inPreview));
        }
    }

    private EditorWindowFixture window(String directoryName, String name) throws Exception {
        return new EditorWindowFixture(directory.resolve(directoryName), name,
            List.of("# Heading", "", "Markdown-looking **content** and $x^2$"),
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])));
    }

    private static void saveAs(EditorWindowFixture window, Path target) throws Exception {
        window.command(TuinkCommand.DOCUMENT_SAVE_AS);
        window.command(TuinkCommand.EDIT_SELECT_ALL); window.event(new TextInput(target.toString()));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        window.await(() -> !window.documentIo.busy() && window.selected().uiState().modalState.isNone()
            && (window.selected() instanceof TerminalEditorApp.EditorTab
                ? target.equals(JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()))
                : target.equals(JvmDocumentStorage.pathOf(window.preview().sourceForFreeze().binding().resource()))));
    }

    private static void openPreview(EditorWindowFixture window, EntryPoint entry) {
        switch (entry) {
            case KEYBOARD -> window.event(ctrlP());
            case BOUND_COMMAND -> window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            case PROGRAMMATIC -> {
                window.controller.invokeCommand(CommandInvocation.of(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW.id()),
                    UiRecipient.presentation(window.selected(), UiRecipient.Flow.LOCAL));
                window.frame();
            }
            case MOUSE_MENU -> {
                window.command(TuinkCommand.MENU_VIEW);
                Point at = menuItem(window, "MD Preview");
                window.pointer(PointerEvent.Kind.PRESS, at.y(), at.x());
                window.pointer(PointerEvent.Kind.RELEASE, at.y(), at.x());
            }
        }
    }

    private static Point menuItem(EditorWindowFixture window, String label) {
        for (int row = 0; row < window.height; row++) {
            int column = window.screenRow(row).indexOf(label);
            if (column >= 0) return new Point(column + 2, row);
        }
        throw new AssertionError("Missing menu item: " + label);
    }

    private static KeyStroke ctrlP() { return KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL); }
}
