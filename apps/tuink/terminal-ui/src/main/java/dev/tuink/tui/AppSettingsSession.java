package dev.tuink.tui;

import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.style.UiStyles;
import java.util.Objects;

/** Keeps temporary launch overrides separate from explicitly changed preferences. */
final class AppSettingsSession {
    private final AppSettings saved;
    private final AppSettings initial;
    private final String saveUnavailableReason;
    private AppSettings observedTemporarySettings;
    private boolean readingModeChanged;
    private boolean mouseModeChanged;
    private boolean themeChanged;

    AppSettingsSession(AppSettings saved, TuiCliOptions cli) {
        this(saved, cli, null);
    }

    AppSettingsSession(AppSettings saved, TuiCliOptions cli, String saveUnavailableReason) {
        this.saved = Objects.requireNonNull(saved, "saved");
        this.saveUnavailableReason = saveUnavailableReason;
        Objects.requireNonNull(cli, "cli");
        initial = withOverrides(saved,
            cli.readModeOverride() == null ? saved.readingMode() : cli.readModeOverride(),
            cli.mouseEnabledOverride() == null ? saved.mouseMode() : cli.mouseEnabledOverride(),
            cli.themeOverride() == null ? saved.theme() : cli.themeOverride());
        observedTemporarySettings = canSavePreferences() ? null : initial;
    }

    AppSettings initialSettings() {
        return initial;
    }

    boolean canSavePreferences() { return saveUnavailableReason == null; }

    String saveUnavailableWarning() {
        if (canSavePreferences()) return null;
        return "Settings changes apply only to this session. " + saveUnavailableReason
            + ". Repair the settings file and restart.";
    }

    /** Only blocked sessions observe runtime changes; launch overrides are already in the baseline. */
    boolean temporaryPreferencesChanged(AppSettings runtime) {
        if (canSavePreferences()) return false;
        boolean changed = !Objects.requireNonNull(runtime, "runtime").equals(observedTemporarySettings);
        observedTemporarySettings = runtime;
        return changed;
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
            base.autoSelectionHighlight(), base.markdownOpenBothTabs(), base.markdownOpenInPreview(),
            base.markdownCodeHighlighting(),
            base.gutterDiffMode(), base.tabWidth(),
            base.tabInsertMode(), theme);
    }
}
