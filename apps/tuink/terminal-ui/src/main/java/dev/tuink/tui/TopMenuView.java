package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.APP_QUIT;
import static dev.tuink.tui.command.TuinkCommand.DOCUMENT_OPEN;
import static dev.tuink.tui.command.TuinkCommand.DOCUMENT_SAVE;
import static dev.tuink.tui.command.TuinkCommand.DOCUMENT_SAVE_AS;
import static dev.tuink.tui.command.TuinkCommand.EDIT_COPY;
import static dev.tuink.tui.command.TuinkCommand.EDIT_CUT;
import static dev.tuink.tui.command.TuinkCommand.EDIT_PASTE;
import static dev.tuink.tui.command.TuinkCommand.EDIT_REDO;
import static dev.tuink.tui.command.TuinkCommand.EDIT_SELECT_ALL;
import static dev.tuink.tui.command.TuinkCommand.EDIT_TOGGLE_LINE_COMMENT;
import static dev.tuink.tui.command.TuinkCommand.EDIT_UNDO;
import static dev.tuink.tui.command.TuinkCommand.HELP_AUTHOR;
import static dev.tuink.tui.command.TuinkCommand.HELP_KEY_HINTS;
import static dev.tuink.tui.command.TuinkCommand.HELP_LICENSES;
import static dev.tuink.tui.command.TuinkCommand.SEARCH_OPEN;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_CYCLE_TAB_INSERT_MODE;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_AUTO_INDENT;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_AUTO_LIST;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_AUTO_PAIRS;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_CONFIRM_ON_CLOSE;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_MOUSE;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_RIGHT_CLICK_CLIPBOARD;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_OPEN_BOTH_TABS;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_OPEN_IN_PREVIEW;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_VISUAL_COPY;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_READING_MODE;
import static dev.tuink.tui.command.TuinkCommand.SETTINGS_TOGGLE_SMART_HOME_END;
import static dev.tuink.tui.command.TuinkCommand.TAB_CLOSE;
import static dev.tuink.tui.command.TuinkCommand.TAB_NEW;
import static dev.tuink.tui.command.TuinkCommand.TAB_NEXT;
import static dev.tuink.tui.command.TuinkCommand.TAB_PREVIOUS;
import static dev.tuink.tui.command.TuinkCommand.VIEW_CYCLE_GUTTER_DIFF;
import static dev.tuink.tui.command.TuinkCommand.VIEW_CYCLE_TAB_WIDTH;
import static dev.tuink.tui.command.TuinkCommand.VIEW_CYCLE_THEME;
import static dev.tuink.tui.command.TuinkCommand.VIEW_TOGGLE_ACTIVE_GUTTER;
import static dev.tuink.tui.command.TuinkCommand.VIEW_TOGGLE_LINE_WRAP;
import static dev.tuink.tui.command.TuinkCommand.VIEW_TOGGLE_RAINBOW_BRACKETS;
import static dev.tuink.tui.command.TuinkCommand.VIEW_TOGGLE_SELECTION_HIGHLIGHT;
import static dev.tuink.tui.command.TuinkCommand.VIEW_TOGGLE_SYNTAX;
import static dev.tuink.tui.command.TuinkCommand.VIEW_TOGGLE_TAB_BAR;
import static dev.tuink.tui.command.TuinkCommand.VIEW_TOGGLE_WHITESPACE;
import static dev.tuink.tui.command.TuinkCommand.VIEW_TOGGLE_WORD_WRAP;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.text.TerminalText;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.InteractionVisibility;
import dev.tuiframe.widget.MenuBar;
import dev.tuiframe.widget.PlacedPointerCapture;
import dev.tuiframe.widget.PointerCaptureCancellation;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import dev.tuink.tui.ui.TabBarView;
import dev.tuink.tui.ui.DocumentCaption;
import dev.tuiframe.explorer.ui.PathPresentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** TUInk menu descriptors and exact-frame adapter over the generic menu bar. */
final class TopMenuView {
    private static final String SEPARATOR = "----------------";
    private static final String DEFAULT_THEME_LABEL = "Cyan";
    private static final String MENU_SUFFIX = " | ";
    private static final String READING_BADGE = " READ ";

    private final CommandRouter commandRouter;
    private final ActiveSurface surface;
    private final List<MenuBar.Menu<CommandInvocation>> menus;
    private final MenuBar<CommandInvocation> widget;

    private TopMenuView(
        UiSessionState ui,
        List<TabItem> tabs,
        int activeTabIndex,
        String themeLabel,
        CommandRouter commandRouter,
        MenuBar.State state,
        ActiveSurface surface
    ) {
        Objects.requireNonNull(ui, "ui");
        List<TabItem> tabSnapshot = List.copyOf(Objects.requireNonNull(tabs, "tabs"));
        if (tabSnapshot.isEmpty()) {
            if (activeTabIndex != -1) {
                throw new IllegalArgumentException("activeTabIndex must be -1 for an empty tab list");
            }
        } else if (activeTabIndex < 0 || activeTabIndex >= tabSnapshot.size()) {
            throw new IllegalArgumentException("activeTabIndex must identify a tab");
        }
        String normalizedTheme = themeLabel == null || themeLabel.isBlank()
            ? DEFAULT_THEME_LABEL
            : themeLabel;
        CommandRouter requiredRouter = Objects.requireNonNull(commandRouter, "commandRouter");
        this.commandRouter = requiredRouter;
        this.surface = Objects.requireNonNull(surface);
        MenuBar.State requiredState = Objects.requireNonNull(state, "state");
        int openMenuIndex = requiredState.isOpen() ? requiredState.openMenuIndex() : -1;
        this.menus = List.of(
            menu(" File ", 2, openMenuIndex == TopMenuController.FILE_INDEX ? fileEntries(surface) : List.of()),
            menu(" Edit ", 1, openMenuIndex == TopMenuController.EDIT_INDEX ? editEntries(surface) : List.of()),
            menu(
                " View ",
                1,
                openMenuIndex == TopMenuController.VIEW_INDEX
                    ? viewEntries(ui, surface, normalizedTheme)
                    : List.of()
            ),
            menu(
                " Tab ",
                1,
                openMenuIndex == TopMenuController.TABS_INDEX
                    ? tabEntries(tabSnapshot, activeTabIndex, requiredRouter)
                    : List.of()
            ),
            menu(
                " Settings ",
                1,
                openMenuIndex == TopMenuController.SETTINGS_INDEX ? settingsEntries(ui, surface) : List.of()
            ),
            menu(" Help ", 1, openMenuIndex == TopMenuController.HELP_INDEX ? helpEntries() : List.of())
        );
        this.widget = new MenuBar<>(menus, requiredState);
    }

    static TopMenuView build(UiSessionState ui, List<TabItem> tabs, int activeTabIndex,
        String themeLabel, CommandRouter commandRouter, MenuBar.State state, ActiveSurface surface) {
        return new TopMenuView(ui, tabs, activeTabIndex, themeLabel, commandRouter, state, surface);
    }

    Placement place(Rect fullLayerBounds) {
        return place(fullLayerBounds, false);
    }

    Placement place(Rect fullLayerBounds, boolean readingMode) {
        return place(fullLayerBounds, readingMode, false);
    }

    Placement place(Rect fullLayerBounds, boolean readingMode, boolean dirty) {
        Rect bounds = Objects.requireNonNull(fullLayerBounds, "fullLayerBounds");
        int readWidth = readingMode ? Math.min(bounds.width(), CellText.width(READING_BADGE)) : 0;
        int bodyWidth = bounds.width() - readWidth;
        boolean compact = bodyWidth < menuPrefixWidth() + (dirty ? 2 : 0) + 1;
        int dirtyWidth = dirty && compact && bodyWidth > 0 ? 1 : 0;
        MenuBar.Placement<CommandInvocation> placed = widget.layout(
            bounds, readWidth + dirtyWidth
        );
        boolean enabled = InteractionVisibility.FULL_BOUNDS.allows(placed.bounds(), bounds);
        FocusContext context = new FocusContext(
            enabled && placed.state().isOpen(),
            enabled,
            FocusSpace.root(bounds)
        );
        return new Placement(placed, context);
    }

    List<MenuBar.Menu<CommandInvocation>> menus() {
        return menus;
    }

    /** Builds the raster-owned remainder of row zero without retained side effects. */
    TopBar topBar(Path currentFile, boolean dirty, int cols, boolean readingMode) {
        String prefix = " ".repeat(widget.preferredBarWidth()) + MENU_SUFFIX;
        int prefixWidth = CellText.width(prefix);
        int width = Math.max(0, cols);
        String read = readingMode ? CellText.alignRight(READING_BADGE, Math.min(width, CellText.width(READING_BADGE))) : "";
        int bodyWidth = width - CellText.width(read);
        String status = dirty ? " *" : "";
        int nameCapacity = bodyWidth - prefixWidth - CellText.width(status);
        if (nameCapacity <= 0) {
            String compactStatus = dirty && bodyWidth > 0 ? "*" : "";
            String text = CellText.fit("", bodyWidth - CellText.width(compactStatus)) + compactStatus + read;
            return new TopBar(text, prefixWidth, 0, 0);
        }
        String file = DocumentCaption.fitPath(currentFile, nameCapacity);
        String text = CellText.fit(prefix + file + status, bodyWidth) + read;
        return new TopBar(text, prefixWidth, prefixWidth, prefixWidth + CellText.width(file));
    }

    int menuPrefixWidth() {
        return widget.preferredBarWidth() + CellText.width(MENU_SUFFIX);
    }

    record TopBar(String text, int menuPrefixWidth, int filenameStart, int filenameEnd) {
        TopBar(String text, int menuPrefixWidth) {
            this(text, menuPrefixWidth, 0, 0);
        }

        TopBar {
            Objects.requireNonNull(text, "text");
            if (menuPrefixWidth < 0 || filenameStart < 0 || filenameEnd < filenameStart
                || filenameEnd > CellText.width(text)) {
                throw new IllegalArgumentException("Invalid prepared top-bar ranges");
            }
        }
    }

    record TabItem(Path file, boolean dirty, boolean hasMatch, boolean searching, boolean awaitingAnswer, String qualifier) {
        TabItem(Path file, boolean dirty, boolean hasMatch, boolean searching, boolean awaitingAnswer) {
            this(file, dirty, hasMatch, searching, awaitingAnswer, "");
        }
        TabItem(Path file, boolean dirty, boolean hasMatch) {
            this(file, dirty, hasMatch, false, false);
        }
    }

    /** One immutable placement shared by paint, hit routing, state and cursor. */
    static final class Placement implements PlacedPointerCapture<MenuBar.Action<CommandInvocation>> {
        private final MenuBar.Placement<CommandInvocation> widgetPlacement;
        private final FocusContext context;

        private Placement(
            MenuBar.Placement<CommandInvocation> widgetPlacement,
            FocusContext context
        ) {
            this.widgetPlacement = Objects.requireNonNull(widgetPlacement, "widgetPlacement");
            this.context = Objects.requireNonNull(context, "context");
        }

        @Override
        public Rect bounds() {
            return widgetPlacement.bounds();
        }

        @Override
        public boolean interactionReady() {
            return widgetPlacement.interactionReady();
        }

        FocusScope<String, MenuBar.Action<CommandInvocation>> captureScope() {
            return FocusScope.single(TopMenuView.class, FocusResetPolicy.CLEARED,
                new RoutedTarget<>("menu", this, context.space(), false, context.enabled()));
        }

        @Override
        public boolean pointerCaptureActive() {
            return widgetPlacement.pointerCaptureActive();
        }

        @Override
        public InteractionResult<MenuBar.Action<CommandInvocation>> cancelPointerCapture(
            PointerCaptureCancellation reason
        ) {
            return widgetPlacement.cancelPointerCapture(reason);
        }

        MenuBar.State state() {
            return widgetPlacement.state();
        }

        Optional<Rect> popupBounds() {
            return widgetPlacement.popupBounds();
        }

        boolean enabled() {
            return context.enabled();
        }

        @Override
        public void paint(Canvas canvas, Theme theme) {
            widgetPlacement.paint(
                Objects.requireNonNull(canvas, "canvas"),
                Objects.requireNonNull(theme, "theme"),
                context.widgetState()
            );
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState state) {
            widgetPlacement.paint(canvas, theme, state);
        }

        InteractionResult<MenuBar.Action<CommandInvocation>> handle(TuiInputEvent event) {
            return widgetPlacement.handle(Objects.requireNonNull(event, "event"), context);
        }

        @Override
        public InteractionResult<MenuBar.Action<CommandInvocation>> handle(TuiInputEvent event, FocusContext ignored) {
            return handle(event);
        }

        boolean contains(PointerEvent event) {
            Objects.requireNonNull(event, "event");
            return context.localPoint(event).filter(widgetPlacement::hit).isPresent();
        }

        Optional<Point> cursorPosition() {
            return widgetPlacement.cursorPosition(context)
                .flatMap(context.space()::terminalPoint);
        }
    }

    private MenuBar.Menu<CommandInvocation> menu(
        String label,
        int mnemonicGraphemeIndex,
        List<EntrySpec> specs
    ) {
        return new MenuBar.Menu<>(
            label,
            mnemonicGraphemeIndex,
            prepareEntries(specs)
        );
    }

    private static List<EntrySpec> fileEntries(ActiveSurface surface) {
        return List.of(
            item(TAB_NEW, "New"),
            item(DOCUMENT_OPEN, "Open..."),
            item(TuinkCommand.DOCUMENT_OPEN_ENCODING, "Open with encoding..."),
            separator(),
            item(DOCUMENT_SAVE, "Save"),
            item(DOCUMENT_SAVE_AS, "Save As..."),
            item(TuinkCommand.DOCUMENT_FORMAT, "Format...", !surface.preview()),
            item(TuinkCommand.DOCUMENT_REOPEN_ENCODING, "Reopen with encoding...", !surface.preview()),
            separator(),
            item(TuinkCommand.DOCUMENT_RECOVER_DRAFTS, "Recover drafts..."),
            separator(),
            item(APP_QUIT, "Quit")
        );
    }

    private static List<EntrySpec> editEntries(ActiveSurface surface) {
        boolean writable = surface.editableField() || surface.documentWritable();
        boolean documentWritable = surface.documentWritable();
        return List.of(
            item(EDIT_UNDO, "Undo", documentWritable),
            item(EDIT_REDO, "Redo", documentWritable),
            separator(),
            item(EDIT_CUT, "Cut", writable),
            item(EDIT_COPY, "Copy"),
            item(EDIT_PASTE, "Paste", writable),
            separator(),
            item(TuinkCommand.VIEW_PREVIEW_COPY_SOURCE, "Copy block source", surface.preview()),
            item(TuinkCommand.VIEW_PREVIEW_COPY_TABLE_TSV, "Copy table as TSV", surface.preview()),
            separator(),
            item(EDIT_SELECT_ALL, "Select All"),
            item(EDIT_TOGGLE_LINE_COMMENT, "Toggle Line Comment", documentWritable),
            separator(),
            item(SEARCH_OPEN, "Find")
        );
    }

    private static List<EntrySpec> viewEntries(UiSessionState ui, ActiveSurface surface, String themeLabel) {
        boolean wrapLines = ui.wrapLinesSetting;
        GutterDiffMode gutterDiff = ui.gutterDiffModeSetting == null
            ? GutterDiffMode.MYERS : ui.gutterDiffModeSetting;
        return List.of(
            item(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW, surface.preview() ? "MD Source" : "MD Preview"),
            item(SETTINGS_TOGGLE_READING_MODE, checked(ui.readingModeSetting) + " Reading mode"),
            separator(),
            item(VIEW_TOGGLE_LINE_WRAP, checked(wrapLines) + " Wrap Lines"),
            item(VIEW_TOGGLE_WORD_WRAP, checked(ui.wrapWordsSetting) + " Wrap by words", wrapLines),
            separator(),
            item(VIEW_TOGGLE_TAB_BAR, checked(ui.showTabBarSetting) + " Show Tab Bar"),
            item(VIEW_TOGGLE_ACTIVE_GUTTER, checked(ui.highlightActiveGutterSetting) + " Highlight current line number"),
            item(VIEW_TOGGLE_WHITESPACE, checked(ui.visibleWhitespaceSetting) + " Visible tabs/spaces"),
            item(VIEW_TOGGLE_SYNTAX, checked(ui.syntaxHighlightingSetting) + " Syntax highlighting"),
            item(VIEW_TOGGLE_RAINBOW_BRACKETS, checked(ui.rainbowBracketsSetting) + " Rainbow brackets"),
            item(VIEW_TOGGLE_SELECTION_HIGHLIGHT, checked(ui.autoSelectionHighlightSetting) + " Auto highlight selection"),
            item(VIEW_CYCLE_GUTTER_DIFF, "[>] Gutter diff: " + gutterDiff.label()),
            item(VIEW_CYCLE_THEME, "[>] Theme: " + themeLabel),
            separator(),
            item(TuinkCommand.VIEW_PREVIEW_FOLLOW_LINK, "Follow internal link", surface.preview()),
            item(TuinkCommand.VIEW_PREVIEW_NEXT_HEADING, "Next heading", surface.preview()),
            item(TuinkCommand.VIEW_PREVIEW_PREVIOUS_HEADING, "Previous heading", surface.preview())
        );
    }

    private static List<EntrySpec> tabEntries(
        List<TabItem> tabs,
        int activeTabIndex,
        CommandRouter commandRouter
    ) {
        ArrayList<EntrySpec> entries = new ArrayList<>(5 + tabs.size());
        entries.add(item(TAB_NEW, "New Tab"));
        entries.add(item(TAB_CLOSE, "Close Tab"));
        entries.add(item(TAB_NEXT, "Next Tab"));
        entries.add(item(TAB_PREVIOUS, "Previous Tab"));
        entries.add(separator());
        for (int index = 0; index < tabs.size(); index++) {
            TabItem tab = Objects.requireNonNull(tabs.get(index), "tab");
            String marker = index == activeTabIndex ? "> " : "   ";
            String title = TabBarView.formatTabTitle(tab.file(), tab.dirty(), tab.hasMatch(),
                tab.searching(), tab.awaitingAnswer(), tab.qualifier());
            entries.add(new EntrySpec(
                commandRouter.tabActivationInvocation(index),
                marker + title,
                true,
                false
            ));
        }
        return List.copyOf(entries);
    }

    private static List<EntrySpec> settingsEntries(UiSessionState ui, ActiveSurface surface) {
        TabInsertMode insertMode = ui.tabInsertModeSetting == null ? TabInsertMode.AUTO : ui.tabInsertModeSetting;
        return List.of(
            item(SETTINGS_TOGGLE_CONFIRM_ON_CLOSE, checked(ui.confirmOnCloseSetting) + " Confirm on close"),
            separator(),
            item(SETTINGS_TOGGLE_MOUSE, checked(ui.mouseModeSetting && surface.mouseAvailable()) + " Mouse mode"),
            item(SETTINGS_TOGGLE_RIGHT_CLICK_CLIPBOARD, checked(ui.rightClickClipboardSetting) + " Right-click: copy/paste"),
            separator(),
            item(SETTINGS_TOGGLE_SMART_HOME_END, checked(ui.smartHomeEndSetting) + " Smart Home/End"),
            item(SETTINGS_TOGGLE_AUTO_INDENT, checked(ui.autoIndentSetting) + " Auto indent"),
            item(SETTINGS_TOGGLE_AUTO_PAIRS, checked(ui.autoPairsSetting) + " Autopairs"),
            item(SETTINGS_TOGGLE_AUTO_LIST, checked(ui.autoListSetting) + " Auto list"),
            item(VIEW_CYCLE_TAB_WIDTH, "[>] Tab width: " + ui.tabWidthSetting),
            item(SETTINGS_CYCLE_TAB_INSERT_MODE, "[>] Tab insert mode: " + insertMode.label()),
            separator(),
            item(SETTINGS_TOGGLE_MARKDOWN_OPEN_BOTH_TABS, checked(ui.markdownOpenBothTabsSetting) + " Open both Markdown tabs"),
            item(SETTINGS_TOGGLE_MARKDOWN_OPEN_IN_PREVIEW, checked(ui.markdownOpenInPreviewSetting) + " Open Markdown in Preview"),
            item(SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX, checked(ui.markdownCodeHighlightingSetting) + " MD code syntax"),
            item(SETTINGS_TOGGLE_MARKDOWN_VISUAL_COPY, checked(ui.markdownVisualCopySetting) + " MD copy as displayed")
        );
    }

    private static List<EntrySpec> helpEntries() {
        return List.of(
            item(HELP_KEY_HINTS, "Keyboard hints"),
            item(HELP_LICENSES, "Licenses"),
            item(HELP_AUTHOR, "About")
        );
    }

    private static EntrySpec item(TuinkCommand command, String label) {
        return item(command, label, true);
    }

    private static EntrySpec item(TuinkCommand command, String label, boolean enabled) {
        return new EntrySpec(CommandInvocation.of(command.id()), label, enabled, false);
    }

    private static EntrySpec separator() {
        return new EntrySpec(null, SEPARATOR, false, true);
    }

    private static String checked(boolean checked) {
        return checked ? "[x]" : "[ ]";
    }

    private List<MenuBar.Entry<CommandInvocation>> prepareEntries(List<EntrySpec> specs) {
        ArrayList<MenuBar.Entry<CommandInvocation>> result = new ArrayList<>(specs.size());
        Set<Integer> usedMnemonics = new HashSet<>();
        for (EntrySpec spec : specs) {
            if (spec.separator()) {
                result.add(new MenuBar.Separator<>());
                continue;
            }
            boolean enabled = spec.enabled() && TuinkCommand.fromId(spec.value().id()).map(surface::allows).orElse(true);
            int mnemonic = enabled
                ? mnemonicGraphemeIndex(spec.label(), usedMnemonics)
                : -1;
            result.add(new MenuBar.Item<>(spec.value(), spec.label(), mnemonic, enabled,
                PathPresentation.LABEL_POLICY, commandRouter.shortcutFor(spec.value())));
        }
        return List.copyOf(result);
    }

    private static int mnemonicGraphemeIndex(String label, Set<Integer> used) {
        List<TerminalText.Grapheme> graphemes = TerminalText.graphemes(label);
        int textStart = textStartOffset(label);
        ArrayList<MnemonicCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < graphemes.size(); index++) {
            TerminalText.Grapheme grapheme = graphemes.get(index);
            if (grapheme.end() <= textStart) {
                continue;
            }
            int letterOffset = firstLetterOffset(label, Math.max(textStart, grapheme.start()), grapheme.end());
            if (letterOffset < 0) {
                continue;
            }
            int codePoint = label.codePointAt(letterOffset);
            boolean wordStart = letterOffset == textStart
                || !Character.isLetter(label.codePointBefore(letterOffset));
            candidates.add(new MnemonicCandidate(index, Character.toLowerCase(codePoint), wordStart));
        }
        for (MnemonicCandidate candidate : candidates) {
            if (candidate.wordStart() && used.add(candidate.key())) {
                return candidate.graphemeIndex();
            }
        }
        for (MnemonicCandidate candidate : candidates) {
            if (used.add(candidate.key())) {
                return candidate.graphemeIndex();
            }
        }
        return -1;
    }

    private static int textStartOffset(String text) {
        if (!text.startsWith("[")) {
            return 0;
        }
        int close = text.indexOf("] ");
        return close >= 0 && close + 2 < text.length() ? close + 2 : 0;
    }

    private static int firstLetterOffset(String text, int start, int end) {
        for (int offset = start; offset < end;) {
            int codePoint = text.codePointAt(offset);
            if (Character.isLetter(codePoint)) {
                return offset;
            }
            offset += Character.charCount(codePoint);
        }
        return -1;
    }

    private record EntrySpec(
        CommandInvocation value,
        String label,
        boolean enabled,
        boolean separator
    ) {
        private EntrySpec {
            Objects.requireNonNull(label, "label");
            if (separator == (value != null)) {
                throw new IllegalArgumentException("separator and command item are mutually exclusive");
            }
        }
    }

    private record MnemonicCandidate(int graphemeIndex, int key, boolean wordStart) {
    }
}
