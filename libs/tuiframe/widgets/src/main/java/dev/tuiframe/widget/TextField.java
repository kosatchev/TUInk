package dev.tuiframe.widget;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import java.util.Objects;
import java.util.Optional;

/**
 * Reusable single-line text input buffer and interactive TUI widget.
 *
 * <p>The field owns its value and grapheme-boundary cursor offset. Focus,
 * submission policy, validation, and application commands remain external.
 * The compatibility {@link #insert(String)} and {@link #set(String)} methods
 * accept trusted application text unchanged, including tabs and controls.
 * Raw {@link TextInput} is printable by contract, while raw paste is
 * normalized to safe single-line content before insertion.</p>
 */
public final class TextField implements InteractiveWidget<TextField.Action> {
    private static final int TAB_WIDTH = 4;
    private static final TerminalText.Metrics EMPTY_METRICS = TerminalText.metrics("", TAB_WIDTH);
    private static final InteractionResult<Action> VALUE_CHANGED_RESULT =
        InteractionResult.output(Action.VALUE_CHANGED);
    private static final InteractionResult<Action> SUBMITTED_RESULT =
        InteractionResult.output(Action.SUBMITTED);
    private static final InteractionResult<Action> PASTE_REJECTED_RESULT =
        InteractionResult.output(Action.PASTE_REJECTED);
    private static final InteractionResult<Action> FOCUS_SELF_RESULT =
        InteractionResult.focus(FocusRequest.SELF);

    private final StringBuilder value = new StringBuilder();
    private int cursor;
    private TerminalText.Metrics cachedMetrics = EMPTY_METRICS;
    private boolean metricsValid = true;

    public void insert(char ch) {
        insertValue(String.valueOf(ch));
    }

    public void insert(String text) {
        insertValue(text);
    }

    public void backspace() {
        backspaceValue();
    }

    public void deleteForward() {
        deleteForwardValue();
    }

    public void moveLeft() {
        moveLeftValue();
    }

    public void moveRight() {
        moveRightValue();
    }

    public void moveHome() {
        moveHomeValue();
    }

    public void moveEnd() {
        moveEndValue();
    }

    public void clear() {
        if (!value.isEmpty()) {
            value.setLength(0);
            invalidateMetrics();
        }
        cursor = 0;
    }

    public void set(String text) {
        String next = text == null ? "" : text;
        if (!metrics().text().equals(next)) {
            value.setLength(0);
            value.append(next);
            invalidateMetrics();
        }
        cursor = value.length();
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    public int length() {
        return value.length();
    }

    /** Returns the cursor as a UTF-16 offset on an extended-grapheme boundary. */
    public int cursor() {
        return cursor;
    }

    public int cursorCellColumn() {
        return metrics().visualColumnAt(cursor);
    }

    public int displayWidth() {
        return metrics().visualWidth();
    }

    public String text() {
        return metrics().text();
    }

    @Override
    public Size measure(Size available) {
        Objects.requireNonNull(available, "available");
        long desiredWidth = Math.max(1L, (long) metrics().visualWidth() + 1L);
        return new Size(
            (int) Math.min(available.width(), Math.min(Integer.MAX_VALUE, desiredWidth)),
            Math.min(1, available.height())
        );
    }

    @Override
    public Placement layout(Rect bounds) {
        return new Placement(
            this,
            Objects.requireNonNull(bounds, "bounds"),
            metrics(),
            cursor
        );
    }

    /** Application-independent effects produced by raw field interaction. */
    public enum Action {
        VALUE_CHANGED,
        SUBMITTED,
        PASTE_REJECTED
    }

    private boolean insertValue(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int insertionOffset = metrics().floorBoundary(cursor);
        value.insert(insertionOffset, text);
        invalidateMetrics();
        int tentativeCursor = insertionOffset + text.length();
        cursor = metrics().ceilBoundary(tentativeCursor);
        return true;
    }

    private boolean backspaceValue() {
        if (cursor <= 0) {
            return false;
        }
        TerminalText.Metrics before = metrics();
        int end = before.ceilBoundary(cursor);
        int start = before.previousBoundary(end);
        if (start >= end) {
            return false;
        }
        value.delete(start, end);
        invalidateMetrics();
        cursor = metrics().floorBoundary(start);
        return true;
    }

    private boolean deleteForwardValue() {
        if (cursor >= value.length()) {
            return false;
        }
        TerminalText.Metrics before = metrics();
        int start = before.floorBoundary(cursor);
        int end = before.nextBoundary(start);
        if (start >= end) {
            return false;
        }
        value.delete(start, end);
        invalidateMetrics();
        cursor = metrics().floorBoundary(start);
        return true;
    }

    private boolean moveLeftValue() {
        int next = metrics().previousBoundary(cursor);
        if (next == cursor) {
            return false;
        }
        cursor = next;
        return true;
    }

    private boolean moveRightValue() {
        int next = metrics().nextBoundary(cursor);
        if (next == cursor) {
            return false;
        }
        cursor = next;
        return true;
    }

    private boolean moveHomeValue() {
        boolean changed = cursor != 0;
        cursor = 0;
        return changed;
    }

    private boolean moveEndValue() {
        int end = value.length();
        boolean changed = cursor != end;
        cursor = end;
        return changed;
    }

    private void moveToVisualColumn(int visualColumn) {
        cursor = metrics().offsetForVisualColumn(
            Math.max(0, visualColumn),
            TerminalText.Bias.NEAREST
        );
    }

    private TerminalText.Metrics metrics() {
        if (!metricsValid) {
            cachedMetrics = TerminalText.metrics(value.toString(), TAB_WIDTH);
            metricsValid = true;
        }
        return cachedMetrics;
    }

    private void invalidateMetrics() {
        metricsValid = false;
    }

    private static String normalizeSingleLinePaste(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(text.length());
        boolean separatorPending = false;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\r' || codePoint == '\n' || codePoint == '\t') {
                separatorPending = !normalized.isEmpty();
                continue;
            }
            if (Character.isISOControl(codePoint)) {
                continue;
            }
            if (separatorPending) {
                normalized.append(' ');
                separatorPending = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString();
    }

    /**
     * Immutable geometry and paint snapshot for one frame.
     *
     * <p>Handling an edit mutates the owning field and makes this snapshot
     * stale; applications must layout the field again before the next paint or
     * cursor query.</p>
     */
    public static final class Placement
        implements PlacedInteractiveWidget<Action>, PlacedCursorSource {
        private final TextField owner;
        private final Rect bounds;
        private final String visibleText;
        private final int viewportLeft;
        private final int cursorColumn;

        private Placement(
            TextField owner,
            Rect bounds,
            TerminalText.Metrics metrics,
            int cursorOffset
        ) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.bounds = Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(metrics, "metrics");
            if (bounds.isEmpty()) {
                visibleText = "";
                viewportLeft = 0;
                cursorColumn = 0;
                return;
            }

            int cursorVisual = metrics.visualColumnAt(cursorOffset);
            int cursorCapacity = Math.max(0, bounds.width() - 1);
            int tailLeft = Math.max(0, metrics.visualWidth() - cursorCapacity);
            int requestedLeft;
            if (cursorCapacity == 0) {
                requestedLeft = cursorVisual;
            } else if (cursorVisual >= tailLeft) {
                requestedLeft = tailLeft;
            } else {
                // A deterministic page keeps ordinary caret movement visual
                // and avoids repainting the whole field on every key without
                // introducing retained viewport state.
                requestedLeft = (cursorVisual / cursorCapacity) * cursorCapacity;
            }
            int startOffset = metrics.offsetForVisualColumn(
                requestedLeft,
                TerminalText.Bias.CEIL
            );
            viewportLeft = metrics.visualColumnAt(startOffset);
            cursorColumn = Math.max(
                0,
                Math.min(bounds.width() - 1, cursorVisual - viewportLeft)
            );
            visibleText = CellText.clip(metrics.text(), viewportLeft, bounds.width());
        }

        @Override
        public Rect bounds() {
            return bounds;
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState state) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(state, "state");
            if (bounds.isEmpty()) {
                return;
            }
            WidgetStyleRole role = !state.enabled()
                ? WidgetThemeKeys.TEXT_FIELD_DISABLED
                : state.focused()
                    ? WidgetThemeKeys.TEXT_FIELD_FOCUSED
                    : WidgetThemeKeys.TEXT_FIELD;
            CellStyle style = theme.resolveStyle(role);
            int mark = canvas.save();
            try {
                if (!canvas.clip(bounds)) {
                    return;
                }
                canvas.fill(bounds, style);
                CellText.draw(canvas, bounds.x(), bounds.y(), visibleText, style);
            } finally {
                canvas.restore(mark);
            }
        }

        @Override
        public InteractionResult<Action> handle(TuiInputEvent event, FocusContext context) {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(context, "context");
            if (!context.enabled()) {
                return InteractionResult.ignored();
            }
            if (event instanceof PointerEvent pointer) {
                return handlePointer(pointer, context);
            }
            if (!context.focused()) {
                return InteractionResult.ignored();
            }
            if (event instanceof TextInput text) {
                return owner.insertValue(text.text())
                    ? VALUE_CHANGED_RESULT
                    : InteractionResult.handled();
            }
            if (event instanceof PasteEvent paste) {
                if (paste.truncated()) {
                    return PASTE_REJECTED_RESULT;
                }
                String normalized = normalizeSingleLinePaste(paste.text());
                return owner.insertValue(normalized)
                    ? VALUE_CHANGED_RESULT
                    : InteractionResult.handled();
            }
            if (!(event instanceof KeyStroke stroke) || !stroke.modifiers().isEmpty()) {
                return InteractionResult.ignored();
            }
            return switch (stroke.key()) {
                case BACKSPACE -> editResult(owner.backspaceValue());
                case DELETE -> editResult(owner.deleteForwardValue());
                // Boundary arrows intentionally bubble so a parent scope can
                // move focus to an adjacent control.
                case ARROW_LEFT -> owner.moveLeftValue()
                    ? InteractionResult.handled()
                    : InteractionResult.ignored();
                case ARROW_RIGHT -> owner.moveRightValue()
                    ? InteractionResult.handled()
                    : InteractionResult.ignored();
                case HOME -> {
                    owner.moveHomeValue();
                    yield InteractionResult.handled();
                }
                case END -> {
                    owner.moveEndValue();
                    yield InteractionResult.handled();
                }
                case ENTER -> SUBMITTED_RESULT;
                default -> InteractionResult.ignored();
            };
        }

        @Override
        public Optional<Point> cursorPosition(FocusContext context) {
            Objects.requireNonNull(context, "context");
            if (!context.focused() || !context.enabled() || bounds.isEmpty()) {
                return Optional.empty();
            }
            Point cursorPoint = new Point(bounds.x() + cursorColumn, bounds.y());
            return bounds.contains(cursorPoint) && context.space().parentClip().contains(cursorPoint)
                ? Optional.of(cursorPoint)
                : Optional.empty();
        }

        private InteractionResult<Action> handlePointer(
            PointerEvent pointer,
            FocusContext context
        ) {
            if (pointer.kind() != PointerEvent.Kind.PRESS
                || pointer.button() != PointerEvent.Button.LEFT) {
                return InteractionResult.ignored();
            }
            Optional<Point> local = context.localPoint(pointer).filter(this::hit);
            if (local.isEmpty()) {
                return InteractionResult.ignored();
            }
            int localColumn = local.orElseThrow().x() - bounds.x();
            long targetVisual = (long) viewportLeft + localColumn;
            owner.moveToVisualColumn((int) Math.min(Integer.MAX_VALUE, targetVisual));
            return FOCUS_SELF_RESULT;
        }

        private static InteractionResult<Action> editResult(boolean changed) {
            return changed ? VALUE_CHANGED_RESULT : InteractionResult.handled();
        }
    }
}
