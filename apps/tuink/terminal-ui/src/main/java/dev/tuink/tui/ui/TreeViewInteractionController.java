package dev.tuink.tui.ui;

final class TreeViewInteractionController {
    private TreeViewInteractionController() {
    }

    enum ListKeyAction {
        UP,
        DOWN,
        PAGE_UP,
        PAGE_DOWN,
        HOME,
        END,
        RIGHT,
        SHIFT_RIGHT,
        LEFT,
        SHIFT_LEFT,
        BACKSPACE,
        ENTER
    }

    record PressResult(
        boolean consumed,
        TreeView.ScrollAxis dragAxis,
        boolean treeHit,
        boolean arrowClick,
        boolean doubleClick,
        TreeView.Item item
    ) {
        static PressResult none() {
            return new PressResult(false, TreeView.ScrollAxis.NONE, false, false, false, null);
        }
    }

    static void handleListKey(TreeView tree, ListKeyAction action, int listRows, int visibleTextWidth) {
        switch (action) {
            case UP -> tree.moveUp(listRows);
            case DOWN -> tree.moveDown(listRows);
            case PAGE_UP -> tree.pageUp(listRows);
            case PAGE_DOWN -> tree.pageDown(listRows);
            case HOME -> tree.moveHome(listRows);
            case END -> tree.moveEnd(listRows);
            case RIGHT -> {
                TreeView.Item selected = tree.selectedItem();
                if (selected != null && (selected.parentLink() || (selected.directory() && !selected.expanded()))) {
                    tree.toggleSelectedAndRefresh();
                }
            }
            case SHIFT_RIGHT -> tree.scrollRight(visibleTextWidth);
            case LEFT -> {
                TreeView.Item selected = tree.selectedItem();
                if (selected != null && selected.directory() && selected.expanded()) {
                    tree.toggleSelectedAndRefresh();
                } else {
                    tree.moveToParentDepth(listRows);
                }
            }
            case SHIFT_LEFT -> tree.scrollLeft();
            case BACKSPACE -> tree.moveToParentDirectory();
            case ENTER -> tree.toggleSelectedAndRefresh();
        }
    }

    static PressResult handlePress(
        TreeView tree,
        int mouseRow,
        int mouseCol,
        int left,
        int right,
        int listStartRow,
        int listRows,
        long nowNanos,
        long doubleClickWindowNanos
    ) {
        TreeView.ScrollPointerResult scrollPointer = tree.handleScrollbarPointer(
            mouseRow,
            mouseCol,
            left,
            right,
            listStartRow,
            listRows
        );
        if (scrollPointer.consumed()) {
            TreeView.ScrollAxis dragAxis = scrollPointer.drag() ? scrollPointer.axis() : TreeView.ScrollAxis.NONE;
            return new PressResult(true, dragAxis, false, false, false, null);
        }

        TreeView.ClickResult click = tree.clickList(
            mouseRow,
            mouseCol,
            left,
            right,
            listStartRow,
            listRows,
            nowNanos,
            doubleClickWindowNanos
        );
        if (!click.consumed()) {
            return PressResult.none();
        }
        return new PressResult(true, TreeView.ScrollAxis.NONE, true, click.arrowClick(), click.doubleClick(), click.item());
    }

    static void handleDrag(
        TreeView tree,
        int mouseRow,
        int mouseCol,
        int left,
        int right,
        int listStartRow,
        int listRows,
        TreeView.ScrollAxis axis
    ) {
        tree.dragScrollbarPointer(mouseRow, mouseCol, left, right, listStartRow, listRows, axis);
    }
}
