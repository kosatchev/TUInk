package dev.tuink.tui.ui;

import dev.tuink.tui.flow.ConfirmChoice;

public final class ConfirmBarView {
    private final String row;
    private final int cursorCol;
    private final ConfirmChoice selectedChoice;
    private final Button saveButton;
    private final Button discardButton;
    private final Button cancelButton;

    private ConfirmBarView(
        String row,
        int cursorCol,
        ConfirmChoice selectedChoice,
        Button saveButton,
        Button discardButton,
        Button cancelButton
    ) {
        this.row = row;
        this.cursorCol = cursorCol;
        this.selectedChoice = selectedChoice == null ? ConfirmChoice.CANCEL : selectedChoice;
        this.saveButton = saveButton;
        this.discardButton = discardButton;
        this.cancelButton = cancelButton;
    }

    public static ConfirmBarView build(String prompt, ConfirmChoice selectedChoice, int cols) {
        ConfirmChoice selected = selectedChoice == null ? ConfirmChoice.CANCEL : selectedChoice;
        Button savePrototype = new Button(0, 0, "Save");
        Button discardPrototype = new Button(0, 0, "Discard");
        Button cancelPrototype = new Button(0, 0, "Cancel");
        String actionsCluster = savePrototype.renderedText() + " "
            + discardPrototype.renderedText() + " "
            + cancelPrototype.renderedText();
        String row = alignLeftRight(prompt, actionsCluster, cols, 1);
        int actionsStart = Math.max(0, rightClusterStart(row, actionsCluster));
        Button saveButton = new Button(0, actionsStart, "Save");
        Button discardButton = new Button(0, actionsStart + saveButton.width() + 1, "Discard");
        Button cancelButton = new Button(0, discardButton.col() + discardButton.width() + 1, "Cancel");
        int cursorCol = switch (selected) {
            case SAVE -> saveButton.cursorCol();
            case DISCARD -> discardButton.cursorCol();
            case CANCEL -> cancelButton.cursorCol();
        };
        return new ConfirmBarView(
            row,
            cursorCol,
            selected,
            saveButton,
            discardButton,
            cancelButton
        );
    }

    public ConfirmChoice choiceAtMouse(int mouseRow, int mouseCol, int topRow) {
        if (mouseCol < 0 || mouseRow != topRow) {
            return null;
        }
        if (mouseCol >= saveButton.col() && mouseCol < saveButton.col() + saveButton.width()) {
            return ConfirmChoice.SAVE;
        }
        if (mouseCol >= discardButton.col() && mouseCol < discardButton.col() + discardButton.width()) {
            return ConfirmChoice.DISCARD;
        }
        if (mouseCol >= cancelButton.col() && mouseCol < cancelButton.col() + cancelButton.width()) {
            return ConfirmChoice.CANCEL;
        }
        return null;
    }

    public String row() {
        return row;
    }

    public int cursorCol() {
        return cursorCol;
    }

    public Button selectedButton() {
        return switch (selectedChoice) {
            case SAVE -> saveButton;
            case DISCARD -> discardButton;
            case CANCEL -> cancelButton;
        };
    }

    private static String clipPad(String text, int cols) {
        if (cols <= 0) {
            return "";
        }
        if (text.length() >= cols) {
            return text.substring(0, cols);
        }
        return text + " ".repeat(cols - text.length());
    }

    private static String alignLeftRight(String left, String right, int cols, int rightMargin) {
        if (cols <= 0) {
            return "";
        }
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        int margin = Math.max(0, rightMargin);
        int rightBlock = safeRight.length() + margin;
        if (safeRight.isEmpty()) {
            return clipPad(safeLeft, cols);
        }
        if (rightBlock >= cols) {
            return clipPad(safeRight, cols);
        }
        int leftWidth = cols - rightBlock;
        String leftPart = safeLeft.length() > leftWidth
            ? safeLeft.substring(0, leftWidth)
            : safeLeft + " ".repeat(leftWidth - safeLeft.length());
        return leftPart + safeRight + " ".repeat(margin);
    }

    private static int rightClusterStart(String row, String cluster) {
        if (row == null || cluster == null || cluster.isEmpty()) {
            return -1;
        }
        return row.indexOf(cluster);
    }
}
