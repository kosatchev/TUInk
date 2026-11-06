package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.style.CellColor;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.flow.AppSettingsStore;
import dev.tuink.tui.flow.AppSettingsStoreFixtures;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.style.UiStyles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UnsupportedSettingsIntegrationTest {
    private static final String QUESTION = "Unsupported settings. Replace invalid values with defaults?";
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"enter", "n", "N", "escape", "later-button"})
    void laterKeepsTheExactFileAndMakesPreferenceChangesTemporary(String input) throws Exception {
        Path profile = invalidProfile();
        byte[] original = Files.readAllBytes(profile);
        var session = unsupported(profile);
        try (var window = window(session)) {
            assertTrue(window.promptText().contains(QUESTION));
            assertEquals(ConfirmChoice.CANCEL, window.host.confirmFocusManager().focused().orElseThrow());
            choose(window, input);

            assertFalse(session.hasUnsupportedSettings());
            assertFalse(session.canSavePreferences());
            assertTrue(window.controller.running());
            assertEquals(ActiveSurface.ModalScope.NONE, window.controller.activeSurface().modalScope());
            assertArrayEquals(original, Files.readAllBytes(profile));
            window.command(TuinkCommand.VIEW_TOGGLE_WHITESPACE);
            assertTrue(window.notification.message.contains("only to this session"));
            assertArrayEquals(original, Files.readAllBytes(profile));
            assertEquals("original", Files.readString(window.document));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"y", "fix-button"})
    void fixPreservesValidFieldsAppliesDefaultsAndRefreshesTheGuardForSubsequentSaves(String input) throws Exception {
        Path profile = invalidProfile();
        var session = unsupported(profile);
        try (var window = window(session)) {
            choose(window, input);

            assertFalse(session.hasUnsupportedSettings());
            assertTrue(session.canSavePreferences());
            assertTrue(window.ui.wrapLinesSetting);
            assertFalse(window.ui.mouseModeSetting);
            assertEquals(4, window.ui.tabWidthSetting);
            assertEquals(UiStyles.Theme.CYAN, window.host.theme);
            var repaired = AppSettingsStoreFixtures.loadSnapshot(profile).settings();
            assertTrue(repaired.wrapLines());
            assertFalse(repaired.mouseMode());
            assertEquals(4, repaired.tabWidth());
            assertEquals(UiStyles.Theme.CYAN, repaired.theme());
            window.command(TuinkCommand.VIEW_TOGGLE_WHITESPACE);
            session.savePreferences(window.ui.settingsSnapshot(window.host.theme));
            assertEquals(window.ui.visibleWhitespaceSetting,
                AppSettingsStoreFixtures.loadSnapshot(profile).settings().visibleWhitespace());
            assertEquals("original", Files.readString(window.document));
        }
    }

    @Test
    void fixingUsesCliOverridesOnlyForTheRunningSession() throws Exception {
        Path profile = invalidProfile();
        var session = unsupported(profile, "--read", "--no-mouse", "--theme=monochrome");
        try (var window = window(session)) {
            choose(window, "y");
            assertTrue(window.ui.readingModeSetting);
            assertFalse(window.ui.mouseModeSetting);
            assertEquals(UiStyles.Theme.MONOCHROME, window.host.theme);
            var repaired = AppSettingsStoreFixtures.loadSnapshot(profile).settings();
            assertFalse(repaired.readingMode());
            assertEquals(UiStyles.Theme.CYAN, repaired.theme());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"d", "delete-button"})
    void deleteAppliesDefaultsAndLaterExplicitChangesCanCreateANewProfile(String input) throws Exception {
        Path profile = invalidProfile();
        var session = unsupported(profile);
        try (var window = window(session)) {
            choose(window, input);
            assertFalse(Files.exists(profile));
            assertFalse(session.hasUnsupportedSettings());
            assertTrue(session.canSavePreferences());
            assertEquals(AppSettings.defaults(), window.ui.settingsSnapshot(window.host.theme));
            window.command(TuinkCommand.VIEW_TOGGLE_WHITESPACE);
            session.savePreferences(window.ui.settingsSnapshot(window.host.theme));
            assertTrue(Files.exists(profile));
            assertEquals(window.ui.visibleWhitespaceSetting,
                AppSettingsStoreFixtures.loadSnapshot(profile).settings().visibleWhitespace());
            assertEquals("original", Files.readString(window.document));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"y", "d"})
    void anExternallyChangedProfileKeepsThePromptAndTheExternalBytes(String input) throws Exception {
        Path profile = invalidProfile();
        var session = unsupported(profile);
        try (var window = window(session)) {
            byte[] external = "format=999\nexternal=do-not-replace\n".getBytes(StandardCharsets.UTF_8);
            Files.write(profile, external);
            choose(window, input);

            assertTrue(window.promptText().contains(QUESTION));
            assertTrue(session.hasUnsupportedSettings());
            assertFalse(session.canSavePreferences());
            assertTrue(window.controller.running());
            assertArrayEquals(external, Files.readAllBytes(profile));
            assertNotNull(window.notification.message);
            assertTrue(window.notification.message.contains("Settings file changed"));
            assertEquals(window.host.styles.windows().notifyError(), window.notification.style());
            assertEquals("original", Files.readString(window.document));
        }
    }

    @Test
    void clippedSettingsPromptBlocksFixAndDeleteButStillAllowsLater() throws Exception {
        Path profile = invalidProfile();
        byte[] original = Files.readAllBytes(profile);
        var session = unsupported(profile);
        try (var window = window(session)) {
            window.resize(24, 5);
            for (String input : List.of("y", "d", "D", "enter")) choose(window, input);
            assertTrue(session.hasUnsupportedSettings());
            assertArrayEquals(original, Files.readAllBytes(profile));
            assertTrue(window.controller.running());
            choose(window, "escape");
            assertFalse(session.hasUnsupportedSettings());
            assertArrayEquals(original, Files.readAllBytes(profile));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"y", "d", "n"})
    void recoveryIsOfferedOnlyAfterTheSettingsResponse(String input) throws Exception {
        var session = unsupported(invalidProfile());
        var draft = draft();
        try (var window = new EditorWindowFixture(directory.resolve("queued-window"), List.of("original"),
            session, List.of(draft), null)) {
            assertTrue(window.promptText().contains(QUESTION));
            assertFalse(window.promptText().contains("Unsaved drafts:"));
            choose(window, input);
            assertTrue(window.promptText().contains("Unsaved drafts: 1."));
            assertTrue(window.recoverySnapshots.containsKey(draft.id()));
            assertEquals(1, window.tabs.size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void quitFromSourceOrPreviewPreservesSettingsAndReleasesQueuedRecoveryWithoutOpeningIt(boolean preview) throws Exception {
        Path profile = invalidProfile();
        byte[] original = Files.readAllBytes(profile);
        var session = unsupported(profile);
        var draft = draft();
        var releases = new AtomicInteger();
        var persistence = new TerminalEditorApp.RecoveryPersistence() {
            @Override public void save(String id, RecoverySnapshot snapshot) {}
            @Override public void delete(String id) { fail("Quit must not delete offered recovery copies"); }
            @Override public void release(String id) {
                assertEquals(draft.id(), id);
                releases.incrementAndGet();
            }
        };
        try (var window = new EditorWindowFixture(directory.resolve("quit-window"), "notes.md", List.of("original"),
            session, List.of(draft), persistence)) {
            if (preview) {
                var source = window.active();
                var projection = new PreviewTab(source);
                window.context.addPreview(projection, source.documentSession().viewId());
                window.context.activatePresentation(projection.presentationId());
                window.frame();
                assertInstanceOf(PreviewTab.class, window.selected());
            }
            assertStandardPromptStyle(window);
            if (!preview) window.resize(24, 5);

            window.event(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL));

            assertFalse(window.controller.running());
            assertFalse(window.promptText().contains("Unsaved drafts:"));
            assertEquals(1, releases.get());
            assertArrayEquals(original, Files.readAllBytes(profile));
            assertEquals("original", Files.readString(window.document));
        }
    }

    private EditorWindowFixture window(AppSettingsSession session) throws IOException {
        return new EditorWindowFixture(directory.resolve("window"), List.of("original"), session);
    }

    private Path invalidProfile() throws IOException {
        Path profile = directory.resolve("profile/tuink.properties");
        AppSettingsStoreFixtures.save(profile, AppSettings.defaults());
        String text = Files.readString(profile, StandardCharsets.ISO_8859_1)
            .replace("ui.wrapLines=false", "ui.wrapLines=true")
            .replace("ui.mouseMode=true", "ui.mouseMode=false")
            .replace("ui.theme=cyan", "ui.theme=unsupported")
            .replace("ui.tabWidth=4", "ui.tabWidth=invalid");
        Files.writeString(profile, text, StandardCharsets.ISO_8859_1);
        return profile;
    }

    private static AppSettingsSession unsupported(Path profile, String... cli) {
        var failure = assertThrows(AppSettingsStore.UnsupportedSettingsException.class,
            () -> AppSettingsStoreFixtures.loadSnapshot(profile));
        return AppSettingsSession.forUnsupportedSettings(failure, TuiCliOptions.parse(cli));
    }

    private static RecoveryStore.Entry draft() {
        var tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        tab.engine().insertInput("offered recovery copy");
        return new RecoveryStore.Entry("offered-draft", tab.engine().captureRecoverySnapshot());
    }

    private static void choose(EditorWindowFixture window, String input) {
        switch (input) {
            case "enter" -> window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            case "escape" -> window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            case "fix-button" -> click(window, "[Fix]");
            case "delete-button" -> click(window, "[Delete]");
            case "later-button" -> click(window, "[Later]");
            default -> window.event(new TextInput(input));
        }
    }

    private static void click(EditorWindowFixture window, String button) {
        for (int row = 0; row < window.height; row++) {
            int column = window.screenRow(row).indexOf(button);
            if (column >= 0) {
                window.pointer(PointerEvent.Kind.PRESS, row, column + 1);
                return;
            }
        }
        fail("Missing visible button " + button);
    }

    private static void assertStandardPromptStyle(EditorWindowFixture window) {
        var alert = window.host.styles.windows().promptAlert();
        assertEquals(CellColor.BRIGHT_WHITE, alert.fg());
        assertEquals(CellColor.RED, alert.bg());
        for (int row = 0; row < window.height; row++) {
            int column = window.screenRow(row).indexOf("Unsupported settings.");
            if (column >= 0) {
                assertEquals(alert, window.screenStyle(row, column),
                    "The settings question uses the existing confirmation warning style on Source and Preview");
                return;
            }
        }
        fail("The settings question was not painted");
    }
}
