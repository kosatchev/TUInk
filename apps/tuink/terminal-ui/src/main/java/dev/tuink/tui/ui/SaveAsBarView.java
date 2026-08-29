package dev.tuink.tui.ui;

import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.cell.CellText;
import dev.tuink.terminal.geometry.Point;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.widget.FocusContext;
import dev.tuink.terminal.widget.FocusManager;
import dev.tuink.terminal.widget.FocusResetPolicy;
import dev.tuink.terminal.widget.FocusScope;
import dev.tuink.terminal.widget.FocusSpace;
import dev.tuink.terminal.widget.PlacedCursorSource;
import dev.tuink.terminal.widget.PlacedInteractiveWidget;
import dev.tuink.terminal.widget.RoutedTarget;
import dev.tuink.terminal.widget.TextField;
import dev.tuink.terminal.widget.Theme;
import java.util.Objects;
import java.util.Optional;

/** Application-owned Save As label and reusable single-field placement. */
public final class SaveAsBarView {
    public static final String DEFAULT_LABEL = "Save As: ";

    private final String row;
    private final TextField field;
    private final int fieldStart;
    private final int fieldWidth;

    private SaveAsBarView(String label, TextField field, int columns) {
        String requiredLabel = Objects.requireNonNull(label, "label");
        this.field = Objects.requireNonNull(field, "field");
        int safeColumns = Math.max(0, columns);
        int labelWidth = CellText.width(requiredLabel);
        this.row = CellText.fit(requiredLabel, safeColumns);
        this.fieldStart = Math.min(labelWidth, safeColumns);
        this.fieldWidth = Math.max(0, safeColumns - labelWidth);
    }

    public static SaveAsBarView build(TextField field, int columns) {
        return build(DEFAULT_LABEL, field, columns);
    }

    public static SaveAsBarView build(String label, TextField field, int columns) {
        return new SaveAsBarView(label, field, columns);
    }

    /** Raster content for the prompt row. The editable value is painted only by the field placement. */
    public String row() {
        return row;
    }

    /**
     * Places the field in terminal-parent coordinates for one frame snapshot.
     * A clipped or empty field is still painted, but cannot receive focus or input.
     */
    public Placement place(Object scopeId, int topRow, Rect interactionClip) {
        Objects.requireNonNull(scopeId, "scopeId");
        Rect safeInteractionClip = Objects.requireNonNull(interactionClip, "interactionClip");
        Rect bounds = new Rect(fieldStart, topRow, fieldWidth, 1);
        PlacedInteractiveWidget<TextField.Action> fieldPlacement = field.layout(bounds);
        boolean interactive = !bounds.isEmpty() && safeInteractionClip.contains(bounds);
        RoutedTarget<Target, TextField.Action> target = new RoutedTarget<>(
            Target.FIELD,
            fieldPlacement,
            FocusSpace.root(safeInteractionClip),
            interactive,
            interactive
        );
        FocusScope<Target, TextField.Action> focusScope = FocusScope.single(
            scopeId,
            FocusResetPolicy.FIRST_FOCUSABLE,
            target
        );
        return new Placement(row, focusScope, fieldPlacement);
    }

    public enum Target {
        FIELD
    }

    /** Exact field placement shared by focus routing, painting, hit testing and cursor lookup. */
    public static final class Placement {
        private final String row;
        private final FocusScope<Target, TextField.Action> focusScope;
        private final PlacedInteractiveWidget<TextField.Action> fieldPlacement;

        private Placement(
            String row,
            FocusScope<Target, TextField.Action> focusScope,
            PlacedInteractiveWidget<TextField.Action> fieldPlacement
        ) {
            this.row = Objects.requireNonNull(row, "row");
            this.focusScope = Objects.requireNonNull(focusScope, "focusScope");
            this.fieldPlacement = Objects.requireNonNull(fieldPlacement, "fieldPlacement");
        }

        public String row() {
            return row;
        }

        public FocusScope<Target, TextField.Action> focusScope() {
            return focusScope;
        }

        public Rect fieldBounds() {
            return fieldPlacement.bounds();
        }

        /** Enters or reconciles the current field scope without storing focus in application state. */
        public void activate(FocusManager<Target> focusManager) {
            Objects.requireNonNull(focusManager, "focusManager");
            focusManager.context(focusScope, Target.FIELD);
        }

        /** Paints the same immutable placement exposed to the current focus scope. */
        public void paint(Canvas canvas, Theme theme, FocusManager<Target> focusManager) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            FocusContext context = focusContext(focusManager);
            fieldPlacement.paint(canvas, theme, context.widgetState());
        }

        /** Returns the terminal-global cursor cell without mutating focus or field state. */
        public Optional<Point> cursorPosition(FocusManager<Target> focusManager) {
            FocusContext context = focusContext(focusManager);
            if (!(fieldPlacement instanceof PlacedCursorSource cursorSource)) {
                return Optional.empty();
            }
            return cursorSource.cursorPosition(context)
                .flatMap(context.space()::terminalPoint);
        }

        private FocusContext focusContext(FocusManager<Target> focusManager) {
            Objects.requireNonNull(focusManager, "focusManager");
            RoutedTarget<Target, TextField.Action> target = focusScope.target(Target.FIELD).orElseThrow();
            boolean active = focusManager.activeScopeId().filter(focusScope.id()::equals).isPresent();
            boolean focused = active
                && focusManager.focused().filter(Target.FIELD::equals).isPresent()
                && target.enabled();
            return new FocusContext(focused, target.enabled(), target.space());
        }
    }
}
