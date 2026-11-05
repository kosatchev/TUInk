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
                for (boolean rainbow : new boolean[] {false, true}) {
                    var ui = UiSessionState.from(AppSettings.defaults());
                    ui.syntaxHighlightingSetting = master;
                    ui.markdownCodeHighlightingSetting = codeLanguages;
                    ui.rainbowBracketsSetting = rainbow;
                    var before = ui.settingsSnapshot(theme);
                    var source = PresentationSyntaxPolicy.source(ui, theme);
                    assertTrue(source.available());
                    assertEquals(master, source.enabled());
                    assertEquals(codeLanguages, source.codeLanguages(),
                        "the provider retains its language option while the Source master is disabled");
                    assertTrue(source.bracketMatching());
                    assertEquals(rainbow, source.rainbowBrackets());
                    assertEquals(PresentationSyntaxPolicy.source(master, codeLanguages, rainbow, theme), source);
                    assertEquals(before, ui.settingsSnapshot(theme), "resolving a frame cannot rewrite persisted preferences");
                }
            }
        }
    }

    @Test
    void previewUsesTheTerminalCapabilityWhileTheThemeSuppliesItsPalette() {
        for (var theme : UiStyles.Theme.values()) for (boolean terminalColors : new boolean[] {false, true}) {
            var preview = PresentationSyntaxPolicy.preview(terminalColors, theme);
            assertEquals(terminalColors, preview.color());
        }
    }
}
