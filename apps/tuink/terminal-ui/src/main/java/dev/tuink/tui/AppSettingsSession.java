package dev.tuink.tui;

import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.style.UiStyles;
import java.util.Objects;

/** Keeps temporary launch overrides separate from explicitly changed preferences. */
final class AppSettingsSession {
    private final AppSettings saved;
    private final AppSettings initial;
    private boolean readingModeChanged;
    private boolean mouseModeChanged;
    private boolean themeChanged;

    AppSettingsSession(AppSettings saved, TuiCliOptions cli) {
        this.saved = Objects.requireNonNull(saved, "saved");
        Objects.requireNonNull(cli, "cli");
        initial = withOverrides(saved,
            cli.readModeOverride() == null ? saved.readingMode() : cli.readModeOverride(),
            cli.mouseEnabledOverride() == null ? saved.mouseMode() : cli.mouseEnabledOverride(),
            cli.themeOverride() == null ? saved.theme() : cli.themeOverride());
    }

    AppSettings initialSettings() {
        return initial;
    }

    void readingModeChanged() {
        readingModeChanged = true;
    }

    void mouseModeChanged() {
        mouseModeChanged = true;
    }

    void themeChanged() {
        themeChanged = true;
    }

    AppSettings preferences(AppSettings runtime) {
        return withOverrides(runtime,
            readingModeChanged ? runtime.readingMode() : saved.readingMode(),
            mouseModeChanged ? runtime.mouseMode() : saved.mouseMode(),
            themeChanged ? runtime.theme() : saved.theme());
    }

    private static AppSettings withOverrides(AppSettings base, boolean readingMode,
                                             boolean mouseMode, UiStyles.Theme theme) {
        return new AppSettings(base.showTabBar(), base.wrapLines(), base.wrapWords(),
            readingMode, base.confirmOnClose(), mouseMode, base.highlightActiveGutter(),
            base.smartHomeEnd(), base.autoIndent(), base.autoPairs(), base.autoList(),
            base.visibleWhitespace(), base.syntaxHighlighting(), base.rainbowBrackets(),
            base.autoSelectionHighlight(), base.gutterDiffMode(), base.tabWidth(),
            base.tabInsertMode(), theme);
    }
}
