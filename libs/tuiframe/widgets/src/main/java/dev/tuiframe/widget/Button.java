package dev.tuiframe.widget;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.text.TerminalText;
import dev.tuiframe.style.CellStyle;
import java.util.Objects;

/**
 * Reusable single-line text button for TUI overlays.
 */
public final class Button implements InteractiveWidget<Button.Action> {
    private static final int TAB_WIDTH = 4;

    private final int row;
    private final int col;
    private final String text;
    private final boolean framed;
    private final String renderedText;
    private final int renderedWidth;
    private final int cursorOffset;

    public Button(String text) {
        this(0, 0, text, true);
    }

    public Button(int row, int col, String text) {
        this(row, col, text, true);
    }

    private Button(int row, int col, String text, boolean framed) {
        this.row = row;
        this.col = col;
        this.text = text;
        this.framed = framed;
        this.renderedText = framed ? "[" + text + "]" : text;
        TerminalText.Metrics metrics = TerminalText.metrics(renderedText, TAB_WIDTH);
        this.renderedWidth = metrics.visualWidth();
        this.cursorOffset = resolveCursorOffset(metrics, framed);
    }

    public static Button plain(int row, int col, String text) {
        return new Button(row, col, text, false);
    }

    public static Button plain(String text) {
        return new Button(0, 0, text, false);
    }

    public int row() {
        return row;
    }

    public int col() {
        return col;
    }

    public String text() {
        return text;
    }

    public int cursorCol() {
        return col + cursorOffset;
    }

    public int width() {
        return renderedWidth;
    }

    public boolean hit(int hitRow, int hitCol) {
        return hitRow == row && hitCol >= col && hitCol < col + width();
    }

    /** Draws at the button's local Canvas coordinates with strict clipping. */
    public void draw(Canvas canvas, CellStyle style) {
        drawAt(canvas, row, style);
    }

    /** Draws on an alternate local row without changing hit-test geometry. */
    public void drawAt(Canvas canvas, int drawRow, CellStyle style) {
        Objects.requireNonNull(canvas, "canvas");
        if (renderedWidth <= 0) {
            return;
        }
        int mark = canvas.save();
        try {
            if (!canvas.clip(col, drawRow, renderedWidth, 1)) {
                return;
            }
            CellText.draw(canvas, col, drawRow, renderedText, style);
        } finally {
            canvas.restore(mark);
        }
    }

    public String renderedText() {
        return renderedText;
    }

    @Override
    public Size measure(Size available) {
        Objects.requireNonNull(available, "available");
        return new Size(
            Math.min(renderedWidth, available.width()),
            Math.min(1, available.height())
        );
    }

    @Override
    public PlacedInteractiveWidget<Action> layout(Rect bounds) {
        return new Placement(this, Objects.requireNonNull(bounds, "bounds"));
    }

    /** Semantic output independent of application commands and keymaps. */
    public enum Action {
        ACTIVATED
    }

    private static int resolveCursorOffset(TerminalText.Metrics metrics, boolean framed) {
        for (int index = 0; index < metrics.graphemeCount(); index++) {
            int codePoint = metrics.text().codePointAt(metrics.start(index));
            if (Character.isLetterOrDigit(codePoint)) {
                return metrics.visualColumnAtBoundary(index);
            }
        }
        return framed ? 1 : 0;
    }

    private record Placement(Button button, Rect bounds) implements PlacedInteractiveWidget<Action> {
        private Placement {
            Objects.requireNonNull(button, "button");
            Objects.requireNonNull(bounds, "bounds");
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState state) {
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(state, "state");
            WidgetStyleRole role = !state.enabled()
                ? WidgetThemeKeys.BUTTON_DISABLED
                : state.focused() ? WidgetThemeKeys.BUTTON_FOCUSED : WidgetThemeKeys.BUTTON;
            paintText(canvas, bounds, button.renderedText, theme.resolveStyle(role));
        }

        @Override
        public InteractionResult<Action> handle(TuiInputEvent event, FocusContext context) {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(context, "context");
            if (!context.enabled()) {
                return InteractionResult.ignored();
            }
            if (event instanceof PointerEvent pointer) {
                boolean activated = pointer.kind() == PointerEvent.Kind.PRESS
                    && pointer.button() == PointerEvent.Button.LEFT
                    && context.localPoint(pointer).filter(this::hit).isPresent();
                return activated
                    ? InteractionResult.output(Action.ACTIVATED)
                    : InteractionResult.ignored();
            }
            if (!context.focused()) {
                return InteractionResult.ignored();
            }
            if (event instanceof KeyStroke stroke
                && stroke.modifiers().isEmpty()
                && (stroke.key() == KeyStroke.Key.ENTER || stroke.key() == KeyStroke.Key.SPACE)) {
                return InteractionResult.output(Action.ACTIVATED);
            }
            if (event instanceof TextInput text && " ".equals(text.text())) {
                return InteractionResult.output(Action.ACTIVATED);
            }
            return InteractionResult.ignored();
        }
    }

    private static void paintText(Canvas canvas, Rect bounds, String text, CellStyle style) {
        Objects.requireNonNull(canvas, "canvas");
        if (bounds.isEmpty()) {
            return;
        }
        int mark = canvas.save();
        try {
            if (!canvas.clip(bounds)) {
                return;
            }
            CellText.draw(canvas, bounds.x(), bounds.y(), text, style);
        } finally {
            canvas.restore(mark);
        }
    }
}
