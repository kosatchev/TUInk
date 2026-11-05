package dev.tuink.tui.ui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.InteractionVisibility;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.text.TerminalText;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.ConfirmationAction;
import dev.tuink.tui.flow.ConfirmationShortcuts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Application-owned confirm prompt geometry composed from reusable buttons. */
public final class ConfirmBarView {
    private final String row;
    private final List<ChoiceButton> choices;
    private final ConfirmChoice initialChoice;
    private final boolean decisionsAvailable;
    private final int questionWidth;
    private final List<Rect> contextBounds;

    private ConfirmBarView(
        String row,
        List<ChoiceButton> choices,
        ConfirmChoice initialChoice,
        boolean decisionsAvailable,
        int questionWidth
    ) {
        this(row, choices, initialChoice, decisionsAvailable, questionWidth,
            List.of(new Rect(0, 0, questionWidth, 1)));
    }

    private ConfirmBarView(String row, List<ChoiceButton> choices, ConfirmChoice initialChoice,
        boolean decisionsAvailable, int questionWidth, List<Rect> contextBounds) {
        this.row = Objects.requireNonNull(row, "row");
        this.choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        this.initialChoice = Objects.requireNonNull(initialChoice, "initialChoice");
        this.decisionsAvailable = decisionsAvailable;
        this.questionWidth = questionWidth;
        this.contextBounds = List.copyOf(contextBounds);
    }

    /** Responsive application prompt. All context and hints must fit before an action can run. */
    public static ConfirmBarView responsive(String prompt, int cols, int availableRows,
        String primaryLabel, String secondaryLabel, boolean includeCancel, String cancelLabel,
        ConfirmationShortcuts shortcuts) {
        Objects.requireNonNull(shortcuts, "shortcuts");
        String question = Objects.requireNonNullElse(prompt, "");
        if (cols < 6 || availableRows < 1 || question.isBlank()) return build("", cols);
        String primary = compactLabel(primaryLabel, cols), secondary = compactLabel(secondaryLabel, cols);
        String cancel = compactLabel(cancelLabel, cols);
        String hint = shortcuts.keysFor(ConfirmChoice.SAVE) + "=" + primaryLabel;
        String discardKeys = shortcuts.keysFor(ConfirmChoice.DISCARD);
        if (includeCancel && !discardKeys.isEmpty()) hint += "  " + discardKeys + "=" + secondaryLabel;
        String cancelKeys = shortcuts.keysFor(ConfirmChoice.CANCEL);
        hint += "  " + cancelKeys + "=" + cancelLabel;
        for (var command : ConfirmationAction.Command.values()) {
            String keys = shortcuts.keysFor(command);
            if (!keys.isEmpty()) hint += "  " + keys + "=" + command.label();
        }
        if (!secondary.equals(secondaryLabel)) hint += "  " + secondary + "=" + secondaryLabel;
        List<String> lines = new ArrayList<>(wrap(hint, cols));
        List<Rect> context = new ArrayList<>();
        for (int row = 0; row < lines.size(); row++) context.add(new Rect(0, row, CellText.width(lines.get(row)), 1));
        String cluster = actionsCluster(primary, secondary, includeCancel, cancel);
        List<ChoiceButton> choices = new ArrayList<>();
        if (CellText.width(question) + CellText.width(cluster) + 1 <= cols) {
            int row = lines.size();
            lines.add(alignLeftRight(question, cluster, cols, 1));
            context.add(new Rect(0, row, CellText.width(question), 1));
            int column = rightClusterStart(lines.get(row), cluster);
            column = addChoice(choices, ConfirmChoice.SAVE, primary, row, column);
            column = addChoice(choices, includeCancel ? ConfirmChoice.DISCARD : ConfirmChoice.CANCEL, secondary, row, column);
            if (includeCancel) addChoice(choices, ConfirmChoice.CANCEL, cancel, row, column);
        } else {
            for (String line : wrap(question, cols)) {
                context.add(new Rect(0, lines.size(), CellText.width(line), 1));
                lines.add(CellText.fit(line, cols));
            }
            List<String> labels = includeCancel ? List.of(primary, secondary, cancel) : List.of(primary, secondary);
            List<ConfirmChoice> ids = includeCancel ? List.of(ConfirmChoice.SAVE, ConfirmChoice.DISCARD, ConfirmChoice.CANCEL)
                : List.of(ConfirmChoice.SAVE, ConfirmChoice.CANCEL);
            int column = 0;
            for (int index = 0; index < labels.size(); index++) {
                Button button = new Button(labels.get(index));
                if (column == 0 || column + button.width() > cols) { lines.add(" ".repeat(cols)); column = 0; }
                int row = lines.size() - 1;
                choices.add(new ChoiceButton(ids.get(index), new Button(row, column, labels.get(index))));
                String text = lines.get(row);
                lines.set(row, CellText.clip(text, 0, column) + button.renderedText()
                    + CellText.clip(text, column + button.width(), cols - column - button.width()));
                column += button.width() + 1;
            }
        }
        if (lines.size() > availableRows || lines.size() > 32) return build("", cols);
        return new ConfirmBarView(String.join("\n", lines), choices, ConfirmChoice.CANCEL, true,
            CellText.width(question), context);
    }

    private static int addChoice(List<ChoiceButton> choices, ConfirmChoice id, String label, int row, int column) {
        Button button = new Button(row, column, label);
        choices.add(new ChoiceButton(id, button));
        return column + button.width() + 1;
    }

    private static String compactLabel(String label, int cols) {
        if (CellText.width(label) + 2 <= cols) return label;
        return switch (label) {
            case "Choose another name" -> cols >= 16 ? "Another name" : "Name";
            case "Discard All" -> "Drop all";
            case "Open UTF-16LE" -> "Open";
            case "Save UTF-8" -> "Save";
            case "Save All", "Save As" -> "Save";
            default -> CellText.clip(label, 0, cols - 2);
        };
    }

    private static List<String> wrap(String text, int cols) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int cells = 0;
        var cursor = TerminalText.graphemeCursor(text);
        while (cursor.advance()) {
            String grapheme = text.substring(cursor.start(), cursor.end());
            int width = CellText.width(grapheme);
            if (cells > 0 && cells + width > cols) { lines.add(line.toString()); line.setLength(0); cells = 0; }
            line.append(grapheme); cells += width;
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    public static ConfirmBarView build(String prompt, int cols) {
        return build(prompt, cols, "Save", "Discard", true, "Cancel");
    }

    public static ConfirmBarView buildRecovery(String prompt, int cols) {
        return build(prompt, cols, "Restore", "Delete", true, "Later");
    }

    /** A two-way explicit choice whose initial focus is always Cancel. */
    public static ConfirmBarView buildAlternative(String prompt, int cols, String primaryLabel) {
        ConfirmBarView prototype = build(prompt, cols, primaryLabel, "Cancel", false, "Cancel");
        List<ChoiceButton> choices = List.of(prototype.choices.get(0),
            new ChoiceButton(ConfirmChoice.CANCEL, prototype.choices.get(1).button()));
        String row = prototype.decisionsAvailable ? prototype.row : compactHint(cols, true);
        return new ConfirmBarView(row, choices, ConfirmChoice.CANCEL,
            prototype.decisionsAvailable, prototype.questionWidth);
    }

    public static ConfirmBarView build(
        String prompt,
        int cols,
        String primaryLabel,
        String secondaryLabel,
        boolean includeCancel,
        String cancelLabel
    ) {
        String actionsCluster = actionsCluster(primaryLabel, secondaryLabel, includeCancel, cancelLabel);
        int availablePromptWidth = promptWidth(cols, primaryLabel, secondaryLabel, includeCancel, cancelLabel);
        String question = prompt == null ? "" : prompt;
        boolean decisionsAvailable = !question.isBlank() && CellText.width(question) <= availablePromptWidth;
        String row = decisionsAvailable
            ? alignLeftRight(question, actionsCluster, cols, 1)
            : compactHint(cols, includeCancel);
        int actionsStart = Math.max(0, rightClusterStart(row, actionsCluster));
        Button primaryButton = new Button(0, actionsStart, primaryLabel);
        Button secondaryButton = new Button(0, actionsStart + primaryButton.width() + 1, secondaryLabel);
        List<ChoiceButton> choices = new ArrayList<>(includeCancel ? 3 : 2);
        choices.add(new ChoiceButton(ConfirmChoice.SAVE, primaryButton));
        choices.add(new ChoiceButton(ConfirmChoice.DISCARD, secondaryButton));
        if (includeCancel) {
            choices.add(new ChoiceButton(
                ConfirmChoice.CANCEL,
                new Button(0, secondaryButton.col() + secondaryButton.width() + 1, cancelLabel)
            ));
        }
        return new ConfirmBarView(
            row,
            choices,
            includeCancel ? ConfirmChoice.CANCEL : ConfirmChoice.SAVE,
            decisionsAvailable,
            CellText.width(question)
        );
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

    public String row() {
        return row;
    }

    /**
     * Places all controls in terminal-parent coordinates for one immutable frame snapshot.
     * Only controls fully contained in {@code interactionClip} can receive focus or input.
     */
    public Placement place(Object scopeId, int topRow, Rect interactionClip) {
        Objects.requireNonNull(scopeId, "scopeId");
        Rect safeInteractionClip = Objects.requireNonNull(interactionClip, "interactionClip");
        FocusSpace space = FocusSpace.root(safeInteractionClip);
        boolean questionVisible = decisionsAvailable && contextBounds.stream().allMatch(bounds ->
            InteractionVisibility.FULL_BOUNDS.allows(new Rect(bounds.x(), topRow + bounds.y(), bounds.width(), bounds.height()), safeInteractionClip));
        List<RoutedTarget<ConfirmChoice, Button.Action>> targets = new ArrayList<>(choices.size());
        List<ConfirmChoice> tabOrder = new ArrayList<>(choices.size());
        for (ChoiceButton choice : choices) {
            Button button = choice.button();
            Rect bounds = new Rect(button.col(), topRow + button.row(), button.width(), 1);
            targets.add(new RoutedTarget<>(
                choice.choice(),
                button.layout(bounds),
                space,
                true,
                questionVisible
            ));
            tabOrder.add(choice.choice());
        }
        List<ConfirmChoice> pointerOrder = new ArrayList<>(tabOrder);
        Collections.reverse(pointerOrder);
        FocusScope<ConfirmChoice, Button.Action> focusScope = new FocusScope<>(
            scopeId,
            FocusResetPolicy.CLEARED,
            targets,
            tabOrder,
            pointerOrder
        );
        return new Placement(focusScope, initialChoice, questionVisible);
    }

    private static String compactHint(int cols, boolean includeCancel) {
        List<String> hints = includeCancel
            ? List.of("Enlarge window; Esc cancels", "Enlarge; Esc cancels", "Enlarge; Esc", "Enlarge")
            : List.of("Enlarge window to choose", "Enlarge");
        for (String hint : hints) {
            if (CellText.width(hint) <= cols) {
                return clipPad(hint, cols);
            }
        }
        return clipPad("Enlarge", cols);
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
        int charOffset = row.lastIndexOf(cluster);
        if (charOffset < 0) {
            return -1;
        }
        return CellText.visualColumnAt(row, charOffset);
    }

    private record ChoiceButton(ConfirmChoice choice, Button button) {
        private ChoiceButton {
            Objects.requireNonNull(choice, "choice");
            Objects.requireNonNull(button, "button");
        }
    }

    /** Reused by routing and painting until content or terminal geometry changes. */
    public static final class Placement {
        private final FocusScope<ConfirmChoice, Button.Action> focusScope;
        private final ConfirmChoice initialChoice;
        private final boolean decisionsAvailable;

        private Placement(
            FocusScope<ConfirmChoice, Button.Action> focusScope,
            ConfirmChoice initialChoice,
            boolean decisionsAvailable
        ) {
            this.focusScope = Objects.requireNonNull(focusScope, "focusScope");
            this.initialChoice = Objects.requireNonNull(initialChoice, "initialChoice");
            this.decisionsAvailable = decisionsAvailable;
        }

        public FocusScope<ConfirmChoice, Button.Action> focusScope() {
            return focusScope;
        }

        public boolean decisionsAvailable() {
            return decisionsAvailable;
        }

        /** Applies initial focus only when entering this prompt instance. */
        public void activate(FocusManager<ConfirmChoice> focusManager) {
            Objects.requireNonNull(focusManager, "focusManager");
            boolean entering = focusManager.activeScopeId()
                .filter(focusScope.id()::equals)
                .isEmpty();
            if (entering) {
                focusManager.focus(focusScope, initialChoice);
            } else {
                focusManager.context(focusScope, initialChoice);
            }
        }

        /** Paints every button without changing focus lifecycle. */
        public void paint(Canvas canvas, Theme theme, FocusManager<ConfirmChoice> focusManager) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(focusManager, "focusManager");
            if (!decisionsAvailable) {
                return;
            }
            ConfirmChoice focused = focusManager.focused().orElse(null);
            for (ConfirmChoice id : focusScope.tabOrder()) {
                RoutedTarget<ConfirmChoice, Button.Action> target = focusScope.target(id).orElseThrow();
                target.placement().paint(
                    canvas,
                    theme,
                    new WidgetState(Objects.equals(focused, id) && target.interactive(), target.interactive())
                );
            }
        }

    }
}
