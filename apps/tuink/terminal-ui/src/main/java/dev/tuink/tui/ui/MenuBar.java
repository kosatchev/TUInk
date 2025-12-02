package dev.tuink.tui.ui;

import dev.tuink.render.Style;
import dev.tuink.tui.KeyEvent;
import dev.tuink.tui.render.CellBuffer;
import java.nio.file.Path;

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

    private enum MenuId {
        FILE,
        EDIT,
        TAB
    }

    private static final class Controller {
        private static final String[] FILE_MENU_ITEMS = {"Open...", "Save", "Save As...", "Quit"};
        private static final String[] EDIT_MENU_ITEMS = {"Undo", "Redo", "Cut", "Copy", "Paste"};
        private static final String[] TAB_MENU_ITEMS = {"New Tab", "Close Tab", "Next Tab", "Previous Tab"};

        private boolean open;
        private MenuId active = MenuId.EDIT;
        private int index;
        private int fileNameStartCol;

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
                index = 0;
            }
        }

        private void open(MenuId menu) {
            active = menu;
            open = true;
            index = 0;
        }

        private void close() {
            open = false;
        }

        private void setIndex(int idx) {
            index = Math.max(0, Math.min(idx, activeItems().length - 1));
        }

        private KeyEvent.Action handleMenuKey(KeyEvent.Action action) {
            switch (action) {
                case ESCAPE -> open = false;
                case CTRL_E -> {
                    active = MenuId.EDIT;
                    index = 0;
                }
                case F10 -> {
                    active = MenuId.FILE;
                    index = 0;
                }
                case CTRL_T -> {
                    active = MenuId.TAB;
                    index = 0;
                }
                case ARROW_LEFT -> {
                    active = previousMenu(active);
                    index = Math.min(index, itemsFor(active).length - 1);
                }
                case ARROW_RIGHT -> {
                    active = nextMenu(active);
                    index = Math.min(index, itemsFor(active).length - 1);
                }
                case ARROW_UP -> {
                    String[] items = itemsFor(active);
                    index = (index + items.length - 1) % items.length;
                }
                case ARROW_DOWN -> {
                    String[] items = itemsFor(active);
                    index = (index + 1) % items.length;
                }
                case ENTER -> {
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

        private String buildTopBar(Path currentFile, boolean dirty, int activeTabIndex, int totalTabs, int cols) {
            String fileMenu = (open && active == MenuId.FILE) ? "[File]" : " File ";
            String editMenu = (open && active == MenuId.EDIT) ? "[Edit]" : " Edit ";
            String tabMenu = (open && active == MenuId.TAB) ? "[Tab]" : " Tab ";
            String tabMarker = "[" + (activeTabIndex + 1) + "/" + Math.max(1, totalTabs) + "]";
            String file = currentFile == null ? "[No Name]" : currentFile.toString();
            String dirtyMarker = dirty ? " [+]" : "";
            String left = fileMenu + " " + editMenu + " " + tabMenu + " " + tabMarker + " ";
            fileNameStartCol = left.length();
            String top = left + file + dirtyMarker;
            if (top.length() > cols) {
                return top.substring(0, cols);
            }
            return top + " ".repeat(cols - top.length());
        }

        private static String[] itemsFor(MenuId menu) {
            return switch (menu) {
                case FILE -> FILE_MENU_ITEMS;
                case EDIT -> EDIT_MENU_ITEMS;
                case TAB -> TAB_MENU_ITEMS;
            };
        }

        private static KeyEvent.Action mapSelection(MenuId menu, int itemIndex) {
            if (menu == MenuId.FILE) {
                return switch (itemIndex) {
                    case 0 -> KeyEvent.Action.CTRL_O;
                    case 1 -> KeyEvent.Action.CTRL_S;
                    case 2 -> KeyEvent.Action.CTRL_K;
                    case 3 -> KeyEvent.Action.CTRL_Q;
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
                    default -> null;
                };
            }
            return switch (itemIndex) {
                case 0 -> KeyEvent.Action.TAB_NEW;
                case 1 -> KeyEvent.Action.TAB_CLOSE;
                case 2 -> KeyEvent.Action.TAB_NEXT;
                case 3 -> KeyEvent.Action.TAB_PREVIOUS;
                default -> null;
            };
        }

        private static MenuId nextMenu(MenuId menu) {
            return switch (menu) {
                case FILE -> MenuId.EDIT;
                case EDIT -> MenuId.TAB;
                case TAB -> MenuId.FILE;
            };
        }

        private static MenuId previousMenu(MenuId menu) {
            return switch (menu) {
                case FILE -> MenuId.TAB;
                case EDIT -> MenuId.FILE;
                case TAB -> MenuId.EDIT;
            };
        }
    }

    private static final class View {
        private static final int FILE_START_COL = 0;
        private static final int EDIT_START_COL = 7;
        private static final int TAB_START_COL = 14;
        private static final int FILE_LABEL_WIDTH = 6;
        private static final int EDIT_LABEL_WIDTH = 6;
        private static final int TAB_LABEL_WIDTH = 5;
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
            };
            drawLine(buffer, 0, startCol(active), label, style, cols);
        }

        private static int startCol(MenuId menu) {
            return switch (menu) {
                case FILE -> FILE_START_COL;
                case EDIT -> EDIT_START_COL;
                case TAB -> TAB_START_COL;
            };
        }

        private static int labelWidth(MenuId menu) {
            return switch (menu) {
                case FILE -> FILE_LABEL_WIDTH;
                case EDIT -> EDIT_LABEL_WIDTH;
                case TAB -> TAB_LABEL_WIDTH;
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
