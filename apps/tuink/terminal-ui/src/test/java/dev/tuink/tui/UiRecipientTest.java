package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.tui.style.UiStyles;
import java.util.List;
import org.junit.jupiter.api.Test;

class UiRecipientTest {
    @Test void tabNavigationDoesNotChangeARecipientAndRetirementDoesNotRetargetIt() {
        var host = host();
        var original = host.newEditorTab();
        var other = host.newEditorTab();
        var context = new TerminalEditorApp.SessionContext(List.of(original), 0);
        var recipient = UiRecipient.presentation(original, UiRecipient.Flow.LOCAL);
        context.addNewTabAndActivate(other);
        assertTrue(recipient.current(context, null));
        context.remove(original.documentSession().viewId());
        assertFalse(original.documentSession().document().ownsView(recipient.viewId()));
        assertFalse(recipient.current(context, null));
        assertSame(other, context.activePresentation());
        assertNotSame(other.uiState(), recipient.state());
        original.uiState().close(); other.uiState().close();
    }

    @Test void windowScopeIsIndependentOfPresentationSelectionAndRetiresWithItsOwnState() {
        var host = host();
        var editor = host.newEditorTab();
        var context = new TerminalEditorApp.SessionContext(List.of(editor), 0);
        var windowUi = new TabUiState();
        var recipient = UiRecipient.window(windowUi, null);
        assertTrue(recipient.current(context, windowUi));
        assertFalse(recipient.current(context, new TabUiState()));
        windowUi.close();
        assertFalse(recipient.current(context, windowUi));
        editor.uiState().close();
    }

    private static TerminalEditorApp host() {
        var host = new TerminalEditorApp();
        host.styles = UiStyles.forTheme(UiStyles.Theme.CYAN);
        return host;
    }

}
