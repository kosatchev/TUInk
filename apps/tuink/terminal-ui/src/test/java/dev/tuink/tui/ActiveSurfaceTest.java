package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.language.LanguageId;
import dev.tuink.tui.command.TuinkCommand;
import org.junit.jupiter.api.Test;

class ActiveSurfaceTest {
    @Test void anUnboundCommandHasNoApplicationIntentOrSideEffects() {
        var preferences = new UiSessionState();
        var menu = new TopMenuController();
        var result = ApplicationCommandHandler.decide(null, preferences, TestSurfaces.source(preferences), menu,
            ignored -> fail(), () -> fail());
        assertFalse(result.handled());
        assertEquals(ApplicationCommandHandler.Action.NONE, result.action());
        assertEquals(InputActionDispatcher.Intent.NONE, result.ui().intent());
        assertFalse(menu.isOpen());
    }

    @Test void previewReadonlyStateIsIntrinsicAndIndependentOfTheReadingPreference() {
        var preferences = new UiSessionState();
        for (var kind : new ActiveSurface.Kind[] {ActiveSurface.Kind.LIVE_PREVIEW, ActiveSurface.Kind.FROZEN_PREVIEW}) {
            var preview = TestSurfaces.source(preferences).withKind(kind);
            assertTrue(preview.reading());
            assertFalse(preferences.readingModeSetting);
        }
    }

    @Test void readonlyDocumentDoesNotMakeItsFindOrSaveAsFieldReadonly() {
        for (var kind : ActiveSurface.Kind.values()) {
            var content = new ActiveSurface(PresentationId.create(), kind, ActiveSurface.Focus.CONTENT,
                ActiveSurface.ModalScope.NONE, true, false, false, true, true, true, LanguageId.MARKDOWN);
            assertFalse(content.allows(TuinkCommand.EDIT_PASTE));
            assertTrue(content.allows(TuinkCommand.EDIT_COPY));
            var field = content.withFocus(ActiveSurface.Focus.EDITABLE_TEXT);
            assertTrue(field.allows(TuinkCommand.EDIT_PASTE));
            assertTrue(field.allows(TuinkCommand.EDIT_CUT));
            assertFalse(field.allows(TuinkCommand.EDIT_UNDO));
            assertFalse(field.allows(TuinkCommand.EDIT_TOGGLE_LINE_COMMENT));
            assertFalse(content.withFocus(ActiveSurface.Focus.CONTROL).allows(TuinkCommand.EDIT_COPY));
        }
    }

    @Test void replacementTargetsTheDocumentIndependentlyOfTheFocusedSearchControl() {
        var source = TestSurfaces.source(new UiSessionState());
        for (var focus : ActiveSurface.Focus.values()) {
            var surface = source.withFocus(focus);
            assertTrue(surface.allows(TuinkCommand.SEARCH_REPLACE));
            assertTrue(surface.allows(TuinkCommand.SEARCH_REPLACE_ALL));
            assertFalse(surface.withKind(ActiveSurface.Kind.LIVE_PREVIEW).allows(TuinkCommand.SEARCH_REPLACE_ALL));
            var locked = new ActiveSurface(surface.presentationId(), surface.kind(), focus, surface.modalScope(),
                surface.reading(), true, false, true, true, true, surface.language());
            assertFalse(locked.allows(TuinkCommand.SEARCH_REPLACE_ALL));
        }
    }

    @Test void globalModalAndIoLocksHaveSeparateScopes() {
        var source = new ActiveSurface(PresentationId.create(), ActiveSurface.Kind.SOURCE,
            ActiveSurface.Focus.CONTENT, ActiveSurface.ModalScope.WINDOW, false, true, true, false, true, false, LanguageId.MARKDOWN);
        assertFalse(source.allows(TuinkCommand.DOCUMENT_OPEN));
        assertFalse(source.allows(TuinkCommand.TAB_ACTIVATE));
        assertFalse(source.allows(TuinkCommand.TAB_NEXT));
        assertFalse(source.allows(TuinkCommand.TAB_CLOSE));
        assertFalse(source.allows(TuinkCommand.EDIT_PASTE));
        assertTrue(source.allows(TuinkCommand.EDIT_COPY));
        assertTrue(source.allows(TuinkCommand.DOCUMENT_SAVE_AS), "the subject workflow owns Save As busy policy");
        assertTrue(source.allows(TuinkCommand.SETTINGS_TOGGLE_MOUSE), "unavailable mouse keeps its warning action");
    }

    @Test void markdownAdmissionIsSharedButReturningFromAnyExistingPreviewDoesNotDependOnItsFileExtension() {
        for (var language : LanguageId.values()) {
            for (var kind : ActiveSurface.Kind.values()) {
                for (var modal : ActiveSurface.ModalScope.values()) {
                    var surface = new ActiveSurface(PresentationId.create(), kind, ActiveSurface.Focus.CONTENT,
                        modal, false, false, false, true, true, true, language);
                    boolean allowed = modal != ActiveSurface.ModalScope.WINDOW
                        && (kind != ActiveSurface.Kind.SOURCE || language == LanguageId.MARKDOWN);
                    assertEquals(allowed, surface.allows(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW),
                        language + " / " + kind + " / " + modal);
                    assertEquals(surface.language(), surface.withRuntime(false, false).language());
                    assertEquals(surface.language(), surface.withFocus(ActiveSurface.Focus.CONTROL).language());
                }
            }
        }
    }

    @Test void applicationIntentsAreSharedAndHelpDoesNotMutatePreferences() {
        for (var kind : ActiveSurface.Kind.values()) {
            var preferences = new UiSessionState();
            var surface = TestSurfaces.source(preferences).withKind(kind);
            for (var command : new TuinkCommand[] { TuinkCommand.DOCUMENT_OPEN,
                TuinkCommand.DOCUMENT_RECOVER_DRAFTS, TuinkCommand.VIEW_CYCLE_THEME }) {
                var result = ApplicationCommandHandler.decide(command, preferences, surface,
                    new TopMenuController(), ignored -> fail(), () -> fail());
                assertTrue(result.handled());
                assertNotEquals(ApplicationCommandHandler.Action.NONE, result.action());
            }
            var help = ApplicationCommandHandler.decide(TuinkCommand.HELP_AUTHOR, preferences, surface,
                new TopMenuController(), ignored -> fail(), () -> fail());
            assertEquals(InputActionDispatcher.Intent.HELP_AUTHOR, help.ui().intent());
        }
    }

    @Test void externalActionsRemainAvailableAfterKeepWhileFrozenHistoryStaysInItsContentScope() {
        var source = TestSurfaces.source(new UiSessionState());
        assertFalse(source.allows(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL));
        var changed = source.withExternal(true, true, false, false);
        assertTrue(changed.allows(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL));
        var kept = changed.withExternal(true, false, false, false);
        assertTrue(kept.allows(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL));
        assertTrue(kept.withRuntime(false, false).withFocus(ActiveSurface.Focus.CONTROL)
            .allows(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL));
        var frozen = changed.withKind(ActiveSurface.Kind.FROZEN_PREVIEW).withExternal(true, true, true, false);
        assertTrue(frozen.allows(TuinkCommand.EDIT_UNDO));
        assertFalse(frozen.allows(TuinkCommand.EDIT_REDO));
        assertFalse(frozen.withFocus(ActiveSurface.Focus.EDITABLE_TEXT).allows(TuinkCommand.EDIT_UNDO));
        assertFalse(frozen.withFocus(ActiveSurface.Focus.CONTROL).allows(TuinkCommand.EDIT_UNDO));
        assertFalse(frozen.withKind(ActiveSurface.Kind.LIVE_PREVIEW).allows(TuinkCommand.EDIT_UNDO));
        var locked = new ActiveSurface(frozen.presentationId(), frozen.kind(), frozen.focus(),
            ActiveSurface.ModalScope.WINDOW, true, true, true, true, true, false, frozen.language())
            .withExternal(true, true, true, true);
        assertFalse(locked.allows(TuinkCommand.DOCUMENT_UPDATE_EXTERNAL));
        assertFalse(locked.allows(TuinkCommand.EDIT_UNDO));
    }
}
