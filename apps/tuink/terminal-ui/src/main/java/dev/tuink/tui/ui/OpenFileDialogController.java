package dev.tuink.tui.ui;

import dev.tuink.tui.input.KeyEvent;
import java.util.function.Function;

public final class OpenFileDialogController {
    private OpenFileDialogController() {
    }

    public enum Focus {
        LIST,
        OK,
        CANCEL
    }

    public record ActivationResult(
        boolean keepDialogOpen,
        int activeTabIndex
    ) {
    }

    public record HandleResult(
        boolean consumed,
        boolean openFileMode,
        Focus focus,
        int activeTabIndex
    ) {
    }

    public record MousePressResult(
        boolean consumed,
        boolean openFileMode,
        Focus focus,
        TreeView.ScrollAxis dragAxis,
        int activeTabIndex
    ) {
    }

    public record OpenDialogLayout(int top, int left, int width, int height, int listStartRow, int listRows) {
        public int right() {
            return left + width - 1;
        }

        public int bottom() {
            return top + height - 1;
        }
    }

    public record OpenDialogButtons(Button ok, Button cancel) {
    }

    public static Focus nextFocus(Focus current) {
        return switch (current) {
            case LIST -> Focus.OK;
            case OK -> Focus.CANCEL;
            case CANCEL -> Focus.LIST;
        };
    }

    public static OpenDialogLayout layoutForTerminal(int rows, int cols) {
        int width = Math.max(24, Math.min(cols - 4, (int) Math.round(cols * 0.8)));
        int height = Math.max(8, Math.min(rows - 2, (int) Math.round(rows * 0.75)));
        int left = Math.max(0, (cols - width) / 2);
        int top = Math.max(0, (rows - height) / 2);
        int listStartRow = top + 2;
        int listRows = Math.max(1, height - 3);
        return new OpenDialogLayout(top, left, width, height, listStartRow, listRows);
    }

    public static OpenDialogButtons buttonsForLayout(OpenDialogLayout layout) {
        String ok = "  OK  ";
        String cancel = " Cancel ";
        int total = (ok.length() + 2) + 2 + (cancel.length() + 2);
        int start = layout.left() + Math.max(1, (layout.width() - 2 - total) / 2);
        Button okButton = new Button(layout.bottom(), start, ok);
        Button cancelButton = new Button(layout.bottom(), start + okButton.width() + 2, cancel);
        return new OpenDialogButtons(okButton, cancelButton);
    }

    public static HandleResult handleKey(
        KeyEvent key,
        boolean openFileMode,
        Focus focus,
        TreeView openFileTree,
        int activeTabIndex,
        int listRows,
        int visibleTextWidth,
        Function<TreeView.Item, ActivationResult> activateSelection
    ) {
        if (!openFileMode) {
            return new HandleResult(false, openFileMode, focus, activeTabIndex);
        }

        if (key instanceof KeyEvent.CharInput) {
            return new HandleResult(true, openFileMode, focus, activeTabIndex);
        }

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
        return switch (action) {
            case ESCAPE -> new HandleResult(true, false, focus, activeTabIndex);
            case TAB -> new HandleResult(true, openFileMode, nextFocus(focus), activeTabIndex);
            case ARROW_UP -> {
                if (focus == Focus.LIST) {
                    TreeViewInteractionController.handleListKey(
                        openFileTree,
                        TreeViewInteractionController.ListKeyAction.UP,
                        listRows,
                        visibleTextWidth
                    );
                }
                yield new HandleResult(true, openFileMode, focus, activeTabIndex);
            }
            case ARROW_DOWN -> {
                if (focus == Focus.LIST) {
                    TreeViewInteractionController.handleListKey(
                        openFileTree,
                        TreeViewInteractionController.ListKeyAction.DOWN,
                        listRows,
                        visibleTextWidth
                    );
                }
                yield new HandleResult(true, openFileMode, focus, activeTabIndex);
            }
            case PAGE_UP -> {
                if (focus == Focus.LIST) {
                    TreeViewInteractionController.handleListKey(
                        openFileTree,
                        TreeViewInteractionController.ListKeyAction.PAGE_UP,
                        listRows,
                        visibleTextWidth
                    );
                }
                yield new HandleResult(true, openFileMode, focus, activeTabIndex);
            }
            case PAGE_DOWN -> {
                if (focus == Focus.LIST) {
                    TreeViewInteractionController.handleListKey(
                        openFileTree,
                        TreeViewInteractionController.ListKeyAction.PAGE_DOWN,
                        listRows,
                        visibleTextWidth
                    );
                }
                yield new HandleResult(true, openFileMode, focus, activeTabIndex);
            }
            case HOME -> {
                if (focus == Focus.LIST) {
                    TreeViewInteractionController.handleListKey(
                        openFileTree,
                        TreeViewInteractionController.ListKeyAction.HOME,
                        listRows,
                        visibleTextWidth
                    );
                }
                yield new HandleResult(true, openFileMode, focus, activeTabIndex);
            }
            case END -> {
                if (focus == Focus.LIST) {
                    TreeViewInteractionController.handleListKey(
                        openFileTree,
                        TreeViewInteractionController.ListKeyAction.END,
                        listRows,
                        visibleTextWidth
                    );
                }
                yield new HandleResult(true, openFileMode, focus, activeTabIndex);
            }
            case ENTER -> {
                if (focus == Focus.CANCEL) {
                    yield new HandleResult(true, false, focus, activeTabIndex);
                }
                if (focus == Focus.OK) {
                    ActivationResult result = activateSelection.apply(openFileTree.selectedItem());
                    yield new HandleResult(
                        true,
                        result.keepDialogOpen(),
                        focus,
                        result.activeTabIndex()
                    );
                }
                ActivationResult activation = activateListItem(openFileTree, activeTabIndex, activateSelection);
                yield new HandleResult(
                    true,
                    activation.keepDialogOpen(),
                    focus,
                    activation.activeTabIndex()
                );
            }
            case ARROW_RIGHT -> {
                Focus nextFocus = focus;
                if (focus == Focus.OK) {
                    nextFocus = Focus.CANCEL;
                } else if (focus == Focus.LIST) {
                    TreeViewInteractionController.handleListKey(
                        openFileTree,
                        TreeViewInteractionController.ListKeyAction.RIGHT,
                        listRows,
                        visibleTextWidth
                    );
                }
                yield new HandleResult(true, openFileMode, nextFocus, activeTabIndex);
            }
            case SHIFT_ARROW_RIGHT -> {
                if (focus == Focus.LIST) {
                    TreeViewInteractionController.handleListKey(
                        openFileTree,
                        TreeViewInteractionController.ListKeyAction.SHIFT_RIGHT,
                        listRows,
                        visibleTextWidth
                    );
                }
                yield new HandleResult(true, openFileMode, focus, activeTabIndex);
            }
            case ARROW_LEFT -> {
                Focus nextFocus = focus;
                if (focus == Focus.CANCEL) {
                    nextFocus = Focus.OK;
                } else if (focus == Focus.OK) {
                    nextFocus = Focus.LIST;
                } else {
                    TreeViewInteractionController.handleListKey(
                        openFileTree,
                        TreeViewInteractionController.ListKeyAction.LEFT,
                        listRows,
                        visibleTextWidth
                    );
                }
                yield new HandleResult(true, openFileMode, nextFocus, activeTabIndex);
            }
            case SHIFT_ARROW_LEFT -> {
                if (focus == Focus.LIST) {
                    TreeViewInteractionController.handleListKey(
                        openFileTree,
                        TreeViewInteractionController.ListKeyAction.SHIFT_LEFT,
                        listRows,
                        visibleTextWidth
                    );
                }
                yield new HandleResult(true, openFileMode, focus, activeTabIndex);
            }
            case BACKSPACE -> {
                if (focus == Focus.LIST) {
                    TreeViewInteractionController.handleListKey(
                        openFileTree,
                        TreeViewInteractionController.ListKeyAction.BACKSPACE,
                        listRows,
                        visibleTextWidth
                    );
                }
                yield new HandleResult(true, openFileMode, focus, activeTabIndex);
            }
            default -> new HandleResult(true, openFileMode, focus, activeTabIndex);
        };
    }

    public static boolean handleScroll(boolean openFileMode, TreeView tree, int listRows, boolean down) {
        if (!openFileMode) {
            return false;
        }
        if (down) {
            tree.scrollDown(listRows);
        } else {
            tree.scrollUp(listRows);
        }
        return true;
    }

    public static MousePressResult handleMousePress(
        boolean openFileMode,
        Focus focus,
        TreeView tree,
        int activeTabIndex,
        int mouseRow,
        int mouseCol,
        int left,
        int right,
        int listStartRow,
        int listRows,
        boolean closeButtonHit,
        boolean okHit,
        boolean cancelHit,
        long nowNanos,
        long doubleClickWindowNanos,
        Function<TreeView.Item, ActivationResult> activateSelection
    ) {
        if (!openFileMode) {
            return new MousePressResult(false, openFileMode, focus, TreeView.ScrollAxis.NONE, activeTabIndex);
        }
        if (closeButtonHit) {
            tree.resetClickTracking();
            return new MousePressResult(true, false, focus, TreeView.ScrollAxis.NONE, activeTabIndex);
        }

        TreeViewInteractionController.PressResult press = TreeViewInteractionController.handlePress(
            tree,
            mouseRow,
            mouseCol,
            left,
            right,
            listStartRow,
            listRows,
            nowNanos,
            doubleClickWindowNanos
        );
        if (press.consumed()) {
            Focus nextFocus = focus;
            if (press.dragAxis() != TreeView.ScrollAxis.NONE || press.treeHit()) {
                nextFocus = Focus.LIST;
            }
            if (press.treeHit()) {
                if (press.arrowClick()) {
                    tree.toggleSelectedAndRefresh();
                    tree.resetClickTracking();
                } else if (press.doubleClick()) {
                    ActivationResult activation = activateListItem(tree, activeTabIndex, activateSelection);
                    tree.resetClickTracking();
                    return new MousePressResult(
                        true,
                        activation.keepDialogOpen(),
                        nextFocus,
                        press.dragAxis(),
                        activation.activeTabIndex()
                    );
                }
            }
            return new MousePressResult(true, openFileMode, nextFocus, press.dragAxis(), activeTabIndex);
        }

        if (okHit) {
            ActivationResult activation = activateSelection.apply(tree.selectedItem());
            return new MousePressResult(
                true,
                activation.keepDialogOpen(),
                Focus.OK,
                TreeView.ScrollAxis.NONE,
                activation.activeTabIndex()
            );
        }
        if (cancelHit) {
            return new MousePressResult(true, false, Focus.CANCEL, TreeView.ScrollAxis.NONE, activeTabIndex);
        }
        return new MousePressResult(true, openFileMode, focus, TreeView.ScrollAxis.NONE, activeTabIndex);
    }

    public static boolean handleMouseDrag(
        boolean openFileMode,
        TreeView tree,
        TreeView.ScrollAxis dragAxis,
        int mouseRow,
        int mouseCol,
        int left,
        int right,
        int listStartRow,
        int listRows
    ) {
        if (!openFileMode || dragAxis == TreeView.ScrollAxis.NONE) {
            return false;
        }
        TreeViewInteractionController.handleDrag(
            tree,
            mouseRow,
            mouseCol,
            left,
            right,
            listStartRow,
            listRows,
            dragAxis
        );
        return true;
    }

    private static ActivationResult activateListItem(
        TreeView tree,
        int activeTabIndex,
        Function<TreeView.Item, ActivationResult> activateSelection
    ) {
        TreeView.Item selected = tree.selectedItem();
        if (selected != null && (selected.parentLink() || selected.directory())) {
            tree.setRoot(selected.path());
            return new ActivationResult(true, activeTabIndex);
        }
        return activateSelection.apply(selected);
    }
}
