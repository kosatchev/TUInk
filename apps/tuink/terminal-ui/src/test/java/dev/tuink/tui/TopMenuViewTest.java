package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.EDIT_SELECT_ALL;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_MOUSE;
import static dev.tuink.tui.command.TuinkCommand.VIEW_TOGGLE_WORD_WRAP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.MenuBar;
import dev.tuiframe.widget.Theme;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TopMenuViewTest {
    @Test
    void markdownToggleIsTheFirstViewItemAndUsesTheCtrlPBinding() {
        var preferences = ui();
        for (var kind : ActiveSurface.Kind.values()) {
            var menu = TopMenuView.build(preferences, List.of(), -1, "Cyan",
                new CommandRouter(dev.tuink.tui.keymap.TuinkKeymap.defaults()),
                MenuBar.State.open(TopMenuController.VIEW_INDEX), TestSurfaces.source(preferences).withKind(kind));
            var toggle = item(menu, TopMenuController.VIEW_INDEX, 0);
            assertEquals(dev.tuink.tui.command.TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW.id(), toggle.value().id());
            assertEquals(kind == ActiveSurface.Kind.SOURCE ? "MD Preview" : "MD Source", toggle.label());
            assertEquals("Ctrl+P", toggle.hint());
            assertTrue(toggle.enabled());
        }
    }

    @Test
    void markdownSyntaxSettingShowsItsIndependentCheckmark() {
        var ui = UiSessionState.from(dev.tuink.tui.flow.AppSettings.defaults());
        for (boolean enabled : new boolean[] {true, false}) {
            ui.markdownCodeHighlightingSetting = enabled;
            var menu = TopMenuView.build(ui, List.of(), -1, "Cyan", new CommandRouter(),
                MenuBar.State.open(TopMenuController.SETTINGS_INDEX), TestSurfaces.source(ui));
            var setting = menu.menus().get(TopMenuController.SETTINGS_INDEX).entries().stream()
                .filter(entry -> entry instanceof MenuBar.Item<?> item
                    && ((CommandInvocation) item.value()).id().equals(
                        dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX.id()))
                .map(entry -> (MenuBar.Item<?>) entry).findFirst().orElseThrow();
            assertEquals((enabled ? "[x]" : "[ ]") + " MD code syntax", setting.label());
            assertTrue(setting.enabled());
        }
    }

    @Test
    void rightClickClipboardHasItsOwnCheckmarkEvenWhenMouseTrackingIsUnavailable() {
        var ui = UiSessionState.from(dev.tuink.tui.flow.AppSettings.defaults());
        ui.mouseModeSetting = false;
        for (boolean enabled : new boolean[] {false, true}) {
            ui.rightClickClipboardSetting = enabled;
            var menu = TopMenuView.build(ui, List.of(), -1, "Cyan", new CommandRouter(),
                MenuBar.State.open(TopMenuController.SETTINGS_INDEX), TestSurfaces.source(ui).withRuntime(false, true));
            var setting = menu.menus().get(TopMenuController.SETTINGS_INDEX).entries().stream()
                .filter(entry -> entry instanceof MenuBar.Item<?> item
                    && ((CommandInvocation) item.value()).id().equals(
                        dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_RIGHT_CLICK_CLIPBOARD.id()))
                .map(entry -> (MenuBar.Item<?>) entry).findFirst().orElseThrow();
            assertEquals((enabled ? "[x]" : "[ ]") + " Right-click: copy/paste", setting.label());
            assertTrue(setting.enabled());
        }
    }

    @Test
    void showsTheCurrentBindingAndDisablesEditsInPreview() {
        var keymap = dev.tuink.tui.keymap.TuinkKeymap.defaults();
        keymap.unbind(dev.tuiframe.input.KeyStroke.of(dev.tuiframe.input.KeyStroke.Key.S,
            dev.tuiframe.input.KeyStroke.Modifier.CTRL));
        keymap.bind(dev.tuiframe.input.KeyStroke.of(dev.tuiframe.input.KeyStroke.Key.F12),
            CommandInvocation.of(dev.tuink.tui.command.TuinkCommand.DOCUMENT_SAVE.id()));
        var file = TopMenuView.build(ui(), List.of(), -1, "Blue", new CommandRouter(keymap),
            MenuBar.State.open(TopMenuController.FILE_INDEX), TestSurfaces.source(ui()));
        assertTrue(file.menus().get(0).entries().stream().anyMatch(entry ->
            entry instanceof MenuBar.Item<?> item && item.label().equals("Save") && item.hint().equals("F12")));

        UiSessionState preview = ui();
        var edit = TopMenuView.build(preview, List.of(), -1, "Blue", new CommandRouter(),
            MenuBar.State.open(TopMenuController.EDIT_INDEX), TestSurfaces.source(preview).withKind(ActiveSurface.Kind.LIVE_PREVIEW));
        var cut = edit.menus().get(1).entries().stream().filter(entry ->
            entry instanceof MenuBar.Item<?> item && item.label().startsWith("Cut"))
            .map(entry -> (MenuBar.Item<?>) entry).findFirst().orElseThrow();
        assertFalse(cut.enabled());
        assertTrue(cut.label().contains("read only"));
    }

    @Test
    void buildsSixFixedMenusWithSemanticCommandsAndDynamicTabs() {
        CommandRouter router = new CommandRouter();
        TopMenuView tabView = TopMenuView.build(
            ui(),
            List.of(
                new TopMenuView.TabItem(Path.of("first.txt"), false, false),
                new TopMenuView.TabItem(Path.of("中.txt"), true, true)
            ),
            1,
            "Blue",
            router,
            MenuBar.State.open(TopMenuController.TABS_INDEX),
            TestSurfaces.source(ui())
        );
        TopMenuView editView = view(ui(), MenuBar.State.open(TopMenuController.EDIT_INDEX));

        assertEquals(
            List.of(" File ", " Edit ", " View ", " Tab ", " Settings ", " Help "),
            tabView.menus().stream().map(MenuBar.Menu::label).toList()
        );
        assertEquals(List.of(2, 1, 1, 1, 1, 1), tabView.menus().stream()
            .map(MenuBar.Menu::mnemonicGraphemeIndex)
            .toList());

        MenuBar.Item<CommandInvocation> selectAll = item(
            editView,
            TopMenuController.EDIT_INDEX,
            6
        );
        assertEquals(EDIT_SELECT_ALL.id(), selectAll.value().id());
        assertEquals("Select All", selectAll.label());

        MenuBar.Item<CommandInvocation> firstTab = item(
            tabView,
            TopMenuController.TABS_INDEX,
            5
        );
        MenuBar.Item<CommandInvocation> activeTab = item(
            tabView,
            TopMenuController.TABS_INDEX,
            6
        );
        assertEquals("   first.txt", firstTab.label());
        assertEquals("> #中.txt*", activeTab.label());
        assertEquals("0", firstTab.value().argument("index").orElseThrow());
        assertEquals("1", activeTab.value().argument("index").orElseThrow());
    }

    @Test
    void tabMenuRetainsQuestionAndSearchMarkersOnInactiveAndActiveTabs() {
        TopMenuView view = TopMenuView.build(ui(), List.of(
            new TopMenuView.TabItem(Path.of("first.txt"), true, false, true, true),
            new TopMenuView.TabItem(Path.of("中.txt"), false, true, true, false)
        ), 1, "Cyan", new CommandRouter(), MenuBar.State.open(TopMenuController.TABS_INDEX), TestSurfaces.source(ui()));

        var inactive = item(view, TopMenuController.TABS_INDEX, 5);
        var active = item(view, TopMenuController.TABS_INDEX, 6);

        assertEquals("   ?/first.txt*", inactive.label());
        assertEquals("> /#中.txt", active.label());
        assertEquals("0", inactive.value().argument("index").orElseThrow());
        assertEquals("1", active.value().argument("index").orElseThrow());
    }

    @Test
    void derivesSettingsLabelsAndDisabledWordWrapFromOneUiSnapshot() {
        UiSessionState ui = ui();
        ui.wrapLinesSetting = false;
        ui.wrapWordsSetting = true;
        ui.mouseModeSetting = true;

        TopMenuView viewMenu = view(ui, MenuBar.State.open(TopMenuController.VIEW_INDEX));
        TopMenuView settingsMenu = TopMenuView.build(ui, List.of(), -1, "Blue", new CommandRouter(),
            MenuBar.State.open(TopMenuController.SETTINGS_INDEX), TestSurfaces.source(ui).withRuntime(false, true));

        MenuBar.Item<CommandInvocation> wordWrap = item(
            viewMenu,
            TopMenuController.VIEW_INDEX,
            4
        );
        assertEquals(VIEW_TOGGLE_WORD_WRAP.id(), wordWrap.value().id());
        assertEquals("[x] Wrap by words (disabled)", wordWrap.label());
        assertFalse(wordWrap.enabled());
        assertEquals(-1, wordWrap.mnemonicGraphemeIndex());

        MenuBar.Item<CommandInvocation> mouse = item(
            settingsMenu,
            TopMenuController.SETTINGS_INDEX,
            1
        );
        assertEquals(SETTINGS_TOGGLE_MOUSE.id(), mouse.value().id());
        assertEquals("[ ] Mouse mode", mouse.label());
        assertTrue(mouse.enabled());
    }

    @Test
    void everyStaticDescriptorUsesTheSemanticCommandRegistryId() {
        UiSessionState ui = ui();

        assertEquals(
            List.of(
                "tuink.tab.new",
                "tuink.document.open",
                "tuink.document.save",
                "tuink.document.save-as",
                "tuink.document.format",
                "tuink.document.reopen-encoding",
                "tuink.document.open-encoding",
                "tuink.document.recover-drafts",
                "tuink.app.quit"
            ),
            commandIds(view(ui, MenuBar.State.open(TopMenuController.FILE_INDEX)), TopMenuController.FILE_INDEX)
        );
        assertEquals(
            List.of(
                "tuink.edit.undo",
                "tuink.edit.redo",
                "tuink.edit.cut",
                "tuink.edit.copy",
                "tuink.edit.paste",
                "tuink.edit.toggle-line-comment",
                "tuink.edit.select-all",
                "tuink.search.open"
            ),
            commandIds(view(ui, MenuBar.State.open(TopMenuController.EDIT_INDEX)), TopMenuController.EDIT_INDEX)
        );
        assertEquals(
            List.of(
                "tuink.view.toggle-markdown-preview",
                "tuink.view.toggle-tab-bar",
                "tuink.view.toggle-line-wrap",
                "tuink.view.toggle-word-wrap",
                "tuink.view.toggle-active-gutter",
                "tuink.view.toggle-whitespace",
                "tuink.view.toggle-syntax",
                "tuink.view.toggle-rainbow-brackets",
                "tuink.view.toggle-selection-highlight",
                "tuink.view.cycle-gutter-diff",
                "tuink.view.cycle-tab-width",
                "tuink.view.cycle-theme",
                "tuink.view.preview.copy-source",
                "tuink.view.preview.copy-table-tsv",
                "tuink.view.preview.reveal-source",
                "tuink.view.preview.follow-link",
                "tuink.view.preview.next-heading",
                "tuink.view.preview.previous-heading"
            ),
            commandIds(view(ui, MenuBar.State.open(TopMenuController.VIEW_INDEX)), TopMenuController.VIEW_INDEX)
        );
        assertEquals(
            List.of(
                "tuink.tab.new",
                "tuink.tab.close",
                "tuink.tab.next",
                "tuink.tab.previous",
                "tuink.tab.activate"
            ),
            commandIds(view(ui, MenuBar.State.open(TopMenuController.TABS_INDEX)), TopMenuController.TABS_INDEX)
        );
        assertEquals(
            List.of(
                "tuink.settings.toggle-reading-mode",
                "tuink.settings.toggle-mouse",
                "tuink.settings.toggle-confirm-on-close",
                "tuink.settings.toggle-smart-home-end",
                "tuink.settings.toggle-auto-indent",
                "tuink.settings.toggle-auto-pairs",
                "tuink.settings.toggle-auto-list",
                "tuink.settings.cycle-tab-insert-mode",
                "tuink.settings.toggle-markdown-open-both-tabs",
                "tuink.settings.toggle-markdown-open-in-preview",
                "tuink.settings.toggle-markdown-code-syntax",
                "tuink.settings.toggle-right-click-clipboard"
            ),
            commandIds(
                view(ui, MenuBar.State.open(TopMenuController.SETTINGS_INDEX)),
                TopMenuController.SETTINGS_INDEX
            )
        );
        assertEquals(
            List.of("tuink.help.key-hints", "tuink.help.licenses", "tuink.help.author"),
            commandIds(view(ui, MenuBar.State.open(TopMenuController.HELP_INDEX)), TopMenuController.HELP_INDEX)
        );
    }

    @Test
    void topBarSnapshotPreservesPrefixDirtyAndReadAlignment() {
        TopMenuView closed = view(ui(), MenuBar.State.closed());
        TopMenuView.TopBar normal = closed.topBar(Path.of("demo.txt"), true, 64, false);
        String closedBase = " ".repeat(44) + " | demo.txt *";

        assertEquals(CellText.fit(closedBase, 64), normal.text());
        assertEquals(47, normal.menuPrefixWidth());
        assertEquals(47, closed.menuPrefixWidth());

        TopMenuView open = view(ui(), MenuBar.State.open(TopMenuController.FILE_INDEX));
        TopMenuView.TopBar reading = open.topBar(Path.of("demo.txt"), false, 64, true);
        String openBase = " ".repeat(44) + " | demo.txt";
        assertEquals(CellText.fit(openBase, 58) + " READ ", reading.text());
        assertEquals(47, reading.menuPrefixWidth());
    }

    @Test
    void longPathsKeepTheBasenameExtensionDirtyAndExactFilenameStyleRange() {
        TopMenuView view = view(ui(), MenuBar.State.closed());
        Path file = Path.of("very-long-parent-directory".repeat(3), "e\u0301👩‍💻界-long-file-name.java");
        for (int width : List.of(60, 64, 80, 120)) {
            TopMenuView.TopBar topBar = view.topBar(file, true, width, false);
            assertTrue(topBar.text().contains(".java *"), topBar.text());
            assertEquals(width, CellText.width(topBar.text()));
            String filename = CellText.clip(topBar.text(), topBar.filenameStart(),
                topBar.filenameEnd() - topBar.filenameStart());
            assertTrue(filename.endsWith(".java"), filename);
            assertFalse(filename.contains("*"), filename);
        }
    }

    @Test
    void dirtyStatusSurvivesMenuPaintingAndNeverCreatesAMenuHitTarget() {
        TopMenuView view = view(ui(), MenuBar.State.closed());
        for (boolean reading : List.of(false, true)) {
            for (int width = 1; width <= 70; width++) {
                TopMenuView.TopBar topBar = view.topBar(Path.of("long-name.java"), true, width, reading);
                TopMenuView.Placement placement = view.place(new Rect(0, 0, width, 12), reading, true);
                CellBuffer buffer = new CellBuffer(12, width);
                Canvas canvas = new Canvas(buffer);
                CellText.drawClipped(canvas, 0, 0, topBar.text(), CellStyle.DEFAULT, width);
                placement.paint(canvas, Theme.fixed(CellStyle.DEFAULT));
                int readWidth = reading ? Math.min(width, 6) : 0;
                if (width > readWidth) {
                    int dirtyColumn = -1;
                    for (int col = 0; col < width - readWidth; col++) {
                        if (buffer.get(0, col).grapheme().equals("*")) dirtyColumn = col;
                    }
                    assertTrue(dirtyColumn >= 0, "dirty hidden at width=" + width + " reading=" + reading);
                    assertFalse(placement.contains(new PointerEvent(0, dirtyColumn, PointerEvent.Kind.PRESS,
                        PointerEvent.Button.LEFT, Set.of())), "status must not activate a clipped menu");
                }
            }
        }
    }

    @Test
    void shiftedHeadingsHaveNoRasterCopiesOrPhantomLabelsInTheirGaps() {
        TopMenuView help = view(ui(), MenuBar.State.open(TopMenuController.HELP_INDEX));
        for (int width : List.of(15, 24, 40)) {
            var placement = help.place(new Rect(0, 0, width, 12));
            var topBar = help.topBar(Path.of("demo.txt"), false, width, false);
            assertTrue(topBar.text().isBlank(), "raster chrome reserves space while the widget owns all headings");
            CellBuffer buffer = new CellBuffer(12, width);
            Canvas canvas = new Canvas(buffer);
            CellText.drawClipped(canvas, 0, 0, topBar.text(), CellStyle.DEFAULT, width);
            placement.paint(canvas, Theme.fixed(CellStyle.DEFAULT));
            String expected = switch (width) {
                case 15 -> " Help ";
                case 24 -> " Tab   Settings   Help ";
                default -> " Edit   View   Tab   Settings   Help ";
            };
            assertEquals(CellText.fit(expected, width), cells(buffer, 0, 0, width));
        }
    }

    @Test
    void composedPlacementPaintsTheRuntimeTopChromeAndPopupOverTheRasterRow() {
        TopMenuView view = view(ui(), MenuBar.State.open(TopMenuController.EDIT_INDEX));
        TopMenuView.Placement placement = view.place(new Rect(0, 0, 80, 12));
        TopMenuView.TopBar topBar = view.topBar(Path.of("demo.txt"), false, 80, false);
        CellBuffer buffer = new CellBuffer(12, 80);
        Canvas canvas = new Canvas(buffer);

        CellText.drawClipped(canvas, 0, 0, topBar.text(), CellStyle.DEFAULT, 80);
        placement.paint(canvas, Theme.fixed(CellStyle.DEFAULT));

        Rect popup = placement.popupBounds().orElseThrow();
        assertEquals(" File ", cells(buffer, 0, 0, 6));
        assertEquals(" Edit ", cells(buffer, 0, 7, 6));
        assertEquals("demo.txt", cells(buffer, 0, topBar.menuPrefixWidth(), 8));
        assertEquals("Undo", cells(buffer, popup.y() + 1, popup.x() + 2, 4));
        assertEquals("Select All", cells(buffer, popup.y() + 7, popup.x() + 2, 10));
    }

    @Test
    void readingBadgeSurvivesClosedAndShiftedMenuHeadingsOnNarrowFrames() {
        for (MenuBar.State state : List.of(MenuBar.State.closed(),
            MenuBar.State.open(TopMenuController.SETTINGS_INDEX),
            MenuBar.State.open(TopMenuController.HELP_INDEX))) {
            TopMenuView view = view(ui(), state);
            for (int width : List.of(6, 10, 20, 40, 64)) {
                TopMenuView.Placement placement = view.place(new Rect(0, 0, width, 12), true);
                TopMenuView.TopBar topBar = view.topBar(Path.of("demo.txt"), true, width, true);
                CellBuffer buffer = new CellBuffer(12, width);
                Canvas canvas = new Canvas(buffer);
                CellText.drawClipped(canvas, 0, 0, topBar.text(), CellStyle.DEFAULT, width);

                placement.paint(canvas, Theme.fixed(CellStyle.DEFAULT));

                assertEquals(" READ ", cells(buffer, 0, width - 6, 6), "width=" + width + " state=" + state);
                if (state.isOpen()) {
                    assertTrue(placement.popupBounds().isPresent());
                } else {
                    assertFalse(placement.contains(new PointerEvent(
                        0, width - 3, PointerEvent.Kind.PRESS,
                        PointerEvent.Button.LEFT, Set.of()
                    )));
                }
            }
        }
    }

    @Test
    void emptyTabsRequireMinusOneAndStillProduceExactLayerPlacement() {
        TopMenuView view = TopMenuView.build(
            ui(),
            List.of(),
            -1,
            "Cyan",
            new CommandRouter(),
            MenuBar.State.closed(),
            TestSurfaces.source(ui())
        );

        TopMenuView.Placement placement = view.place(new Rect(3, 2, 50, 8));

        assertEquals(new Rect(3, 2, 50, 8), placement.bounds());
        assertEquals(MenuBar.State.closed(), placement.state());
        assertTrue(placement.enabled());
        assertTrue(placement.popupBounds().isEmpty());
        assertTrue(placement.cursorPosition().isEmpty());
    }

    private static TopMenuView view(UiSessionState ui, MenuBar.State state) {
        return TopMenuView.build(
            ui,
            List.of(new TopMenuView.TabItem(Path.of("demo.txt"), false, false)),
            0,
            "Cyan",
            new CommandRouter(),
            state,
            TestSurfaces.source(ui)
        );
    }

    private static UiSessionState ui() {
        UiSessionState ui = new UiSessionState();
        ui.showTabBarSetting = true;
        ui.wrapLinesSetting = true;
        ui.wrapWordsSetting = true;
        ui.readingModeSetting = false;
        ui.confirmOnCloseSetting = true;
        ui.mouseModeSetting = true;
        ui.highlightActiveGutterSetting = true;
        ui.smartHomeEndSetting = true;
        ui.autoIndentSetting = true;
        ui.autoPairsSetting = true;
        ui.autoListSetting = true;
        ui.visibleWhitespaceSetting = true;
        ui.syntaxHighlightingSetting = true;
        ui.rainbowBracketsSetting = true;
        ui.autoSelectionHighlightSetting = true;
        ui.gutterDiffModeSetting = GutterDiffMode.MYERS;
        ui.tabWidthSetting = 4;
        ui.tabInsertModeSetting = TabInsertMode.AUTO;
        return ui;
    }

    @SuppressWarnings("unchecked")
    private static MenuBar.Item<CommandInvocation> item(
        TopMenuView view,
        int menuIndex,
        int entryIndex
    ) {
        MenuBar.Entry<CommandInvocation> entry = view.menus().get(menuIndex).entries().get(entryIndex);
        return (MenuBar.Item<CommandInvocation>) assertInstanceOf(MenuBar.Item.class, entry);
    }

    private static List<String> commandIds(TopMenuView view, int menuIndex) {
        return view.menus().get(menuIndex).entries().stream()
            .filter(MenuBar.Item.class::isInstance)
            .map(MenuBar.Item.class::cast)
            .map(item -> ((CommandInvocation) item.value()).id().value())
            .toList();
    }

    private static String cells(CellBuffer buffer, int row, int column, int width) {
        StringBuilder text = new StringBuilder(width);
        for (int offset = 0; offset < width; offset++) {
            text.append(buffer.get(row, column + offset).grapheme());
        }
        return text.toString();
    }
}
