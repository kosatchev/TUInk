package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.text.TerminalPlainText;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A typed value control with an intrinsic edit state, like TextField. Its popup is painted separately. */
public final class DropDown<T> implements InteractiveWidget<DropDown.Action> {
    public enum Action { OPENED, CLOSED, NAVIGATED, VALUE_CHANGED }
    public record Option<T>(T value, String label, String description) {
        public Option {
            Objects.requireNonNull(value); label = TerminalPlainText.singleLine(Objects.requireNonNull(label));
            description = TerminalPlainText.singleLine(Objects.requireNonNull(description));
            if (label.isBlank()) throw new IllegalArgumentException("option label must not be blank");
        }
        public Option(T value, String label) { this(value, label, ""); }
    }
    private final List<Option<T>> options;
    private T value;
    private boolean open;
    private int highlighted;

    public DropDown(List<Option<T>> options, T selected) {
        this.options = List.copyOf(options);
        if (this.options.isEmpty()) throw new IllegalArgumentException("options must not be empty");
        HashSet<T> seen = new HashSet<>();
        for (var option : this.options) if (!seen.add(option.value())) throw new IllegalArgumentException("duplicate option value");
        setValue(selected);
    }
    public T value() { return value; }
    public List<Option<T>> options() { return options; }
    public boolean popupOpen() { return open; }
    public void setValue(T value) {
        int index = indexOf(Objects.requireNonNull(value));
        if (!value.equals(this.value)) { this.value = value; highlighted = index; open = false; }
    }
    public void dismissPopup() { open = false; highlighted = indexOf(value); }
    private int indexOf(T value) {
        for (int i = 0; i < options.size(); i++) if (options.get(i).value().equals(value)) return i;
        throw new IllegalArgumentException("unknown option value");
    }
    public int width() { return new Button(options.get(indexOf(value)).label() + " ▾").width(); }
    @Override public Size measure(Size available) { return new Size(Math.min(width(), available.width()), Math.min(1, available.height())); }
    @Override public Placement layout(Rect bounds) { return layout(bounds, bounds); }
    public Placement layout(Rect bounds, Rect viewport) { return new Placement(bounds, viewport); }

    public final class Placement implements PlacedPopup<Action> {
        private final Rect bounds, popup;
        private final PlacedInteractiveWidget<Button.Action> control;
        private final int first;
        private Placement(Rect bounds, Rect viewport) {
            this.bounds = Objects.requireNonNull(bounds); Objects.requireNonNull(viewport);
            control = new Button(options.get(indexOf(value)).label() + " ▾").layout(bounds);
            int preferred = width();
            for (var option : options) preferred = Math.max(preferred, 4 + CellText.width(option.label())
                + (option.description().isEmpty() ? 0 : 2 + CellText.width(option.description())));
            int width = Math.min(viewport.width(), Math.max(bounds.width(), Math.min(64, preferred)));
            int height = Math.min(options.size(), viewport.height());
            int x = Math.max(viewport.x(), Math.min(bounds.x(), viewport.rightExclusive() - width));
            int y = bounds.bottomExclusive() + (long) height <= viewport.bottomExclusive() ? bounds.bottomExclusive()
                : Math.max(viewport.y(), Math.min(bounds.y() - height, viewport.bottomExclusive() - height));
            popup = new Rect(x, y, width, height);
            first = Math.max(0, Math.min(highlighted - height + 1, options.size() - height));
        }
        @Override public Rect bounds() { return bounds; }
        @Override public boolean interactionReady() { return control.interactionReady(); }
        @Override public Rect popupBounds() { return popup; }
        @Override public boolean popupOpen() { return open; }
        @Override public void dismissPopup() { DropDown.this.dismissPopup(); }
        @Override public void paint(Canvas canvas, Theme theme, WidgetState state) {
            control.paint(canvas, theme, state);
        }
        @Override public void paintPopup(Canvas canvas, Theme theme, WidgetState state) {
            if (!open || !state.enabled() || popup.isEmpty()) return;
            var normal = ControlStyles.dropDownPopup(theme); var selected = ControlStyles.dropDownPopupSelected(theme);
            canvas.fill(popup, normal);
            for (int row = 0; row < popup.height(); row++) {
                int index = first + row; var option = options.get(index);
                var style = index == highlighted ? selected : normal;
                canvas.fill(new Rect(popup.x(), popup.y() + row, popup.width(), 1), style);
                String text = (option.value().equals(value) ? "✓ " : "  ") + option.label();
                if (!option.description().isEmpty() && CellText.width(text) + 5 < popup.width()) text += "  " + option.description();
                CellText.drawClipped(canvas, popup.x() + 1, popup.y() + row, text, style, Math.max(0, popup.width() - 2));
            }
        }
        private InteractionResult<Action> action(Action action) { return InteractionResult.output(action); }
        private InteractionResult<Action> choose(int index) {
            boolean changed = !value.equals(options.get(index).value());
            value = options.get(index).value(); dismissPopup();
            return action(changed ? Action.VALUE_CHANGED : Action.CLOSED);
        }
        private Optional<Point> point(PointerEvent pointer, FocusContext context) {
            long x = (long) pointer.column() - context.space().terminalOrigin().x();
            long y = (long) pointer.row() - context.space().terminalOrigin().y();
            return x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                ? Optional.empty() : Optional.of(new Point((int) x, (int) y));
        }
        @Override public InteractionResult<Action> handle(TuiInputEvent event, FocusContext context) {
            Objects.requireNonNull(event); Objects.requireNonNull(context);
            if (!context.enabled()) {
                if (open) { dismissPopup(); return action(Action.CLOSED); }
                return InteractionResult.ignored();
            }
            if (event instanceof TextInput text && text.text().equals(" ")) event = KeyStroke.of(KeyStroke.Key.SPACE);
            if (!open) {
                if (!control.interactionReady() || !InteractionVisibility.FULL_BOUNDS.allows(bounds, context.space().parentClip())) return InteractionResult.ignored();
                boolean arrow = context.focused() && event instanceof KeyStroke key && key.key() == KeyStroke.Key.ARROW_DOWN
                    && (key.modifiers().isEmpty() || key.modifiers().equals(java.util.Set.of(KeyStroke.Modifier.ALT)));
                if (arrow || control.handle(event, context).output().isPresent()) {
                    if (popup.isEmpty()) return InteractionResult.handled();
                    open = true; highlighted = indexOf(value); return action(Action.OPENED);
                }
                return InteractionResult.ignored();
            }
            if (event instanceof PointerEvent pointer) {
                var local = point(pointer, context);
                if (pointer.kind() == PointerEvent.Kind.PRESS) {
                    if (local.filter(popup::contains).isPresent() && pointer.button() == PointerEvent.Button.LEFT)
                        return choose(first + local.orElseThrow().y() - popup.y());
                    dismissPopup(); return action(Action.CLOSED);
                }
                if (pointer.kind() == PointerEvent.Kind.MOVE && local.filter(popup::contains).isPresent()) {
                    highlighted = first + local.orElseThrow().y() - popup.y(); return action(Action.NAVIGATED);
                }
                if (pointer.kind() == PointerEvent.Kind.SCROLL_DOWN || pointer.kind() == PointerEvent.Kind.SCROLL_UP) {
                    highlighted = Math.floorMod(highlighted + (pointer.kind() == PointerEvent.Kind.SCROLL_DOWN ? 1 : -1), options.size());
                    return action(Action.NAVIGATED);
                }
                return InteractionResult.handled();
            }
            if (event instanceof KeyStroke key) {
                if (key.key() == KeyStroke.Key.ESCAPE || key.key() == KeyStroke.Key.TAB) { dismissPopup(); return action(Action.CLOSED); }
                if (key.modifiers().isEmpty() && (key.key() == KeyStroke.Key.SPACE || key.key() == KeyStroke.Key.ENTER)) return choose(highlighted);
                int next = switch (key.key()) {
                    case ARROW_DOWN -> Math.floorMod(highlighted + 1, options.size());
                    case ARROW_UP -> Math.floorMod(highlighted - 1, options.size());
                    case HOME -> 0; case END -> options.size() - 1;
                    case PAGE_DOWN -> Math.min(options.size() - 1, highlighted + Math.max(1, popup.height()));
                    case PAGE_UP -> Math.max(0, highlighted - Math.max(1, popup.height()));
                    default -> highlighted;
                };
                if (next != highlighted) { highlighted = next; return action(Action.NAVIGATED); }
            }
            return InteractionResult.handled();
        }
    }
}
