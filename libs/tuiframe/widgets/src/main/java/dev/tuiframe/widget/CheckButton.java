package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.TuiInputEvent;
import java.util.Objects;

/** Owner-controlled boolean button. Only the check mark depends on its value; styling follows Button focus. */
public final class CheckButton implements InteractiveWidget<Boolean> {
    private final Button button;
    private final boolean checked;
    public CheckButton(String label, boolean checked) {
        this.checked = checked;
        button = new Button((checked ? "✓ " : "  ") + Objects.requireNonNull(label));
    }
    public int width() { return button.width(); }
    public boolean checked() { return checked; }
    @Override public Size measure(Size available) { return button.measure(available); }
    @Override public Placement layout(Rect bounds) { return new Placement(button.layout(bounds)); }
    public final class Placement implements PlacedInteractiveWidget<Boolean> {
        private final PlacedInteractiveWidget<Button.Action> placed;
        private Placement(PlacedInteractiveWidget<Button.Action> placed) { this.placed = placed; }
        @Override public boolean interactionReady() { return placed.interactionReady(); }
        @Override public Rect bounds() { return placed.bounds(); }
        @Override public InteractionResult<Boolean> handle(TuiInputEvent event, FocusContext context) {
            return placed.handle(event, context).mapOutput(ignored -> !checked);
        }
        @Override public void paint(Canvas canvas, Theme theme, WidgetState state) {
            placed.paint(canvas, theme, state);
        }
    }
}
