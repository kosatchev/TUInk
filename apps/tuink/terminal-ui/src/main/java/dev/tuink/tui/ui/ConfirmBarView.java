package dev.tuink.tui.ui;

import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.terminal.cell.CellText;

public final class ConfirmBarView {
    private final String row;
    private final int cursorCol;
    private final ConfirmChoice selectedChoice;
    private final ChoiceButton[] choices;
    private final ConfirmChoice defaultChoice;

    private ConfirmBarView(
        String row,
        int cursorCol,
        ConfirmChoice selectedChoice,
        ChoiceButton[] choices,
        ConfirmChoice defaultChoice
    ) {
        this.row = row;
        this.cursorCol = cursorCol;
        this.choices = choices == null ? new ChoiceButton[0] : choices;
        this.defaultChoice = defaultChoice == null ? ConfirmChoice.SAVE : defaultChoice;
        this.selectedChoice = normalizeSelected(selectedChoice, this.choices, this.defaultChoice);
    }

    public static ConfirmBarView build(String prompt, ConfirmChoice selectedChoice, int cols) {
        return build(prompt, selectedChoice, cols, "Save", "Discard", true, "Cancel");
    }

    public static ConfirmBarView buildRecovery(String prompt, ConfirmChoice selectedChoice, int cols) {
        return build(prompt, selectedChoice, cols, "Restore", "Discard", false, "Cancel");
    }

    public static ConfirmBarView build(
        String prompt,
        ConfirmChoice selectedChoice,
        int cols,
        String primaryLabel,
        String secondaryLabel,
        boolean includeCancel,
        String cancelLabel
    ) {
        String actionsCluster = actionsCluster(primaryLabel, secondaryLabel, includeCancel, cancelLabel);
        String row = alignLeftRight(prompt, actionsCluster, cols, 1);
        int actionsStart = Math.max(0, rightClusterStart(row, actionsCluster));
        Button primaryButton = new Button(0, actionsStart, primaryLabel);
        Button secondaryButton = new Button(0, actionsStart + primaryButton.width() + 1, secondaryLabel);
        ChoiceButton[] choices = includeCancel
            ? new ChoiceButton[] {
                new ChoiceButton(ConfirmChoice.SAVE, primaryButton),
                new ChoiceButton(ConfirmChoice.DISCARD, secondaryButton),
                new ChoiceButton(
                    ConfirmChoice.CANCEL,
                    new Button(0, secondaryButton.col() + secondaryButton.width() + 1, cancelLabel)
                )
            }
            : new ChoiceButton[] {
                new ChoiceButton(ConfirmChoice.SAVE, primaryButton),
                new ChoiceButton(ConfirmChoice.DISCARD, secondaryButton)
            };
        ConfirmChoice selected = normalizeSelected(selectedChoice, choices, ConfirmChoice.SAVE);
        Button selectedButton = buttonFor(choices, selected);
        int cursorCol = selectedButton == null ? 0 : selectedButton.cursorCol();
        return new ConfirmBarView(row, cursorCol, selected, choices, ConfirmChoice.SAVE);
    }

    public static int promptWidth(
        int cols,
        String primaryLabel,
        String secondaryLabel,
        boolean includeCancel,
        String cancelLabel
    ) {
        String actions = actionsCluster(primaryLabel, secondaryLabel, includeCancel, cancelLabel);
        return Math.max(0, cols - CellText.width(actions) - 1);
    }

    private static String actionsCluster(
        String primaryLabel,
        String secondaryLabel,
        boolean includeCancel,
        String cancelLabel
    ) {
        Button primaryPrototype = new Button(0, 0, primaryLabel);
        Button secondaryPrototype = new Button(0, 0, secondaryLabel);
        StringBuilder clusterBuilder = new StringBuilder();
        clusterBuilder.append(primaryPrototype.renderedText())
            .append(" ")
            .append(secondaryPrototype.renderedText());
        if (includeCancel) {
            Button cancelPrototype = new Button(0, 0, cancelLabel);
            clusterBuilder.append(" ").append(cancelPrototype.renderedText());
        }
        return clusterBuilder.toString();
    }

    public ConfirmChoice choiceAtMouse(int mouseRow, int mouseCol, int topRow) {
        if (mouseCol < 0 || mouseRow != topRow) {
            return null;
        }
        for (ChoiceButton choice : choices) {
            Button button = choice.button();
            if (mouseCol >= button.col() && mouseCol < button.col() + button.width()) {
                return choice.choice();
            }
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
        Button selected = buttonFor(choices, selectedChoice);
        if (selected != null) {
            return selected;
        }
        Button fallback = buttonFor(choices, defaultChoice);
        return fallback == null ? new Button(0, 0, "") : fallback;
    }

    private static String clipPad(String text, int cols) {
        if (cols <= 0) {
            return "";
        }
        return CellText.fit(text, cols);
    }

    private static String alignLeftRight(String left, String right, int cols, int rightMargin) {
        if (cols <= 0) {
            return "";
        }
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        int margin = Math.max(0, rightMargin);
        int rightBlock = CellText.width(safeRight) + margin;
        if (safeRight.isEmpty()) {
            return clipPad(safeLeft, cols);
        }
        if (rightBlock >= cols) {
            return clipPad(safeRight, cols);
        }
        int leftWidth = cols - rightBlock;
        String leftPart = CellText.fit(safeLeft, leftWidth);
        return leftPart + safeRight + " ".repeat(margin);
    }

    private static int rightClusterStart(String row, String cluster) {
        if (row == null || cluster == null || cluster.isEmpty()) {
            return -1;
        }
        int charOffset = row.indexOf(cluster);
        if (charOffset < 0) {
            return -1;
        }
        return dev.tuink.terminal.text.TerminalText.visualColumnAt(row, charOffset, 4);
    }

    private static ConfirmChoice normalizeSelected(
        ConfirmChoice selected,
        ChoiceButton[] choices,
        ConfirmChoice defaultChoice
    ) {
        if (buttonFor(choices, selected) != null) {
            return selected;
        }
        if (buttonFor(choices, defaultChoice) != null) {
            return defaultChoice;
        }
        return choices != null && choices.length > 0 ? choices[0].choice() : ConfirmChoice.SAVE;
    }

    private static Button buttonFor(ChoiceButton[] choices, ConfirmChoice choice) {
        if (choices == null || choice == null) {
            return null;
        }
        for (ChoiceButton candidate : choices) {
            if (candidate.choice() == choice) {
                return candidate.button();
            }
        }
        return null;
    }

    private record ChoiceButton(ConfirmChoice choice, Button button) {
    }
}
