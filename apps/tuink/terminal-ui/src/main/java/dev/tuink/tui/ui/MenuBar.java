package dev.tuink.tui.ui;

import dev.tuink.render.Style;
import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.KeyEvent;
import dev.tuink.tui.render.Cell;
import dev.tuink.tui.render.CellBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * UI element for the top menu bar and its dropdown popup.
 */
public final class MenuBar {
    private final Controller controller = new Controller();
    private Style popupStyle;
    private Style popupSelectedStyle;
    private Style topSelectedStyle;
    private Style selectedMonoStyle;

    public MenuBar(Style popupStyle, Style popupSelectedStyle, Style topSelectedStyle, Style selectedMonoStyle) {
        this.popupStyle = popupStyle;
        this.popupSelectedStyle = popupSelectedStyle;
        this.topSelectedStyle = topSelectedStyle;
        this.selectedMonoStyle = selectedMonoStyle;
    }

    public void setStyles(Style popupStyle, Style popupSelectedStyle, Style topSelectedStyle, Style selectedMonoStyle) {
        this.popupStyle = popupStyle;
        this.popupSelectedStyle = popupSelectedStyle;
        this.topSelectedStyle = topSelectedStyle;
        this.selectedMonoStyle = selectedMonoStyle;
    }

    public void setThemeLabel(String label) {
        controller.setThemeLabel(label);
    }

    public void setOpenTabs(List<OpenTab> openTabs, int activeTabIndex) {
        controller.setOpenTabs(openTabs, activeTabIndex);
    }

    public void setShowTabBarEnabled(boolean enabled) {
        controller.setShowTabBarEnabled(enabled);
    }

    public void setWrapLinesEnabled(boolean enabled) {
        controller.setWrapLinesEnabled(enabled);
    }

    public void setWrapWordsEnabled(boolean enabled) {
        controller.setWrapWordsEnabled(enabled);
    }

    public void setReadingModeEnabled(boolean enabled) {
        controller.setReadingModeEnabled(enabled);
    }

    public void setConfirmOnCloseEnabled(boolean enabled) {
        controller.setConfirmOnCloseEnabled(enabled);
    }

    public void setMouseModeEnabled(boolean enabled) {
        controller.setMouseModeEnabled(enabled);
    }

    public void setActiveGutterHighlightEnabled(boolean enabled) {
        controller.setActiveGutterHighlightEnabled(enabled);
    }

    public void setSmartHomeEndEnabled(boolean enabled) {
        controller.setSmartHomeEndEnabled(enabled);
    }

    public void setAutoIndentEnabled(boolean enabled) {
        controller.setAutoIndentEnabled(enabled);
    }

    public void setAutoPairsEnabled(boolean enabled) {
        controller.setAutoPairsEnabled(enabled);
    }

    public void setAutoListEnabled(boolean enabled) {
        controller.setAutoListEnabled(enabled);
    }

    public void setVisibleWhitespaceEnabled(boolean enabled) {
        controller.setVisibleWhitespaceEnabled(enabled);
    }

    public void setSyntaxHighlightingEnabled(boolean enabled) {
        controller.setSyntaxHighlightingEnabled(enabled);
    }

    public void setRainbowBracketsEnabled(boolean enabled) {
        controller.setRainbowBracketsEnabled(enabled);
    }

    public void setAutoSelectionHighlightEnabled(boolean enabled) {
        controller.setAutoSelectionHighlightEnabled(enabled);
    }

    public void setGutterDiffMode(GutterDiffMode mode) {
        controller.setGutterDiffMode(mode);
    }

    public void setTabWidth(int tabWidth) {
        controller.setTabWidth(tabWidth);
    }

    public void setTabInsertMode(TabInsertMode mode) {
        controller.setTabInsertMode(mode);
    }

    public Integer consumeTabActivationRequest() {
        return controller.consumePendingTabActivation();
    }

    public String buildTopBar(Path currentFile, boolean dirty, int activeTabIndex, int totalTabs, int cols, boolean readingMode) {
        return controller.buildTopBar(currentFile, dirty, activeTabIndex, totalTabs, cols, readingMode);
    }

    public int fileNameStartCol() {
        return controller.fileNameStartCol();
    }

    public int popupCursorCol() {
        return View.popupContentCol(controller.active()) + controller.selectedMnemonicIndex();
    }

    public void draw(CellBuffer buffer, int cols, boolean colorEnabled) {
        MenuItem[] activeItems = controller.activeItems();
        View.drawPopup(
            buffer,
            cols,
            controller.isOpen(),
            controller.active(),
            controller.selectedIndex(),
            activeItems,
            controller.activeMnemonics(),
            colorEnabled,
            popupStyle,
            popupSelectedStyle,
            selectedMonoStyle
        );
        View.drawTopSelection(
            buffer,
            cols,
            controller.isOpen(),
            controller.active(),
            colorEnabled,
            topSelectedStyle,
            selectedMonoStyle
        );
        View.drawTopMenuMnemonics(buffer, cols);
    }

    public boolean isOpen() {
        return controller.isOpen();
    }

    public int selectedIndex() {
        return controller.selectedIndex();
    }

    public void openEdit() {
        controller.open(MenuId.EDIT);
    }

    public void openFile() {
        controller.open(MenuId.FILE);
    }

    public void openTab() {
        controller.open(MenuId.TAB);
    }

    public void openView() {
        controller.open(MenuId.VIEW);
    }

    public void openSettings() {
        controller.open(MenuId.SETTINGS);
    }

    public void openHelp() {
        controller.open(MenuId.HELP);
    }

    public void close() {
        controller.close();
    }

    public KeyEvent.Action handleKey(KeyEvent.Action action) {
        return controller.handleMenuKey(action);
    }

    public KeyEvent.Action handleChar(char ch) {
        return controller.handleMenuChar(ch);
    }

    public boolean handleTopBarClick(int row, int col) {
        if (row != 0) {
            return false;
        }
        if (View.isMenuLabelHit(MenuId.FILE, col)) {
            controller.toggle(MenuId.FILE);
            return true;
        }
        if (View.isMenuLabelHit(MenuId.EDIT, col)) {
            controller.toggle(MenuId.EDIT);
            return true;
        }
        if (View.isMenuLabelHit(MenuId.VIEW, col)) {
            controller.toggle(MenuId.VIEW);
            return true;
        }
        if (View.isMenuLabelHit(MenuId.TAB, col)) {
            controller.toggle(MenuId.TAB);
            return true;
        }
        if (View.isMenuLabelHit(MenuId.SETTINGS, col)) {
            controller.toggle(MenuId.SETTINGS);
            return true;
        }
        if (View.isMenuLabelHit(MenuId.HELP, col)) {
            controller.toggle(MenuId.HELP);
            return true;
        }
        return false;
    }

    public PopupMouseResult handlePopupClick(int row, int col, int cols) {
        if (!controller.isOpen()) {
            return new PopupMouseResult(false, null);
        }
        PopupHit hit = View.hitPopup(
            row,
            col,
            cols,
            controller.active(),
            controller.activeItems()
        );
        if (hit.insideItemContent()) {
            if (!controller.isSelectable(hit.itemIndex())) {
                return new PopupMouseResult(true, null);
            }
            controller.setIndex(hit.itemIndex());
            return new PopupMouseResult(true, controller.handleMenuKey(KeyEvent.Action.ENTER));
        }
        if (hit.insidePopup()) {
            return new PopupMouseResult(true, null);
        }
        controller.close();
        return new PopupMouseResult(true, null);
    }

    public record PopupMouseResult(boolean consumed, KeyEvent.Action mappedAction) {
    }

    public record OpenTab(Path file, boolean dirty, boolean hasMatch) {
    }

    private enum MenuId {
        FILE,
        EDIT,
        TAB,
        VIEW,
        SETTINGS,
        HELP
    }

    private static final class Controller {
        private static final String NO_NAME_LABEL = "--no name--";
        private static final String SEPARATOR = "----------------";
        private static final int TAB_SEPARATOR_INDEX = 4;
        private static final int TAB_DYNAMIC_START_INDEX = 5;

        private static final String[] FILE_MENU_ITEMS = {"New", "Open...", "Save", "Save As...", "Quit"};
        private static final String[] EDIT_MENU_ITEMS = {"Undo", "Redo", "Cut", "Copy", "Paste", "Select All", "Find"};
        private static final String[] TAB_MENU_BASE_ITEMS = {"New Tab", "Close Tab", "Next Tab", "Previous Tab", SEPARATOR};
        private static final String[] HELP_MENU_ITEMS = {"Keyboard hints", "Licenses", "About"};
        private boolean open;
        private MenuId active = MenuId.EDIT;
        private int index;
        private int fileNameStartCol;
        private List<OpenTab> openTabs = List.of();
        private int activeTabIndex;
        private Integer pendingTabActivation;
        private boolean showTabBarEnabled = true;
        private boolean wrapLinesEnabled;
        private boolean wrapWordsEnabled;
        private boolean readingModeEnabled;
        private boolean confirmOnCloseEnabled = true;
        private boolean mouseModeEnabled = true;
        private boolean activeGutterHighlightEnabled = true;
        private boolean smartHomeEndEnabled = true;
        private boolean autoIndentEnabled = true;
        private boolean autoPairsEnabled = true;
        private boolean autoListEnabled = true;
        private boolean visibleWhitespaceEnabled = true;
        private boolean syntaxHighlightingEnabled = true;
        private boolean rainbowBracketsEnabled = true;
        private boolean autoSelectionHighlightEnabled = true;
        private GutterDiffMode gutterDiffMode = GutterDiffMode.MYERS;
        private int tabWidth = 4;
        private TabInsertMode tabInsertMode = TabInsertMode.AUTO;
        private String themeLabel = "Cyan";
        private int[] activeMnemonics = new int[0];

        private void setOpenTabs(List<OpenTab> tabs, int activeTabIndex) {
            if (tabs == null || tabs.isEmpty()) {
                this.openTabs = List.of();
            } else {
                this.openTabs = new ArrayList<>(tabs);
            }
            if (this.openTabs.isEmpty()) {
                this.activeTabIndex = 0;
            } else {
                this.activeTabIndex = Math.max(0, Math.min(activeTabIndex, this.openTabs.size() - 1));
            }
            if (open && active == MenuId.TAB) {
                rebuildMnemonics();
            }
        }

        private void setShowTabBarEnabled(boolean enabled) {
            this.showTabBarEnabled = enabled;
            if (open && active == MenuId.VIEW) {
                rebuildMnemonics();
            }
        }

        private void setWrapLinesEnabled(boolean enabled) {
            this.wrapLinesEnabled = enabled;
            if (open && active == MenuId.VIEW) {
                rebuildMnemonics();
            }
        }

        private void setWrapWordsEnabled(boolean enabled) {
            this.wrapWordsEnabled = enabled;
            if (open && active == MenuId.VIEW) {
                rebuildMnemonics();
            }
        }

        private void setReadingModeEnabled(boolean enabled) {
            this.readingModeEnabled = enabled;
            if (open && active == MenuId.SETTINGS) {
                rebuildMnemonics();
            }
        }

        private void setConfirmOnCloseEnabled(boolean enabled) {
            this.confirmOnCloseEnabled = enabled;
            if (open && active == MenuId.SETTINGS) {
                rebuildMnemonics();
            }
        }

        private void setMouseModeEnabled(boolean enabled) {
            this.mouseModeEnabled = enabled;
            if (open && active == MenuId.SETTINGS) {
                rebuildMnemonics();
            }
        }

        private void setActiveGutterHighlightEnabled(boolean enabled) {
            this.activeGutterHighlightEnabled = enabled;
            if (open && active == MenuId.VIEW) {
                rebuildMnemonics();
            }
        }

        private void setSmartHomeEndEnabled(boolean enabled) {
            this.smartHomeEndEnabled = enabled;
            if (open && active == MenuId.SETTINGS) {
                rebuildMnemonics();
            }
        }

        private void setAutoIndentEnabled(boolean enabled) {
            this.autoIndentEnabled = enabled;
            if (open && active == MenuId.SETTINGS) {
                rebuildMnemonics();
            }
        }

        private void setAutoPairsEnabled(boolean enabled) {
            this.autoPairsEnabled = enabled;
            if (open && active == MenuId.SETTINGS) {
                rebuildMnemonics();
            }
        }

        private void setAutoListEnabled(boolean enabled) {
            this.autoListEnabled = enabled;
            if (open && active == MenuId.SETTINGS) {
                rebuildMnemonics();
            }
        }

        private void setVisibleWhitespaceEnabled(boolean enabled) {
            this.visibleWhitespaceEnabled = enabled;
            if (open && active == MenuId.VIEW) {
                rebuildMnemonics();
            }
        }

        private void setSyntaxHighlightingEnabled(boolean enabled) {
            this.syntaxHighlightingEnabled = enabled;
            if (open && active == MenuId.VIEW) {
                rebuildMnemonics();
            }
        }

        private void setRainbowBracketsEnabled(boolean enabled) {
            this.rainbowBracketsEnabled = enabled;
            if (open && active == MenuId.VIEW) {
                rebuildMnemonics();
            }
        }

        private void setAutoSelectionHighlightEnabled(boolean enabled) {
            this.autoSelectionHighlightEnabled = enabled;
            if (open && active == MenuId.VIEW) {
                rebuildMnemonics();
            }
        }

        private void setGutterDiffMode(GutterDiffMode mode) {
            this.gutterDiffMode = mode == null ? GutterDiffMode.MYERS : mode;
            if (open && active == MenuId.VIEW) {
                rebuildMnemonics();
            }
        }

        private void setTabWidth(int tabWidth) {
            this.tabWidth = tabWidth <= 2 ? 2 : 4;
            if (open && active == MenuId.VIEW) {
                rebuildMnemonics();
            }
        }

        private void setTabInsertMode(TabInsertMode mode) {
            this.tabInsertMode = mode == null ? TabInsertMode.AUTO : mode;
            if (open && active == MenuId.SETTINGS) {
                rebuildMnemonics();
            }
        }

        private void setThemeLabel(String label) {
            this.themeLabel = label == null || label.isBlank() ? "Cyan" : label;
            if (open && active == MenuId.VIEW) {
                rebuildMnemonics();
            }
        }

        private Integer consumePendingTabActivation() {
            Integer value = pendingTabActivation;
            pendingTabActivation = null;
            return value;
        }

        private boolean isOpen() {
            return open;
        }

        private boolean isSelectable(int itemIndex) {
            return isSelectable(active, itemIndex);
        }

        private MenuId active() {
            return active;
        }

        private int selectedIndex() {
            return index;
        }

        private int fileNameStartCol() {
            return fileNameStartCol;
        }

        private MenuItem[] activeItems() {
            return itemsFor(active);
        }

        private void toggle(MenuId menu) {
            boolean sameMenuOpen = open && active == menu;
            active = menu;
            open = !sameMenuOpen;
            if (open) {
                index = firstSelectableIndex(active);
                rebuildMnemonics();
            } else {
                activeMnemonics = new int[0];
            }
        }

        private void open(MenuId menu) {
            active = menu;
            open = true;
            index = firstSelectableIndex(active);
            rebuildMnemonics();
        }

        private void close() {
            open = false;
            activeMnemonics = new int[0];
        }

        private void setIndex(int idx) {
            MenuItem[] items = activeItems();
            if (items.length == 0) {
                index = 0;
                return;
            }
            int clamped = Math.max(0, Math.min(idx, items.length - 1));
            if (isSelectable(active, clamped)) {
                index = clamped;
            }
        }

        private KeyEvent.Action handleMenuKey(KeyEvent.Action action) {
            switch (action) {
                case ESCAPE -> open = false;
                case CTRL_E -> {
                    active = MenuId.EDIT;
                    index = firstSelectableIndex(active);
                    rebuildMnemonics();
                }
                case F10 -> {
                    active = MenuId.FILE;
                    index = firstSelectableIndex(active);
                    rebuildMnemonics();
                }
                case CTRL_T -> {
                    active = MenuId.TAB;
                    index = firstSelectableIndex(active);
                    rebuildMnemonics();
                }
                case OPEN_VIEW_MENU -> {
                    active = MenuId.VIEW;
                    index = firstSelectableIndex(active);
                    rebuildMnemonics();
                }
                case F2 -> {
                    active = MenuId.SETTINGS;
                    index = firstSelectableIndex(active);
                    rebuildMnemonics();
                }
                case OPEN_HELP_MENU -> {
                    active = MenuId.HELP;
                    index = firstSelectableIndex(active);
                    rebuildMnemonics();
                }
                case ARROW_LEFT -> {
                    active = previousMenu(active);
                    index = firstSelectableIndex(active);
                    rebuildMnemonics();
                }
                case ARROW_RIGHT -> {
                    active = nextMenu(active);
                    index = firstSelectableIndex(active);
                    rebuildMnemonics();
                }
                case ARROW_UP -> index = moveSelection(-1);
                case ARROW_DOWN -> index = moveSelection(1);
                case ENTER -> {
                    if (!isSelectable(active, index)) {
                        return null;
                    }
                    KeyEvent.Action mapped = mapSelection(active, index);
                    open = false;
                    return mapped;
                }
                default -> {
                    // Keep focus in menu until explicit close/action.
                }
            }
            return null;
        }

        private KeyEvent.Action handleMenuChar(char ch) {
            if (!open) {
                return null;
            }
            if (ch >= '1' && ch <= '9') {
                int target = ch - '0';
                int selectableIndex = findSelectableByOrdinal(target);
                if (selectableIndex >= 0) {
                    index = selectableIndex;
                    return handleMenuKey(KeyEvent.Action.ENTER);
                }
                return null;
            }
            MenuItem[] items = activeItems();
            char target = Character.toLowerCase(ch);
            for (int i = 0; i < items.length; i++) {
                if (!isSelectable(active, i) || mnemonicAt(i) < 0) {
                    continue;
                }
                char key = Character.toLowerCase(items[i].text().charAt(mnemonicAt(i)));
                if (key == target) {
                    index = i;
                    return handleMenuKey(KeyEvent.Action.ENTER);
                }
            }
            return null;
        }

        private int findSelectableByOrdinal(int ordinal) {
            if (ordinal <= 0) {
                return -1;
            }
            MenuItem[] items = activeItems();
            int count = 0;
            for (int i = 0; i < items.length; i++) {
                if (!isSelectable(active, i)) {
                    continue;
                }
                count++;
                if (count == ordinal) {
                    return i;
                }
            }
            return -1;
        }

        private int moveSelection(int delta) {
            MenuItem[] items = activeItems();
            if (items.length == 0) {
                return 0;
            }
            int next = index;
            for (int i = 0; i < items.length; i++) {
                next = Math.floorMod(next + delta, items.length);
                if (isSelectable(active, next)) {
                    return next;
                }
            }
            return index;
        }

        private int selectedMnemonicIndex() {
            if (index < 0) {
                return 1;
            }
            int mnemonic = mnemonicAt(index);
            return mnemonic >= 0 ? mnemonic : 1;
        }

        private int[] activeMnemonics() {
            return activeMnemonics.clone();
        }

        private int mnemonicAt(int itemIndex) {
            if (itemIndex < 0 || itemIndex >= activeMnemonics.length) {
                return -1;
            }
            return activeMnemonics[itemIndex];
        }

        private void rebuildMnemonics() {
            activeMnemonics = mnemonicIndexes(activeItems());
        }

        private int[] mnemonicIndexes(MenuItem[] items) {
            int[] result = new int[items.length];
            Set<Character> used = new HashSet<>();
            for (int i = 0; i < items.length; i++) {
                result[i] = findMnemonicIndex(items[i], used);
            }
            return result;
        }

        private static int findMnemonicIndex(MenuItem item, Set<Character> used) {
            if (item == null || !item.selectable()) {
                return -1;
            }
            String text = item.text();
            int start = textStartIndex(text);
            List<Integer> candidates = new ArrayList<>();
            for (int i = start; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (!Character.isLetter(ch)) {
                    continue;
                }
                if (i == start || !Character.isLetter(text.charAt(i - 1))) {
                    candidates.add(i);
                }
            }
            for (int i = start; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (Character.isLetter(ch)) {
                    candidates.add(i);
                }
            }
            for (int pos : candidates) {
                char key = Character.toLowerCase(text.charAt(pos));
                if (used.add(key)) {
                    return pos;
                }
            }
            return -1;
        }

        private static int textStartIndex(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            if (text.startsWith("[")) {
                int close = text.indexOf("] ");
                if (close >= 0 && close + 2 < text.length()) {
                    return close + 2;
                }
            }
            return 0;
        }

        private String buildTopBar(Path currentFile, boolean dirty, int activeTabIndex, int totalTabs, int cols, boolean readingMode) {
            String fileMenu = (open && active == MenuId.FILE) ? "[File]" : " File ";
            String editMenu = (open && active == MenuId.EDIT) ? "[Edit]" : " Edit ";
            String viewMenu = (open && active == MenuId.VIEW) ? "[View]" : " View ";
            String tabMenu = (open && active == MenuId.TAB) ? "[Tab]" : " Tab ";
            String settingsMenu = (open && active == MenuId.SETTINGS) ? "[Settings]" : " Settings ";
            String helpMenu = (open && active == MenuId.HELP) ? "[Help]" : " Help ";
            String file = currentFile == null ? NO_NAME_LABEL : currentFile.toString();
            String dirtyMarker = dirty ? " *" : "";
            String left = fileMenu + " " + editMenu + " " + viewMenu + " " + tabMenu + " "
                + settingsMenu + " " + helpMenu + " | ";
            fileNameStartCol = left.length();
            String base = left + file + dirtyMarker;
            String right = readingMode ? " READ " : "";
            if (right.isEmpty()) {
                if (base.length() > cols) {
                    return base.substring(0, cols);
                }
                return base + " ".repeat(cols - base.length());
            }

            int rightLen = right.length();
            if (cols <= rightLen) {
                return right.substring(rightLen - cols);
            }

            int leftSpace = cols - rightLen;
            String leftPart = base.length() > leftSpace ? base.substring(0, leftSpace) : base + " ".repeat(leftSpace - base.length());
            return leftPart + right;
        }

        private MenuItem[] itemsFor(MenuId menu) {
            if (menu == MenuId.FILE) {
                return toMenuItems(FILE_MENU_ITEMS);
            }
            if (menu == MenuId.EDIT) {
                return toMenuItems(EDIT_MENU_ITEMS);
            }
            if (menu == MenuId.VIEW) {
                String wrapWordsLabel = "[" + (wrapWordsEnabled ? "x" : " ") + "] Wrap by words";
                if (!wrapLinesEnabled) {
                    wrapWordsLabel += " (disabled)";
                }
                return new MenuItem[] {
                    new MenuItem("[" + (showTabBarEnabled ? "x" : " ") + "] Show Tab Bar", true),
                    new MenuItem("[" + (wrapLinesEnabled ? "x" : " ") + "] Wrap Lines", true),
                    new MenuItem(wrapWordsLabel, wrapLinesEnabled),
                    new MenuItem(SEPARATOR, false),
                    new MenuItem("[" + (activeGutterHighlightEnabled ? "x" : " ") + "] Highlight current line number", true),
                    new MenuItem("[" + (visibleWhitespaceEnabled ? "x" : " ") + "] Visible tabs/spaces", true),
                    new MenuItem(SEPARATOR, false),
                    new MenuItem("[" + (syntaxHighlightingEnabled ? "x" : " ") + "] Syntax highlighting", true),
                    new MenuItem("[" + (rainbowBracketsEnabled ? "x" : " ") + "] Rainbow brackets", true),
                    new MenuItem("[" + (autoSelectionHighlightEnabled ? "x" : " ") + "] Auto highlight selection", true),
                    new MenuItem("[>] Gutter diff: " + gutterDiffMode.label(), true),
                    new MenuItem(SEPARATOR, false),
                    new MenuItem("[" + (tabWidth <= 2 ? "x" : " ") + "] Thin tabs", true),
                    new MenuItem("[>] Theme: " + themeLabel, true)
                };
            }
            if (menu == MenuId.SETTINGS) {
                String autoIndentLabel = "[" + (autoIndentEnabled ? "x" : " ") + "] Auto indent";
                String autoPairsLabel = "[" + (autoPairsEnabled ? "x" : " ") + "] Autopairs";
                String autoListLabel = "[" + (autoListEnabled ? "x" : " ") + "] Auto list";
                String tabInsertLabel = "[>] Tab insert mode: " + tabInsertMode.label();
                return new MenuItem[] {
                    new MenuItem("[" + (readingModeEnabled ? "x" : " ") + "] Reading mode", true),
                    new MenuItem("[" + (mouseModeEnabled ? "x" : " ") + "] Mouse mode", true),
                    new MenuItem("[" + (confirmOnCloseEnabled ? "x" : " ") + "] Confirm on close", true),
                    new MenuItem(SEPARATOR, false),
                    new MenuItem("[" + (smartHomeEndEnabled ? "x" : " ") + "] Smart Home/End", true),
                    new MenuItem(autoIndentLabel, true),
                    new MenuItem(autoPairsLabel, true),
                    new MenuItem(autoListLabel, true),
                    new MenuItem(tabInsertLabel, true),
                };
            }
            if (menu == MenuId.HELP) {
                return toMenuItems(HELP_MENU_ITEMS);
            }
            List<MenuItem> items = new ArrayList<>();
            for (String base : TAB_MENU_BASE_ITEMS) {
                boolean selectable = !SEPARATOR.equals(base);
                items.add(new MenuItem(base, selectable));
            }
            for (int i = 0; i < openTabs.size(); i++) {
                items.add(new MenuItem(tabLabel(openTabs.get(i), i), true));
            }
            return items.toArray(MenuItem[]::new);
        }

        private boolean isSelectable(MenuId menu, int itemIndex) {
            MenuItem[] items = itemsFor(menu);
            if (itemIndex < 0 || itemIndex >= items.length) {
                return false;
            }
            return items[itemIndex].selectable();
        }

        private int firstSelectableIndex(MenuId menu) {
            MenuItem[] items = itemsFor(menu);
            for (int i = 0; i < items.length; i++) {
                if (isSelectable(menu, i)) {
                    return i;
                }
            }
            return 0;
        }

        private static MenuItem[] toMenuItems(String[] labels) {
            MenuItem[] items = new MenuItem[labels.length];
            for (int i = 0; i < labels.length; i++) {
                items[i] = new MenuItem(labels[i], true);
            }
            return items;
        }

        private KeyEvent.Action mapSelection(MenuId menu, int itemIndex) {
            if (menu == MenuId.FILE) {
                return switch (itemIndex) {
                    case 0 -> KeyEvent.Action.TAB_NEW;
                    case 1 -> KeyEvent.Action.CTRL_O;
                    case 2 -> KeyEvent.Action.CTRL_S;
                    case 3 -> KeyEvent.Action.CTRL_K;
                    case 4 -> KeyEvent.Action.CTRL_Q;
                    default -> null;
                };
            }
            if (menu == MenuId.EDIT) {
                return switch (itemIndex) {
                    case 0 -> KeyEvent.Action.CTRL_Z;
                    case 1 -> KeyEvent.Action.CTRL_Y;
                    case 2 -> KeyEvent.Action.CTRL_X;
                    case 3 -> KeyEvent.Action.CTRL_C;
                    case 4 -> KeyEvent.Action.CTRL_V;
                    case 5 -> KeyEvent.Action.CTRL_A;
                    case 6 -> KeyEvent.Action.CTRL_F;
                    default -> null;
                };
            }
            if (menu == MenuId.VIEW) {
                return switch (itemIndex) {
                    case 0 -> KeyEvent.Action.TOGGLE_TAB_BAR;
                    case 1 -> KeyEvent.Action.TOGGLE_WRAP_LINES;
                    case 2 -> KeyEvent.Action.TOGGLE_WORD_WRAP;
                    case 4 -> KeyEvent.Action.TOGGLE_ACTIVE_GUTTER_HIGHLIGHT;
                    case 5 -> KeyEvent.Action.TOGGLE_VISIBLE_WHITESPACE;
                    case 7 -> KeyEvent.Action.TOGGLE_SYNTAX_HIGHLIGHTING;
                    case 8 -> KeyEvent.Action.TOGGLE_RAINBOW_BRACKETS;
                    case 9 -> KeyEvent.Action.TOGGLE_AUTO_SELECTION_HIGHLIGHT;
                    case 10 -> KeyEvent.Action.TOGGLE_GUTTER_DIFF;
                    case 12 -> KeyEvent.Action.TOGGLE_TAB_WIDTH;
                    case 13 -> KeyEvent.Action.TOGGLE_THEME;
                    default -> null;
                };
            }
            if (menu == MenuId.SETTINGS) {
                return switch (itemIndex) {
                    case 0 -> KeyEvent.Action.TOGGLE_READING_MODE;
                    case 1 -> KeyEvent.Action.TOGGLE_MOUSE_MODE;
                    case 2 -> KeyEvent.Action.TOGGLE_CONFIRM_ON_CLOSE;
                    case 4 -> KeyEvent.Action.TOGGLE_SMART_HOME_END;
                    case 5 -> KeyEvent.Action.TOGGLE_AUTO_INDENT;
                    case 6 -> KeyEvent.Action.TOGGLE_AUTO_PAIRS;
                    case 7 -> KeyEvent.Action.TOGGLE_AUTO_LIST;
                    case 8 -> KeyEvent.Action.TOGGLE_TAB_INSERT_MODE;
                    default -> null;
                };
            }
            if (menu == MenuId.HELP) {
                return switch (itemIndex) {
                    case 0 -> KeyEvent.Action.SHOW_KEY_HINTS;
                    case 1 -> KeyEvent.Action.SHOW_LICENSES;
                    case 2 -> KeyEvent.Action.SHOW_AUTHOR;
                    default -> null;
                };
            }
            return mapTabSelection(itemIndex);
        }

        private KeyEvent.Action mapTabSelection(int itemIndex) {
            return switch (itemIndex) {
                case 0 -> KeyEvent.Action.TAB_NEW;
                case 1 -> KeyEvent.Action.TAB_CLOSE;
                case 2 -> KeyEvent.Action.TAB_NEXT;
                case 3 -> KeyEvent.Action.TAB_PREVIOUS;
                default -> {
                    if (itemIndex >= TAB_DYNAMIC_START_INDEX) {
                        int tabIndex = itemIndex - TAB_DYNAMIC_START_INDEX;
                        if (tabIndex >= 0 && tabIndex < openTabs.size()) {
                            pendingTabActivation = tabIndex;
                        }
                    }
                    yield null;
                }
            };
        }

        private String tabLabel(OpenTab tab, int tabIndex) {
            if (tab == null) {
                return NO_NAME_LABEL;
            }
            String marker = tabIndex == activeTabIndex ? "> " : "   ";
            return marker + TabBar.formatTabTitle(tab.file(), tab.dirty(), tab.hasMatch());
        }

        private static MenuId nextMenu(MenuId menu) {
            return switch (menu) {
                case FILE -> MenuId.EDIT;
                case EDIT -> MenuId.VIEW;
                case VIEW -> MenuId.TAB;
                case TAB -> MenuId.SETTINGS;
                case SETTINGS -> MenuId.HELP;
                case HELP -> MenuId.FILE;
            };
        }

        private static MenuId previousMenu(MenuId menu) {
            return switch (menu) {
                case FILE -> MenuId.HELP;
                case EDIT -> MenuId.FILE;
                case VIEW -> MenuId.EDIT;
                case TAB -> MenuId.VIEW;
                case SETTINGS -> MenuId.TAB;
                case HELP -> MenuId.SETTINGS;
            };
        }
    }

    private static final class View {
        private static final int FILE_START_COL = 0;
        private static final int EDIT_START_COL = 7;
        private static final int VIEW_START_COL = 14;
        private static final int TAB_START_COL = 21;
        private static final int SETTINGS_START_COL = 27;
        private static final int HELP_START_COL = 38;
        private static final int FILE_LABEL_WIDTH = 6;
        private static final int EDIT_LABEL_WIDTH = 6;
        private static final int TAB_LABEL_WIDTH = 5;
        private static final int VIEW_LABEL_WIDTH = 6;
        private static final int SETTINGS_LABEL_WIDTH = 10;
        private static final int HELP_LABEL_WIDTH = 6;
        private static final int POPUP_TOP_ROW = 1;

        private View() {
        }

        private static boolean isMenuLabelHit(MenuId menu, int col) {
            int start = startCol(menu);
            int width = labelWidth(menu);
            return col >= start && col < start + width;
        }

        private static PopupHit hitPopup(int row, int col, int cols, MenuId menu, MenuItem[] items) {
            ContextMenu.Hit hit = ContextMenu.hit(row, col, cols, startCol(menu), POPUP_TOP_ROW, items);
            return new PopupHit(hit.insidePopup(), hit.insideItemContent(), hit.itemIndex());
        }

        private static void drawPopup(
            CellBuffer buffer,
            int cols,
            boolean visible,
            MenuId menu,
            int selectedIndex,
            MenuItem[] items,
            int[] mnemonicIndexes,
            boolean colorEnabled,
            Style popupStyle,
            Style selectedStyle,
            Style selectedMonoStyle
        ) {
            ContextMenu.draw(
                buffer,
                cols,
                visible,
                startCol(menu),
                POPUP_TOP_ROW,
                selectedIndex,
                items,
                mnemonicIndexes,
                colorEnabled,
                popupStyle,
                selectedStyle,
                selectedMonoStyle
            );
        }

        private static void drawTopSelection(
            CellBuffer buffer,
            int cols,
            boolean menuOpen,
            MenuId active,
            boolean colorEnabled,
            Style selectedStyle,
            Style selectedMonoStyle
        ) {
            if (!menuOpen || buffer.rows() < 1 || cols < 1) {
                return;
            }
            Style style = colorEnabled ? selectedStyle : selectedMonoStyle;
            String label = switch (active) {
                case FILE -> " File ";
                case EDIT -> " Edit ";
                case VIEW -> " View ";
                case TAB -> " Tab ";
                case SETTINGS -> " Settings ";
                case HELP -> " Help ";
            };
            drawLine(buffer, 0, startCol(active), label, style, cols);
        }

        private static void drawTopMenuMnemonics(CellBuffer buffer, int cols) {
            if (buffer.rows() < 1 || cols < 1) {
                return;
            }
            underlineTopMnemonic(buffer, cols, MenuId.FILE, 2);     // Alt+I
            underlineTopMnemonic(buffer, cols, MenuId.EDIT, 1);     // Alt+E
            underlineTopMnemonic(buffer, cols, MenuId.VIEW, 1);     // Alt+V
            underlineTopMnemonic(buffer, cols, MenuId.TAB, 1);      // Alt+T
            underlineTopMnemonic(buffer, cols, MenuId.SETTINGS, 1); // Alt+S
            underlineTopMnemonic(buffer, cols, MenuId.HELP, 1);     // Alt+H
        }

        private static void underlineTopMnemonic(CellBuffer buffer, int cols, MenuId menu, int offset) {
            int col = startCol(menu) + offset;
            if (col < 0 || col >= cols) {
                return;
            }
            Cell cell = buffer.get(0, col);
            if (cell == null) {
                return;
            }
            Style style = cell.style();
            if (style == null || style.underline()) {
                return;
            }
            buffer.set(
                0,
                col,
                cell.grapheme(),
                new Style(style.fg(), style.bg(), style.bold(), true)
            );
        }

        private static int startCol(MenuId menu) {
            return switch (menu) {
                case FILE -> FILE_START_COL;
                case EDIT -> EDIT_START_COL;
                case TAB -> TAB_START_COL;
                case VIEW -> VIEW_START_COL;
                case SETTINGS -> SETTINGS_START_COL;
                case HELP -> HELP_START_COL;
            };
        }

        private static int popupContentCol(MenuId menu) {
            // Cursor addressing is 1-based; content text starts at buffer col (start + 2).
            return startCol(menu) + 3;
        }

        private static int labelWidth(MenuId menu) {
            return switch (menu) {
                case FILE -> FILE_LABEL_WIDTH;
                case EDIT -> EDIT_LABEL_WIDTH;
                case TAB -> TAB_LABEL_WIDTH;
                case VIEW -> VIEW_LABEL_WIDTH;
                case SETTINGS -> SETTINGS_LABEL_WIDTH;
                case HELP -> HELP_LABEL_WIDTH;
            };
        }

        private static void drawLine(CellBuffer buffer, int row, int startCol, String text, Style style, int cols) {
            for (int i = 0; i < text.length() && startCol + i < cols; i++) {
                buffer.set(row, startCol + i, String.valueOf(text.charAt(i)), style);
            }
        }
    }

    private record PopupHit(boolean insidePopup, boolean insideItemContent, int itemIndex) {
    }
}
