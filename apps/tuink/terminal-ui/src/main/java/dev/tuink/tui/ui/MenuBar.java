package dev.tuink.tui.ui;

import dev.tuink.render.Style;
import dev.tuink.tui.KeyEvent;
import dev.tuink.tui.render.CellBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * UI element for the top menu bar and its dropdown popup.
 */
public final class MenuBar {
    private final Controller controller = new Controller();
    private final Style popupStyle;
    private final Style selectedStyle;
    private final Style selectedMonoStyle;

    public MenuBar(Style popupStyle, Style selectedStyle, Style selectedMonoStyle) {
        this.popupStyle = popupStyle;
        this.selectedStyle = selectedStyle;
        this.selectedMonoStyle = selectedMonoStyle;
    }

    public void setOpenTabs(List<OpenTab> openTabs, int activeTabIndex) {
        controller.setOpenTabs(openTabs, activeTabIndex);
    }

    public void setShowTabBarEnabled(boolean enabled) {
        controller.setShowTabBarEnabled(enabled);
    }

    public Integer consumeTabActivationRequest() {
        return controller.consumePendingTabActivation();
    }

    public String buildTopBar(Path currentFile, boolean dirty, int activeTabIndex, int totalTabs, int cols) {
        return controller.buildTopBar(currentFile, dirty, activeTabIndex, totalTabs, cols);
    }

    public int fileNameStartCol() {
        return controller.fileNameStartCol();
    }

    public int popupCursorCol() {
        return switch (controller.active()) {
            case FILE -> 3;
            case EDIT -> 10;
            case TAB -> 17;
            case SETTINGS -> 24;
        };
    }

    public void draw(CellBuffer buffer, int cols, boolean colorEnabled) {
        String[] activeItems = controller.activeItems();
        View.drawPopup(
            buffer,
            cols,
            controller.isOpen(),
            controller.active(),
            controller.selectedIndex(),
            activeItems,
            colorEnabled,
            popupStyle,
            selectedStyle,
            selectedMonoStyle
        );
        View.drawTopSelection(
            buffer,
            cols,
            controller.isOpen(),
            controller.active(),
            colorEnabled,
            selectedStyle,
            selectedMonoStyle
        );
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

    public void openSettings() {
        controller.open(MenuId.SETTINGS);
    }

    public void close() {
        controller.close();
    }

    public KeyEvent.Action handleKey(KeyEvent.Action action) {
        return controller.handleMenuKey(action);
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
        if (View.isMenuLabelHit(MenuId.TAB, col)) {
            controller.toggle(MenuId.TAB);
            return true;
        }
        if (View.isMenuLabelHit(MenuId.SETTINGS, col)) {
            controller.toggle(MenuId.SETTINGS);
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
        SETTINGS
    }

    private static final class Controller {
        private static final String NO_NAME_LABEL = "--no name--";
        private static final String SEPARATOR = "----------------";
        private static final int TAB_SEPARATOR_INDEX = 4;
        private static final int TAB_DYNAMIC_START_INDEX = 5;

        private static final String[] FILE_MENU_ITEMS = {"New", "Open...", "Save", "Save As...", "Quit"};
        private static final String[] EDIT_MENU_ITEMS = {"Undo", "Redo", "Cut", "Copy", "Paste", "Find"};
        private static final String[] TAB_MENU_BASE_ITEMS = {"New Tab", "Close Tab", "Next Tab", "Previous Tab", SEPARATOR};
        private static final String[] SETTINGS_MENU_ITEMS_ON = {"[x] Show Tab Bar"};
        private static final String[] SETTINGS_MENU_ITEMS_OFF = {"[ ] Show Tab Bar"};

        private boolean open;
        private MenuId active = MenuId.EDIT;
        private int index;
        private int fileNameStartCol;
        private List<OpenTab> openTabs = List.of();
        private int activeTabIndex;
        private Integer pendingTabActivation;
        private boolean showTabBarEnabled = true;

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
        }

        private void setShowTabBarEnabled(boolean enabled) {
            this.showTabBarEnabled = enabled;
        }

        private Integer consumePendingTabActivation() {
            Integer value = pendingTabActivation;
            pendingTabActivation = null;
            return value;
        }

        private boolean isOpen() {
            return open;
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

        private String[] activeItems() {
            return itemsFor(active);
        }

        private void toggle(MenuId menu) {
            boolean sameMenuOpen = open && active == menu;
            active = menu;
            open = !sameMenuOpen;
            if (open) {
                index = firstSelectableIndex(active);
            }
        }

        private void open(MenuId menu) {
            active = menu;
            open = true;
            index = firstSelectableIndex(active);
        }

        private void close() {
            open = false;
        }

        private void setIndex(int idx) {
            String[] items = activeItems();
            if (items.length == 0) {
                index = 0;
                return;
            }
            index = Math.max(0, Math.min(idx, items.length - 1));
        }

        private KeyEvent.Action handleMenuKey(KeyEvent.Action action) {
            switch (action) {
                case ESCAPE -> open = false;
                case CTRL_E -> {
                    active = MenuId.EDIT;
                    index = firstSelectableIndex(active);
                }
                case F10 -> {
                    active = MenuId.FILE;
                    index = firstSelectableIndex(active);
                }
                case CTRL_T -> {
                    active = MenuId.TAB;
                    index = firstSelectableIndex(active);
                }
                case F2 -> {
                    active = MenuId.SETTINGS;
                    index = firstSelectableIndex(active);
                }
                case ARROW_LEFT -> {
                    active = previousMenu(active);
                    index = firstSelectableIndex(active);
                }
                case ARROW_RIGHT -> {
                    active = nextMenu(active);
                    index = firstSelectableIndex(active);
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

        private int moveSelection(int delta) {
            String[] items = activeItems();
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

        private String buildTopBar(Path currentFile, boolean dirty, int activeTabIndex, int totalTabs, int cols) {
            String fileMenu = (open && active == MenuId.FILE) ? "[File]" : " File ";
            String editMenu = (open && active == MenuId.EDIT) ? "[Edit]" : " Edit ";
            String tabMenu = (open && active == MenuId.TAB) ? "[Tab]" : " Tab ";
            String settingsMenu = (open && active == MenuId.SETTINGS) ? "[Settings]" : " Settings ";
            String tabMarker = "[" + (activeTabIndex + 1) + "/" + Math.max(1, totalTabs) + "]";
            String file = currentFile == null ? NO_NAME_LABEL : currentFile.toString();
            String dirtyMarker = dirty ? " *" : "";
            String left = fileMenu + " " + editMenu + " " + tabMenu + " " + settingsMenu + " " + tabMarker + " ";
            fileNameStartCol = left.length();
            String top = left + file + dirtyMarker;
            if (top.length() > cols) {
                return top.substring(0, cols);
            }
            return top + " ".repeat(cols - top.length());
        }

        private String[] itemsFor(MenuId menu) {
            if (menu == MenuId.FILE) {
                return FILE_MENU_ITEMS;
            }
            if (menu == MenuId.EDIT) {
                return EDIT_MENU_ITEMS;
            }
            if (menu == MenuId.SETTINGS) {
                return showTabBarEnabled ? SETTINGS_MENU_ITEMS_ON : SETTINGS_MENU_ITEMS_OFF;
            }
            List<String> items = new ArrayList<>();
            items.addAll(List.of(TAB_MENU_BASE_ITEMS));
            for (OpenTab tab : openTabs) {
                items.add(tabLabel(tab));
            }
            return items.toArray(String[]::new);
        }

        private boolean isSelectable(MenuId menu, int itemIndex) {
            if (menu != MenuId.TAB) {
                return true;
            }
            return itemIndex != TAB_SEPARATOR_INDEX;
        }

        private int firstSelectableIndex(MenuId menu) {
            String[] items = itemsFor(menu);
            for (int i = 0; i < items.length; i++) {
                if (isSelectable(menu, i)) {
                    return i;
                }
            }
            return 0;
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
                    case 5 -> KeyEvent.Action.CTRL_F;
                    default -> null;
                };
            }
            if (menu == MenuId.SETTINGS) {
                return itemIndex == 0 ? KeyEvent.Action.TOGGLE_TAB_BAR : null;
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

        private String tabLabel(OpenTab tab) {
            if (tab == null) {
                return NO_NAME_LABEL;
            }
            return TabBar.formatTabTitle(tab.file(), tab.dirty(), tab.hasMatch());
        }

        private static MenuId nextMenu(MenuId menu) {
            return switch (menu) {
                case FILE -> MenuId.EDIT;
                case EDIT -> MenuId.TAB;
                case TAB -> MenuId.SETTINGS;
                case SETTINGS -> MenuId.FILE;
            };
        }

        private static MenuId previousMenu(MenuId menu) {
            return switch (menu) {
                case FILE -> MenuId.SETTINGS;
                case EDIT -> MenuId.FILE;
                case TAB -> MenuId.EDIT;
                case SETTINGS -> MenuId.TAB;
            };
        }
    }

    private static final class View {
        private static final int FILE_START_COL = 0;
        private static final int EDIT_START_COL = 7;
        private static final int TAB_START_COL = 14;
        private static final int SETTINGS_START_COL = 20;
        private static final int FILE_LABEL_WIDTH = 6;
        private static final int EDIT_LABEL_WIDTH = 6;
        private static final int TAB_LABEL_WIDTH = 5;
        private static final int SETTINGS_LABEL_WIDTH = 10;
        private static final int POPUP_TOP_ROW = 1;

        private View() {
        }

        private static boolean isMenuLabelHit(MenuId menu, int col) {
            int start = startCol(menu);
            int width = labelWidth(menu);
            return col >= start && col < start + width;
        }

        private static PopupHit hitPopup(int row, int col, int cols, MenuId menu, String[] items) {
            ContextMenu.Hit hit = ContextMenu.hit(row, col, cols, startCol(menu), POPUP_TOP_ROW, items);
            return new PopupHit(hit.insidePopup(), hit.insideItemContent(), hit.itemIndex());
        }

        private static void drawPopup(
            CellBuffer buffer,
            int cols,
            boolean visible,
            MenuId menu,
            int selectedIndex,
            String[] items,
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
                case TAB -> " Tab ";
                case SETTINGS -> " Settings ";
            };
            drawLine(buffer, 0, startCol(active), label, style, cols);
        }

        private static int startCol(MenuId menu) {
            return switch (menu) {
                case FILE -> FILE_START_COL;
                case EDIT -> EDIT_START_COL;
                case TAB -> TAB_START_COL;
                case SETTINGS -> SETTINGS_START_COL;
            };
        }

        private static int labelWidth(MenuId menu) {
            return switch (menu) {
                case FILE -> FILE_LABEL_WIDTH;
                case EDIT -> EDIT_LABEL_WIDTH;
                case TAB -> TAB_LABEL_WIDTH;
                case SETTINGS -> SETTINGS_LABEL_WIDTH;
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
