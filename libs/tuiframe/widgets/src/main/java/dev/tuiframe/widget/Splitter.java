package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellStyle;
import java.util.Objects;

/**
 * A one-cell divider for {@link SplitPane}. Its consumer adopts every output,
 * rebuilds the split geometry, and delivers captured drag/release events through
 * the ordinary pointer-capture coordinator. Pointer resizing keeps keyboard
 * focus where it was; keyboard resizing is available when explicitly focused.
 */
public final class Splitter implements InteractiveWidget<Splitter.StateChanged> {
    private final SplitPane split;
    private final State state;

    public Splitter(SplitPane split, State state) {
        this.split = Objects.requireNonNull(split, "split");
        this.state = Objects.requireNonNull(state, "state");
    }

    /** Application-owned gesture state. Preferred region size is owned by the consumer. */
    public record State(boolean dragging) {
        public static State initial() { return new State(false); }
    }

    /** Adopt both the extent and gesture state before rebuilding current-frame placement. */
    public record StateChanged(int firstExtent, State state) {
        public StateChanged {
            if (firstExtent < 0) {
                throw new IllegalArgumentException("firstExtent must be non-negative");
            }
            Objects.requireNonNull(state, "state");
        }
    }

    @Override
    public Size measure(Size available) {
        Objects.requireNonNull(available, "available");
        return split.axis() == SplitPane.Axis.HORIZONTAL
            ? new Size(Math.min(1, available.width()), available.height())
            : new Size(available.width(), Math.min(1, available.height()));
    }

    public Placement layout() {
        return new Placement(split, state);
    }

    /** The divider allocation must come from the same split snapshot as its child regions. */
    @Override
    public Placement layout(Rect bounds) {
        if (!split.dividerBounds().equals(Objects.requireNonNull(bounds, "bounds"))) {
            throw new IllegalArgumentException("bounds must equal the split divider allocation");
        }
        return layout();
    }

    public static final class Placement implements PlacedPointerCapture<StateChanged> {
        private final SplitPane split;
        private final State state;

        private Placement(SplitPane split, State state) {
            this.split = split;
            this.state = state;
        }

        @Override
        public Rect bounds() { return split.dividerBounds(); }

        public State state() { return state; }

        @Override
        public boolean pointerCaptureActive() { return state.dragging(); }

        @Override
        public InteractionResult<StateChanged> cancelPointerCapture(PointerCaptureCancellation reason) {
            Objects.requireNonNull(reason, "reason");
            return changed(split.firstExtent(), State.initial()).withPointerCapture(PointerCaptureRequest.RELEASE);
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState widgetState) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(widgetState, "widgetState");
            CellStyle normal = theme.resolveStyle(WidgetThemeKeys.SPLITTER,
                theme.resolveStyle(WidgetThemeKeys.MODAL_BORDER));
            CellStyle style = !widgetState.enabled()
                ? theme.resolveStyle(WidgetThemeKeys.SPLITTER_DISABLED, normal)
                : widgetState.focused() || state.dragging()
                    ? theme.resolveStyle(WidgetThemeKeys.SPLITTER_FOCUSED, normal) : normal;
            canvas.fillCell(bounds(), new Cell(split.axis() == SplitPane.Axis.HORIZONTAL ? "│" : "─", style));
        }

        @Override
        public InteractionResult<StateChanged> handle(TuiInputEvent event, FocusContext context) {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(context, "context");
            if (state.dragging()) {
                if (event instanceof ResizeEvent) {
                    return cancelPointerCapture(PointerCaptureCancellation.RESIZE);
                }
                if (event instanceof PointerEvent pointer && pointer.kind() == PointerEvent.Kind.RELEASE) {
                    return cancelPointerCapture(PointerCaptureCancellation.RELEASE);
                }
                if (!context.enabled() || !split.divided()) {
                    return cancelPointerCapture(PointerCaptureCancellation.HIDDEN);
                }
            }
            if (!context.enabled() || !split.divided()) {
                return InteractionResult.ignored();
            }
            if (event instanceof PointerEvent pointer) {
                if (state.dragging() && pointer.kind() == PointerEvent.Kind.DRAG
                    && pointer.button() == PointerEvent.Button.LEFT) {
                    long extent = split.axis() == SplitPane.Axis.HORIZONTAL
                        ? (long) pointer.column() - context.space().terminalOrigin().x() - split.bounds().x()
                        : (long) pointer.row() - context.space().terminalOrigin().y() - split.bounds().y();
                    return changed(split.clampFirstExtent(extent), state);
                }
                if (pointer.kind() == PointerEvent.Kind.PRESS && pointer.button() == PointerEvent.Button.LEFT
                    && pointer.modifiers().isEmpty() && context.localPoint(pointer).filter(bounds()::contains).isPresent()) {
                    return changed(split.firstExtent(), new State(true)).withPointerCapture(PointerCaptureRequest.ACQUIRE);
                }
                return InteractionResult.ignored();
            }
            if (!context.focused() || !(event instanceof KeyStroke stroke) || !stroke.modifiers().isEmpty()) {
                return InteractionResult.ignored();
            }
            long extent = switch (stroke.key()) {
                case HOME -> split.minimumFirstExtent();
                case END -> split.maximumFirstExtent();
                case ARROW_LEFT -> split.axis() == SplitPane.Axis.HORIZONTAL ? (long) split.firstExtent() - 1 : -1L;
                case ARROW_RIGHT -> split.axis() == SplitPane.Axis.HORIZONTAL ? (long) split.firstExtent() + 1 : -1L;
                case ARROW_UP -> split.axis() == SplitPane.Axis.VERTICAL ? (long) split.firstExtent() - 1 : -1L;
                case ARROW_DOWN -> split.axis() == SplitPane.Axis.VERTICAL ? (long) split.firstExtent() + 1 : -1L;
                default -> -1L;
            };
            return extent < 0L ? InteractionResult.ignored() : changed(split.clampFirstExtent(extent), state);
        }

        private InteractionResult<StateChanged> changed(int firstExtent, State next) {
            return InteractionResult.output(new StateChanged(firstExtent, next));
        }
    }
}
