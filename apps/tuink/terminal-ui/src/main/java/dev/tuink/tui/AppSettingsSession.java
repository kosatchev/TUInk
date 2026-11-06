package dev.tuink.tui;

import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.flow.AppSettingsStore;
import dev.tuink.tui.style.UiStyles;
import java.io.IOException;
import java.util.Objects;

/** Keeps temporary launch overrides separate from explicitly changed preferences. */
final class AppSettingsSession {
    private AppSettings saved;
    private AppSettings initial;
    private String saveUnavailableReason;
    private final TuiCliOptions cli;
    private AppSettingsStore.LoadedSettings snapshot;
    private AppSettingsStore.UnsupportedSettingsException unsupported;
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
        this.cli = Objects.requireNonNull(cli, "cli");
        initial = withOverrides(saved,
            cli.readModeOverride() == null ? saved.readingMode() : cli.readModeOverride(),
            cli.mouseEnabledOverride() == null ? saved.mouseMode() : cli.mouseEnabledOverride(),
            cli.themeOverride() == null ? saved.theme() : cli.themeOverride());
        observedTemporarySettings = canSavePreferences() ? null : initial;
    }

    static AppSettingsSession load(TuiCliOptions cli) throws IOException {
        try {
            var loaded = AppSettingsStore.loadSnapshot();
            var session = new AppSettingsSession(loaded.settings(), cli);
            session.snapshot = loaded;
            return session;
        } catch (AppSettingsStore.UnsupportedSettingsException unsupported) {
            return forUnsupportedSettings(unsupported, cli);
        }
    }

    static AppSettingsSession forUnsupportedSettings(AppSettingsStore.UnsupportedSettingsException failure,
                                                    TuiCliOptions cli) {
        var session = new AppSettingsSession(AppSettings.defaults(), cli, failure.getMessage());
        session.unsupported = Objects.requireNonNull(failure);
        return session;
    }

    boolean hasUnsupportedSettings() { return unsupported != null; }

    AppSettings repairUnsupportedSettings() throws IOException {
        return accept(AppSettingsStore.repairIfUnchanged(Objects.requireNonNull(unsupported)));
    }

    AppSettings deleteUnsupportedSettings() throws IOException {
        return accept(AppSettingsStore.deleteIfUnchanged(Objects.requireNonNull(unsupported)));
    }

    void postponeUnsupportedSettings() { unsupported = null; }

    private AppSettings accept(AppSettingsStore.LoadedSettings loaded) {
        snapshot = loaded;
        saved = loaded.settings();
        initial = withOverrides(saved,
            cli.readModeOverride() == null ? saved.readingMode() : cli.readModeOverride(),
            cli.mouseEnabledOverride() == null ? saved.mouseMode() : cli.mouseEnabledOverride(),
            cli.themeOverride() == null ? saved.theme() : cli.themeOverride());
        saveUnavailableReason = null;
        unsupported = null;
        observedTemporarySettings = null;
        readingModeChanged = mouseModeChanged = themeChanged = false;
        return initial;
    }

    void savePreferences(AppSettings runtime) throws IOException {
        AppSettings preferences = preferences(runtime);
        if (canSavePreferences() && snapshot != null && !preferences.equals(saved)) {
            AppSettingsStore.saveIfUnchanged(snapshot, preferences);
        }
    }

    AppSettings initialSettings() {
        return initial;
    }

    boolean canSavePreferences() { return saveUnavailableReason == null; }

    String saveUnavailableWarning() {
        if (canSavePreferences()) return null;
        return "Settings changes apply only to this session. " + saveUnavailableReason;
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
            base.tabInsertMode(), theme, base.rightClickClipboard(), base.markdownVisualCopy());
    }
}
