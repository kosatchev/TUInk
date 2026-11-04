package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.style.UiStyles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PresentationSyntaxPolicyTest {
    @ParameterizedTest
    @EnumSource(UiStyles.Theme.class)
    void sourceMatrixKeepsConfiguredCodeLanguagesSeparateFromTheEffectiveMaster(UiStyles.Theme theme) {
        for (boolean master : new boolean[] {false, true}) {
            for (boolean codeLanguages : new boolean[] {false, true}) {
                var ui = UiSessionState.from(AppSettings.defaults());
                ui.syntaxHighlightingSetting = master;
                ui.markdownCodeHighlightingSetting = codeLanguages;
                var before = ui.settingsSnapshot(theme);
                var source = PresentationSyntaxPolicy.source(ui, theme);
                if (theme == UiStyles.Theme.LIGHT) {
                    assertFalse(source.available());
                    assertFalse(source.enabled());
                } else {
                    assertTrue(source.available());
                    assertEquals(master, source.enabled());
                }
                assertEquals(codeLanguages, source.codeLanguages(),
                    "the provider retains its language option while the Source master is disabled");
                assertEquals(PresentationSyntaxPolicy.source(master, codeLanguages, theme), source);
                assertEquals(before, ui.settingsSnapshot(theme), "resolving a frame cannot rewrite persisted preferences");
            }
        }
    }

    @Test
    void previewColorFollowsTheTerminalCapability() {
        assertTrue(PresentationSyntaxPolicy.preview(true).color());
        assertFalse(PresentationSyntaxPolicy.preview(false).color());
    }
}
