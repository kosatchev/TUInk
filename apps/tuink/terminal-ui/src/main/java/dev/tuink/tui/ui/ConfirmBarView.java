package dev.tuink.tui.ui;

import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.widget.Button;
import dev.tuink.terminal.widget.FocusManager;
import dev.tuink.terminal.widget.FocusResetPolicy;
import dev.tuink.terminal.widget.FocusScope;
import dev.tuink.terminal.widget.FocusSpace;
import dev.tuink.terminal.widget.RoutedTarget;
import dev.tuink.terminal.widget.Theme;
import dev.tuink.terminal.widget.WidgetState;
import dev.tuink.terminal.cell.CellText;
import dev.tuink.tui.flow.ConfirmChoice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Application-owned confirm prompt geometry composed from reusable buttons. */
public final class ConfirmBarView {
    private final String row;
    private final List<ChoiceButton> choices;
    private final ConfirmChoice initialChoice;

    private ConfirmBarView(String row, List<ChoiceButton> choices, ConfirmChoice initialChoice) {
        this.row = Objects.requireNonNull(row, "row");
        this.choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        this.initialChoice = Objects.requireNonNull(initialChoice, "initialChoice");
    }

    public static ConfirmBarView build(String prompt, int cols) {
        return build(prompt, cols, "Save", "Discard", true, "Cancel");
    }

    public static ConfirmBarView buildRecovery(String prompt, int cols) {
        return build(prompt, cols, "Restore", "Discard", false, "Cancel");
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
        String row = alignLeftRight(prompt, actionsCluster, cols, 1);
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
            includeCancel ? ConfirmChoice.CANCEL : ConfirmChoice.SAVE
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
        List<RoutedTarget<ConfirmChoice, Button.Action>> targets = new ArrayList<>(choices.size());
        List<ConfirmChoice> tabOrder = new ArrayList<>(choices.size());
        for (ChoiceButton choice : choices) {
            Button button = choice.button();
            Rect bounds = new Rect(button.col(), topRow, button.width(), 1);
            boolean interactive = safeInteractionClip.contains(bounds);
            targets.add(new RoutedTarget<>(
                choice.choice(),
                button.layout(bounds),
                space,
                interactive,
                interactive
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
        return new Placement(focusScope, initialChoice);
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

        private Placement(
            FocusScope<ConfirmChoice, Button.Action> focusScope,
            ConfirmChoice initialChoice
        ) {
            this.focusScope = Objects.requireNonNull(focusScope, "focusScope");
            this.initialChoice = Objects.requireNonNull(initialChoice, "initialChoice");
        }

        public FocusScope<ConfirmChoice, Button.Action> focusScope() {
            return focusScope;
        }

        /** Applies initial focus only when entering this prompt instance. */
        public void activate(FocusManager<ConfirmChoice> focusManager) {
            Objects.requireNonNull(focusManager, "focusManager");
            boolean entering = focusManager.activeScopeId()
                .filter(focusScope.id()::equals)
                .isEmpty();
            if (entering) {
                focusManager.focus(focusScope, initialChoice);
                return;
            }
            ConfirmChoice focused = focusManager.focused().orElse(null);
            if (focused == null) {
                // Keep an explicitly safe no-focus state after an unavailable
                // default or an application-requested clear.
                focusManager.clear(focusScope);
                return;
            }
            RoutedTarget<ConfirmChoice, Button.Action> target = focusScope.target(focused).orElse(null);
            if (!eligible(target)) {
                // A resize must never turn a hidden safe default into the first
                // remaining dangerous action.
                focusManager.clear(focusScope);
            }
        }

        /** Paints every button without changing focus lifecycle. */
        public void paint(Canvas canvas, Theme theme, FocusManager<ConfirmChoice> focusManager) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(focusManager, "focusManager");
            ConfirmChoice focused = focusManager.focused().orElse(null);
            for (ConfirmChoice id : focusScope.tabOrder()) {
                RoutedTarget<ConfirmChoice, Button.Action> target = focusScope.target(id).orElseThrow();
                target.placement().paint(
                    canvas,
                    theme,
                    new WidgetState(Objects.equals(focused, id) && target.enabled(), target.enabled())
                );
            }
        }

        private static boolean eligible(RoutedTarget<?, ?> target) {
            return target != null && target.enabled() && target.focusable();
        }
    }
}
