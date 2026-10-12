package dev.tuink.tui.ui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.PlacedCursorSource;
import dev.tuiframe.widget.PlacedInteractiveWidget;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Application-owned Save As row composed from a reusable field and buttons. */
public final class SaveAsBarView {
    public static final String DEFAULT_LABEL = "Save As: ";
    private static final List<Target> ORDER = List.of(Target.FIELD, Target.SAVE, Target.CANCEL);
    private static final int RIGHT_MARGIN = 1;

    private final String row;
    private final TextField field;
    private final int fieldStart;
    private final int fieldWidth;
    private final Button save = new Button("Save");
    private final Button cancel = new Button("Cancel");
    private final int saveStart;
    private final int cancelStart;
    private final boolean buttonsVisible;

    private SaveAsBarView(String label, TextField field, int columns) {
        String requiredLabel = Objects.requireNonNull(label, "label");
        this.field = Objects.requireNonNull(field, "field");
        int safeColumns = Math.max(0, columns);
        int labelWidth = CellText.width(requiredLabel);
        this.row = CellText.fit(requiredLabel, safeColumns);
        this.fieldStart = Math.min(labelWidth, safeColumns);
        int buttonsWidth = save.width() + 1 + cancel.width();
        long required = (long) labelWidth + 1 + 1 + buttonsWidth + RIGHT_MARGIN;
        this.buttonsVisible = safeColumns >= required;
        this.saveStart = buttonsVisible ? safeColumns - buttonsWidth - RIGHT_MARGIN : safeColumns;
        this.cancelStart = buttonsVisible ? saveStart + save.width() + 1 : safeColumns;
        this.fieldWidth = buttonsVisible ? saveStart - 1 - fieldStart : Math.max(0, safeColumns - labelWidth);
    }

    public static SaveAsBarView build(TextField field, int columns) {
        return build(DEFAULT_LABEL, field, columns);
    }

    public static SaveAsBarView build(String label, TextField field, int columns) {
        return new SaveAsBarView(label, field, columns);
    }

    /** Raster label and background; interactive controls are painted by their placements. */
    public String row() {
        return row;
    }

    /**
     * Places the row's controls in terminal-parent coordinates for one frame snapshot.
     * Narrow rows retain the filename field and hide both buttons. Clipped or empty
     * controls cannot receive focus or input.
     */
    public Placement place(Object scopeId, int topRow, Rect interactionClip) {
        Objects.requireNonNull(scopeId, "scopeId");
        Rect safeInteractionClip = Objects.requireNonNull(interactionClip, "interactionClip");
        List<RoutedTarget<Target, Action>> targets = new ArrayList<>(ORDER.size());
        addTarget(targets, Target.FIELD,
            new FieldPlacement(field.layout(new Rect(fieldStart, topRow, fieldWidth, 1))), safeInteractionClip);
        addTarget(targets, Target.SAVE,
            new ButtonPlacement(save.layout(new Rect(saveStart, topRow, buttonsVisible ? save.width() : 0, 1)),
                Action.SUBMITTED), safeInteractionClip);
        addTarget(targets, Target.CANCEL,
            new ButtonPlacement(cancel.layout(new Rect(cancelStart, topRow, buttonsVisible ? cancel.width() : 0, 1)),
                Action.CANCELLED), safeInteractionClip);
        FocusScope<Target, Action> focusScope = new FocusScope<>(
            scopeId,
            FocusResetPolicy.FIRST_FOCUSABLE,
            targets,
            ORDER,
            ORDER
        );
        return new Placement(row, focusScope);
    }

    public enum Target {
        FIELD,
        SAVE,
        CANCEL
    }

    public enum Action {
        VALUE_CHANGED,
        SUBMITTED,
        PASTE_REJECTED,
        CANCELLED
    }

    /** Exact field placement shared by focus routing, painting, hit testing and cursor lookup. */
    public static final class Placement {
        private final String row;
        private final FocusScope<Target, Action> focusScope;

        private Placement(
            String row,
            FocusScope<Target, Action> focusScope
        ) {
            this.row = Objects.requireNonNull(row, "row");
            this.focusScope = Objects.requireNonNull(focusScope, "focusScope");
        }

        public String row() {
            return row;
        }

        public FocusScope<Target, Action> focusScope() {
            return focusScope;
        }

        public boolean captures(PointerEvent pointer) {
            return capturedTarget(pointer).isPresent();
        }

        public Optional<Target> capturedTarget(PointerEvent pointer) {
            Objects.requireNonNull(pointer, "pointer");
            RoutedTarget<Target, Action> target = focusScope.target(Target.FIELD).orElseThrow();
            TextField.Placement field = ((FieldPlacement) target.placement()).delegate();
            if (!target.enabled()) {
                field.cancelMouseSelection();
                return Optional.empty();
            }
            return field.captures(pointer) ? Optional.of(Target.FIELD) : Optional.empty();
        }

        public void cancelMouseSelection() {
            ((FieldPlacement) focusScope.target(Target.FIELD).orElseThrow().placement())
                .delegate().cancelMouseSelection();
        }

        public void retainMouseSelection(Target focused) {
            if (focused != Target.FIELD || !focusScope.target(Target.FIELD).orElseThrow().enabled()) {
                cancelMouseSelection();
            }
        }

        public Rect fieldBounds() {
            return targetBounds(Target.FIELD);
        }

        public Rect targetBounds(Target target) {
            return focusScope.target(Objects.requireNonNull(target, "target")).orElseThrow().placement().bounds();
        }

        /** Enters or reconciles the controls without storing focus in application state. */
        public void activate(FocusManager<Target> focusManager) {
            Objects.requireNonNull(focusManager, "focusManager");
            focusManager.context(focusScope, Target.FIELD);
        }

        /** Paints the same immutable placement exposed to the current focus scope. */
        public void paint(Canvas canvas, Theme theme, FocusManager<Target> focusManager) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(focusManager, "focusManager");
            for (Target id : ORDER) {
                RoutedTarget<Target, Action> target = focusScope.target(id).orElseThrow();
                target.placement().paint(canvas, theme, focusContext(focusManager, target).widgetState());
            }
        }

        /** Returns the terminal-global cursor cell without mutating focus or field state. */
        public Optional<Point> cursorPosition(FocusManager<Target> focusManager) {
            Objects.requireNonNull(focusManager, "focusManager");
            RoutedTarget<Target, Action> target = focusScope.target(Target.FIELD).orElseThrow();
            if (!(target.placement() instanceof PlacedCursorSource cursorSource)) {
                return Optional.empty();
            }
            FocusContext context = focusContext(focusManager, target);
            return cursorSource.cursorPosition(context)
                .flatMap(context.space()::terminalPoint);
        }

        private FocusContext focusContext(FocusManager<Target> focusManager, RoutedTarget<Target, Action> target) {
            Objects.requireNonNull(focusManager, "focusManager");
            boolean active = focusManager.activeScopeId().filter(focusScope.id()::equals).isPresent();
            boolean focused = active
                && focusManager.focused().filter(target.id()::equals).isPresent()
                && target.enabled();
            return new FocusContext(focused, target.enabled(), target.space());
        }
    }

    private static void addTarget(List<RoutedTarget<Target, Action>> targets, Target id,
        PlacedInteractiveWidget<Action> placement, Rect clip) {
        boolean interactive = !placement.bounds().isEmpty() && clip.contains(placement.bounds());
        targets.add(new RoutedTarget<>(id, placement, FocusSpace.root(clip), interactive, interactive));
    }

    private static InteractionResult<Action> mapped(InteractionResult<?> result, Action action) {
        return new InteractionResult<>(result.consumed(), result.focusRequest(),
            result.output().isPresent() ? Optional.of(action) : Optional.empty());
    }

    private record FieldPlacement(TextField.Placement delegate)
        implements PlacedInteractiveWidget<Action>, PlacedCursorSource {
        @Override
        public Rect bounds() {
            return delegate.bounds();
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState state) {
            delegate.paint(canvas, theme, state);
        }

        @Override
        public InteractionResult<Action> handle(TuiInputEvent event, FocusContext context) {
            InteractionResult<TextField.Action> result = delegate.handle(event, context);
            Action action = result.output().map(value -> switch (value) {
                case VALUE_CHANGED -> Action.VALUE_CHANGED;
                case SUBMITTED -> Action.SUBMITTED;
                case PASTE_REJECTED -> Action.PASTE_REJECTED;
            }).orElse(Action.VALUE_CHANGED);
            return mapped(result, action);
        }

        @Override
        public Optional<Point> cursorPosition(FocusContext context) {
            return delegate.cursorPosition(context);
        }
    }

    private record ButtonPlacement(PlacedInteractiveWidget<Button.Action> delegate, Action action)
        implements PlacedInteractiveWidget<Action> {
        @Override
        public Rect bounds() {
            return delegate.bounds();
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState state) {
            delegate.paint(canvas, theme, state);
        }

        @Override
        public InteractionResult<Action> handle(TuiInputEvent event, FocusContext context) {
            return mapped(delegate.handle(event, context), action);
        }
    }
}
